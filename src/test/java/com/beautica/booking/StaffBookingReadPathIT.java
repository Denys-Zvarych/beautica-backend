package com.beautica.booking;

import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The READ half of the staff walk-in track (22.x): a {@code booking_source = STAFF} booking listed
 * back through the endpoints a provider's calendar actually calls.
 *
 * <h2>Why this suite exists</h2>
 * Every staff-booking test that existed before it stops at the INSERT. {@link StaffBookingIT} and
 * {@link StaffBookingEndpointIT} drive {@code POST /masters/{masterId}/bookings} and then assert
 * against {@code jdbcTemplate.queryForList("SELECT * FROM bookings")} — they never call a read
 * endpoint, so no test had ever proven a STAFF row survives the trip back out through
 * {@link com.beautica.booking.dto.BookingDetailResponse}. The read-path suites, symmetrically, had
 * never seen a STAFF row: {@code BookingMyBookingsDateRangeFilterIT}, {@code BookingMyBookedDaysIT}
 * and the rest of the 26.x family hardcode {@code 'APP'} in their {@code insertBooking} SQL, and
 * {@code ProviderCanReviewClientIT#insertGuestBooking} hardcodes {@code 'LINK'}. So the ONE source
 * value whose row shape is unlike every other was created by one set of tests and read by none.
 *
 * <p>That shape is genuinely distinct, and every difference is a place a mapper can drop a field:
 * <ul>
 *   <li>{@code clientId} is {@code null} — there is no {@code users} row (V137
 *       {@code chk_bookings_guest_fields}: a STAFF walk-in has {@code client_id IS NULL} together
 *       with non-blank {@code guest_name}/{@code guest_surname}/{@code guest_phone}).</li>
 *   <li>{@code clientFirstName}/{@code clientLastName} are therefore NOT read off a
 *       {@code User} — they come from the booking row's own {@code guest_name}/{@code guest_surname}
 *       through the fallback in {@code BookingDetailResponse#from}. A provider whose calendar card
 *       renders {@code null} here has an unnamed appointment.</li>
 *   <li>{@code clientAvatarUrl} is {@code null} and has NO fallback (no account, no photo).</li>
 *   <li>{@code appointmentId} is <b>set</b> — V139 widened {@code chk_appointment_source} to admit
 *       {@code 'STAFF'}, and Phase 22.12's D2 decision refuses to short-circuit on {@code N = 1}, so
 *       every walk-in created after that phase carries an {@code appointments} header exactly like
 *       the single-service APP/LINK case. (The pre-22.12 {@code appointment_id IS NULL} shape still
 *       exists on old rows and is never backfilled — {@link AbstractStaffVisitShapeIT} is the suite
 *       that covers BOTH shapes; this one reads only the new one.)</li>
 *   <li>{@code canReview} and {@code providerCanReviewClient} are both hard {@code false}, and stay
 *       false even once the visit is {@code COMPLETED} — the one status at which the same row shape
 *       WITH a registered client flips {@code providerCanReviewClient} to true.</li>
 * </ul>
 *
 * <h2>What each area pins</h2>
 * <ol>
 *   <li><b>The wire contract</b> — the whole field set above, on one row, through
 *       {@code GET /bookings/me}.</li>
 *   <li><b>The date-range filter</b> — a STAFF row is returned on its own Kyiv day and absent from
 *       the neighbouring one, i.e. the 26.2 range predicate treats it like any other booking.</li>
 *   <li><b>A mixed day</b> — one STAFF row beside one registered-client row: BOTH serialize, and
 *       neither borrows the other's identity. Asserted in both directions, because a mapper that
 *       always reads the guest columns and one that never reads them fail in opposite rows.</li>
 *   <li><b>The day rail</b> — {@code GET /bookings/me/booked-days} against
 *       {@code findBookedDatesByMasterId}'s {@code IN ('CONFIRMED','COMPLETED','NOT_COMPLETED')}
 *       allow-list, with STAFF rows in all five statuses. The rail must make exactly the same
 *       CANCELLED/DECLINED choice for a walk-in that it makes for an app booking, and must agree
 *       with the day listing it navigates to.</li>
 *   <li><b>The SALON_OWNER day rail</b> — the SAME endpoint routed to a DIFFERENT query.
 *       {@code getMyBookedDays} dispatches {@code SALON_OWNER} to
 *       {@code findBookedDatesBySalonIds}, which carries <b>no status predicate at all</b>, unlike
 *       the master-scoped query area 4 pins. Area 4 therefore proves nothing about it. See
 *       {@link BookedDaysRail#should_dotEveryStaffDayIncludingCancelled_when_salonOwnerQueriesBookedDays()}
 *       for the asymmetry it pins and why the test pins it rather than argues with it.</li>
 *   <li><b>The DETAIL endpoint</b> — {@code GET /bookings/&#123;id&#125;}, which is a genuinely
 *       different code path and not a one-row special case of area 1. The listing computes
 *       {@code providerCanReviewClient} from {@code BookingService#loadProviderReviewBatch}'s
 *       page-batched inputs, whose first statement drops every {@code client == null} row; the
 *       detail path computes it per row through {@code computeProviderCanReviewClient}, which has
 *       no such pre-filter. The two therefore have DIFFERENT effective guard counts on a walk-in,
 *       which was measured, not assumed — see {@link BookingDetail} for the mutation.</li>
 * </ol>
 *
 * <h2>Fixture conventions</h2>
 * Rows whose STATUS is the input (the rail matrix) are inserted with SQL, for the reason
 * {@code BookingMyBookedDaysIT#insertBookingWithStatus} documents: driving the transition endpoints
 * would let their own guards, not the test, decide which statuses are reachable. Every OTHER STAFF
 * row here is created through the real {@code POST /masters/{masterId}/bookings}, so the suite is
 * reading back rows production actually writes.
 *
 * <p>The guest identity is deliberately NOT the {@code MASTER_FIRST_NAME}/{@code MASTER_LAST_NAME}
 * pair the base fixture stamps on every seeded user, and the mixed-day registered client gets a
 * third, distinct pair: a fixture whose values coincide cannot fail an attribution assertion.
 *
 * <p>Fixture data uses no occupied-territory locality references.
 */
@Import(TestSecurityConfig.class)
@DisplayName("StaffBookingReadPathIT — a STAFF walk-in read back through the provider read endpoints")
class StaffBookingReadPathIT extends AbstractStaffBookingIT {

    /** Mocked at the interface, exactly as {@link StaffBookingEndpointIT} does — see its comment. */
    @MockBean
    private SmsService smsService;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    private static final String BOOKINGS_URL = "/api/v1/bookings";

    /** Distinct from MASTER_FIRST_NAME and from {@link #CLIENT_FIRST_NAME}. */
    private static final String GUEST_FIRST_NAME = "Оксана";
    private static final String GUEST_LAST_NAME = "Коваль";
    private static final String CLIENT_FIRST_NAME = "Ірина";
    private static final String CLIENT_LAST_NAME = "Бондаренко";
    private static final String CLIENT_AVATAR_URL = "https://cdn.beautica.test/avatars/client.jpg";

    /**
     * {@code findBookedDatesByMasterId}'s allow-list, spelled as the {@code ?status=} filter the
     * provider screen sends. Kept as ONE constant so the rail-vs-list agreement test compares the
     * two sides of the SAME set rather than a hand-retyped near-copy of it.
     */
    private static final List<String> RAIL_STATUSES =
            List.of("CONFIRMED", "COMPLETED", "NOT_COMPLETED");

    // ════════════════════════════════════════════════════════════════════════════
    // 1 — the wire contract of a walk-in row
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /bookings/me — the walk-in row's wire contract")
    class WireContract {

        @Test
        @DisplayName("a STAFF walk-in lists with clientId/clientAvatarUrl null but appointmentId set "
                + "(Phase 22.12), the client name carried by the guest columns, and both review "
                + "flags false")
        void should_returnGuestIdentityAndNullAccountFields_when_providerListsAStaffBooking()
                throws Exception {
            Visit visit = createStaffVisit(salon.masterId(), salon.masterServiceId(),
                    tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            JsonNode row = onlyRowOf(listMyBookings(tokenFor(salon.ownerEmail()), null, null));

            assertThat(row.path("id").asText())
                    .as("control: the listed row is the walk-in that was just created")
                    .isEqualTo(visit.booking(0).toString());
            assertThat(row.path("clientId").isNull())
                    .as("a walk-in has no users row at all (V137 chk_bookings_guest_fields pins "
                            + "client_id IS NULL for a STAFF guest booking), so clientId must "
                            + "serialize as null — not as an empty string, and not omitted")
                    .isTrue();
            assertThat(row.path("clientFirstName").asText(null))
                    .as("the provider's calendar card gets its name from the booking row's own "
                            + "guest_name — there is no User to read it off. Null here is an "
                            + "unnamed appointment on the provider's screen")
                    .isEqualTo(GUEST_FIRST_NAME);
            assertThat(row.path("clientLastName").asText(null))
                    .as("same fallback, guest_surname — required (unlike the LINK flow) precisely "
                            + "so this field is never blank for a walk-in")
                    .isEqualTo(GUEST_LAST_NAME);
            assertThat(row.path("clientAvatarUrl").isNull())
                    .as("clientAvatarUrl has NO guest fallback: no account means no uploaded photo "
                            + "and nothing to fall back TO, so the card renders the generic glyph")
                    .isTrue();
            assertThat(row.path("appointmentId").asText(null))
                    .as("Phase 22.12 — N = 1 still creates an appointments header (D2's locked "
                            + "no-size-short-circuit decision), so a walk-in created after this "
                            + "phase carries the header's OWN id, exactly like the single-service "
                            + "APP/LINK case. Asserted as a VALUE, never as `isNull() == false`: "
                            + "Jackson's MissingNode.isNull() also answers false, so the negative "
                            + "form was satisfied by a mapper that dropped the field entirely — and "
                            + "this is the single assertion carrying 22.12's new behaviour here")
                    .isEqualTo(visit.appointmentId().toString());
            // Both flags are also false for a future-dated CONFIRMED booking of ANY shape, so on
            // THIS row they are documentation of the wire contract rather than a discriminating
            // check. The next test is the one that proves the false comes from the missing client.
            assertThat(row.path("canReview").asBoolean(true))
                    .as("canReview is hard false for a booking with no registered client: there is "
                            + "no account that could ever post the review (proved non-vacuously by "
                            + "should_keepProviderCanReviewClientFalseForTheWalkInOnly_when_bothAreCompleted)")
                    .isFalse();
            assertThat(row.path("providerCanReviewClient").asBoolean(true))
                    .as("the provider->client direction is equally impossible — there is no client "
                            + "record to rate (same, proved non-vacuously by the next test)")
                    .isFalse();
        }

        /**
         * Makes the two {@code false}s above non-vacuous. {@code providerCanReviewClient} is false
         * for a CONFIRMED booking of ANY shape (the status conjunct alone decides it), so the first
         * test cannot tell "false because it is a guest booking" from "false because it is not
         * COMPLETED yet". This test moves both rows to the one status at which the flag is supposed
         * to flip and shows only the registered one does.
         */
        @Test
        @DisplayName("once COMPLETED, the registered-client row flips providerCanReviewClient to "
                + "true while the STAFF walk-in beside it stays false — proving the walk-in's false "
                + "comes from its missing client, not merely from its status")
        void should_keepProviderCanReviewClientFalseForTheWalkInOnly_when_bothAreCompleted()
                throws Exception {
            UUID staffId = createStaffBooking(tomorrowAt(LocalTime.of(12, 0)));
            UUID clientUserId = insertRegisteredClient();
            UUID appId = insertAppBooking(clientUserId, tomorrowAt(LocalTime.of(14, 0)), "CONFIRMED");
            markCompleted(staffId);
            markCompleted(appId);

            List<JsonNode> rows = listMyBookings(tokenFor(salon.ownerEmail()), null, null);

            assertThat(rowById(rows, appId).path("providerCanReviewClient").asBoolean(false))
                    .as("control: a COMPLETED booking WITH a registered client, owned by this "
                            + "salon's owner, is exactly the row the flag exists to turn on — if "
                            + "this is false the test below proves nothing")
                    .isTrue();
            assertThat(rowById(rows, staffId).path("providerCanReviewClient").asBoolean(true))
                    .as("the walk-in is COMPLETED and owned by the same actor, so ONLY the missing "
                            + "client can be keeping it false")
                    .isFalse();
            assertThat(rowById(rows, staffId).path("canReview").asBoolean(true))
                    .as("the client-side flag stays false for the same reason, on the same row")
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 2 & 3 — the day listing the provider's calendar loads
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /bookings/me?from=&to= — the provider's day listing")
    class DayListing {

        @Test
        @DisplayName("a STAFF walk-in is returned by the from/to day filter on its own Kyiv day and "
                + "absent from the next one — the 26.2 range predicate must not treat a walk-in "
                + "differently from any other booking")
        void should_returnTheStaffRowOnItsOwnDayOnly_when_providerFiltersByDate() throws Exception {
            LocalDate day = kyivToday().plusDays(1);
            UUID bookingId = createStaffBooking(tomorrowAtNoon());
            String token = tokenFor(salon.ownerEmail());

            List<JsonNode> onItsDay = listMyBookings(token, day, day);
            List<JsonNode> onTheNextDay = listMyBookings(token, day.plusDays(1), day.plusDays(1));

            assertThat(idsOf(onItsDay))
                    .as("the walk-in must appear when the provider opens %s", day)
                    .containsExactly(bookingId);
            assertThat(onTheNextDay)
                    .as("and must not leak into %s — a walk-in obeys the same half-open Kyiv-day "
                            + "range every other booking obeys", day.plusDays(1))
                    .isEmpty();
        }

        @Test
        @DisplayName("a day holding one STAFF walk-in and one registered-client booking returns "
                + "BOTH, each with its own identity — neither row is dropped and neither borrows "
                + "the other's name")
        void should_serializeBothRowsDistinctly_when_aStaffAndARegisteredBookingShareADay()
                throws Exception {
            LocalDate day = kyivToday().plusDays(1);
            UUID staffId = createStaffBooking(tomorrowAt(LocalTime.of(12, 0)));
            UUID clientUserId = insertRegisteredClient();
            UUID appId = insertAppBooking(clientUserId, tomorrowAt(LocalTime.of(14, 0)), "CONFIRMED");

            List<JsonNode> rows = listMyBookings(tokenFor(salon.ownerEmail()), day, day);

            assertThat(idsOf(rows))
                    .as("both bookings of the day must come back — a walk-in must not be filtered "
                            + "out of a mixed day, and must not displace its neighbour")
                    .containsExactlyInAnyOrder(staffId, appId);

            JsonNode staffRow = rowById(rows, staffId);
            JsonNode appRow = rowById(rows, appId);

            assertThat(staffRow.path("clientFirstName").asText(null))
                    .as("the walk-in keeps its OWN guest identity beside a registered row — a "
                            + "mapper that never reads the guest columns yields null here")
                    .isEqualTo(GUEST_FIRST_NAME);
            assertThat(appRow.path("clientFirstName").asText(null))
                    .as("and the registered row keeps its account name — a mapper that ALWAYS "
                            + "reads the guest columns yields null here instead. The two "
                            + "assertions fail on opposite rows, which is why both are needed")
                    .isEqualTo(CLIENT_FIRST_NAME);
            assertThat(staffRow.path("clientId").isNull())
                    .as("identity is per row: the walk-in must not pick up its neighbour's clientId")
                    .isTrue();
            assertThat(appRow.path("clientId").asText(null))
                    .as("and the registered row must still carry its own")
                    .isEqualTo(clientUserId.toString());
            assertThat(staffRow.path("clientAvatarUrl").isNull())
                    .as("the walk-in has no photo; the registered client on the same page has one, "
                            + "so a page-level (rather than per-row) avatar resolution would show "
                            + "the wrong person's face on the walk-in card")
                    .isTrue();
            assertThat(appRow.path("clientAvatarUrl").asText(null))
                    .as("control: the registered client's avatar is present, so the null above is "
                            + "a real per-row absence and not an empty page-wide value")
                    .isEqualTo(CLIENT_AVATAR_URL);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 4 — the day rail
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /bookings/me/booked-days — the master's day rail")
    class BookedDaysRail {

        /**
         * The rail's own allow-list, exercised entirely with STAFF rows. {@code
         * BookingMyBookedDaysIT} already pins this matrix for {@code 'APP'} rows; the point here is
         * that {@code booking_source} is not part of the predicate and must never become part of it
         * — a walk-in day is dotted on exactly the same statuses an app booking's day is.
         */
        @Test
        @DisplayName("STAFF days follow findBookedDatesByMasterId's IN "
                + "('CONFIRMED','COMPLETED','NOT_COMPLETED') allow-list exactly: confirmed, "
                + "completed and no-show walk-in days are dotted, cancelled- and declined-only "
                + "walk-in days are not")
        void should_dotStaffDaysMatchingTheStatusAllowList_when_masterQueriesBookedDays()
                throws Exception {
            Independent solo = seedIndependentMaster();
            LocalDate confirmed = kyivToday().plusDays(1);
            // The CONFIRMED day is created through the REAL endpoint, so the matrix is anchored on
            // a row production actually wrote; the four terminal statuses are seeded directly
            // because STATUS is the input under test here (see the class javadoc).
            createStaffBooking(solo.masterId(), solo.masterServiceId(), tokenFor(solo.email()),
                    tomorrowAtNoon());
            LocalDate completed = confirmed.plusDays(1);
            LocalDate notCompleted = confirmed.plusDays(2);
            LocalDate cancelled = confirmed.plusDays(3);
            LocalDate declined = confirmed.plusDays(4);
            insertStaffBooking(solo, completed, "COMPLETED");
            insertStaffBooking(solo, notCompleted, "NOT_COMPLETED");
            insertStaffBooking(solo, cancelled, "CANCELLED");
            insertStaffBooking(solo, declined, "DECLINED");

            List<LocalDate> dotted = bookedDays(tokenFor(solo.email()), confirmed, declined);

            assertThat(dotted)
                    .as("the walk-in rail must make the identical CANCELLED/DECLINED choice the "
                            + "2026-08-13 decision fixed for app bookings — booking_source is not "
                            + "in the predicate. NOT_COMPLETED stays dotted: a walk-in no-show is "
                            + "still the master's own record of the visit")
                    .containsExactly(confirmed, completed, notCompleted)
                    .doesNotContain(cancelled, declined);
        }

        /**
         * The rail-vs-list agreement, stated at the precision the endpoint actually supports.
         *
         * <p>The comparison is made against the list <b>filtered to the rail's own allow-list</b>,
         * not against a bare {@code GET /bookings/me}. That is not a convenience: an unfiltered
         * {@code /me} carries NO status predicate at all and returns cancelled bookings too, so
         * "undotted day ⇒ empty list" is simply false against it — the first draft of this test
         * asserted exactly that and went red, correctly. What {@code findBookedDatesByMasterId}
         * encodes is narrower and is what the third assertion below pins: a dot means "this day has
         * a booking in the set the master's screen shows by default". The final assertion keeps the
         * distinction honest by proving the cancelled walk-in is still retrievable — the rail hides
         * a DAY from one filtered view, it does not make a booking disappear.
         */
        @Test
        @DisplayName("a dotted STAFF day opens to a non-empty day listing, and an undotted "
                + "cancelled-only STAFF day is empty under the rail's own status allow-list while "
                + "remaining visible to an unfiltered listing — the rail hides a day from the "
                + "default view, it never hides the booking")
        void should_agreeWithTheDayListing_when_aStaffDayIsDotted() throws Exception {
            Independent solo = seedIndependentMaster();
            LocalDate live = kyivToday().plusDays(1);
            LocalDate cancelledOnly = live.plusDays(1);
            createStaffBooking(solo.masterId(), solo.masterServiceId(), tokenFor(solo.email()),
                    tomorrowAtNoon());
            insertStaffBooking(solo, cancelledOnly, "CANCELLED");
            String token = tokenFor(solo.email());

            List<LocalDate> dotted = bookedDays(token, live, cancelledOnly);

            assertThat(dotted).as("control: only the live walk-in day carries a dot")
                    .containsExactly(live);
            assertThat(listMyBookings(token, live, live, RAIL_STATUSES))
                    .as("the dotted day must open to at least the walk-in itself — a dot the day "
                            + "listing cannot reproduce is a user-visible lie")
                    .isNotEmpty();
            assertThat(listMyBookings(token, cancelledOnly, cancelledOnly, RAIL_STATUSES))
                    .as("and the undotted day must be empty under the SAME status set the rail "
                            + "filters on, read from the other direction — tapping an undotted day "
                            + "in the default view must never open a populated day")
                    .isEmpty();
            assertThat(listMyBookings(token, cancelledOnly, cancelledOnly))
                    .as("control on the scope of that claim: with no status predicate the very "
                            + "same cancelled walk-in IS returned, so the assertion above is about "
                            + "the rail's filtered view and not about the booking being dropped")
                    .hasSize(1);
        }

        /**
         * The SALON_OWNER arm of the SAME endpoint, which the two tests above do not touch at all.
         *
         * <p>{@code BookingService#getMyBookedDays} switches on the caller's role:
         * {@code SALON_MASTER}/{@code INDEPENDENT_MASTER} reach
         * {@code BookingRepository#findBookedDatesByMasterId}, whose {@code IN
         * ('CONFIRMED','COMPLETED','NOT_COMPLETED')} allow-list is what the tests above pin;
         * {@code SALON_OWNER} reaches {@code findBookedDatesBySalonIds}, which has <b>no status
         * predicate whatsoever</b> — as does the {@code CLIENT} arm's
         * {@code findBookedDatesByClientId}. So a walk-in day that the master's own rail leaves
         * undotted IS dotted on their salon owner's rail. Same endpoint, same row, opposite answer.
         *
         * <p><b>This test pins that asymmetry as the current contract; it does not endorse it.</b>
         * The three queries are deliberately not uniform (see the block comment above them in
         * {@code BookingRepository} and {@code getMyBookedDays}'s javadoc: the allow-list exists to
         * mirror what the MASTER's «Мої записи» screen hides by default, a screen-specific rule that
         * was never claimed to hold for the owner's cross-salon view). Until someone decides
         * otherwise at product level, the risk this test exists to eliminate is a "make the three
         * queries consistent" refactor silently changing what a salon owner sees — a change no
         * existing test would catch for a walk-in row, since the salon-scoped query had never been
         * exercised with one. Adding the master allow-list to {@code findBookedDatesBySalonIds}
         * turns this test red on the CANCELLED and DECLINED days.
         *
         * <p>The rows are STAFF and salon-bound (the base fixture's owner-operated master carries
         * {@code salon_id}, which is what {@code findBookedDatesBySalonIds} filters on) so the
         * failure mode this covers is precisely the walk-in one.
         */
        @Test
        @DisplayName("SALON_OWNER's booked-days dots EVERY walk-in day including the cancelled- and "
                + "declined-only ones — findBookedDatesBySalonIds carries no status predicate, so "
                + "the owner's rail deliberately disagrees with the master's on the same rows")
        void should_dotEveryStaffDayIncludingCancelled_when_salonOwnerQueriesBookedDays()
                throws Exception {
            LocalDate confirmed = kyivToday().plusDays(1);
            // The CONFIRMED day comes from the REAL endpoint, so the matrix is anchored on a row
            // production writes; the four terminal statuses are seeded (status is the input here).
            createStaffBooking(tomorrowAtNoon());
            LocalDate completed = confirmed.plusDays(1);
            LocalDate notCompleted = confirmed.plusDays(2);
            LocalDate cancelled = confirmed.plusDays(3);
            LocalDate declined = confirmed.plusDays(4);
            insertSalonStaffBooking(completed, "COMPLETED");
            insertSalonStaffBooking(notCompleted, "NOT_COMPLETED");
            insertSalonStaffBooking(cancelled, "CANCELLED");
            insertSalonStaffBooking(declined, "DECLINED");

            List<LocalDate> dotted = bookedDays(tokenFor(salon.ownerEmail()), confirmed, declined);

            assertThat(dotted)
                    .as("the salon-scoped query has no status predicate at all, so ALL FIVE walk-in "
                            + "days are dotted for the owner — including the two the master-scoped "
                            + "rail above leaves undotted for the identical row shape")
                    .containsExactly(confirmed, completed, notCompleted, cancelled, declined);
            assertThat(dotted)
                    .as("stated as the discriminating claim on its own, because it is the ONLY part "
                            + "of the list above that a 'unify the three booked-days queries' "
                            + "refactor would change: a cancelled-only and a declined-only walk-in "
                            + "day both stay dotted on the owner's rail")
                    .contains(cancelled, declined);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 5 — the single-booking detail endpoint
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * {@code GET /bookings/&#123;id&#125;} for a STAFF walk-in — a code path the listing tests
     * above demonstrably do not cover.
     *
     * <h2>Why "the listing already asserts this" is false here</h2>
     * The two endpoints compute {@code providerCanReviewClient} through the same final conjunction
     * ({@code BookingService#providerCanReviewClient}) but reach it with a different number of
     * live guards:
     * <ul>
     *   <li><b>Listing</b> — {@code loadProviderReviewBatch} drops every {@code client == null} row
     *       from its candidate list before any authority lookup runs, so a walk-in never lands in
     *       {@code withAuthority} and arrives at the conjunction with {@code hasProviderAuthority ==
     *       false}. It is already {@code false} at the FIRST term.</li>
     *   <li><b>Detail</b> — {@code computeProviderCanReviewClient} has no pre-filter. It derives
     *       authority from the actor's relationship to the master/salon, which a walk-in satisfies
     *       exactly as well as an account-bound booking does, so it arrives with
     *       {@code hasProviderAuthority == true} and {@code isProviderReviewEligible == true} once
     *       the visit is COMPLETED. The {@code hasClient} conjunct is then the ONLY term left
     *       holding the flag down.</li>
     * </ul>
     *
     * <p>That is not a theory. Mutation <b>M6</b> of this suite's authoring pass deleted the single
     * {@code hasClient} conjunct from {@code providerCanReviewClient} and every listing test in this
     * class stayed GREEN — because on that path the deletion is a no-op. The COMPLETED-walk-in test
     * below is the one that goes red for it, and is the reason this nested class exists rather than
     * a comment saying the listing covers it. See {@code providerCanReviewClient}'s javadoc, which
     * now records the redundancy from the production side.
     *
     * <p>The first test is the plain wire contract, so the detail response's field set is pinned
     * independently of the review flags; the second is the M6 kill.
     */
    @Nested
    @DisplayName("GET /bookings/{id} — the walk-in row on the per-row detail path")
    class BookingDetail {

        @Test
        @DisplayName("a STAFF walk-in fetched by id returns the same wire contract the listing does "
                + "— clientId/clientAvatarUrl null but appointmentId set (Phase 22.12), name from "
                + "the guest columns, both review flags false")
        void should_returnGuestIdentityAndNullAccountFields_when_providerFetchesAStaffBookingById()
                throws Exception {
            Visit visit = createStaffVisit(salon.masterId(), salon.masterServiceId(),
                    tokenFor(salon.ownerEmail()), tomorrowAtNoon());

            JsonNode row = getBooking(tokenFor(salon.ownerEmail()), visit.booking(0));

            assertThat(row.path("id").asText())
                    .as("control: the fetched row is the walk-in that was just created")
                    .isEqualTo(visit.booking(0).toString());
            assertThat(row.path("clientId").isNull())
                    .as("clientId must serialize as null on the detail path too — findByIdWith"
                            + "FullGraph LEFT JOIN FETCHes the client precisely so a null-client row "
                            + "resolves here at all (the track-24.7 CRITICAL), and the mapper must "
                            + "then carry that null out rather than substitute anything for it")
                    .isTrue();
            assertThat(row.path("clientFirstName").asText(null))
                    .as("the detail screen's header name comes from the booking row's own "
                            + "guest_name — same fallback as the card, exercised through the "
                            + "single-row enrichSingle path rather than the batch one")
                    .isEqualTo(GUEST_FIRST_NAME);
            assertThat(row.path("clientLastName").asText(null))
                    .as("same fallback, guest_surname")
                    .isEqualTo(GUEST_LAST_NAME);
            assertThat(row.path("clientAvatarUrl").isNull())
                    .as("no account means nothing to fall back TO, on this path as on the listing")
                    .isTrue();
            assertThat(row.path("appointmentId").asText(null))
                    .as("Phase 22.12 — N = 1 still creates an appointments header, so this walk-in "
                            + "carries the header's OWN id on the per-row detail path exactly as it "
                            + "does on the listing path (same mapper, same underlying column). "
                            + "Asserted as a VALUE — see the listing test for why `isNull() == "
                            + "false` was satisfied by a dropped field")
                    .isEqualTo(visit.appointmentId().toString());
            // Both flags are false for a future-dated CONFIRMED booking of ANY shape, so here they
            // pin the wire contract only. The next test is the discriminating one.
            assertThat(row.path("canReview").asBoolean(true))
                    .as("no registered account exists that could post a review")
                    .isFalse();
            assertThat(row.path("providerCanReviewClient").asBoolean(true))
                    .as("and none exists to be rated (made non-vacuous by the next test)")
                    .isFalse();
        }

        /**
         * <b>The M6 kill.</b> Deleting the {@code hasClient} conjunct from
         * {@code BookingService#providerCanReviewClient} leaves this exact request answering
         * {@code true}: the salon owner HAS provider authority over the walk-in, the visit IS
         * COMPLETED, and no {@code ClientReview} exists for it — three of four conjuncts satisfied,
         * with the deleted one the only thing that had been holding the flag down.
         *
         * <p>The registered-client row is fetched through the SAME endpoint in the same test, so the
         * {@code false} cannot be a detail path that simply never returns {@code true}.
         */
        @Test
        @DisplayName("fetched by id once COMPLETED, the registered-client booking reports "
                + "providerCanReviewClient true while the STAFF walk-in reports false — the detail "
                + "path's per-row hasClient guard, which no listing test can reach")
        void should_keepProviderCanReviewClientFalse_when_aCompletedWalkInIsFetchedById()
                throws Exception {
            UUID staffId = createStaffBooking(tomorrowAt(LocalTime.of(12, 0)));
            UUID clientUserId = insertRegisteredClient();
            UUID appId = insertAppBooking(clientUserId, tomorrowAt(LocalTime.of(14, 0)), "CONFIRMED");
            markCompleted(staffId);
            markCompleted(appId);
            String token = tokenFor(salon.ownerEmail());

            JsonNode staffRow = getBooking(token, staffId);
            JsonNode appRow = getBooking(token, appId);

            assertThat(appRow.path("providerCanReviewClient").asBoolean(false))
                    .as("control: a COMPLETED booking WITH a registered client, fetched by the "
                            + "salon owner through this very endpoint, is exactly what the flag "
                            + "exists to turn on — if this is false the assertion below is vacuous")
                    .isTrue();
            assertThat(staffRow.path("providerCanReviewClient").asBoolean(true))
                    .as("the walk-in differs from the control ONLY in having no client, and the "
                            + "detail path reaches the hasClient conjunct with the other three "
                            + "already true — so this false is that one conjunct's entire output. "
                            + "Deleting it (mutation M6) turns this true while every listing test "
                            + "in this class stays green, which is why this test exists")
                    .isFalse();
            assertThat(staffRow.path("canReview").asBoolean(true))
                    .as("the client direction stays false on the same row for the same reason — "
                            + "there is no account to post the review from")
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 7 — cross-tenant READ: a walk-in is not readable by a provider from another salon
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * The IDOR half nothing covered on the READ side of a walk-in.
     *
     * <p>The foreign-owner / foreign-master 403 matrix exists only on the CREATE endpoint
     * ({@code StaffBookingEndpointIT.Denied}); {@link AbstractStaffVisitShapeIT} covers
     * {@code CLIENT → 403} only, which is a different guard (a CLIENT is refused by ownership, a
     * foreign PROVIDER by salon/master scope); and {@code BookingDetailContractIT}'s cross-tenant row
     * uses a client-bound booking. So the shape a walk-in read actually presents —
     * {@code client_id IS NULL}, exactly what an "actor is the owning client OR actor has provider
     * authority" predicate falls through — had never met a foreign provider.
     *
     * <p>Both directions are asserted, because they fail independently: the by-id fetch is the
     * per-row authorization gate, while {@code /bookings/me}'s scope is a QUERY predicate. A
     * listing that leaked the row would be an equally real cross-tenant read even with the detail
     * gate intact.
     */
    @Nested
    @DisplayName("Cross-tenant reads — a provider from another salon")
    class ForeignProvider {

        @Test
        @DisplayName("GET /bookings/{id} by the owner of a DIFFERENT salon is 403 on a walk-in")
        void should_return403_when_aForeignSalonOwnerFetchesAWalkInById() throws Exception {
            UUID walkInId = createStaffBooking(tomorrowAtNoon());
            Salon other = seedSalon();

            ResponseEntity<String> resp = restTemplate.exchange(
                    BOOKINGS_URL + "/" + walkInId, HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(tokenFor(other.ownerEmail()))), String.class);

            assertThat(resp.getStatusCode())
                    .as("a walk-in row is client-less, so the ownership half of the guard can never "
                            + "match — provider scope is the only thing refusing this read. "
                            + "body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("GET /bookings/me for the owner of a DIFFERENT salon never lists the walk-in")
        void should_omitTheWalkIn_when_aForeignSalonOwnerListsTheirCalendar() throws Exception {
            UUID walkInId = createStaffBooking(tomorrowAtNoon());
            Salon other = seedSalon();

            List<UUID> foreignIds = idsOf(listMyBookings(tokenFor(other.ownerEmail()), null, null));
            List<UUID> ownIds = idsOf(listMyBookings(tokenFor(salon.ownerEmail()), null, null));

            assertThat(ownIds)
                    .as("control: the row IS listable — so the absence below is scoping, not an "
                            + "endpoint that lists nothing")
                    .contains(walkInId);
            assertThat(foreignIds)
                    .as("the listing's scope predicate must exclude another salon's walk-in; a leak "
                            + "here is a cross-tenant read even with the by-id gate intact")
                    .doesNotContain(walkInId);
        }
    }

    // ── staff-create helpers (the real endpoint) ─────────────────────────────────

    /**
     * Creates a SINGLE-SERVICE walk-in on the base fixture's salon master, as its owner, and returns
     * its BOOKING id — not the visit id.
     *
     * <p>Phase 22.14 — the 201 body is now {@code AppointmentDetailResponse}, whose top-level
     * {@code id} is the appointment (visit) header, not the row every caller of this helper reads by
     * ({@code GET /bookings/{id}}, the day/list rails, {@code markCompleted}). Every command built
     * here is single-service, so {@code items[0]} IS the one booking the visit contains — read the
     * booking id off there instead of off the header.
     */
    private UUID createStaffBooking(OffsetDateTime startsAt) throws Exception {
        return createStaffBooking(salon.masterId(), salon.masterServiceId(),
                tokenFor(salon.ownerEmail()), startsAt);
    }

    private UUID createStaffBooking(
            UUID masterId, UUID masterServiceId, String token, OffsetDateTime startsAt)
            throws Exception {
        return createStaffVisit(masterId, masterServiceId, token, startsAt).booking(0);
    }

    /**
     * The same create, returning BOTH ids — the header's and the single chained booking's.
     *
     * <p>Exists because the {@code appointmentId} wire assertions must compare a VALUE, and a helper
     * that discards the header id forces those assertions into the "is it non-null" shape that
     * Jackson's {@code MissingNode} silently satisfies. Not a second copy of the POST:
     * {@link #createStaffBooking(UUID, UUID, String, OffsetDateTime)} delegates here.
     *
     * <p>{@code AbstractStaffBookingIT#postWalkIn} is NOT reused: it hardcodes the base fixture's
     * guest name pair, while this suite's whole point is asserting attribution against its own
     * deliberately-distinct {@link #GUEST_FIRST_NAME}/{@link #GUEST_LAST_NAME}.
     */
    private Visit createStaffVisit(
            UUID masterId, UUID masterServiceId, String token, OffsetDateTime startsAt)
            throws Exception {
        String body = """
                {"masterServiceIds":["%s"],"startsAt":"%s",
                 "guest":{"name":"%s","surname":"%s","phone":"%s"}}
                """.formatted(masterServiceId, startsAt, GUEST_FIRST_NAME, GUEST_LAST_NAME, RAW_PHONE);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/bookings", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("walk-in setup must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        List<UUID> bookingIds = new ArrayList<>();
        data.path("items").forEach(item -> bookingIds.add(UUID.fromString(item.path("bookingId").asText())));
        return new Visit(UUID.fromString(data.path("id").asText()), bookingIds);
    }

    // ── SQL fixtures ─────────────────────────────────────────────────────────────

    /**
     * A CLIENT with a name and an avatar, both deliberately unlike the guest's and unlike the base
     * fixture's {@code MASTER_FIRST_NAME}/{@code MASTER_LAST_NAME}: an attribution assertion cannot
     * fail if the two identities it must tell apart hold the same strings.
     */
    private UUID insertRegisteredClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, "
                        + "last_name, avatar_url, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', ?, ?, ?, true, true)",
                id, "staff-read-client-" + id + "@beautica.test",
                CLIENT_FIRST_NAME, CLIENT_LAST_NAME, CLIENT_AVATAR_URL);
        return id;
    }

    /** An ordinary account-bound (APP) booking on the base fixture's salon master. */
    private UUID insertAppBooking(UUID clientId, OffsetDateTime startsAt, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, "
                        + "salon_id, status, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'APP', NOW(), NOW())",
                id, clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), status,
                startsAt, startsAt.plusMinutes(DURATION_MINUTES), PRICE, DURATION_MINUTES);
        return id;
    }

    /**
     * A STAFF walk-in row at a caller-chosen status, seeded with SQL because STATUS is the input of
     * the rail matrix — see the class javadoc, and {@code BookingMyBookedDaysIT}'s
     * {@code insertBookingWithStatus} for the same rationale. Satisfies V137's STAFF arm: null
     * {@code client_id}, non-blank guest triple, null {@code cancel_token}, and a
     * {@code created_by_user_id} the application layer requires.
     */
    private void insertStaffBooking(Independent solo, LocalDate day, String status) {
        insertStaffBooking(solo.masterId(), solo.masterServiceId(), null, solo.userId(), day, status);
    }

    /**
     * The salon-bound counterpart, on the base fixture's owner-operated master. {@code salon_id} is
     * what {@code findBookedDatesBySalonIds} filters on, so a row without it is invisible to the
     * SALON_OWNER rail — the real endpoint stamps it (pinned by
     * {@code StaffBookingEndpointIT}), and this seeded row must match that shape or the owner-rail
     * test would pass by seeing nothing at all.
     */
    private void insertSalonStaffBooking(LocalDate day, String status) {
        insertStaffBooking(salon.masterId(), salon.masterServiceId(), salon.salonId(),
                salon.ownerId(), day, status);
    }

    private void insertStaffBooking(UUID masterId, UUID masterServiceId, UUID salonId,
            UUID createdByUserId, LocalDate day, String status) {
        OffsetDateTime startsAt = day.atTime(LocalTime.NOON)
                .atZone(com.beautica.common.TimeZones.KYIV).toOffsetDateTime();
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, "
                        + "status, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_surname, guest_phone, created_by_user_id, created_at, "
                        + "updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'STAFF', ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, masterServiceId, salonId, status,
                startsAt, startsAt.plusMinutes(DURATION_MINUTES), PRICE, DURATION_MINUTES,
                GUEST_FIRST_NAME, GUEST_LAST_NAME, E164_PHONE, createdByUserId);
    }

    private void markCompleted(UUID bookingId) {
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
    }

    private LocalDate kyivToday() {
        return LocalDate.now(com.beautica.common.TimeZones.KYIV);
    }

    // ── read-endpoint helpers ────────────────────────────────────────────────────

    private List<JsonNode> listMyBookings(String token, LocalDate from, LocalDate to) throws Exception {
        return listMyBookings(token, from, to, List.of());
    }

    private List<JsonNode> listMyBookings(
            String token, LocalDate from, LocalDate to, List<String> statuses) throws Exception {
        StringBuilder url = new StringBuilder(BOOKINGS_URL).append("/me?size=50");
        if (from != null) {
            url.append("&from=").append(from);
        }
        if (to != null) {
            url.append("&to=").append(to);
        }
        statuses.forEach(s -> url.append("&status=").append(s));
        ResponseEntity<String> resp = restTemplate.exchange(
                url.toString(), HttpMethod.GET, new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/me must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        List<JsonNode> rows = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data").path("data").forEach(rows::add);
        return rows;
    }

    /**
     * {@code GET /bookings/&#123;id&#125;}. Unwraps ONE level of {@code ApiResponse} — the detail
     * endpoint returns the row directly under {@code data}, unlike {@code /me}'s
     * {@code data.data} page envelope, and reading the wrong depth would silently yield a missing
     * node whose {@code path(...)} answers null for every field under assertion.
     */
    private JsonNode getBooking(String token, UUID bookingId) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/{id} must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("id").asText(null))
                .as("guard against asserting against an empty node: the detail row must be under "
                        + "data and must be the booking that was requested")
                .isEqualTo(bookingId.toString());
        return data;
    }

    private List<LocalDate> bookedDays(String token, LocalDate from, LocalDate to) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me/booked-days?from=" + from + "&to=" + to,
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/me/booked-days must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        List<LocalDate> days = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data")
                .forEach(node -> days.add(LocalDate.parse(node.asText())));
        return days;
    }

    private static List<UUID> idsOf(List<JsonNode> rows) {
        return rows.stream().map(r -> UUID.fromString(r.path("id").asText())).toList();
    }

    private static JsonNode onlyRowOf(List<JsonNode> rows) {
        assertThat(rows).as("exactly one booking is expected on this page").hasSize(1);
        return rows.get(0);
    }

    private static JsonNode rowById(List<JsonNode> rows, UUID id) {
        return rows.stream()
                .filter(r -> id.toString().equals(r.path("id").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "booking " + id + " is missing from the listing — ids present: " + idsOf(rows)));
    }
}
