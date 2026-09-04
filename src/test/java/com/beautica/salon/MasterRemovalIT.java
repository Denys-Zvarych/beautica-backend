package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.review.service.RatingRecalculationService;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real-DB coverage for {@code SalonService#removeMaster} — see
 * {@code docs/backend-phases/phase-297-remove-one-master-from-a-salon.md}.
 *
 * <p><b>Split by what each case actually exercises</b> (mirrors {@code SalonStaffHardDeleteIT}'s
 * convention). Cases 1-9, 13-16 call {@code salonService.removeMaster(...)} directly, exactly like
 * {@code SalonStaffHardDeleteIT} calls {@code salonService.deactivateSalon(...)} directly — they
 * pin the DISPOSAL and its guards, not the HTTP/authorization layer, and a direct call lets the
 * negative cases assert the precise exception type via {@code assertThatThrownBy} rather than a
 * bare status code. Cases 10-12 go over real HTTP through {@link TestRestTemplate}, because they
 * exist specifically to pin the {@code @PreAuthorize} expression on {@code
 * SalonMasterController#removeMaster} — a direct service call would not even observe the SpEL
 * gate deletions the phase doc's mutation check names.
 *
 * <p>Every fixture is inserted with raw SQL, the same house convention {@code
 * SalonStaffHardDeleteIT}'s class javadoc documents — this class cares about the state the removal
 * leaves behind, not about how staff came to exist. {@link BookingTestFixtures} is reused for the
 * login/bearer-header helpers only.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonService.removeMaster — Phase 297 single-master removal")
class MasterRemovalIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(7);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);
    private static final String REMOVE_MASTER_URL = "/api/v1/salons/%s/masters/%s";

    @Autowired
    private SalonService salonService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** Case 15 — proves {@code MasterService#deactivateMaster} actually fired. */
    @SpyBean
    private RatingRecalculationService ratingRecalculationService;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── case 1 — the common case: nothing points at the master, both rows go ─────────────────

    @Test
    @DisplayName("case 1 — a master who never took a booking: NO masters row and NO users row "
            + "survive the removal")
    void should_deleteBothRows_when_masterNeverTookABooking() {
        Salon salon = createSalon();
        String masterEmail = emailOf(salon.masterUserId());

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThat(userExists(salon.masterUserId()))
                .as("the account row is hard-deleted unconditionally (D2)")
                .isFalse();
        assertThat(masterExists(salon.masterId()))
                .as("nothing references this master, so the provider row goes too")
                .isFalse();
        assertThat(countUsersWithEmail(masterEmail)).isZero();
    }

    // ── case 2 — the detach branch, and the binding order ────────────────────────────────────

    @Test
    @DisplayName("case 2 — a master with a past COMPLETED booking: the users row is gone, the "
            + "masters row survives DETACHED with user_id NULL, detached_at set and the "
            + "pre-removal name snapshotted")
    void should_detachMasterAndDeleteAccount_when_masterHasPastBooking() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThat(userExists(salon.masterUserId())).isFalse();
        assertThat(masterExists(salon.masterId()))
                .as("bookings.master_id is NOT NULL / NO ACTION — the row must survive")
                .isTrue();
        assertThat(masterUserId(salon.masterId())).isNull();
        assertThat(masterDetachedAt(salon.masterId())).isNotNull();
        assertThat(masterDetachedFirstName(salon.masterId())).isEqualTo("Тест");
        assertThat(masterDetachedLastName(salon.masterId())).isEqualTo("Користувач");
        assertThat(masterIsActive(salon.masterId())).isFalse();
    }

    // ── case 3 — the client's own history does not go anonymous ──────────────────────────────

    @Test
    @DisplayName("case 3 — the client's own GET /bookings/{id} for that past booking still renders "
            + "the provider's first/last name after the removal")
    void should_stillRenderProviderName_when_clientReadsPastBookingAfterRemoval() throws Exception {
        Salon salon = createSalon();
        String clientEmail = "mr-c3-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID bookingId = insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);
        String clientToken = fixtures.tokenFor(clientEmail);

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("masterFirstName").asText()).isEqualTo("Тест");
        assertThat(data.path("masterLastName").asText()).isEqualTo("Користувач");
    }

    // ── case 4 — the salon and its other masters are untouched ──────────────────────────────

    @Test
    @DisplayName("case 4 — the salon's other masters are untouched and the salon row itself is "
            + "untouched")
    void should_leaveOtherMastersAndSalonUntouched_when_oneMasterRemoved() {
        Salon salon = createSalon();
        UUID otherMasterUserId = createUser(
                "mr-c4-other-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salon.salonId());
        UUID otherMasterId = insertMaster(otherMasterUserId, salon.salonId(), "SALON_MASTER");

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThat(masterExists(otherMasterId)).isTrue();
        assertThat(masterIsActive(otherMasterId)).isTrue();
        assertThat(userExists(otherMasterUserId)).isTrue();
        assertThat(userIsActive(otherMasterUserId)).isTrue();
        assertThat(salonIsActive(salon.salonId())).isTrue();
    }

    // ── case 5 — the ON DELETE CASCADE fan-out ───────────────────────────────────────────────

    @Test
    @DisplayName("case 5 — refresh tokens, device tokens, weekly schedules and schedule "
            + "exceptions of the removed master are gone (cascade)")
    void should_cascadeSessionsAndSchedules_when_masterRemoved() {
        Salon salon = createSalon();
        insertRefreshToken(salon.masterUserId());
        insertDeviceToken(salon.masterUserId());
        insertWeeklySchedule(salon.masterId());
        insertScheduleException(salon.masterId());
        assertThat(countRefreshTokens(salon.masterUserId())).isEqualTo(1);
        assertThat(countDeviceTokens(salon.masterUserId())).isEqualTo(1);
        assertThat(countWeeklySchedules(salon.masterId())).isEqualTo(1);
        assertThat(countScheduleExceptions(salon.masterId())).isEqualTo(1);

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThat(countRefreshTokens(salon.masterUserId())).isZero();
        assertThat(countDeviceTokens(salon.masterUserId())).isZero();
        assertThat(countWeeklySchedules(salon.masterId())).isZero();
        assertThat(countScheduleExceptions(salon.masterId())).isZero();
    }

    // ── case 6 — phase 294 D5: created_by_user_id RESTRICT -> SET NULL ──────────────────────

    @Test
    @DisplayName("case 6 — a walk-in booking the removed master rang up survives with "
            + "created_by_user_id NULL")
    void should_keepWalkInBookingWithNullCreator_when_creatingMasterRemoved() {
        Salon salon = createSalon();
        UUID walkInMasterUserId = createUser(
                "mr-c6-walkin-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salon.salonId());
        UUID walkInMasterId = insertMaster(walkInMasterUserId, salon.salonId(), "SALON_MASTER");
        UUID walkInMasterServiceId = insertMasterService(walkInMasterId, salon.serviceDefId());
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(
                clientId, walkInMasterId, walkInMasterServiceId, salon.salonId(), "COMPLETED", PAST);
        jdbcTemplate.update("UPDATE bookings SET created_by_user_id = ? WHERE id = ?",
                salon.masterUserId(), bookingId);

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThat(bookingExists(bookingId)).isTrue();
        assertThat(bookingCreatedByUserId(bookingId)).isNull();
        assertThat(userExists(salon.masterUserId())).isFalse();
    }

    // ── case 7 — D3: a future CONFIRMED booking refuses the removal ─────────────────────────

    @Test
    @DisplayName("case 7 — 409 when the master has a future CONFIRMED booking; nothing is written "
            + "(D3 — Phase 298 inverts this case, it is not deleted)")
    void should_refuseWith409_when_masterHasFutureConfirmedBooking() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(clientId, salon, "CONFIRMED", FUTURE);

        assertThatThrownBy(() -> salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(userExists(salon.masterUserId())).isTrue();
        assertThat(masterExists(salon.masterId())).isTrue();
        assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    // ── case 8 — D6: the owner's own master row cannot be removed here ──────────────────────

    @Test
    @DisplayName("case 8 — 409 when masterId is the owner's own SALON_OWNER-type row; the owner's "
            + "users row still exists (D6)")
    void should_refuseWith409_when_targetIsOwnersOwnMasterRow() {
        Salon salon = createSalon();
        UUID ownerMasterId = insertMaster(salon.ownerId(), salon.salonId(), "SALON_OWNER");

        assertThatThrownBy(() -> salonService.removeMaster(salon.ownerId(), salon.salonId(), ownerMasterId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(userExists(salon.ownerId())).isTrue();
        assertThat(masterExists(ownerMasterId)).isTrue();
        assertThat(masterUserId(ownerMasterId)).isEqualTo(salon.ownerId());
    }

    // ── case 9a — D4: bookings.client_id blocks the removal ─────────────────────────────────

    @Test
    @DisplayName("case 9a — 409 when the master's user is referenced as a bookings.client_id "
            + "elsewhere; nothing is deleted (D4)")
    void should_refuseWith409_when_masterUserIsBookingClient() {
        Salon salon = createSalon();
        insertStandaloneBooking(salon.masterUserId(), salon, "COMPLETED", PAST);

        assertThatThrownBy(() -> salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(userExists(salon.masterUserId())).isTrue();
        assertThat(masterExists(salon.masterId())).isTrue();
        assertThat(masterUserId(salon.masterId())).isEqualTo(salon.masterUserId());
    }

    // ── case 9b — D4: appointments.client_id (the fourth reference site) also blocks it ─────

    @Test
    @DisplayName("case 9b — 409 when the master's user is referenced ONLY as an "
            + "appointments.client_id (no sibling bookings row); nothing is deleted (D4)")
    void should_refuseWith409_when_masterUserIsAppointmentClientOnly() {
        Salon salon = createSalon();
        insertAppointmentHeader(salon.masterUserId(), salon.salonId());
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE client_id = ?", salon.masterUserId()))
                .isZero();

        assertThatThrownBy(() -> salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(userExists(salon.masterUserId())).isTrue();
        assertThat(masterExists(salon.masterId())).isTrue();
    }

    // ── case 10 — D5 IDOR pin: masterId belongs to a DIFFERENT salon the caller also owns ───

    @Test
    @DisplayName("case 10 — 404-or-403 when masterId belongs to a different salon the caller also "
            + "owns (the masterBelongsToSalon @PreAuthorize arm, over real HTTP)")
    void should_deny_when_masterBelongsToADifferentSalonTheCallerAlsoOwns() throws Exception {
        Salon salonA = createSalon();
        Salon salonB = createSalonForOwner(salonA.ownerId());
        String ownerToken = fixtures.tokenFor(emailOf(salonA.ownerId()));

        ResponseEntity<String> response = deleteMaster(ownerToken, salonA.salonId(), salonB.masterId());

        assertThat(response.getStatusCode())
                .as("an id belonging to a DIFFERENT salon must be indistinguishable from a "
                        + "nonexistent one — matches the removeAdmin/adminBelongsToSalon IDOR posture")
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
        assertThat(userExists(salonB.masterUserId())).isTrue();
        assertThat(masterExists(salonB.masterId())).isTrue();
    }

    // ── case 11 — D5: SALON_ADMIN of THIS salon is denied (deliberate divergence from removeAdmin)

    @Test
    @DisplayName("case 11 — 403 for a SALON_ADMIN of this salon (D5's deliberate divergence from "
            + "removeAdmin — hasRole('SALON_OWNER') only)")
    void should_return403_when_salonAdminOfThisSalonCallsRemoveMaster() throws Exception {
        Salon salon = createSalon();
        UUID adminId = createUser("mr-c11-admin-" + System.nanoTime() + "@beautica.test",
                "SALON_ADMIN", salon.salonId());
        String adminToken = fixtures.tokenFor(emailOf(adminId));

        ResponseEntity<String> response = deleteMaster(adminToken, salon.salonId(), salon.masterId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userExists(salon.masterUserId())).isTrue();
        assertThat(masterExists(salon.masterId())).isTrue();
    }

    // ── case 12 — canManageSalon: a SALON_OWNER of a DIFFERENT salon is denied ──────────────

    @Test
    @DisplayName("case 12 — 403 for a SALON_OWNER of a different salon")
    void should_return403_when_ownerOfADifferentSalonCallsRemoveMaster() throws Exception {
        Salon salonA = createSalon();
        Salon salonB = createSalon();
        String ownerBToken = fixtures.tokenFor(emailOf(salonB.ownerId()));

        ResponseEntity<String> response = deleteMaster(ownerBToken, salonA.salonId(), salonA.masterId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userExists(salonA.masterUserId())).isTrue();
    }

    // ── case 13 — D6: an already-detached row cannot be removed again ───────────────────────

    @Test
    @DisplayName("case 13 — 409 when the target masters row is already detached (D6)")
    void should_refuseWith409_when_targetMasterIsAlreadyDetached() {
        Salon salon = createSalon();
        UUID detachedMasterId = insertDetachedMaster(salon.salonId());

        assertThatThrownBy(() -> salonService.removeMaster(salon.ownerId(), salon.salonId(), detachedMasterId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(masterExists(detachedMasterId)).isTrue();
    }

    // ── case 14 — idempotency comes from row absence, not a flag ────────────────────────────

    @Test
    @DisplayName("case 14 — a second removal of the same masterId is 404: the row is simply gone")
    void should_return404_when_removingTheSameMasterTwice() {
        Salon salon = createSalon();

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThatThrownBy(() -> salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId()))
                .isInstanceOf(NotFoundException.class);
    }

    // ── case 15 — the salon's rating aggregate is recalculated exactly once ─────────────────

    @Test
    @DisplayName("case 15 — recalculateSalonRating fires exactly ONCE after the removal (proves "
            + "MasterService#deactivateMaster reuse actually fired), and the master's own past "
            + "reviews are numerically unchanged")
    void should_recalculateSalonRatingOnce_when_masterRemoved() {
        Salon salon = createSalon();
        UUID clientId = createClient();
        UUID bookingId = insertStandaloneBooking(clientId, salon, "COMPLETED", PAST);
        insertReview(bookingId, clientId, salon.masterId(), salon.salonId(), 4);
        jdbcTemplate.update(
                "UPDATE masters SET avg_rating = 4.00, review_count = 1 WHERE id = ?", salon.masterId());

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        verify(ratingRecalculationService, times(1)).recalculateSalonRating(salon.salonId());
        assertThat(reviewCountOf(salon.masterId())).isEqualTo(1);
        assertThat(avgRatingOf(salon.masterId())).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    // ── case 16 — the removed master's access token stops working immediately ───────────────

    @Test
    @DisplayName("case 16 — the removed master's PRE-REMOVAL access token no longer authenticates "
            + "on the very next request (Phase 295 HIGH-1 contract, re-pinned)")
    void should_rejectAccessToken_when_masterWasRemoved() throws Exception {
        Salon salon = createSalon();
        String masterEmail = emailOf(salon.masterUserId());
        String staffToken = fixtures.tokenFor(masterEmail);
        HttpEntity<Void> authed = new HttpEntity<>(fixtures.bearerHeaders(staffToken));
        assertThat(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, authed, String.class)
                .getStatusCode())
                .as("control: the very same token must be accepted BEFORE the removal")
                .isEqualTo(HttpStatus.OK);

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThat(userExists(salon.masterUserId())).isFalse();
        assertThat(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, authed, String.class)
                .getStatusCode())
                .as("a removed master's outstanding access token must NOT authenticate")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> deleteMaster(String token, UUID salonId, UUID masterId) {
        return restTemplate.exchange(
                String.format(REMOVE_MASTER_URL, salonId, masterId), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private record Salon(UUID ownerId, UUID salonId, UUID masterUserId, UUID masterId,
                         UUID serviceDefId, UUID masterServiceId) {}

    private Salon createSalon() {
        UUID ownerId = createUser("mr-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
        return createSalonForOwner(ownerId);
    }

    /** Builds a second salon for an EXISTING owner — used by case 10's cross-salon IDOR pin. */
    private Salon createSalonForOwner(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        UUID masterUserId = createUser(
                "mr-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
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
        return createUser("mr-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
    }

    /**
     * {@code first_name}/{@code last_name} are populated because a real staff account has them —
     * see {@code SalonStaffHardDeleteIT#createUser}'s identical rationale.
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

    /** An already-detached stub — no account, satisfies {@code chk_masters_detachment_coherent}. */
    private UUID insertDetachedMaster(UUID salonId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, "
                        + "detached_first_name, detached_last_name, detached_at, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'SALON_MASTER', false, 'Тест', 'Майстер', NOW(), NOW(), NOW())",
                masterId, salonId);
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

    private void insertWeeklySchedule(UUID masterId) {
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, CURRENT_DATE - 30, NULL)",
                UUID.randomUUID(), masterId);
    }

    private void insertScheduleException(UUID masterId) {
        jdbcTemplate.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, created_at) "
                        + "VALUES (?, ?, CURRENT_DATE + 10, NOW())",
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

    private int countWeeklySchedules(UUID masterId) {
        return count("SELECT COUNT(*) FROM weekly_schedules WHERE master_id = ?", masterId);
    }

    private int countScheduleExceptions(UUID masterId) {
        return count("SELECT COUNT(*) FROM schedule_exceptions WHERE master_id = ?", masterId);
    }

    private int count(String sql, Object arg) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arg);
        return value == null ? -1 : value;
    }
}
