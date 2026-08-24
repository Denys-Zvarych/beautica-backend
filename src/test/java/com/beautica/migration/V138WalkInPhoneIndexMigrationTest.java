package com.beautica.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.repository.BookingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract test for {@code V138__bookings_staff_walkin_phone_index.sql} and for the ONE query it
 * exists to serve, {@code BookingRepository#countStaffWalkInVisitsForPhoneSince} — the per-recipient
 * walk-in SMS-spend cap enforced by {@code StaffBookingService#assertWalkInSmsBudgetForPhone}
 * (QA MEDIUM, 2026-08-19; query widened from a row-count to a visit-count, Phase 22.13).
 *
 * <h2>Why the index and the query are ONE contract, tested in one class</h2>
 * The index is partial ({@code WHERE booking_source = 'STAFF'}) and composite
 * ({@code guest_phone, created_at}) precisely because the query's predicate is those three terms and
 * nothing else. That coupling is invisible to both halves in isolation: adding
 * {@code AND b.status = CONFIRMED} to the JPQL would leave the index existing, correctly shaped and
 * silently no longer covering — while ALSO handing an attacker the create/cancel refund loop the cap
 * exists to remove. Splitting these rows across two classes would let either half change without the
 * other's test noticing. Neither half was covered at all before this class: the migration shipped
 * with no assertion (Q21) and the query's "every status counts, deliberately" property lived only in
 * a Javadoc paragraph, because {@code StaffBookingServiceTest} mocks the repository.
 *
 * <p>Read against the fully migrated chain, so the schema block doubles as the "applies cleanly on a
 * fresh DB" proof. Fixture data uses no occupied-territory locality references.
 */
@DisplayName("V138 — the partial walk-in phone index and the SMS-budget count it serves")
class V138WalkInPhoneIndexMigrationTest extends AbstractIntegrationTest {

    private static final String INDEX = "idx_bookings_staff_walkin_phone";
    private static final String PHONE = "+380501234567";
    private static final String OTHER_PHONE = "+380509876543";

    private static final String INDEX_DEF_QUERY = """
            SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?
            """;

    /**
     * Raw SQL rather than JPA, deliberately: {@code created_at} carries {@code @CreationTimestamp},
     * so a persisted entity always lands at "now" and the {@code since} window could never be
     * exercised from the JPA side. {@code status} is likewise set verbatim, which is the point of the
     * cancelled-still-counts rows.
     *
     * <p>{@code appointment_id} is the LAST bound parameter, nullable — every pre-existing call site
     * in this class binds {@code NULL}, which is exactly the pre-22.12 legacy shape the coalesce
     * fallback exists for (Phase 22.13).
     *
     * <p>{@code starts_at}/{@code ends_at} carry a bound minute offset (twice — once per column) ON
     * TOP of the fixed {@code +1 day} base: {@code no_overlapping_bookings} (V113) excludes
     * CONFIRMED rows on the SAME {@code master_id} whose spans overlap, and the coalesce tests below
     * insert MULTIPLE CONFIRMED rows for one master in one test method — a fixed literal window
     * would collide with itself on the second row. Every pre-existing single-CONFIRMED-row call site
     * passes {@code 0}, which reproduces the old fixed window exactly.
     */
    private static final String INSERT_BOOKING = """
            INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at,
                price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                booking_source, guest_name, guest_surname, guest_phone, cancel_token,
                created_by_user_id, created_at, updated_at, appointment_id)
            VALUES (?, ?, ?, ?,
                NOW() + INTERVAL '1 day' + make_interval(mins => ?),
                NOW() + INTERVAL '1 day 1 hour' + make_interval(mins => ?),
                350.00, 60, 0, ?, 'Олена', 'Коваль', ?, CAST(? AS uuid), ?,
                NOW() - make_interval(mins => ?), NOW(), CAST(? AS uuid))
            """;

    /**
     * A STAFF appointment header satisfying {@code chk_appointment_guest_fields}'s walk-in branch —
     * the FK {@code bookings.appointment_id} resolves against this table (V125).
     */
    private static final String INSERT_APPOINTMENT = """
            INSERT INTO appointments (id, status, booking_source, guest_name, guest_surname,
                guest_phone, cancel_token, created_by_user_id, created_at, updated_at)
            VALUES (?, 'CONFIRMED', 'STAFF', 'Олена', 'Коваль', ?, NULL, ?, NOW(), NOW())
            """;

    @Autowired
    private BookingRepository bookingRepository;

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        @DisplayName("the index exists on bookings under the name the migration declares")
        void should_createTheIndex_when_v138Applied() {
            assertThat(indexDef())
                    .as("without it the SMS-spend cap costs a sequential scan of the whole bookings "
                            + "table on every walk-in create — a throttle that degrades the endpoint "
                            + "it protects")
                    .isNotNull();
        }

        @Test
        @DisplayName("it is PARTIAL on booking_source = 'STAFF' — LINK and APP rows are not indexed")
        void should_bePartialOnStaffSource_when_v138Applied() {
            assertThat(indexDef())
                    .as("the predicate is a constant in the only query using this index; folding it "
                            + "into the definition is what keeps the index to the STAFF minority. A "
                            + "full index here would still answer the query, so ONLY this assertion "
                            + "catches the WHERE clause being dropped")
                    .containsIgnoringCase("WHERE")
                    .contains("booking_source")
                    .contains("'STAFF'");
        }

        @Test
        @DisplayName("its columns are exactly (guest_phone, created_at), in that order")
        void should_indexPhoneThenCreatedAt_when_v138Applied() {
            String columns = indexDef().replaceFirst(".*USING btree \\(([^)]*)\\).*", "$1")
                    .replace(" ", "");

            assertThat(columns)
                    .as("guest_phone must LEAD — the query is an equality on it plus a range on "
                            + "created_at, and the reverse order degrades the equality to a filter")
                    .isEqualTo("guest_phone,created_at");
        }

        private String indexDef() {
            return jdbcTemplate.query(INDEX_DEF_QUERY,
                    rs -> rs.next() ? rs.getString(1) : null, INDEX);
        }
    }

    /**
     * The predicate itself, against real rows. Every row here is a way the cap could be silently
     * refunded or silently widened.
     */
    @Nested
    @DisplayName("countStaffWalkInVisitsForPhoneSince")
    class CountStaffWalkIns {

        private BookingMigrationFixtures.Ids ids;

        @BeforeEach
        void seed() {
            ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        }

        @Test
        @DisplayName("a CONFIRMED STAFF walk-in for this phone counts")
        void should_countTheWalkIn_when_confirmedStaffRowExists() {
            insert("CONFIRMED", "STAFF", PHONE, 10);

            assertThat(countSinceAnHourAgo(PHONE))
                    .as("the baseline — every negative row below is only meaningful because this one "
                            + "counts")
                    .isEqualTo(1);
        }

        /**
         * The Phase 22.13 coalesce contract, D1's sharpest case, written first per the phase doc.
         * Two rows sharing ONE {@code appointments} header (a post-22.12 multi-service visit) must
         * collapse to ONE visit — the unit the confirmation SMS is denominated in, since exactly one
         * message is sent for the whole visit regardless of its service count.
         *
         * <p>Mutation-check RED by dropping the {@code coalesce(..., b.id)} fallback down to a bare
         * {@code appointment_id}: that would still pass THIS row (both rows share the id), which is
         * exactly why {@link #should_countLegacyNullAppointmentRowsIndividually_when_theySharePhoneButNoHeader}
         * exists beside it — the fallback's failure mode is invisible unless both rows are checked.
         */
        @Test
        @DisplayName("two bookings sharing one appointment header count as ONE visit")
        void should_countAsOneVisit_when_twoBookingsShareOneAppointmentHeader() {
            UUID appointmentId = UUID.randomUUID();
            jdbcTemplate.update(INSERT_APPOINTMENT, appointmentId, PHONE, ids.staffUserId());
            // Distinct offsets: both rows are CONFIRMED on the same master, so a shared window would
            // collide on no_overlapping_bookings — chained services of a real visit ARE contiguous in
            // production, but that adjacency plays no role in what this row proves.
            insertLinked("CONFIRMED", "STAFF", PHONE, 20, appointmentId, 0);
            insertLinked("CONFIRMED", "STAFF", PHONE, 10, appointmentId, 90);

            assertThat(countSinceAnHourAgo(PHONE))
                    .as("one appointments header, whatever its chained row count, is ONE confirmation "
                            + "SMS and must consume exactly one budget unit")
                    .isEqualTo(1);
        }

        /**
         * D1's other half: two PRE-22.12-shaped rows (no header at all, {@code appointment_id NULL})
         * for the same phone must count as TWO visits — each legacy row IS its own visit. This is
         * what makes the {@code coalesce(..., b.id)} fallback load-bearing rather than decorative:
         * dropping it collapses every NULL {@code appointment_id} into ONE {@code DISTINCT} group
         * (SQL {@code NULL}s are indistinguishable from each other under {@code coalesce} but NOT
         * under grouping unless coalesced to a per-row-unique value), silently handing a prober an
         * unlimited budget after the first legacy-shaped row. Mutation-check RED by making that exact
         * edit.
         */
        @Test
        @DisplayName("two legacy rows with no appointment header count as TWO visits, not one")
        void should_countLegacyNullAppointmentRowsIndividually_when_theySharePhoneButNoHeader() {
            // Distinct offsets: two CONFIRMED rows on the same master must not collide on
            // no_overlapping_bookings — unrelated to the NULL-appointment_id shape under test.
            insertLinked("CONFIRMED", "STAFF", PHONE, 20, null, 0);
            insertLinked("CONFIRMED", "STAFF", PHONE, 10, null, 90);

            assertThat(countSinceAnHourAgo(PHONE))
                    .as("no appointment_id to coalesce on other than each row's OWN id — every legacy "
                            + "STAFF row is its own visit")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("CANCELLED, DECLINED and NOT_COMPLETED walk-ins STILL count — cancelling refunds nothing")
        void should_stillCountTheWalkIn_when_theBookingWasCancelledOrDeclined() {
            insert("CANCELLED", "STAFF", PHONE, 30);
            insert("DECLINED", "STAFF", PHONE, 20);
            insert("NOT_COMPLETED", "STAFF", PHONE, 10);

            assertThat(countSinceAnHourAgo(PHONE))
                    .as("the cost being bounded is the CONFIRMATION SMS, dispatched at create time "
                            + "and impossible to recall. A status filter would make cancel/recreate "
                            + "the way to reset the cap — i.e. would hand the attacker the exact "
                            + "lever this query removes")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a LINK (guest) booking on the same phone does NOT count")
        void should_notCountTheBooking_when_itIsAGuestLinkBooking() {
            insert("CONFIRMED", "LINK", PHONE, 10);

            assertThat(countSinceAnHourAgo(PHONE))
                    .as("a LINK phone is OTP-verified and already throttled by PhoneOtpService; "
                            + "counting it would let a legitimate guest self-booking consume a "
                            + "different budget and lock the walk-in path out")
                    .isZero();
        }

        @Test
        @DisplayName("a walk-in older than the window does NOT count")
        void should_notCountTheWalkIn_when_itPredatesTheWindow() {
            insert("CONFIRMED", "STAFF", PHONE, 90);

            assertThat(countSinceAnHourAgo(PHONE))
                    .as("the cap is a RATE, not a lifetime ceiling — a phone booked yesterday must "
                            + "start today with a full budget")
                    .isZero();
        }

        @Test
        @DisplayName("a walk-in for a different phone does NOT count")
        void should_notCountTheWalkIn_when_itIsForAnotherRecipient() {
            insert("CONFIRMED", "STAFF", OTHER_PHONE, 10);

            assertThat(countSinceAnHourAgo(PHONE))
                    .as("the budget is per RECIPIENT; a shared counter would let one busy number "
                            + "throttle every other client in the salon")
                    .isZero();
        }

        /**
         * {@code created_at} is aged in SQL ({@code NOW() - make_interval}) rather than by binding a
         * {@code java.sql.Timestamp}: the JVM here runs on {@code Europe/Kyiv} while the container is
         * UTC, so a JDBC-bound local timestamp would land the row hours away from where the test
         * meant it and the window rows would pass or fail for a timezone reason.
         *
         * <p>Legacy shape: {@code appointment_id = NULL} — every row is its own visit. The fixed
         * {@code +1 day} window (offset 0) is safe here because every EXISTING call site inserts at
         * most one CONFIRMED row per test method.
         */
        private void insert(String status, String source, String phone, int createdMinutesAgo) {
            insertLinked(status, source, phone, createdMinutesAgo, null, 0);
        }

        /**
         * {@code appointmentId} is nullable — {@code null} reproduces every existing call site's
         * legacy (pre-22.12) shape; a real id links the row to a header exactly as
         * {@code StaffBookingService#createStaffBooking} does for a post-22.12 visit.
         *
         * @param startOffsetMinutes shifts {@code starts_at}/{@code ends_at} away from the class's
         *                           fixed {@code +1 day} window, so two CONFIRMED rows for the same
         *                           master inserted in one test do not collide on
         *                           {@code no_overlapping_bookings} — irrelevant to every OTHER
         *                           status, which the exclude constraint does not cover at all.
         */
        private void insertLinked(String status, String source, String phone, int createdMinutesAgo,
                UUID appointmentId, int startOffsetMinutes) {
            // chk_bookings_guest_fields (V91/V137): a non-terminal LINK row MUST carry a cancel
            // token, and a STAFF row must NOT — a staff booking has no self-service cancel link.
            // Hard-coding NULL for both would make the LINK row uninsertable and silently turn the
            // "a LINK booking does not count" row into a fixture error.
            UUID cancelToken = "LINK".equals(source) ? UUID.randomUUID() : null;
            jdbcTemplate.update(INSERT_BOOKING, UUID.randomUUID(), ids.masterId(),
                    ids.masterServiceId(), status, startOffsetMinutes, startOffsetMinutes,
                    source, phone, cancelToken, ids.staffUserId(), createdMinutesAgo, appointmentId);
        }

        private long countSinceAnHourAgo(String phone) {
            return bookingRepository.countStaffWalkInVisitsForPhoneSince(
                    phone, Instant.now().minus(60, ChronoUnit.MINUTES));
        }
    }
}
