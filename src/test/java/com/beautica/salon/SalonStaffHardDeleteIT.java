package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.exception.SalonDeletionBlockedException;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.review.service.RatingRecalculationService;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real-DB coverage for {@code SalonService#deactivateSalon}'s salon-deletion staff HARD-DELETE
 * cascade (Phase 295) — see
 * {@code docs/backend-phases/phase-295-salon-deletion-hard-deletes-staff.md}.
 *
 * <p><b>This class replaces {@code SalonStaffDeactivationCascadeIT} (phase 295 D3, rewritten not
 * extended).</b> That class asserted {@code is_active = false} on rows this phase now DELETES, so
 * every one of its end-state assertions was inverted rather than added to. The one case kept
 * verbatim in intent is the different-salon negative, re-pointed from "still active" to "still
 * exists". {@code SalonStaffPiiScrubIT} is deleted outright, not migrated: phase 291's tombstone /
 * PII-scrub apparatus existed ONLY because the {@code users} row survived the deletion, and it
 * does not survive any more (D2).
 *
 * <p><b>The order under test is the only representable one</b> (phase 294 case 5, marked BINDING
 * ON PHASE 295): {@code Master#detach(...)} first — one UPDATE writing the name snapshot,
 * {@code user_id = NULL}, {@code detached_at} and {@code is_active = false} together — and only
 * then {@code DELETE FROM users}. A bare {@code DELETE FROM users} leaves a row satisfying neither
 * arm of {@code chk_masters_detachment_coherent}, and Postgres cannot defer a CHECK. Cases 1 and 2
 * are what fail if that order is ever inverted or the flush between the two steps is dropped.
 *
 * <p>Every fixture is inserted with raw SQL (the local house convention shared with
 * {@code SalonDeactivationCascadeIT} and {@code StaffClientReferenceAuditServiceIT}) rather than
 * driven through the invite/registration flow — this class cares about the STATE the delete leaves
 * behind, not about how staff came to exist. {@link BookingTestFixtures} is reused for the login /
 * bearer-header helpers only, so case 3 exercises a real JWT-authenticated read.
 *
 * <h3>Mutation checks (both executed, both mandatory — phase doc § Mutation check)</h3>
 * <ul>
 *   <li>Force {@code MasterRepository#findIdsWithHistoricalReferences} to return an EMPTY list
 *       (it was {@code countHistoricalReferences} returning {@code 0} before the phase 295 audit
 *       collapsed the per-master loop into one set-based query) → cases 2, 2b, 4, 8, 10 and 11 red
 *       on {@code bookings_master_id_fkey}, proving the conditional is what prevents the crash
 *       rather than an untested branch. ✅ EXECUTED, RED as specified.</li>
 *   <li>Reverse the D6 ordering in {@code SalonService#deactivateSalon} (staff delete before the
 *       booking cascade) → case 11 was expected red. ❌ EXECUTED, STILL GREEN — see case 11's own
 *       javadoc for the measured reason and why no assertion can currently distinguish the two
 *       orders. Not hidden, not worked around.</li>
 * </ul>
 */
@DisplayName("SalonService.deactivateSalon — Phase 295 salon-deletion staff HARD-DELETE cascade")
class SalonStaffHardDeleteIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(7);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private SalonService salonService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Phase 290 finding #2 is still live under phase 295: {@code MasterService#deactivateMasters}
     * still runs, still ahead of the delete, and must still publish ONE
     * {@code SalonStaffChangedEvent} for the whole batch. SpyBean wraps the REAL bean, so the
     * end-state assertions elsewhere in this class stay meaningful.
     */
    @SpyBean
    private RatingRecalculationService ratingRecalculationService;

    private BookingTestFixtures fixtures;
    private TransactionTemplate tx;

    /**
     * The appointment-child decline path ({@code AppointmentTransitionService
     * #declineAppointmentItems} → {@code AuthorizationService#enforceCanManageAppointment})
     * resolves the actor's ROLE from {@code SecurityContextHolder}, and a direct service call has
     * no {@code JwtAuthenticationFilter} to populate it — same reason
     * {@code SalonDeactivationCascadeIT} pushes one. Needed by case 11's appointment visit.
     */
    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        tx = new TransactionTemplate(transactionManager);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "test@example.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + Role.SALON_OWNER.name()))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── case 1 — the common case: nothing points at the master, both rows go ─────────────────

    @Test
    @DisplayName("case 1 — a master who never took a booking: NO masters row and NO users row "
            + "survive the salon delete")
    void should_deleteBothRows_when_masterNeverTookABooking() {
        Salon salon = createSalon();
        String masterEmail = emailOf(salon.masterUserId());

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(userExists(salon.masterUserId()))
                .as("the account row is deleted unconditionally (D1)")
                .isFalse();
        assertThat(masterExists(salon.masterId()))
                .as("nothing references this master, so the provider row goes too")
                .isFalse();
        assertThat(countUsersWithEmail(masterEmail))
                .as("acceptance criterion 1 — zero rows hold the address. Not a tombstone. Zero.")
                .isZero();
    }

    // ── case 2 — the detach branch, and the binding order ────────────────────────────────────

    @Test
    @DisplayName("case 2 — a master with a past COMPLETED booking: the users row is gone, the "
            + "masters row survives DETACHED with user_id NULL, detached_at set and the "
            + "pre-delete name snapshotted")
    void should_detachMasterAndDeleteAccount_when_masterHasPastBooking() {
        Salon salon = createSalon();
        jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?",
                "Олена", "Коваленко", salon.masterUserId());
        UUID clientId = createClient();
        insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(userExists(salon.masterUserId()))
                .as("the account is deleted even when the provider row cannot be (D1)")
                .isFalse();
        assertThat(masterExists(salon.masterId()))
                .as("bookings.master_id is NOT NULL / NO ACTION — the row must survive")
                .isTrue();
        assertThat(masterUserId(salon.masterId())).isNull();
        assertThat(masterDetachedAt(salon.masterId())).isNotNull();
        assertThat(masterDetachedFirstName(salon.masterId())).isEqualTo("Олена");
        assertThat(masterDetachedLastName(salon.masterId())).isEqualTo("Коваленко");
        assertThat(masterIsActive(salon.masterId()))
                .as("a detached stub is unreachable from any roster")
                .isFalse();
    }

    /**
     * {@code users.first_name} is NULLABLE (V1:6) — an invited staff member who never completed
     * their profile genuinely has none — while {@code chk_masters_detachment_coherent} requires
     * {@code detached_first_name IS NOT NULL}. Before {@code Master#detach} normalised it, ONE such
     * staff member took the whole {@code DELETE /salons/&#123;id&#125;} down with a
     * {@code DataIntegrityViolationException}. RED before that fix, on exactly this case.
     */
    @Test
    @DisplayName("case 2b — a master whose account had NO first name detaches under the neutral "
            + "fallback label instead of violating chk_masters_detachment_coherent")
    void should_snapshotFallbackLabel_when_deletedMasterHadNoFirstName() {
        Salon salon = createSalon();
        jdbcTemplate.update("UPDATE users SET first_name = NULL, last_name = NULL WHERE id = ?",
                salon.masterUserId());
        UUID clientId = createClient();
        insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(userExists(salon.masterUserId())).isFalse();
        assertThat(masterExists(salon.masterId())).isTrue();
        assertThat(masterDetachedFirstName(salon.masterId()))
                .isEqualTo(com.beautica.master.entity.Master.DETACHED_FALLBACK_FIRST_NAME);
        assertThat(masterDetachedLastName(salon.masterId()))
                .as("a null surname is stored as null — only the first name is CHECK-required")
                .isNull();
    }

    // ── case 3 — the client's own history does not go anonymous ──────────────────────────────

    @Test
    @DisplayName("case 3 — the client's own GET /bookings/{id} for that past booking still renders "
            + "the provider's first/last name after the staff account is deleted")
    void should_stillRenderProviderName_when_clientReadsPastBookingAfterStaffDelete() throws Exception {
        Salon salon = createSalon();
        jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?",
                "Олена", "Коваленко", salon.masterUserId());
        String clientEmail = "shd-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID bookingId = insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);
        String clientToken = fixtures.tokenFor(clientEmail);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("masterFirstName").asText()).isEqualTo("Олена");
        assertThat(data.path("masterLastName").asText()).isEqualTo("Коваленко");
    }

    // ── case 3b — the deleted staff member's own access token stops working ──────────────────

    /**
     * Phase 295 audit HIGH-1. Nothing in the original 14 cases touched the TOKEN path, and the
     * chain failed OPEN through three layers:
     * {@code UserRepository.findTokensValidAfterById} returned {@code Optional.empty()} for both
     * "no such row" and "row with a null tokens_valid_after", {@code TokensValidAfterCache} cached
     * that ambiguous empty, and {@code JwtAuthenticationFilter}'s
     * {@code if (tokensValidAfter.isPresent())} guard therefore ran NO check at all for a deleted
     * account — accepting the token and handing {@code SecurityContextHolder} the dead user's
     * authority for the rest of the 3600 s access-token TTL. Phase 290 had masked this by stamping
     * {@code tokensValidAfter}; phase 295 removed the stamp along with the row.
     *
     * <p>The pre-delete leg is not decoration: it proves the token was genuinely accepted before
     * the cascade, so the post-delete rejection cannot pass vacuously on a token that never worked
     * (a wrong password or an unverified fixture would fail the login instead). It also WARMS
     * {@code TokensValidAfterCache} for this user, so the assertion additionally exercises
     * {@code SalonService#evictTokensValidAfterCacheAfterCommit} — without that eviction the
     * warmed {@code PRESENT_NO_RESET} entry would keep serving for the cache's whole 60 s TTL and
     * this test would go red.
     */
    @Test
    @DisplayName("case 3b — the deleted staff member's PRE-DELETE access token no longer "
            + "authenticates: an authenticated read is refused, not served")
    void should_rejectAccessToken_when_staffUserRowWasHardDeleted() throws Exception {
        Salon salon = createSalon();
        String masterEmail = emailOf(salon.masterUserId());
        String staffToken = fixtures.tokenFor(masterEmail);
        HttpEntity<Void> authed = new HttpEntity<>(fixtures.bearerHeaders(staffToken));
        assertThat(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, authed, String.class)
                .getStatusCode())
                .as("control: the very same token must be accepted BEFORE the delete, so the "
                        + "assertion below cannot pass on a token that never worked")
                .isEqualTo(HttpStatus.OK);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(userExists(salon.masterUserId()))
                .as("precondition — the account row really is gone")
                .isFalse();
        assertThat(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, authed, String.class)
                .getStatusCode())
                .as("a hard-deleted account's outstanding access token must NOT authenticate — "
                        + "the filter rejects on row ABSENCE, since there is no is_active flag and "
                        + "no tokensValidAfter stamp left to reject on")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // ── case 4 — the salon's accumulated reviews and the rating aggregate are untouched ──────

    @Test
    @DisplayName("case 4 — the review row survives and masters.avg_rating / review_count are "
            + "numerically unchanged by the delete")
    void should_leaveRatingAggregatesUnchanged_when_reviewedMasterIsDetached() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);
        insertReview(bookingId, clientId, salon.masterId(), salon.salonId(), 4);
        tx.executeWithoutResult(s -> reviewRepository.recalculateMasterRating(salon.masterId()));
        // Fixture check — the aggregate MOVED off its default before the delete, so the assertion
        // below cannot pass vacuously against an untouched zero.
        assertThat(reviewCountOf(salon.masterId())).isEqualTo(1);
        assertThat(avgRatingOf(salon.masterId())).isEqualByComparingTo(new BigDecimal("4.00"));

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(countReviewsForSalon(salon.salonId()))
                .as("the salon's reviews belong to the clients who wrote them — never deleted here")
                .isEqualTo(1);
        assertThat(reviewCountOf(salon.masterId())).isEqualTo(1);
        assertThat(avgRatingOf(salon.masterId())).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    // ── case 5 — an admin has no masters row at all ──────────────────────────────────────────

    @Test
    @DisplayName("case 5 — a SALON_ADMIN (no masters row) is deleted outright")
    void should_deleteAdminOutright_when_adminHasNoMasterRow() {
        Salon salon = createSalon();
        UUID adminId = createUser("shd-admin-" + System.nanoTime() + "@beautica.test",
                "SALON_ADMIN", salon.salonId());
        String adminEmail = emailOf(adminId);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(userExists(adminId)).isFalse();
        assertThat(countUsersWithEmail(adminEmail)).isZero();
    }

    /**
     * The zero-masters staff shape, <b>entirely unexercised</b> before this test: every other
     * fixture in this class — case 5 included — builds its salon through {@code createSalon()},
     * which always seeds a master, so a salon whose staff resolves to a NON-empty set of users but
     * an EMPTY set of masters never occurred. That is not a theoretical shape: it is any salon
     * staffed only by administrators, e.g. the first admin invited to a brand-new salon before its
     * first master accepts.
     *
     * <p><b>MEASURED, 2026-09-04 — what this case does and does NOT pin.</b> It does NOT pin the
     * {@code staffMasters.isEmpty()} guard in {@code SalonService#deleteSalonStaff} as a
     * correctness guard, and the guard's own comment used to claim otherwise. Deleting that branch
     * outright leaves all 17 cases here GREEN, because Hibernate 6 rewrites an empty {@code IN}
     * bind into an always-false form rather than emitting the {@code IN ()} syntax error the
     * comment predicted — probed directly against the Testcontainers Postgres. Both that comment
     * and {@code MasterRepository#findIdsWithHistoricalReferences}'s javadoc were corrected to say
     * so; the guard is kept purely to skip a pointless round trip.
     *
     * <p>What it DOES pin is the realistic refactor that breaks this path: an early
     * {@code if (staffMasters.isEmpty()) return;} — "no masters, nothing to do" — which silently
     * leaves the admin's {@code users} row alive and the salon deleted around it. That mutation
     * fails this case and ONLY this case.
     *
     * <p>Deliberately NOT built on {@code createSalon()}: the seeded master is exactly what would
     * defang the fixture, so the salon is assembled inline and the zero-masters precondition is
     * ASSERTED, not assumed.
     */
    @Test
    @DisplayName("case 5b — a salon staffed ONLY by admins (staff users exist, ZERO masters rows) "
            + "is still fully deleted — the zero-masters branch of the staff cascade")
    void should_deleteCleanly_when_salonHasStaffUsersButNoMastersRows() {
        UUID ownerId = createUser("shd-adminonly-owner-" + System.nanoTime() + "@beautica.test",
                "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());
        UUID adminId = createUser("shd-adminonly-admin-" + System.nanoTime() + "@beautica.test",
                "SALON_ADMIN", salonId);

        assertThat(count("SELECT COUNT(*) FROM masters WHERE salon_id = ?", salonId))
                .as("the fixture's whole value is ZERO masters rows — a seeded master would put "
                        + "this salon back on the ordinary path every other case already covers")
                .isZero();

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(userExists(adminId))
                .as("the admin's account is hard-deleted like any other staff member")
                .isFalse();
        assertThat(salonIsActive(salonId))
                .as("the delete must COMPLETE, not abort on an IN () syntax error")
                .isFalse();
        assertThat(userExists(ownerId))
                .as("the owner is structurally exempt and survives (phase 290 D3)")
                .isTrue();
    }

    // ── case 6 — the scoping negative (the one case kept from the phase 290 class) ───────────

    @Test
    @DisplayName("case 6 — staff of a DIFFERENT salon are untouched: their rows still EXIST and "
            + "are still is_active = true")
    void should_leaveOtherSalonsStaffUntouched_when_salonDeleted() {
        Salon salon = createSalon();

        Salon otherSalon = createSalon();
        UUID otherAdminId = createUser("shd-other-admin-" + System.nanoTime() + "@beautica.test",
                "SALON_ADMIN", otherSalon.salonId());

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(masterExists(otherSalon.masterId())).isTrue();
        assertThat(masterIsActive(otherSalon.masterId())).isTrue();
        assertThat(masterUserId(otherSalon.masterId()))
                .as("the other salon's master must not even be detached")
                .isEqualTo(otherSalon.masterUserId());
        assertThat(userExists(otherSalon.masterUserId())).isTrue();
        assertThat(userIsActive(otherSalon.masterUserId())).isTrue();
        assertThat(userExists(otherAdminId)).isTrue();
        assertThat(userIsActive(otherAdminId)).isTrue();
        assertThat(salonIsActive(otherSalon.salonId())).isTrue();
    }

    // ── case 7 — the ON DELETE CASCADE fan-out ───────────────────────────────────────────────

    @Test
    @DisplayName("case 7 — refresh tokens, device tokens, password-reset tickets and weekly "
            + "schedules of the deleted staff are gone with the row (cascade)")
    void should_cascadeSessionsAndSchedules_when_staffAccountDeleted() {
        Salon salon = createSalon();
        insertRefreshToken(salon.masterUserId());
        insertDeviceToken(salon.masterUserId());
        insertPasswordResetTicket(salon.masterUserId());
        insertWeeklySchedule(salon.masterId());
        // Fixture check — every count is non-zero BEFORE the delete, so the zeros below prove a
        // cascade rather than a fixture that never inserted anything.
        assertThat(countRefreshTokens(salon.masterUserId())).isEqualTo(1);
        assertThat(countDeviceTokens(salon.masterUserId())).isEqualTo(1);
        assertThat(countPasswordResetTickets(salon.masterUserId())).isEqualTo(1);
        assertThat(countWeeklySchedules(salon.masterId())).isEqualTo(1);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(countRefreshTokens(salon.masterUserId())).isZero();
        assertThat(countDeviceTokens(salon.masterUserId())).isZero();
        assertThat(countPasswordResetTickets(salon.masterUserId())).isZero();
        assertThat(countWeeklySchedules(salon.masterId()))
                .as("weekly_schedules cascades off masters (V69), which is itself deleted here")
                .isZero();
    }

    // ── case 8 — phase 294 D5: created_by_user_id RESTRICT -> SET NULL ───────────────────────

    @Test
    @DisplayName("case 8 — a walk-in booking created by the deleted staff member survives with "
            + "created_by_user_id NULL (phase 294 D5)")
    void should_keepWalkInBookingWithNullCreator_when_creatingStaffDeleted() {
        Salon salon = createSalon();
        // A SECOND master carries the walk-in, so the booking's own master_id survives as a
        // detached stub while the CREATOR's account is what this case is about. Without it the
        // two effects would be indistinguishable on one row.
        UUID walkInMasterUserId = createUser(
                "shd-walkin-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salon.salonId());
        UUID walkInMasterId = insertMaster(walkInMasterUserId, salon.salonId(), "SALON_MASTER");
        UUID walkInMasterServiceId = insertMasterService(walkInMasterId, salon.serviceDefId());
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(
                clientId, walkInMasterId, walkInMasterServiceId, salon.salonId(), "COMPLETED", PAST);
        jdbcTemplate.update("UPDATE bookings SET created_by_user_id = ? WHERE id = ?",
                salon.masterUserId(), bookingId);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingExists(bookingId))
                .as("no booking row is ever deleted by this cascade")
                .isTrue();
        assertThat(bookingCreatedByUserId(bookingId))
                .as("attribution to a staff member of a deleted salon nulls out, the row does not")
                .isNull();
        assertThat(userExists(salon.masterUserId())).isFalse();
    }

    // ── case 9 — the phase 289 fail-closed precondition still aborts BEFORE any mutation ─────

    @Test
    @DisplayName("case 9 — audit VIOLATIONS_FOUND aborts the WHOLE deletion before any mutation: "
            + "the salon stays active and NOTHING is deleted — the users row still exists")
    void should_deleteNothing_when_auditFindsViolation() {
        Salon salon = createSalon();
        // The salon's own master user is referenced as a booking CLIENT — bypassing the service
        // layer exactly as StaffClientReferenceAuditServiceIT's fixtures do (every application
        // write path rejects a non-CLIENT actor here; only a seed/fixture script can produce it).
        insertStandaloneBooking(salon.masterUserId(), salon, "COMPLETED", PAST);

        assertThatThrownBy(() -> salonService.deactivateSalon(salon.ownerId(), salon.salonId()))
                .isInstanceOf(SalonDeletionBlockedException.class);

        assertThat(salonIsActive(salon.salonId()))
                .as("the abort happens before salon.setActive(false)")
                .isTrue();
        assertThat(userExists(salon.masterUserId()))
                .as("a hard delete makes this precondition strictly more load-bearing than it was "
                        + "as the precondition of a flag flip")
                .isTrue();
        assertThat(masterExists(salon.masterId())).isTrue();
        assertThat(masterUserId(salon.masterId()))
                .as("not even detached — no mutation at all runs")
                .isEqualTo(salon.masterUserId());
        assertThat(masterIsActive(salon.masterId())).isTrue();
    }

    /**
     * Case 9's sibling for the FOURTH reference site (phase 295 audit, LOW-8). The three phase 289
     * finders all report CLEAN on this fixture — there is no {@code bookings}, {@code reviews} or
     * {@code client_reviews} row naming the staff user as a client, only an
     * {@code appointments.client_id}. That column is nullable {@code NO ACTION} exactly like
     * {@code bookings.client_id}, so without the fourth finder the audit waves the delete through
     * and {@code deleteAllByIdInBatch} dies on an FK violation: a 500 where the product contract
     * says 409.
     *
     * <p><b>This is the assertion the exception TYPE carries.</b> Asserting merely "it threw" would
     * pass on the unfixed code too, because a {@code DataIntegrityViolationException} is also a
     * throw. {@code SalonDeletionBlockedException} is what distinguishes "refused on purpose,
     * nothing written" from "crashed halfway through a destructive cascade".
     */
    @Test
    @DisplayName("case 9b — an appointments.client_id-only violation also aborts with the "
            + "deliberate 409, not an FK-violation 500 (phase 295 audit LOW-8, fourth finder)")
    void should_blockWith409_when_onlyAnAppointmentNamesStaffAsClient() {
        Salon salon = createSalon();
        insertAppointmentHeader(salon.masterUserId(), salon.salonId());

        assertThat(count("SELECT COUNT(*) FROM bookings WHERE client_id = ?", salon.masterUserId()))
                .as("no bookings row names the staff user as client — the three phase 289 finders "
                        + "see NOTHING here, which is the entire point of this fixture")
                .isZero();

        assertThatThrownBy(() -> salonService.deactivateSalon(salon.ownerId(), salon.salonId()))
                .as("the audit must fail CLOSED on the fourth reference site — a bare throw would "
                        + "also be satisfied by the FK-violation 500 this finder exists to prevent")
                .isInstanceOf(SalonDeletionBlockedException.class);

        assertThat(userExists(salon.masterUserId()))
                .as("nothing may be deleted when the precondition refuses")
                .isTrue();
        assertThat(masterExists(salon.masterId())).isTrue();
        assertThat(salonIsActive(salon.salonId())).isTrue();
    }

    // ── case 10 — idempotency comes from row absence, not a flag (D4) ────────────────────────

    @Test
    @DisplayName("case 10 — a second DELETE /salons/{id} is a no-op: it resolves an empty staff "
            + "set and writes nothing (D4 — no persisted scrub flag involved)")
    void should_noOp_when_salonAlreadyInactive() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());
        Timestamp firstDetachedAt = masterDetachedAt(salon.masterId());

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(masterDetachedAt(salon.masterId()))
                .as("a repeat DELETE must not re-stamp detached_at — that would prove it re-ran")
                .isEqualTo(firstDetachedAt);
        assertThat(masterExists(salon.masterId())).isTrue();
        assertThat(userExists(salon.masterUserId())).isFalse();
        assertThat(salonIsActive(salon.salonId())).isFalse();
    }

    // ── case 11 — D6 regression pin: the booking cascade runs FIRST and is unchanged ─────────

    /**
     * <b>MEASURED, 2026-09-04 — the D6 ordering mutation does NOT go red, and that is recorded
     * here rather than papered over.</b> The phase doc's mutation check says reversing the order
     * (staff delete before the booking cascade) must fail this case "with a null master on the
     * notice". It was executed and this case still PASSED, for two reasons that are both facts
     * about the shipped code, not about this test:
     * <ul>
     *   <li>the {@code SALON_CLOSED} notice never reads the master at all —
     *       {@code NotificationService#notifySalonClosed} / {@code EmailNotificationService
     *       #sendSalonClosedEmail} render the CLIENT's name and the visit's service names, and the
     *       outbox row carries only {@code (event_type, aggregate_id)};</li>
     *   <li>a master carrying a future CONFIRMED booking always has {@code
     *       findIdsWithHistoricalReferences reports it}, so the reversed order DETACHES it rather than deleting
     *       it — and phase 294 already made every read path on this cascade detach-safe (LEFT
     *       joins, {@code COALESCE(mu.*, m.detached*)}), so the declines and the outbox rows come
     *       out identical.</li>
     * </ul>
     * D6's ordering is implemented as written and is still the right direction — it is the one
     * that cannot depend on a whole subsystem staying detach-safe — but it is DEFENSIVE, not
     * load-bearing under the current code. Do not "strengthen" this case with an assertion that
     * only passes because of the ordering; there is none to write today. What this case does pin
     * is that the phase 293 contract (future CONFIRMED → DECLINED, one {@code SALON_CLOSED} entry
     * per VISIT) is untouched by the hard delete, which is the other half of D6.
     */
    @Test
    @DisplayName("case 11 — future CONFIRMED bookings are still DECLINED and SALON_CLOSED outbox "
            + "rows are still ONE PER VISIT (D6 regression pin)")
    void should_stillDeclineFutureBookingsAndEmitOnePerVisit_when_salonDeleted() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID standaloneId = insertStandaloneBooking(clientId, salon, "CONFIRMED", FUTURE);
        UUID appointmentId = insertAppointmentHeader(clientId, salon.salonId());
        UUID itemA = insertAppointmentItem(clientId, salon, appointmentId, FUTURE.plusDays(1));
        UUID itemB = insertAppointmentItem(clientId, salon, appointmentId, FUTURE.plusDays(1).plusHours(1));

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(bookingStatus(standaloneId)).isEqualTo("DECLINED");
        assertThat(bookingStatus(itemA)).isEqualTo("DECLINED");
        assertThat(bookingStatus(itemB)).isEqualTo("DECLINED");
        List<UUID> aggregateIds = salonClosedAggregateIds();
        assertThat(aggregateIds)
                .as("D12 — one SALON_CLOSED entry per VISIT: the standalone plus the 2-item visit")
                .hasSize(2);
        assertThat(aggregateIds).contains(standaloneId);
        assertThat(aggregateIds.stream().filter(id -> id.equals(itemA) || id.equals(itemB)).toList())
                .as("the 2-item visit contributes exactly ONE entry, keyed to its representative")
                .hasSize(1);
        assertThat(salonIsActive(salon.salonId()))
                .as("the whole cascade committed — a rolled-back transaction would leave this true")
                .isFalse();
    }

    // ── acceptance criterion 5 — the owner is structurally exempt ────────────────────────────

    @Test
    @DisplayName("owner exemption — the owner's OWN SALON_OWNER-type master row deactivates, but "
            + "their users row, role and session all survive deleting their last salon")
    void should_leaveOwnerAccountIntact_when_salonDeleted() {
        Salon salon = createSalon();
        UUID ownerMasterId = insertMaster(salon.ownerId(), salon.salonId(), "SALON_OWNER");
        insertRefreshToken(salon.ownerId());

        // Phase 295 audit MEDIUM-5. A SALON_MASTER-TYPED masters row whose user is in fact a
        // SALON_OWNER: master_type and users.role disagree, which nothing in the schema forbids
        // (this is exactly the shape a seed script or a future role transition that forgets to
        // rewrite master_type produces). Before findSalonStaffUserIds re-asserted users.role on
        // its first arm, this row's user_id was resolved as "staff" from master_type alone and the
        // owner's account was HARD-DELETED — the one caller that irreversibly deletes rows was the
        // only consumer of that finder without the role re-assert the three read paths all carry.
        UUID rogueOwnerId = createUser(
                "shd-rogue-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
        UUID rogueMasterId = insertMaster(rogueOwnerId, salon.salonId(), "SALON_MASTER");

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(userExists(rogueOwnerId))
                .as("a SALON_OWNER behind a SALON_MASTER-typed masters row is NOT this salon's "
                        + "staff — users.role is authoritative, and the destructive path must "
                        + "scope on it exactly as the three audit read paths do")
                .isTrue();
        assertThat(userRole(rogueOwnerId)).isEqualTo("SALON_OWNER");
        assertThat(masterUserId(rogueMasterId))
                .as("nor may that masters row be detached — detaching it would destroy the link "
                        + "just as permanently as deleting the account")
                .isEqualTo(rogueOwnerId);

        assertThat(userExists(salon.ownerId()))
                .as("locked decision (user, 2026-08-31, carried into 295): the owner's account "
                        + "survives deleting their last salon")
                .isTrue();
        assertThat(userIsActive(salon.ownerId())).isTrue();
        assertThat(userRole(salon.ownerId())).isEqualTo("SALON_OWNER");
        assertThat(countRefreshTokens(salon.ownerId()))
                .as("the owner's session is never revoked by this cascade")
                .isEqualTo(1);
        assertThat(masterExists(ownerMasterId))
                .as("the owner's own provider row is DEACTIVATED, never deleted or detached")
                .isTrue();
        assertThat(masterUserId(ownerMasterId)).isEqualTo(salon.ownerId());
        assertThat(masterIsActive(ownerMasterId)).isFalse();
    }

    // ── phase 290 finding #2, still live: one rating recalculation per BATCH ─────────────────

    @Test
    @DisplayName("N masters deleted in one cascade — recalculateSalonRating fires exactly ONCE for "
            + "the salon (phase 290 finding #2, unchanged by the hard delete)")
    void should_recalculateSalonRatingExactlyOnce_when_multipleMastersDeleted() {
        Salon salon = createSalon();
        for (int i = 0; i < 2; i++) {
            UUID extraUserId = createUser(
                    "shd-recalc-" + i + "-" + System.nanoTime() + "@beautica.test",
                    "SALON_MASTER", salon.salonId());
            insertMaster(extraUserId, salon.salonId(), "SALON_MASTER");
        }

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        verify(ratingRecalculationService, times(1)).recalculateSalonRating(salon.salonId());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private record Salon(UUID ownerId, UUID salonId, UUID masterUserId, UUID masterId,
                         UUID serviceDefId, UUID masterServiceId) {}

    private Salon createSalon() {
        UUID ownerId = createUser("shd-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        UUID masterUserId = createUser(
                "shd-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = insertMaster(masterUserId, salonId, "SALON_MASTER");

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId));
        UUID masterServiceId = insertMasterService(masterId, serviceDefId);

        return new Salon(ownerId, salonId, masterUserId, masterId, serviceDefId, masterServiceId);
    }

    private UUID createClient() {
        return createUser("shd-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
    }

    /**
     * {@code first_name}/{@code last_name} are populated because a real staff account has them —
     * a nameless fixture would have quietly exercised {@code Master#DETACHED_FALLBACK_FIRST_NAME}
     * on every detach in this class and left the ordinary snapshot path unpinned. The nameless
     * case gets its own test instead.
     */
    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, "
                        + "email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, ?, ?, true, true, 'Тест', 'Користувач')",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID insertMaster(UUID userId, UUID salonId, String masterType) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                masterId, userId, salonId, masterType);
        return masterId;
    }

    private UUID insertMasterService(UUID masterId, UUID serviceDefId) {
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID insertStandaloneBooking(UUID clientId, Salon salon, String status, OffsetDateTime startsAt) {
        return insertStandaloneBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), status, startsAt);
    }

    private UUID insertStandaloneBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                                         UUID salonId, String status, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status,
                startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    private UUID insertAppointmentHeader(UUID clientId, UUID salonId) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId, salonId);
        return appointmentId;
    }

    private UUID insertAppointmentItem(UUID clientId, Salon salon, UUID appointmentId, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, "
                        + "appointment_id, status, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                appointmentId, startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    private void insertReview(UUID bookingId, UUID clientId, UUID masterId, UUID salonId, int rating) {
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId, rating);
    }

    private void insertRefreshToken(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (id, token, user_id, expires_at, is_revoked, family_id, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, NOW() + interval '30 days', false, ?, NOW(), NOW())",
                UUID.randomUUID(), "rt-" + UUID.randomUUID(), userId, UUID.randomUUID());
    }

    private void insertDeviceToken(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO device_tokens (id, user_id, token, platform, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ANDROID', true, NOW(), NOW())",
                UUID.randomUUID(), userId, "dt-" + UUID.randomUUID());
    }

    private void insertPasswordResetTicket(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO password_reset_tickets (id, ticket_hash, user_id, expires_at, is_used, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, NOW() + interval '1 hour', false, NOW(), NOW())",
                UUID.randomUUID(), "prt-" + UUID.randomUUID(), userId);
    }

    private void insertWeeklySchedule(UUID masterId) {
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, CURRENT_DATE - 30, NULL)",
                UUID.randomUUID(), masterId);
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────────

    private boolean userExists(UUID userId) {
        return count("SELECT COUNT(*) FROM users WHERE id = ?", userId) == 1;
    }

    private boolean masterExists(UUID masterId) {
        return count("SELECT COUNT(*) FROM masters WHERE id = ?", masterId) == 1;
    }

    private boolean bookingExists(UUID bookingId) {
        return count("SELECT COUNT(*) FROM bookings WHERE id = ?", bookingId) == 1;
    }

    private int countUsersWithEmail(String email) {
        return count("SELECT COUNT(*) FROM users WHERE email = ?", email);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private boolean userIsActive(UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM users WHERE id = ?", Boolean.class, userId));
    }

    private String userRole(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
    }

    private boolean salonIsActive(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId));
    }

    private boolean masterIsActive(UUID masterId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = ?", Boolean.class, masterId));
    }

    private UUID masterUserId(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private Timestamp masterDetachedAt(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT detached_at FROM masters WHERE id = ?", Timestamp.class, masterId);
    }

    private String masterDetachedFirstName(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT detached_first_name FROM masters WHERE id = ?", String.class, masterId);
    }

    private String masterDetachedLastName(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT detached_last_name FROM masters WHERE id = ?", String.class, masterId);
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private UUID bookingCreatedByUserId(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM bookings WHERE id = ?", UUID.class, bookingId);
    }

    private int countReviewsForSalon(UUID salonId) {
        return count("SELECT COUNT(*) FROM reviews WHERE salon_id = ?", salonId);
    }

    private int reviewCountOf(UUID masterId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT review_count FROM masters WHERE id = ?", Integer.class, masterId);
        return value == null ? -1 : value;
    }

    private BigDecimal avgRatingOf(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class, masterId);
    }

    private int countRefreshTokens(UUID userId) {
        return count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", userId);
    }

    private int countDeviceTokens(UUID userId) {
        return count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", userId);
    }

    private int countPasswordResetTickets(UUID userId) {
        return count("SELECT COUNT(*) FROM password_reset_tickets WHERE user_id = ?", userId);
    }

    private int countWeeklySchedules(UUID masterId) {
        return count("SELECT COUNT(*) FROM weekly_schedules WHERE master_id = ?", masterId);
    }

    private List<UUID> salonClosedAggregateIds() {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'SALON_CLOSED'",
                UUID.class);
    }

    private int count(String sql, Object arg) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arg);
        return value == null ? -1 : value;
    }
}
