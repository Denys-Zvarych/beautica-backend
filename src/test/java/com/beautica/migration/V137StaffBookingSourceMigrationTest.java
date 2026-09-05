package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.BookingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@code V137__bookings_staff_source.sql}: the widened {@code chk_bookings_source}
 * domain, the rewritten polymorphic {@code chk_bookings_guest_fields}, and the
 * {@code created_by_user_id} audit column + its partial index.
 *
 * <p>Two things this class exists to lock down beyond the happy path:
 * <ul>
 *   <li><strong>The APP and LINK branches survived the CHECK rewrite verbatim</strong> — including
 *       V91's terminal-status relaxation of the LINK {@code cancel_token} clause. Re-copying V89's
 *       original predicate into V137 would have silently re-opened the bug that 500s every real
 *       guest cancellation.</li>
 *   <li><strong>The deferred linked-CLIENT STAFF mode is already schema-legal.</strong>
 *       {@code should_acceptStaffBooking_when_onlyClientIdPresent} is the executable form of the
 *       "lighting it up later needs zero migration" guarantee — if someone narrows the CHECK to
 *       today's walk-in-only service scope, that test goes red.</li>
 * </ul>
 *
 * <p>All inserts go through {@code JdbcTemplate}, not JPA: the subject is DB-level CHECK
 * behaviour, and routing through {@code Booking}'s own factory guards would mask it.
 *
 * <p><strong>Every reject test names the constraint it expects.</strong> {@code bookings} carries
 * at least four independent rejecters — {@code chk_bookings_guest_fields},
 * {@code chk_bookings_guest_phone_format} (V89), {@code chk_bookings_source} and the
 * {@code no_overlapping_bookings} exclusion constraint — and all four surface as the same
 * {@link DataIntegrityViolationException}. A bare {@code isInstanceOf} assertion therefore proves
 * only "something rejected this row", which is not the claim any of these tests are making. Two
 * concrete false-greens this guards against, both observed against the real schema:
 * <ul>
 *   <li>an out-of-domain {@code booking_source} is reported by {@code chk_bookings_guest_fields},
 *       never by {@code chk_bookings_source} — see
 *       {@link #should_rejectRowWithSourceOutsideDomain_when_guestFieldsBranchMatchesNothing()};</li>
 *   <li>a walk-in row that the CHECK <em>accepts</em> can still fail the exclusion constraint on
 *       the master's slot, so an assertion-free reject test stays green even after the branch it
 *       claims to test has been deleted.</li>
 * </ul>
 */
@DisplayName("V137 migration — STAFF booking source")
class V137StaffBookingSourceMigrationTest extends AbstractIntegrationTest {

    private static final String INSERT_WITH_IDENTITY = """
            INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at,
                price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                booking_source, guest_name, guest_surname, guest_phone, cancel_token,
                created_by_user_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                350.00, 60, 0, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    // ── schema shape ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should add a nullable created_by_user_id UUID column")
    void should_addCreatedByUserIdColumn_when_migrationApplied() {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'bookings' AND column_name = 'created_by_user_id'
                """, String.class);
        String isNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'bookings' AND column_name = 'created_by_user_id'
                """, String.class);

        assertThat(dataType).isEqualTo("uuid");
        assertThat(isNullable).isEqualTo("YES");
    }

    @Test
    @DisplayName("should index created_by_user_id partially on IS NOT NULL")
    void should_createPartialIndex_when_migrationApplied() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_bookings_created_by'",
                String.class);

        assertThat(indexDef)
                .contains("created_by_user_id")
                .contains("WHERE (created_by_user_id IS NOT NULL)");
    }

    @Test
    @DisplayName("should round-trip created_by_user_id — the staff creator is actually persisted")
    void should_persistCreatedByUserId_when_staffWalkInInserted() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        UUID bookingId = insertStaffWalkIn(ids, ids.staffUserId());

        UUID storedCreator = jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM bookings WHERE id = ?", UUID.class, bookingId);
        assertThat(storedCreator)
                .as("the audit column must store the creator it was given, actual=%s", storedCreator)
                .isEqualTo(ids.staffUserId());
    }

    /**
     * The constraint NAME asserted here is {@code fk_bookings_created_by}, not V137's
     * auto-generated {@code bookings_created_by_user_id_fkey}:
     * {@code V157__masters_detachable_and_staff_delete_fks.sql} § 5 drops V137's inline FK and
     * re-adds it under an explicit name. The FK itself — the property this test exists to prove —
     * is unchanged; only its name and its {@code ON DELETE} action moved.
     */
    @Test
    @DisplayName("created_by_user_id is a real FK — an id that is not a users row is rejected")
    void should_rejectStaffBooking_when_createdByUserIdIsNotAUser() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), null, ids.masterId(), ids.masterServiceId(),
                "STAFF", "Олена", "Коваль", "+380501234567", null, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_bookings_created_by");
    }

    /**
     * !! THIS TEST RECORDS A DECISION — DO NOT "KEEP IT GREEN" BLINDLY. !!
     *
     * <p><b>Reversed by V157 (Phase 294 D5, 2026-09-04).</b> V137 originally declared
     * {@code SET NULL}; a security finding flipped it to {@code RESTRICT}, arguing the column
     * exists purely for attribution and that {@code SET NULL} makes that attribution destructible
     * by deleting the very account under suspicion. That rationale was written when NOTHING in the
     * codebase deleted a user ("RESTRICT matches the codebase's actual lifecycle, which is
     * deactivate-never-delete", V137:129) and it explicitly deferred the case to "a future
     * right-to-erasure flow". {@code V157__masters_detachable_and_staff_delete_fks.sql} § 5 IS that
     * flow: the salon/staff hard-delete track needs the {@code users} row to actually go, so the FK
     * is back to {@code ON DELETE SET NULL} under the explicit name
     * {@code fk_bookings_created_by}. Read V157 § 5's header comment before touching this again —
     * reverting to RESTRICT re-blocks the whole deletion track.
     *
     * <p>Both halves stay load-bearing, only inverted. "The DELETE succeeded" alone would be
     * satisfied by a {@code CASCADE} that took the booking with it, and "the booking still exists"
     * alone would be satisfied by {@code RESTRICT} on a delete that never ran. Asserting the row
     * SURVIVES with a NULLed creator is what pins {@code SET NULL} specifically.
     *
     * <p>The creator here is {@code staffUserId} (a SALON_OWNER that owns nothing else in the
     * fixture), not the client and not the master's user. The client is the wrong actor — V137's
     * whole point is that the creator is staff — and {@code masterUserId} is unusable: its
     * {@code masters} row would trip {@code chk_masters_detachment_coherent}
     * (V157 § 4) the moment the FK's SET NULL blanks {@code masters.user_id} without a name
     * snapshot, so the {@code DELETE} would fail for a reason that has nothing to do with this
     * column.
     */
    @Test
    @DisplayName("should NULL the attribution (not reject) when a staff user with bookings is deleted")
    void should_nullBookingAttribution_when_staffUserDeleted() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID bookingId = insertStaffWalkIn(ids, ids.staffUserId());

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ids.staffUserId());

        Integer surviving = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE id = ?", Integer.class, bookingId);
        assertThat(surviving)
                .as("SET NULL, never CASCADE: the booking itself must outlive its creator's account")
                .isEqualTo(1);

        UUID clearedCreator = jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM bookings WHERE id = ?", UUID.class, bookingId);
        assertThat(clearedCreator)
                .as("the attribution must be NULLed by the FK, not left dangling, actual=%s", clearedCreator)
                .isNull();
    }

    /**
     * The catalog twin of {@link #should_nullBookingAttribution_when_staffUserDeleted()}: asserts
     * the declared delete action directly rather than inferring it from an observed row. Behaviour
     * and declaration are asserted separately on purpose — this test is what distinguishes a
     * deliberate {@code SET NULL} from a column that merely happens to be NULL after some other
     * statement touched it.
     *
     * <p>Changed back to {@code 'n'} (SET NULL) deliberately by V157 § 5 / Phase 294 D5, as the
     * record of the decision described on the twin — never because the build went red.
     */
    @Test
    @DisplayName("created_by_user_id's FK declares ON DELETE SET NULL ('n' in pg_constraint) after V157")
    void should_declareSetNullDeleteAction_when_v157Applied() {
        String deleteAction = jdbcTemplate.queryForObject("""
                SELECT confdeltype FROM pg_constraint
                WHERE conrelid = 'bookings'::regclass AND contype = 'f'
                  AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                        WHERE attrelid = 'bookings'::regclass AND attname = 'created_by_user_id')]
                """, String.class);

        assertThat(deleteAction)
                .as("'n' = SET NULL, 'r' = RESTRICT, 'a' = NO ACTION, 'c' = CASCADE; actual=%s", deleteAction)
                .isEqualTo("n");
    }

    // ── chk_bookings_source ────────────────────────────────────────────────────

    /**
     * {@code chk_bookings_source} cannot be provoked in isolation by any INSERT: every branch of
     * {@code chk_bookings_guest_fields} pins {@code booking_source} to a specific literal, so a row
     * carrying an out-of-domain source violates that constraint too — and Postgres evaluates check
     * constraints in name order, so {@code chk_bookings_guest_fields} is the one it reports. Naming
     * it here is what makes the test honest; the widened domain itself is asserted from the catalog
     * by {@link #should_admitExactlyAppLinkStaff_when_sourceDomainInspected()}.
     */
    @Test
    @DisplayName("a row whose booking_source is outside the domain matches no identity branch")
    void should_rejectRowWithSourceOutsideDomain_when_guestFieldsBranchMatchesNothing() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), null, ids.masterId(), ids.masterServiceId(),
                "KIOSK", "Олена", "Коваль", "+380501234567", null, ids.staffUserId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
    }

    @Test
    @DisplayName("chk_bookings_source admits exactly APP, LINK and STAFF — no more, no fewer")
    void should_admitExactlyAppLinkStaff_when_sourceDomainInspected() {
        String constraintDef = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_bookings_source'",
                String.class);

        assertThat(constraintDef)
                .as("actual=%s", constraintDef)
                .contains("'APP'")
                .contains("'LINK'")
                .contains("'STAFF'");
        assertThat(constraintDef.split("'").length)
                .as("a fourth literal would mean the domain was widened without updating BookingSource")
                .isEqualTo(7); // 3 literals => 6 quotes => 7 split segments
    }

    /**
     * Enum/DB parity. {@code BookingSource}'s own javadoc states that any divergence from
     * {@code chk_bookings_source} throws {@link IllegalArgumentException} during Hibernate
     * hydration — a production-only failure that no APP/LINK test would ever surface. This is the
     * tripwire: adding a value on either side alone goes red here.
     */
    @Test
    @DisplayName("BookingSource's constants and chk_bookings_source's domain are the same set")
    void should_matchDbDomain_when_bookingSourceConstantsEnumerated() {
        String constraintDef = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_bookings_source'",
                String.class);

        List<String> enumNames = Arrays.stream(BookingSource.values()).map(Enum::name).toList();

        assertThat(enumNames)
                .as("enum constants must all appear in the DB domain, actual domain=%s", constraintDef)
                .allSatisfy(name -> assertThat(constraintDef).contains("'" + name + "'"));
        assertThat(constraintDef.split("'").length)
                .as("the DB domain must carry no literal the enum lacks")
                .isEqualTo(enumNames.size() * 2 + 1);
    }

    // ── chk_bookings_guest_fields — STAFF branch accepts ───────────────────────

    @Test
    @DisplayName("a STAFF walk-in row with name + surname + phone, no client_id, no cancel_token")
    void should_acceptStaffBooking_when_fullWalkInIdentityPresent() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        UUID bookingId = insertStaffWalkIn(ids, ids.staffUserId());

        assertThat(sourceOf(bookingId)).isEqualTo("STAFF");
    }

    /**
     * Guards the "future linked-client phase needs ZERO migration" promise written into V137.
     * The service layer does not use this mode yet (Phase 22.3 is deferred) — the schema does.
     */
    @Test
    @DisplayName("a STAFF row carrying only client_id (deferred linked-client mode stays schema-legal)")
    void should_acceptStaffBooking_when_onlyClientIdPresent() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID bookingId = UUID.randomUUID();

        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                bookingId, ids.clientId(), ids.masterId(), ids.masterServiceId(),
                "STAFF", null, null, null, null, ids.staffUserId());

        assertThat(sourceOf(bookingId)).isEqualTo("STAFF");
    }

    // ── chk_bookings_guest_fields — STAFF branch rejects ───────────────────────

    @Test
    @DisplayName("a STAFF row with a cancel_token (staff bookings have no guest cancel link)")
    void should_rejectStaffBooking_when_cancelTokenPresent() {
        assertStaffRowRejected(null, "Олена", "Коваль", "+380501234567", UUID.randomUUID());
    }

    @Test
    @DisplayName("a STAFF row carrying BOTH a client_id and guest identity")
    void should_rejectStaffBooking_when_clientIdAndGuestFieldsBothPresent() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), ids.clientId(), ids.masterId(), ids.masterServiceId(),
                "STAFF", "Олена", "Коваль", "+380501234567", null, ids.staffUserId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
    }

    @Test
    @DisplayName("a STAFF walk-in row missing guest_name")
    void should_rejectStaffWalkIn_when_guestNameMissing() {
        assertStaffRowRejected(null, null, "Коваль", "+380501234567", null);
    }

    /**
     * {@code guest_name IS NOT NULL} is satisfied by the empty string, so the walk-in branch as
     * first written admitted a booking with no usable client identity at all — the one thing the
     * branch exists to prevent, and unfixable after the fact because a walk-in has no account to
     * recover the name from. Pins the {@code btrim(...) <> ''} guard; without it the row inserts
     * cleanly and this test fails on "expected a throwable".
     */
    @Test
    @DisplayName("a STAFF walk-in row whose guest_name is blank (present but empty)")
    void should_rejectStaffWalkIn_when_guestNameBlank() {
        assertStaffRowRejected(null, "   ", "Коваль", "+380501234567", null);
    }

    @Test
    @DisplayName("a STAFF walk-in row whose guest_surname is blank (present but empty)")
    void should_rejectStaffWalkIn_when_guestSurnameBlank() {
        assertStaffRowRejected(null, "Олена", "   ", "+380501234567", null);
    }

    /**
     * The empty string, not just whitespace — {@code btrim} collapses both, but a guard written as
     * {@code <> ' '} or a length check would pass one and fail the other.
     */
    @Test
    @DisplayName("a STAFF walk-in row whose guest_name is the empty string")
    void should_rejectStaffWalkIn_when_guestNameEmptyString() {
        assertStaffRowRejected(null, "", "Коваль", "+380501234567", null);
    }

    @Test
    @DisplayName("a STAFF walk-in row missing guest_surname (stricter than the LINK branch)")
    void should_rejectStaffWalkIn_when_guestSurnameMissing() {
        assertStaffRowRejected(null, "Олена", null, "+380501234567", null);
    }

    @Test
    @DisplayName("a STAFF walk-in row missing guest_phone")
    void should_rejectStaffWalkIn_when_guestPhoneMissing() {
        assertStaffRowRejected(null, "Олена", "Коваль", null, null);
    }

    @Test
    @DisplayName("a STAFF row with neither a client_id nor any guest identity")
    void should_rejectStaffBooking_when_noIdentityAtAll() {
        assertStaffRowRejected(null, null, null, null, null);
    }

    // ── APP / LINK regression: both branches survived the rewrite verbatim ─────

    @Test
    @DisplayName("an APP row (client_id set, all guest fields null) is still accepted")
    void should_acceptAppBooking_when_migrationApplied() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID bookingId = UUID.randomUUID();

        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                bookingId, ids.clientId(), ids.masterId(), ids.masterServiceId(),
                "APP", null, null, null, null, null);

        assertThat(sourceOf(bookingId)).isEqualTo("APP");
    }

    @Test
    @DisplayName("a LINK row with guest identity + cancel_token is still accepted")
    void should_acceptLinkBooking_when_migrationApplied() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID bookingId = UUID.randomUUID();

        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                bookingId, null, ids.masterId(), ids.masterServiceId(),
                "LINK", "Олена", "Коваль", "+380501234567", UUID.randomUUID(), null);

        assertThat(sourceOf(bookingId)).isEqualTo("LINK");
    }

    /**
     * V91's relaxation, re-asserted after V137 rewrote the same constraint: a terminal LINK row
     * may have a NULL cancel_token, because the guest-cancel UPDATE nulls the token while
     * setting status = CANCELLED. Copying V89's unrelaxed predicate into V137 would 500 every
     * real guest cancellation — this test is the tripwire for that.
     */
    @Test
    @DisplayName("V91 relaxation intact: a CANCELLED LINK row may carry a NULL cancel_token")
    void should_acceptTerminalLinkBooking_when_cancelTokenNull() {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);
        UUID bookingId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at)
                VALUES (?, ?, ?, 'CANCELLED', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                    350.00, 60, 0, 'LINK', 'Олена', 'Коваль', '+380501234567', NULL, NOW(), NOW())
                """, bookingId, ids.masterId(), ids.masterServiceId());

        assertThat(sourceOf(bookingId)).isEqualTo("LINK");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID insertStaffWalkIn(BookingMigrationFixtures.Ids ids, UUID createdByUserId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(INSERT_WITH_IDENTITY,
                bookingId, null, ids.masterId(), ids.masterServiceId(),
                "STAFF", "Олена", "Коваль", "+380501234567", null, createdByUserId);
        return bookingId;
    }

    /**
     * Asserts the row is rejected <em>by {@code chk_bookings_guest_fields} specifically</em>.
     * Naming the constraint is the whole value of this helper: {@code bookings} has several other
     * rejecters that raise the identical exception type, so an {@code isInstanceOf}-only assertion
     * would stay green even if the STAFF branch it is probing were deleted outright.
     */
    private void assertStaffRowRejected(
            UUID clientId, String guestName, String guestSurname, String guestPhone, UUID cancelToken) {
        BookingMigrationFixtures.Ids ids = BookingMigrationFixtures.seedBookingGraph(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_WITH_IDENTITY,
                UUID.randomUUID(), clientId, ids.masterId(), ids.masterServiceId(),
                "STAFF", guestName, guestSurname, guestPhone, cancelToken, ids.staffUserId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
    }

    private String sourceOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT booking_source FROM bookings WHERE id = ?", String.class, bookingId);
    }
}
