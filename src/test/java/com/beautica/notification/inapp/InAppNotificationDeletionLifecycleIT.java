package com.beautica.notification.inapp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.user.ClientSelfDeleteTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 332 gap close: {@code InAppNotificationRepositoryIT} proves the {@code V181} FK cascades
 * fire under RAW SQL {@code DELETE}s. Nothing proved they fire the same way under the REAL
 * production deletion services — {@link com.beautica.user.ClientAccountDeletionService}, {@link
 * com.beautica.user.StaffAccountSelfDeletionService}, {@link
 * com.beautica.salon.service.SalonService#removeMaster}, {@link
 * com.beautica.salon.service.SalonService#deactivateSalon} — which run inside their own
 * multi-statement transactions, batch-deletes and pre-cascade ordering. This class seeds feed rows
 * (see {@link #insertFeedRow} below) and then drives the actual HTTP endpoints, exactly as {@code
 * ClientAccountHardDeleteIT} / {@code StaffAccountHardDeleteIT} / {@code
 * MasterSelfDeleteBookingDisposalIT} do — reusing {@link ClientSelfDeleteTestFixtures} and {@link
 * BookingTestFixtures} rather than hand-rolling parallel builders.
 *
 * <p>Feed rows are seeded via a raw-SQL {@code INSERT} (the {@link #insertFeedRow} helper below),
 * mirroring {@link com.beautica.notification.inapp.repository.InAppNotificationRepository
 * #insertIgnoringDuplicate}'s exact column list and
 * semantics — never {@code repository.save(entity)} (there is no such method: the entity's
 * repository deliberately extends the bare {@code Repository} marker, not {@code JpaRepository}).
 * Calling the repository's own {@code @Modifying} native-query method directly from a test method
 * was tried first and rejected: {@code AbstractIntegrationTest} is a plain {@code @SpringBootTest},
 * not {@code @DataJpaTest} (which wraps every test in an ambient transaction), so the call throws
 * {@code TransactionRequiredException} with no transaction to join; and even if a test-level
 * {@code @Transactional} were added, {@code TestRestTemplate} drives the deletion endpoint on a
 * SEPARATE thread/connection, which would never see an uncommitted insert. Every other fixture in
 * this file's sibling classes ({@code ClientSelfDeleteTestFixtures}, {@code BookingTestFixtures})
 * already uses plain {@code jdbcTemplate.update} for exactly this reason (REUSE-FIRST — same
 * convention, not a new one).
 *
 * <p><b>Non-vacuity, per test — what assertion would go red if the named FK action were flipped:</b>
 * <ul>
 *   <li>{@link #should_deleteRecipientFeedRows_when_clientHardDeletesOwnAccount} — if {@code
 *       recipient_user_id} were changed from {@code ON DELETE CASCADE} to {@code SET NULL} or
 *       {@code NO ACTION}, the "row A gone" assertion would fail (row A would survive with a null
 *       recipient, or the client hard-delete itself would 500 on an FK violation instead of
 *       returning 204).</li>
 *   <li>{@link #should_deleteFeedRowsReferencingBooking_when_clientSelfDeleteHardDeletesFutureBooking}
 *       — if {@code booking_id} were {@code SET NULL} instead of {@code CASCADE}, the "future-booking
 *       row gone" assertion would fail (the row would survive with {@code booking_id = NULL} instead
 *       of disappearing).</li>
 *   <li>{@link #should_deleteFeedRowsReferencingAppointment_when_masterSelfDeleteCollapsesOnlyLegHeader}
 *       — same reasoning, for {@code appointment_id}.</li>
 *   <li>{@link #should_hardDeleteRecipientRows_andNullSubjectUserId_when_ownerRemovesMasterFromSalon}
 *       — two independent flips: if {@code recipient_user_id} were not {@code CASCADE}, the removed
 *       master's own row would survive; if {@code subject_user_id} were {@code CASCADE} instead of
 *       {@code SET NULL}, the owner's surviving {@code INVITE_ACCEPTED} row would be DELETED instead
 *       of merely having its {@code subject_user_id} column nulled — the "row still exists" assertion
 *       would fail, not just the null check.</li>
 *   <li>{@link #should_hardDeleteStaffRecipientRows_butNeverNullSalonId_when_ownerDeactivatesSalon} —
 *       does NOT exercise {@code salon_id}'s FK action at all; see that test's own javadoc.</li>
 * </ul>
 */
@DisplayName("in_app_notification FK cascades under the REAL deletion services (phase 332 gap close)")
class InAppNotificationDeletionLifecycleIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(5);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(3);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    // ── 1. Client account self-deletion — recipient_user_id CASCADE ────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/users/me (CLIENT) — the client's own feed rows are gone; a "
            + "different recipient's row about the SAME booking is untouched")
    void should_deleteRecipientFeedRows_when_clientHardDeletesOwnAccount() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        // A PAST/COMPLETED booking — deliberately untouched by the client self-delete's
        // future-booking cancel+hard-delete cascade (steps 4-5), so the ONLY thing that can make
        // row A disappear is the recipient_user_id CASCADE off the users row itself, not a
        // booking_id CASCADE as a side effect.
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);

        UUID rowA = UUID.randomUUID();
        insertFeedRow(rowA, clientId,
                InAppNotificationType.REVIEW_REQUESTED.name(), pastBookingId, null, null, null,
                "REVIEW_REQUESTED:" + pastBookingId);
        UUID rowB = UUID.randomUUID();
        insertFeedRow(rowB, salon.masterUserId(),
                InAppNotificationType.BOOKING_CREATED.name(), pastBookingId, null, null, null,
                "BOOKING_CREATED:" + pastBookingId);
        // Non-vacuity guard: prove both rows actually landed BEFORE the act — otherwise a silently
        // swallowed insert would make the post-delete isZero() assertion below trivially true.
        assertThat(rowCount(rowA)).isEqualTo(1);
        assertThat(rowCount(rowB)).isEqualTo(1);

        String token = fixtures.tokenFor(emailOf(clientId));
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(rowA))
                .as("the deleted client's own feed row must be gone via recipient_user_id CASCADE")
                .isZero();
        assertThat(rowCount(rowB))
                .as("a different recipient's row about the same (untouched) booking must survive "
                        + "unchanged")
                .isEqualTo(1);
        assertThat(bookingIdOf(rowB)).isEqualTo(pastBookingId);
    }

    // ── 2. Client self-delete's future-booking hard-delete — booking_id CASCADE ────────────────

    @Test
    @DisplayName("DELETE /api/v1/users/me (CLIENT) — a feed row about the client's FUTURE booking "
            + "is gone once that booking is hard-deleted; a sibling row about a PAST booking of the "
            + "same recipient survives")
    void should_deleteFeedRowsReferencingBooking_when_clientSelfDeleteHardDeletesFutureBooking()
            throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID futureBookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);

        // Both rows recipient the MASTER, not the client — isolates the effect to booking_id
        // CASCADE, not recipient_user_id CASCADE (the client's own users row is what's deleted,
        // but that is a SEPARATE FK from the one this test targets).
        UUID futureRow = UUID.randomUUID();
        insertFeedRow(futureRow, salon.masterUserId(),
                InAppNotificationType.BOOKING_CREATED.name(), futureBookingId, null, null, null,
                "BOOKING_CREATED:" + futureBookingId);
        UUID pastRow = UUID.randomUUID();
        insertFeedRow(pastRow, salon.masterUserId(),
                InAppNotificationType.BOOKING_CREATED.name(), pastBookingId, null, null, null,
                "BOOKING_CREATED:" + pastBookingId);
        assertThat(rowCount(futureRow)).isEqualTo(1);
        assertThat(rowCount(pastRow)).isEqualTo(1);

        String token = fixtures.tokenFor(emailOf(clientId));
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(futureBookingId))
                .as("sanity — ClientAccountDeletionService step 5 really hard-deletes the future "
                        + "booking (cancelled-then-deleted, D4)")
                .isFalse();
        assertThat(rowCount(futureRow))
                .as("the feed row pointing at the now-hard-deleted future booking must be gone via "
                        + "booking_id CASCADE")
                .isZero();
        assertThat(rowCount(pastRow))
                .as("the sibling row about the untouched past booking must survive")
                .isEqualTo(1);
        assertThat(bookingIdOf(pastRow)).isEqualTo(pastBookingId);
    }

    // ── 3. Master self-delete's booking disposal — appointment_id CASCADE ──────────────────────

    @Test
    @DisplayName("DELETE /api/v1/users/me (SALON_MASTER) — a feed row keyed by appointment_id is "
            + "gone once its header collapses (the departing master's only leg); a sibling row "
            + "about a past booking of the same recipient survives")
    void should_deleteFeedRowsReferencingAppointment_when_masterSelfDeleteCollapsesOnlyLegHeader()
            throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE, appointmentId);
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);

        UUID appointmentRow = UUID.randomUUID();
        insertFeedRow(appointmentRow, clientId,
                InAppNotificationType.BOOKING_CANCELLED_MASTER_REMOVED.name(), null, appointmentId,
                null, null, "BOOKING_CANCELLED_MASTER_REMOVED:" + appointmentId);
        UUID pastRow = UUID.randomUUID();
        insertFeedRow(pastRow, clientId,
                InAppNotificationType.BOOKING_CREATED.name(), pastBookingId, null, null, null,
                "BOOKING_CREATED:" + pastBookingId);
        assertThat(rowCount(appointmentRow)).isEqualTo(1);
        assertThat(rowCount(pastRow)).isEqualTo(1);

        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.appointmentExists(appointmentId))
                .as("sanity — a header whose only leg belonged to the departing master is "
                        + "collapsed (hard-deleted), mirroring MasterSelfDeleteBookingDisposalIT")
                .isFalse();
        assertThat(rowCount(appointmentRow))
                .as("the feed row pointing at the now-collapsed appointment header must be gone "
                        + "via appointment_id CASCADE")
                .isZero();
        assertThat(rowCount(pastRow))
                .as("the sibling row about the untouched past booking must survive")
                .isEqualTo(1);
    }

    // ── 4. Owner removes one master — recipient_user_id CASCADE + subject_user_id SET NULL ─────

    @Test
    @DisplayName("DELETE /salons/{salonId}/masters/{masterId} — the removed master's own feed "
            + "rows are gone; the owner's INVITE_ACCEPTED row about that master survives with "
            + "subject_user_id NULL")
    void should_hardDeleteRecipientRows_andNullSubjectUserId_when_ownerRemovesMasterFromSalon()
            throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);

        UUID masterOwnRow = UUID.randomUUID();
        insertFeedRow(masterOwnRow, salon.masterUserId(),
                InAppNotificationType.BOOKING_CREATED.name(), pastBookingId, null, null, null,
                "BOOKING_CREATED:" + pastBookingId);
        UUID inviteRow = UUID.randomUUID();
        insertFeedRow(inviteRow, salon.ownerId(),
                InAppNotificationType.INVITE_ACCEPTED.name(), null, null, salon.salonId(),
                salon.masterUserId(),
                "INVITE_ACCEPTED:" + salon.salonId() + ":" + salon.masterUserId());
        assertThat(rowCount(masterOwnRow)).isEqualTo(1);
        assertThat(rowCount(inviteRow)).isEqualTo(1);

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId() + "/masters/" + salon.masterId(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(salon.masterUserId()))
                .as("sanity — removeMaster really hard-deletes the master's users row")
                .isFalse();
        assertThat(rowCount(masterOwnRow))
                .as("the removed master's own feed row must be gone via recipient_user_id CASCADE")
                .isZero();
        assertThat(rowCount(inviteRow))
                .as("the owner's INVITE_ACCEPTED row must SURVIVE — subject_user_id is SET NULL, "
                        + "not CASCADE, because its recipient (the owner) is a different, unrelated "
                        + "user")
                .isEqualTo(1);
        assertThat(subjectUserIdOf(inviteRow))
                .as("the surviving row's subject_user_id column must be nulled")
                .isNull();
    }

    // ── 5. Owner deletes the whole salon — deleteSalonStaff's bulk hard-delete; salon_id NEVER
    //        nulled because the salons row itself is never hard-deleted ─────────────────────────

    @Test
    @DisplayName("DELETE /salons/{salonId} — deleteSalonStaff's BULK hard-delete removes the "
            + "master's own feed rows exactly like removeMaster's single-master path; the owner's "
            + "salon-scoped row survives with salon_id UNCHANGED because deactivateSalon never "
            + "hard-deletes the salons row (finding: the phase-332 doc's 'salon deletion' "
            + "acceptance criterion has no reachable production trigger today)")
    void should_hardDeleteStaffRecipientRows_butNeverNullSalonId_when_ownerDeactivatesSalon()
            throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);

        UUID masterOwnRow = UUID.randomUUID();
        insertFeedRow(masterOwnRow, salon.masterUserId(),
                InAppNotificationType.BOOKING_CREATED.name(), pastBookingId, null, null, null,
                "BOOKING_CREATED:" + pastBookingId);
        // The owner is NOT hard-deleted by deactivateSalon (only staff are) — a salon-scoped row
        // recipiented to the owner is the right probe for what happens to salon_id.
        UUID ownerSalonRow = UUID.randomUUID();
        insertFeedRow(ownerSalonRow, salon.ownerId(),
                InAppNotificationType.INVITE_ACCEPTED.name(), null, null, salon.salonId(), null,
                "INVITE_ACCEPTED:" + salon.salonId());
        assertThat(rowCount(masterOwnRow)).isEqualTo(1);
        assertThat(rowCount(ownerSalonRow)).isEqualTo(1);

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(salon.masterUserId()))
                .as("sanity — deleteSalonStaff really hard-deletes the salon's staff users")
                .isFalse();
        assertThat(rowCount(masterOwnRow))
                .as("the disposed master's own feed row is gone via recipient_user_id CASCADE — "
                        + "same FK, different (bulk) code path from test 4's single removeMaster")
                .isZero();
        assertThat(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salon.salonId())))
                .as("deactivateSalon only flips is_active — the salons row itself is never deleted")
                .isFalse();
        assertThat(salonRowExists(salon.salonId()))
                .as("the salons row still physically exists")
                .isTrue();
        assertThat(rowCount(ownerSalonRow))
                .as("the owner's salon-scoped row survives (its recipient was never touched)")
                .isEqualTo(1);
        assertThat(salonIdOf(ownerSalonRow))
                .as("salon_id is UNCHANGED, not nulled — deactivateSalon never issues a DELETE "
                        + "against the salons table, so the ON DELETE SET NULL action this row's "
                        + "column carries is never triggered by this flow. Only a raw SQL DELETE "
                        + "FROM salons (InAppNotificationRepositoryIT#should_setSalonIdNull_when_"
                        + "salonDeleted) or a future admin hard-delete endpoint (neither of which "
                        + "exists today) would exercise it.")
                .isEqualTo(salon.salonId());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Raw-SQL insert mirroring {@code InAppNotificationRepository#insertIgnoringDuplicate}'s exact
     * column list — see this class's javadoc for why a raw {@code jdbcTemplate} write is used here
     * instead of calling that repository method directly (auto-commit visibility to the SEPARATE
     * {@code TestRestTemplate} request thread).
     */
    private void insertFeedRow(UUID id, UUID recipientUserId, String type, UUID bookingId,
                                UUID appointmentId, UUID salonId, UUID subjectUserId, String dedupKey) {
        jdbcTemplate.update("""
                INSERT INTO in_app_notification
                    (id, recipient_user_id, type, booking_id, appointment_id, salon_id, subject_user_id, dedup_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, recipientUserId, type, bookingId, appointmentId, salonId, subjectUserId, dedupKey);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private int rowCount(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private UUID bookingIdOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT booking_id FROM in_app_notification WHERE id = ?", UUID.class, id);
    }

    private UUID subjectUserIdOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT subject_user_id FROM in_app_notification WHERE id = ?", UUID.class, id);
    }

    private UUID salonIdOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT salon_id FROM in_app_notification WHERE id = ?", UUID.class, id);
    }

    private boolean salonRowExists(UUID salonId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM salons WHERE id = ?", Integer.class, salonId);
        return count != null && count == 1;
    }
}
