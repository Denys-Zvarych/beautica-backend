package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.salon.dto.SalonStaffMemberResponse;
import com.beautica.user.UserProfileResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 21.5 — integration tests for {@code GET /api/v1/salons/{salonId}/staff}.
 *
 * <p>Mirrors {@code PendingInvitesIntegrationTest}/{@code SalonAdminRemovalIntegrationTest}: real
 * HTTP through {@link TestRestTemplate} against a Testcontainers PostgreSQL instance, fixtures
 * inserted directly via JDBC. Cleanup is handled by {@link AbstractIntegrationTest#cleanDb()}.
 *
 * <p>The last test in this class is the important regression pin: it proves the new
 * management-gated roster (unmasked {@code phoneNumber}) coexists with the existing public
 * {@code GET /masters/{id}} contract (masked {@code phoneNumber}) for the SAME master row — this
 * new endpoint must never loosen that masking.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonController.getSalonStaff — staff roster endpoint (Phase 21.5)")
class SalonStaffEndpointIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonStaffEndpointIT.class);

    private static final String STAFF_URL = "/api/v1/salons/%s/staff";
    private static final String MASTER_DETAIL_URL = "/api/v1/masters/%s";
    /** Phase 265 — the owner-as-master toggle pair (SalonMasterController:53 / :73). */
    private static final String OWNER_MASTER_URL = "/api/v1/salons/%s/master";
    /** Phase 265 — the authenticated self-read that carries the derived {@code hasMasterProfile}. */
    private static final String USERS_ME_URL = "/api/v1/users/me";
    private static final String TEST_PASSWORD = SalonItFixtures.TEST_PASSWORD;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private SalonItFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
    }

    @Test
    @DisplayName("200 with both a master and an admin entry when SALON_OWNER requests their own salon's roster")
    void should_return200WithBothRoles_when_ownerRequestsStaff() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-staff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Staff Roster Salon");
        UUID masterUserId = insertStaffUser(
                "master-staff-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId,
                "+380501112233", "master_insta", "Master bio", "Майстер манікюру", "https://cdn.test/master.png");
        UUID masterId = insertMaster(masterUserId, salonId, "SALON_MASTER");
        UUID adminUserId = insertStaffUser(
                "admin-staff-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", salonId,
                "+380502223344", "admin_insta", "Admin bio", "Адміністратор", "https://cdn.test/admin.png");
        insertActiveMasterService(salonId, masterId);
        insertActiveMasterService(salonId, masterId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} as SALON_OWNER — must return both the master and the admin",
                String.format(STAFF_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("owner requesting their own salon's staff roster must return 200")
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonStaffMemberResponse>>>() {});
        List<SalonStaffMemberResponse> staff = body.data();
        assertThat(staff).hasSize(2);

        SalonStaffMemberResponse masterEntry = staff.stream()
                .filter(s -> s.userId().equals(masterUserId)).findFirst().orElseThrow();
        SalonStaffMemberResponse adminEntry = staff.stream()
                .filter(s -> s.userId().equals(adminUserId)).findFirst().orElseThrow();

        assertThat(masterEntry.masterId()).isEqualTo(masterId);
        assertThat(masterEntry.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(masterEntry.phoneNumber())
                .as("management-scoped roster must return UNMASKED phoneNumber")
                .isEqualTo("+380501112233");
        assertThat(masterEntry.instagram()).isEqualTo("master_insta");
        assertThat(masterEntry.serviceCount())
                .as("serviceCount must reflect the master's active service assignments")
                .isEqualTo(2L);

        assertThat(adminEntry.masterId())
                .as("an admin entry has no Master row — masterId must be null")
                .isNull();
        assertThat(adminEntry.role()).isEqualTo(Role.SALON_ADMIN);
        assertThat(adminEntry.avgRating())
                .as("an admin has no master rating")
                .isNull();
        assertThat(adminEntry.reviewCount()).isZero();
        assertThat(adminEntry.serviceCount()).isZero();
        assertThat(adminEntry.phoneNumber())
                .as("admin contact details must also be unmasked on this management-gated endpoint")
                .isEqualTo("+380502223344");
    }

    @Test
    @DisplayName("200 when the salon's OWN SALON_ADMIN requests the roster")
    void should_return200_when_ownAdminRequestsStaff() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-adminview-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Admin View Salon");
        UUID adminUserId = insertStaffUser(
                "admin-view-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", salonId,
                "+380503334455", null, null, null, null);
        String adminToken = fixtures.loginAndGetToken(fixtures.emailOf(adminUserId));

        // Act
        log.debug("Act: GET {} as the salon's own SALON_ADMIN — must succeed",
                String.format(STAFF_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(adminToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("an admin viewing their own salon's roster must return 200")
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonStaffMemberResponse>>>() {});
        assertThat(body.data()).hasSize(1);
        assertThat(body.data().get(0).userId()).isEqualTo(adminUserId);
    }

    @Test
    @DisplayName("403 when a foreign SALON_OWNER requests another salon's roster")
    void should_return403_when_foreignOwnerRequestsStaff() throws Exception {
        // Arrange
        UUID ownerAId = fixtures.insertUser("owner-a-staff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerAId, "Salon A Staff");
        UUID ownerBId = fixtures.insertUser("owner-b-staff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        fixtures.insertSalon(ownerBId, "Salon B Staff");
        String ownerBToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerBId));

        // Act
        log.debug("Act: GET {} as a foreign SALON_OWNER — must be denied",
                String.format(STAFF_URL, salonAId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonAId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a foreign owner must be denied access to another salon's staff roster")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("403 when a foreign SALON_ADMIN requests another salon's roster")
    void should_return403_when_foreignAdminRequestsStaff() throws Exception {
        // Arrange
        UUID ownerAId = fixtures.insertUser("owner-a-admstaff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerAId, "Salon A Admin Staff");
        UUID ownerBId = fixtures.insertUser("owner-b-admstaff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = fixtures.insertSalon(ownerBId, "Salon B Admin Staff");
        UUID adminBId = insertStaffUser(
                "admin-b-staff-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", salonBId,
                null, null, null, null, null);
        String adminBToken = fixtures.loginAndGetToken(fixtures.emailOf(adminBId));

        // Act
        log.debug("Act: GET {} as a SALON_ADMIN of a different salon — must be denied",
                String.format(STAFF_URL, salonAId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonAId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(adminBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a foreign admin must be denied access to another salon's staff roster")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("403 when a CLIENT requests a salon's roster")
    void should_return403_when_clientRequestsStaff() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-client-staff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Client Denied Salon");
        UUID clientId = fixtures.insertUser("client-staff-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = fixtures.loginAndGetToken(fixtures.emailOf(clientId));

        // Act
        log.debug("Act: GET {} as CLIENT — role guard must deny with 403",
                String.format(STAFF_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to the staff roster")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("REGRESSION PIN: GET /masters/{id} still masks phoneNumber for the same master the new roster exposes unmasked")
    void should_stillMaskPhoneNumber_when_publicMasterDetailRequestedForSameMaster() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-regression-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Regression Pin Salon");
        UUID masterUserId = insertStaffUser(
                "master-regression-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId,
                "+380509998877", "regression_insta", "bio", "title", null);
        UUID masterId = insertMaster(masterUserId, salonId, "SALON_MASTER");
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act — the new management-gated roster
        ResponseEntity<String> staffResponse = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);
        var staffBody = objectMapper.readValue(
                staffResponse.getBody(), new TypeReference<ApiResponse<List<SalonStaffMemberResponse>>>() {});

        // Act — the existing public master-detail endpoint, unauthenticated
        log.debug("Act: GET {} unauthenticated — phoneNumber must stay masked",
                String.format(MASTER_DETAIL_URL, masterId));
        ResponseEntity<String> publicResponse = restTemplate.exchange(
                String.format(MASTER_DETAIL_URL, masterId), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);
        var publicBody = objectMapper.readValue(
                publicResponse.getBody(), new TypeReference<ApiResponse<MasterDetailResponse>>() {});

        // Assert
        assertThat(staffBody.data().get(0).phoneNumber())
                .as("sanity check: the management roster DOES carry the real phone number")
                .isEqualTo("+380509998877");

        assertThat(publicResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicBody.data().phoneNumber())
                .as("the public GET /masters/{id} contract must stay unchanged — phoneNumber always masked")
                .isNull();
    }

    @Test
    @DisplayName("200 with empty list when a salon has no masters and no admins")
    void should_returnEmptyList_when_salonHasNoStaff() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-empty-staff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Empty Staff Salon");
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} for a salon with zero masters and zero admins",
                String.format(STAFF_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a staffless salon must still return 200, not an error")
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonStaffMemberResponse>>>() {});
        assertThat(body.data())
                .as("no masters and no admins on this salon — roster must be an empty list")
                .isEmpty();
    }

    @Test
    @DisplayName("a deactivated master is excluded from the roster while the still-active master remains")
    void should_excludeInactiveMaster_when_masterHasBeenDeactivated() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-inactive-staff-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Inactive Master Salon");
        UUID activeMasterUserId = insertStaffUser(
                "master-active-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId,
                "+380504445566", null, null, null, null);
        insertMaster(activeMasterUserId, salonId, "SALON_MASTER");
        UUID inactiveMasterUserId = insertStaffUser(
                "master-inactive-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId,
                "+380505556677", null, null, null, null);
        insertMaster(inactiveMasterUserId, salonId, "SALON_MASTER", false);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — salon has one active and one deactivated master",
                String.format(STAFF_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonStaffMemberResponse>>>() {});
        assertThat(body.data())
                .as("the deactivated master's is_active=false row must be filtered out, exactly the "
                        + "still-active master must remain")
                .extracting(SalonStaffMemberResponse::userId)
                .containsExactly(activeMasterUserId);
    }

    @Test
    @DisplayName("all SALON_ADMINs are returned when a salon has more than one (multi-admin, Phase 21.1)")
    void should_returnAllAdmins_when_salonHasMultipleAdmins() throws Exception {
        // Arrange — V108 dropped uq_users_salon_admin, so a salon can now carry >1 admin.
        UUID ownerId = fixtures.insertUser("owner-multiadmin-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Multi Admin Salon");
        UUID adminOneId = insertStaffUser(
                "admin-one-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", salonId,
                "+380506667788", null, null, null, null);
        UUID adminTwoId = insertStaffUser(
                "admin-two-" + System.nanoTime() + "@beautica.test", "SALON_ADMIN", salonId,
                "+380507778899", null, null, null, null);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — salon has two SALON_ADMINs, neither a master",
                String.format(STAFF_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonStaffMemberResponse>>>() {});
        assertThat(body.data())
                .as("both admins on this salon must be present in the roster, not just the first")
                .extracting(SalonStaffMemberResponse::userId)
                .containsExactlyInAnyOrder(adminOneId, adminTwoId);
        assertThat(body.data())
                .as("every row of a multi-admin salon with no masters must be labelled SALON_ADMIN")
                .allSatisfy(entry -> assertThat(entry.role()).isEqualTo(Role.SALON_ADMIN));
    }

    @Test
    @DisplayName("serviceCount is attributed to the CORRECT master when two masters have different counts")
    void should_attributeServiceCountPerMaster_when_countsDiffer() throws Exception {
        // Arrange — two masters with DISCRIMINATING counts (3 vs 1): a batch GROUP BY that
        // misattributes rows across masters, or collapses to a single shared value, must fail this.
        UUID ownerId = fixtures.insertUser("owner-countattr-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Count Attribution Salon");
        UUID masterThreeUserId = insertStaffUser(
                "master-three-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId,
                "+380508889900", null, null, null, null);
        UUID masterThreeId = insertMaster(masterThreeUserId, salonId, "SALON_MASTER");
        insertActiveMasterService(salonId, masterThreeId);
        insertActiveMasterService(salonId, masterThreeId);
        insertActiveMasterService(salonId, masterThreeId);

        UUID masterOneUserId = insertStaffUser(
                "master-one-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId,
                "+380509990011", null, null, null, null);
        UUID masterOneId = insertMaster(masterOneUserId, salonId, "SALON_MASTER");
        insertActiveMasterService(salonId, masterOneId);

        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — one master has 3 active services, the other has 1",
                String.format(STAFF_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(STAFF_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonStaffMemberResponse>>>() {});
        SalonStaffMemberResponse masterThreeEntry = body.data().stream()
                .filter(s -> s.userId().equals(masterThreeUserId)).findFirst().orElseThrow();
        SalonStaffMemberResponse masterOneEntry = body.data().stream()
                .filter(s -> s.userId().equals(masterOneUserId)).findFirst().orElseThrow();

        assertThat(masterThreeEntry.serviceCount())
                .as("the master with 3 active service assignments must show serviceCount=3, "
                        + "not swapped with the other master's count")
                .isEqualTo(3L);
        assertThat(masterOneEntry.serviceCount())
                .as("the master with 1 active service assignment must show serviceCount=1")
                .isEqualTo(1L);
    }

    // ── Phase 265 — the owner-as-master toggle round trip ──────────────────────
    //
    // Phase 265 test case 4. Every other assertion on `hasMasterProfile` in this repo stops short
    // of the real toggle: UserServiceTest stubs the finder (a Mockito stub cannot toggle
    // anything), MasterRepositoryOwnerMasterFlagTest persists the row by hand via TestEntityManager
    // (it never calls the endpoint that writes it), and OwnerMasterCacheTest asserts eviction. None
    // of them proves the derived flag on GET /users/me tracks what POST/DELETE
    // /api/v1/salons/{salonId}/master actually DO to the database. This test drives both real
    // endpoints over real HTTP and reads the flag back over real HTTP in between.
    //
    // It lives in SalonStaffEndpointIT rather than a new class because the toggle endpoints are
    // SalonMasterController's, it needs exactly this class's owner+salon fixtures, and a new
    // @SpringBootTest class would fork nothing but cost a context lookup (§M-3 / Q3).

    @Test
    @DisplayName("Phase 265 — hasMasterProfile flips true then false across POST then DELETE "
            + "/salons/{salonId}/master, read back over GET /users/me")
    void should_flipTrueThenFalse_when_postThenDeleteSalonMasterIsCalled() throws Exception {
        // Arrange — a SALON_OWNER whose salon is inserted by JDBC, so SalonService.createSalon's
        // auto-createMasterForOwner never ran and the toggle starts genuinely OFF. That gives the
        // assertion sequence three distinct observations (false → true → false) instead of two,
        // so a derivation hard-wired to either constant fails somewhere in it.
        UUID ownerId = fixtures.insertUser(
                "owner-toggle-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Owner Master Toggle Salon");
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        assertThat(readHasMasterProfile(ownerToken))
                .as("precondition: this owner has no master row yet, so the derived flag must "
                        + "start OFF — otherwise the flip below proves nothing")
                .isFalse();

        // Act 1 — opt in through the real endpoint the mobile toggle calls.
        log.debug("Act: POST {} to opt the owner IN as a master, then re-read GET /users/me",
                String.format(OWNER_MASTER_URL, salonId));
        ResponseEntity<String> enableResponse = restTemplate.exchange(
                String.format(OWNER_MASTER_URL, salonId), HttpMethod.POST,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert 1
        assertThat(enableResponse.getStatusCode())
                .as("POST /salons/{salonId}/master must return 200 for the salon's own owner, "
                        + "body=%s", enableResponse.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(readHasMasterProfile(ownerToken))
                .as("an active SALON_OWNER-type master row now exists — GET /users/me must derive "
                        + "hasMasterProfile=true on the very next read. This read goes THROUGH the "
                        + "live `user-profile` Caffeine cache, which the precondition read above "
                        + "already populated with false: a POST that writes the masters row but "
                        + "fails to evict leaves that false entry standing for the full 5-minute "
                        + "TTL and fails here. This is the cross-aggregate staleness assertion, "
                        + "not merely a derivation assertion.")
                .isTrue();

        // Act 2 — opt back out. DELETE DEACTIVATES the row, it does not remove it.
        log.debug("Act: DELETE {} to opt the owner back OUT, then re-read GET /users/me",
                String.format(OWNER_MASTER_URL, salonId));
        ResponseEntity<String> disableResponse = restTemplate.exchange(
                String.format(OWNER_MASTER_URL, salonId), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert 2
        assertThat(disableResponse.getStatusCode())
                .as("DELETE /salons/{salonId}/master must return 204, body=%s",
                        disableResponse.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT is_active FROM masters WHERE user_id = ? AND master_type = 'SALON_OWNER'",
                        Boolean.class, ownerId))
                .as("the toggle-off is a soft delete: the row must still be there, deactivated. "
                        + "If it were hard-deleted the final assertion below would pass for the "
                        + "WRONG reason and stop guarding the isActive predicate.")
                .isFalse();
        assertThat(readHasMasterProfile(ownerToken))
                .as("the row survives deactivation, so a derivation that checks mere row existence "
                        + "would still read true here — hasMasterProfile must track is_active")
                .isFalse();
    }

    @Test
    @DisplayName("Phase 265 — a second POST /salons/{salonId}/master reactivates the soft-deleted "
            + "row and hasMasterProfile returns to true")
    void should_returnToTrue_when_ownerReEnablesTheMasterProfileAfterDelete() throws Exception {
        // Arrange — drive the owner through a full OFF cycle first, so the second POST lands on
        // createMasterForOwner's REACTIVATION branch (MasterService.java:180) rather than its
        // create branch. That branch is the one a mobile user hits every time they flip the
        // switch back on, and it is a different code path with its own cache evictions.
        UUID ownerId = fixtures.insertUser(
                "owner-retoggle-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Owner Master Retoggle Salon");
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));
        String url = String.format(OWNER_MASTER_URL, salonId);

        restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);
        restTemplate.exchange(url, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(readHasMasterProfile(ownerToken))
                .as("precondition: the owner is opted OUT before the reactivating POST")
                .isFalse();

        // Act
        log.debug("Act: POST {} a SECOND time — reactivation branch, not creation", url);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("re-enabling is idempotent and must return 200, body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(readHasMasterProfile(ownerToken))
                .as("the reactivation branch flips is_active back to true, so the derived flag "
                        + "must follow it back up — a one-way flag would strand the user with a "
                        + "toggle that never turns back on")
                .isTrue();
    }

    /** {@code GET /api/v1/users/me} as {@code token}, returning only the derived Phase 265 flag. */
    private boolean readHasMasterProfile(String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                USERS_ME_URL, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)),
                String.class);
        assertThat(response.getStatusCode())
                .as("GET %s must return 200 for an authenticated owner — a non-200 here means the "
                        + "flag was never read and the surrounding assertion is meaningless; body=%s",
                        USERS_ME_URL, response.getBody())
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        return body.data().hasMasterProfile();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID insertStaffUser(String email, String role, UUID salonId, String phoneNumber,
            String instagram, String bio, String professionalTitle, String avatarUrl) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, phone_number, instagram, bio, "
                        + "professional_title, avatar_url, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, true)",
                id, email, hash, role, salonId, phoneNumber, instagram, bio, professionalTitle, avatarUrl);
        return id;
    }

    private UUID insertMaster(UUID userId, UUID salonId, String masterType) {
        return insertMaster(userId, salonId, masterType, true);
    }

    private UUID insertMaster(UUID userId, UUID salonId, String masterType, boolean isActive) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?, NOW(), NOW())",
                masterId, userId, salonId, masterType, isActive);
        return masterId;
    }

    private void insertActiveMasterService(UUID salonId, UUID masterId) {
        UUID serviceTypeId = resolveUnusedServiceTypeId("SALON", salonId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, price_type, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Staff Roster Service', ?, 60, 500.00, 'FIXED', 0, true, NOW(), NOW())",
                serviceDefId, salonId, serviceTypeId);
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId);
    }

}
