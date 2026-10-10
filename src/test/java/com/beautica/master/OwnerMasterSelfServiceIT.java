package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CreateStaffBookingRequest;
import com.beautica.booking.dto.GuestClientDto;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.service.BookabilityHttp;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.UpdateMasterServiceBandRequest;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 345 — owner-master self-service contract, end to end over HTTP (locked rule 2026-10-05):
 * the salon owner is ALWAYS a master of their salon (no switcher) and becomes client-visible and
 * bookable purely by setting up services + a schedule through the EXISTING endpoints — the same
 * structural rule ({@code MasterBookabilitySql}: ≥1 active service AND hours within 180 days) every
 * other master meets. «Команда» ({@code GET /salons/{id}/staff}) stays unfiltered throughout.
 *
 * <p>Every fixture step goes through the real API ({@code POST /salons} auto-enrols the owner-master;
 * services, assignment, schedule and bookings are all HTTP), so no {@code createOwnerAsMaster} JDBC
 * copy is involved. Caches stay warm between steps — no manual eviction.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Owner-master self-service — services + schedule via existing endpoints make the owner "
        + "client-visible and bookable (full HTTP + real Postgres)")
class OwnerMasterSelfServiceIT extends AbstractIntegrationTest {

    private static final String MASTER_SEARCH_URL = "/api/v1/search/masters?page=0&size=20";
    private static final String SALON_SEARCH_URL = "/api/v1/search/salons?page=0&size=20";
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final LocalTime CLOSE = LocalTime.of(17, 0);
    private static final LocalTime CLIENT_SLOT = LocalTime.of(10, 0);
    private static final LocalTime WALK_IN_SLOT = LocalTime.of(12, 0);
    private static final int DAYS_AHEAD = 2;
    private static final BigDecimal PRICE = new BigDecimal("500.00");
    private static final int BAND_DURATION = 90;
    /** {@code GlobalExceptionHandler.handleForbidden} body message for every {@code ForbiddenException}. */
    private static final String FORBIDDEN_MESSAGE = "Access denied";
    /** {@code GlobalExceptionHandler} body message for every 409 {@code BusinessException}. */
    private static final String CONFLICT_MESSAGE = "Request could not be completed due to a conflict";
    /** Phase 346 — what Spring answers for the removed {@code POST/DELETE /salons/{id}/master}. */
    private static final HttpStatus REMOVED_ENDPOINT_STATUS = HttpStatus.NOT_FOUND;
    private static final UpdateMasterServiceBandRequest BAND_PATCH =
            new UpdateMasterServiceBandRequest(null, null, null, BAND_DURATION, null, null);
    /** Catalogue-level PATCH body: a rename + a new base price (null fields stay untouched). */
    private static final BigDecimal NEW_BASE_PRICE = new BigDecimal("650.00");
    private static final Map<String, Object> DEFINITION_PATCH =
            Map.of("name", "Renamed Service", "priceType", "FIXED", "price", NEW_BASE_PRICE);
    /** A minimal JPEG magic-byte payload — enough to reach the controller's multipart binding. */
    private static final byte[] JPEG_BYTES =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;
    private BookabilityHttp http;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        http = new BookabilityHttp(restTemplate, objectMapper);
    }

    // ── 1. registration ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /salons auto-enrols the owner: GET /masters/me returns the SALON_OWNER master row")
    void should_returnSalonOwnerMasterRow_when_ownerRegistersSalon() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("me");

        JsonNode me = http.get("/api/v1/masters/me", owner.token());

        assertThat(me.path("masterId").asText()).isEqualTo(owner.masterId().toString());
        assertThat(me.path("masterType").asText()).isEqualTo("SALON_OWNER");
    }

    // ── 2. before any setup ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unconfigured owner-master: absent from both public rosters and from search (sole "
            + "master → salon not searchable); present in «Команда»")
    void should_hideOwnerFromClients_but_listInStaff_when_ownerHasNoServicesOrSchedule() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("fresh");
        String clientToken = newClient("fresh");

        assertHiddenFromClients(owner, clientToken, "fresh salon");
        assertThat(staffMasterIds(owner)).as("«Команда»").containsExactly(owner.masterId().toString());
    }

    // ── 3. self-service setup ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Owner creates a SALON service, assigns it to their OWN master row and publishes weekly "
            + "hours → listed on both rosters, per-service coverage and salon search (master search "
            + "stays INDEPENDENT_MASTER-only, Phase 19.7)")
    void should_showOwnerEverywhere_when_ownerSetsUpOwnServicesAndSchedule() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("setup");
        String clientToken = newClient("setup");
        assertHiddenFromClients(owner, clientToken, "precondition (search + roster now cached)");

        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Owner Manicure");
        assertThat(assign(owner, defId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createWeeklySchedule(owner.masterId(), owner.token()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertVisibleToClients(owner, clientToken);
        assertThat(coverageMasterIds(owner.salonId(), defId)).as("per-service coverage")
                .containsExactly(owner.masterId().toString());
    }

    // ── 4. bookings ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Configured owner-master is bookable: a client POST /bookings and the owner's own "
            + "walk-in POST /masters/{id}/bookings both return 201")
    void should_acceptClientAndWalkInBookings_when_ownerMasterConfigured() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("book");
        UUID assignmentId = configureOwnerMaster(owner);
        String clientToken = newClient("book");
        LocalDate day = LocalDate.now(TimeZones.KYIV).plusDays(DAYS_AHEAD);

        ResponseEntity<String> clientBooking = exchange("/api/v1/bookings", HttpMethod.POST,
                new CreateBookingRequest(owner.masterId(), assignmentId,
                        ZonedDateTime.of(day, CLIENT_SLOT, TimeZones.KYIV), null, null, false),
                clientToken);
        ResponseEntity<String> walkIn = exchange("/api/v1/masters/" + owner.masterId() + "/bookings",
                HttpMethod.POST,
                new CreateStaffBookingRequest(List.of(assignmentId),
                        ZonedDateTime.of(day, WALK_IN_SLOT, TimeZones.KYIV).toOffsetDateTime(),
                        new GuestClientDto("Марія", "Левченко", "050 123 45 67")),
                owner.token());

        assertThat(clientBooking.getStatusCode()).as(clientBooking.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(walkIn.getStatusCode()).as(walkIn.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = 'CONFIRMED'",
                Long.class, owner.masterId())).isEqualTo(2L);
    }

    // ── 5. un-configuring ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Owner removes their ONLY assignment → hidden from rosters and search again (warm "
            + "caches); «Команда» still lists them")
    void should_hideOwnerAgain_when_ownerRemovesOnlyAssignment() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("unassign");
        configureOwnerMaster(owner);
        String clientToken = newClient("unassign");
        assertVisibleToClients(owner, clientToken);
        UUID defId = jdbcTemplate.queryForObject(
                "SELECT service_def_id FROM master_services WHERE master_id = ? AND is_active = true",
                UUID.class, owner.masterId());

        ResponseEntity<String> removed = exchange(
                "/api/v1/salons/" + owner.salonId() + "/masters/" + owner.masterId() + "/services/" + defId,
                HttpMethod.DELETE, null, owner.token());

        assertThat(removed.getStatusCode()).as(removed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertHiddenFromClients(owner, clientToken, "after unassign");
        assertThat(staffMasterIds(owner)).as("«Команда»").containsExactly(owner.masterId().toString());
    }

    // ── 6. permissions ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SALON_ADMIN of the same salon is 403 on the OWNER's own master row — assign, band "
            + "edit, unassign, bulk create and weekly schedule — and nothing is written (user decision "
            + "2026-10-05: only the owner edits their own services and schedule)")
    void should_forbidServicesAndSchedule_when_salonAdminEditsOwnerMaster() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("admin");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("admin"));
        UUID ownedDefId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Owner Pedicure");
        String rowUrl = "/api/v1/salons/" + owner.salonId() + "/masters/" + owner.masterId() + "/services";

        // The admin tries FIRST, on a not-yet-assigned definition, so a leaked assign would be a
        // clean 201 (and would then make the owner's own assign below 409) — not a masking 409.
        ResponseEntity<String> adminAssign = assign(owner, ownedDefId, adminToken);
        ResponseEntity<String> ownerAssign = assign(owner, ownedDefId, owner.token());
        assertThat(ownerAssign.getStatusCode()).as("owner keeps full access: %s", ownerAssign.getBody())
                .isEqualTo(HttpStatus.CREATED);
        long definitionsBefore = salonDefinitionCount(owner.salonId());
        ResponseEntity<String> adminBand = exchange(rowUrl + "/" + ownedDefId, HttpMethod.PATCH,
                BAND_PATCH, adminToken);
        ResponseEntity<String> adminUnassign = exchange(rowUrl + "/" + ownedDefId, HttpMethod.DELETE,
                null, adminToken);
        ResponseEntity<String> adminBulk = exchange(rowUrl + "/bulk", HttpMethod.POST, bulkRequest(),
                adminToken);
        ResponseEntity<String> adminSchedule = createWeeklySchedule(owner.masterId(), adminToken);

        assertThat(adminAssign.getStatusCode()).as("assign: %s", adminAssign.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminBand.getStatusCode()).as("band edit: %s", adminBand.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminUnassign.getStatusCode()).as("unassign: %s", adminUnassign.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminBulk.getStatusCode()).as("bulk: %s", adminBulk.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminSchedule.getStatusCode()).as("schedule: %s", adminSchedule.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ?", Long.class, owner.masterId()))
                .as("only the owner's own assignment exists — no admin assign/bulk row").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ? "
                        + "AND is_active = true AND duration_override_minutes IS NULL",
                Long.class, owner.masterId(), ownedDefId))
                .as("owner's assignment still active and un-banded — no admin unassign/band edit").isEqualTo(1L);
        assertThat(salonDefinitionCount(owner.salonId()))
                .as("no definition created by the admin's bulk call").isEqualTo(definitionsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM weekly_schedules WHERE master_id = ?", Long.class, owner.masterId()))
                .as("no schedule written by the admin").isZero();
    }

    @Test
    @DisplayName("SALON_ADMIN still manages a REGULAR salon master's services (no regression): assign "
            + "201, band edit 200, unassign 204")
    void should_allowServices_when_salonAdminEditsRegularSalonMaster() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("admin-regular");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("admin-regular"));
        UUID staffMasterId = fixtures.createSalonMaster(owner.salonId());
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Staff Brows");
        OwnerSalon staffRow = new OwnerSalon(owner.salonId(), owner.token(), staffMasterId);
        String rowUrl = "/api/v1/salons/" + owner.salonId() + "/masters/" + staffMasterId + "/services/" + defId;

        ResponseEntity<String> adminAssign = assign(staffRow, defId, adminToken);
        ResponseEntity<String> adminBand = exchange(rowUrl, HttpMethod.PATCH, BAND_PATCH, adminToken);
        ResponseEntity<String> adminUnassign = exchange(rowUrl, HttpMethod.DELETE, null, adminToken);

        assertThat(adminAssign.getStatusCode()).as("assign: %s", adminAssign.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(adminBand.getStatusCode()).as("band edit: %s", adminBand.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(adminUnassign.getStatusCode()).as("unassign: %s", adminUnassign.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ? "
                        + "AND is_active = false AND duration_override_minutes = ?",
                Long.class, staffMasterId, defId, BAND_DURATION))
                .as("admin's band edit and unassign both landed on the regular master's row").isEqualTo(1L);
    }

    @Test
    @DisplayName("Owner of salon B is 403 on salon A's owner-master: assign, unassign, weekly schedule "
            + "and day override; nothing is written")
    void should_forbid_when_foreignOwnerEditsOwnerMasterServicesOrSchedule() throws Exception {
        OwnerSalon ownerA = registerOwnerWithSalon("victim");
        OwnerSalon ownerB = registerOwnerWithSalon("intruder");
        UUID defId = fixtures.createServiceDefinition(ownerA.token(), ownerA.salonId(), "Victim Brows");
        String assignmentUrl = "/api/v1/salons/" + ownerA.salonId() + "/masters/" + ownerA.masterId()
                + "/services";
        LocalDate overrideDay = LocalDate.now(TimeZones.KYIV).plusDays(DAYS_AHEAD);

        ResponseEntity<String> foreignAssign = assign(ownerA, defId, ownerB.token());
        ResponseEntity<String> foreignUnassign = exchange(assignmentUrl + "/" + defId, HttpMethod.DELETE,
                null, ownerB.token());
        ResponseEntity<String> foreignSchedule = createWeeklySchedule(ownerA.masterId(), ownerB.token());
        ResponseEntity<String> foreignOverride = exchange(
                "/api/v1/masters/" + ownerA.masterId() + "/overrides/" + overrideDay, HttpMethod.PUT,
                dayOffBody(overrideDay), ownerB.token());

        assertThat(foreignAssign.getStatusCode()).as("assign").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignUnassign.getStatusCode()).as("unassign").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignSchedule.getStatusCode()).as("weekly schedule").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignOverride.getStatusCode()).as("override").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM master_services WHERE master_id = ?) "
                        + "+ (SELECT COUNT(*) FROM weekly_schedules WHERE master_id = ?) "
                        + "+ (SELECT COUNT(*) FROM schedule_exceptions WHERE master_id = ?)",
                Long.class, ownerA.masterId(), ownerA.masterId(), ownerA.masterId()))
                .as("nothing written on salon A's owner-master").isZero();
    }

    // ── 7. catalogue-level gate (architect decision 2026-10-06) ────────────────────────────
    // A SALON_ADMIN cannot write a SALON definition with an ACTIVE assignment on the owner's own
    // row — owner-only or shared — through PATCH/DELETE /services/{id} or the photo endpoints.
    // The owner always can; a definition assigned only to other masters stays admin-writable.

    @Test
    @DisplayName("SALON_ADMIN is 403 on PATCH, DELETE, photo POST and photo DELETE of an OWNER-ONLY "
            + "definition; the definition row is unchanged on re-read")
    void should_forbidCatalogueWrites_when_adminEditsOwnerOnlyDefinition() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("cat-owner-only");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("cat-admin-1"));
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Owner Only Brows");
        assertThat(assign(owner, defId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        seedPhotoKey(defId);
        Map<String, Object> before = definitionRow(defId);

        Map<String, ResponseEntity<String>> responses = adminCatalogueWrites(defId, adminToken);

        assertCatalogueWritesRefused(responses);
        assertThat(definitionRow(defId)).as("definition untouched by the admin").isEqualTo(before);
    }

    @Test
    @DisplayName("SALON_ADMIN is 403 on PATCH, DELETE, photo POST and photo DELETE of a definition SHARED "
            + "by the owner and a regular master; the definition row is unchanged on re-read")
    void should_forbidCatalogueWrites_when_adminEditsDefinitionSharedWithOwner() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("cat-shared");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("cat-admin-2"));
        UUID staffMasterId = fixtures.createSalonMaster(owner.salonId());
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Shared Brows");
        assertThat(assign(owner, defId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OwnerSalon staffRow = new OwnerSalon(owner.salonId(), owner.token(), staffMasterId);
        assertThat(assign(staffRow, defId, adminToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        seedPhotoKey(defId);
        Map<String, Object> before = definitionRow(defId);

        Map<String, ResponseEntity<String>> responses = adminCatalogueWrites(defId, adminToken);

        assertCatalogueWritesRefused(responses);
        assertThat(definitionRow(defId)).as("definition untouched by the admin").isEqualTo(before);
    }

    @Test
    @DisplayName("SALON_ADMIN still writes a definition assigned ONLY to other masters: PATCH 200, DELETE 204")
    void should_allowCatalogueWrites_when_adminEditsDefinitionNotAssignedToOwner() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("cat-others");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("cat-admin-3"));
        UUID staffMasterId = fixtures.createSalonMaster(owner.salonId());
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Staff Only Brows");
        OwnerSalon staffRow = new OwnerSalon(owner.salonId(), owner.token(), staffMasterId);
        assertThat(assign(staffRow, defId, adminToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> patch = exchange("/api/v1/services/" + defId, HttpMethod.PATCH,
                DEFINITION_PATCH, adminToken);
        ResponseEntity<String> delete = exchange("/api/v1/services/" + defId, HttpMethod.DELETE, null, adminToken);

        assertThat(patch.getStatusCode()).as("PATCH: %s", patch.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(delete.getStatusCode()).as("DELETE: %s", delete.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(definitionRow(defId))
                .containsEntry("name", "Renamed Service")
                .containsEntry("is_active", false);
    }

    @Test
    @DisplayName("Owner PATCHes a definition shared with a regular master → 200 and the new base price is "
            + "the effective price on BOTH masters' rows")
    void should_applyToBothMasters_when_ownerPatchesSharedDefinition() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("cat-owner-patch");
        UUID staffMasterId = fixtures.createSalonMaster(owner.salonId());
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Shared Nails");
        OwnerSalon staffRow = new OwnerSalon(owner.salonId(), owner.token(), staffMasterId);
        // No price override on either row — both inherit the definition's base price.
        assertThat(assignInheriting(owner, defId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(assignInheriting(staffRow, defId).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> patch = exchange("/api/v1/services/" + defId, HttpMethod.PATCH,
                DEFINITION_PATCH, owner.token());

        assertThat(patch.getStatusCode()).as("PATCH: %s", patch.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(effectivePrice(owner.salonId(), owner.masterId(), defId, owner.token()))
                .as("owner row").isEqualByComparingTo(NEW_BASE_PRICE);
        assertThat(effectivePrice(owner.salonId(), staffMasterId, defId, owner.token()))
                .as("regular master row").isEqualByComparingTo(NEW_BASE_PRICE);
    }

    @Test
    @DisplayName("Once the owner DEACTIVATES their own assignment, the SALON_ADMIN's PATCH succeeds (200)")
    void should_allowAdminPatch_when_ownerDeactivatesOwnAssignment() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("cat-deactivated");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("cat-admin-4"));
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Released Brows");
        assertThat(assign(owner, defId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> blocked = exchange("/api/v1/services/" + defId, HttpMethod.PATCH,
                DEFINITION_PATCH, adminToken);
        assertThat(blocked.getStatusCode()).as("precondition: blocked while assigned").isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> unassigned = exchange("/api/v1/salons/" + owner.salonId() + "/masters/"
                + owner.masterId() + "/services/" + defId, HttpMethod.DELETE, null, owner.token());
        assertThat(unassigned.getStatusCode()).as(unassigned.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> patch = exchange("/api/v1/services/" + defId, HttpMethod.PATCH,
                DEFINITION_PATCH, adminToken);

        assertThat(patch.getStatusCode()).as("PATCH: %s", patch.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(definitionRow(defId)).containsEntry("name", "Renamed Service");
    }

    @Test
    @DisplayName("Owner RE-ASSIGNS a released definition → the SALON_ADMIN is blocked again (PATCH and "
            + "DELETE 403) and the definition keeps the admin's earlier rename only")
    void should_blockAdminAgain_when_ownerReassignsReleasedDefinition() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("cat-reassigned");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("cat-admin-5"));
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Reclaimed Brows");
        String definitionUrl = "/api/v1/services/" + defId;
        String ownerRowUrl = "/api/v1/salons/" + owner.salonId() + "/masters/" + owner.masterId()
                + "/services/" + defId;
        assertThat(assign(owner, defId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exchange(ownerRowUrl, HttpMethod.DELETE, null, owner.token()).getStatusCode())
                .as("owner releases the definition").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(definitionUrl, HttpMethod.PATCH, DEFINITION_PATCH, adminToken).getStatusCode())
                .as("precondition: admin may edit while released").isEqualTo(HttpStatus.OK);
        ResponseEntity<String> reassigned = assign(owner, defId, owner.token());
        assertThat(reassigned.getStatusCode()).as("owner reactivates: %s", reassigned.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> before = definitionRow(defId);

        ResponseEntity<String> patch = exchange(definitionUrl, HttpMethod.PATCH,
                Map.of("name", "Admin Rename Again"), adminToken);
        ResponseEntity<String> delete = exchange(definitionUrl, HttpMethod.DELETE, null, adminToken);

        assertThat(patch.getStatusCode()).as("PATCH: %s", patch.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete.getStatusCode()).as("DELETE: %s", delete.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readBody(patch).path("message").asText()).as("generic 403 body").isEqualTo(FORBIDDEN_MESSAGE);
        assertThat(definitionRow(defId)).as("definition untouched after the re-block").isEqualTo(before)
                .containsEntry("name", "Renamed Service")
                .containsEntry("is_active", true);
    }

    @Test
    @DisplayName("SALON_ADMIN manages an OWNER-PERFORMED definition on ANOTHER master's row: assign 201, "
            + "band edit 200, unassign 204 — the owner's own assignment is untouched")
    void should_allowRowWrites_when_adminAssignsOwnerPerformedDefinitionToAnotherMaster() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("row-other");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("row-admin"));
        UUID staffMasterId = fixtures.createSalonMaster(owner.salonId());
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Owner Performed Nails");
        assertThat(assign(owner, defId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OwnerSalon staffRow = new OwnerSalon(owner.salonId(), owner.token(), staffMasterId);
        String staffRowUrl = "/api/v1/salons/" + owner.salonId() + "/masters/" + staffMasterId
                + "/services/" + defId;

        ResponseEntity<String> adminAssign = assign(staffRow, defId, adminToken);
        ResponseEntity<String> adminBand = exchange(staffRowUrl, HttpMethod.PATCH, BAND_PATCH, adminToken);
        ResponseEntity<String> adminUnassign = exchange(staffRowUrl, HttpMethod.DELETE, null, adminToken);

        assertThat(adminAssign.getStatusCode()).as("assign: %s", adminAssign.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(adminBand.getStatusCode()).as("band edit: %s", adminBand.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(adminUnassign.getStatusCode()).as("unassign: %s", adminUnassign.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ? "
                        + "AND is_active = false AND duration_override_minutes = ?",
                Long.class, staffMasterId, defId, BAND_DURATION))
                .as("admin's band edit and unassign landed on the other master's row").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ? "
                        + "AND is_active = true AND duration_override_minutes IS NULL",
                Long.class, owner.masterId(), defId))
                .as("owner's own assignment still active and un-banded").isEqualTo(1L);
    }

    // ── 8. owner-row schedule writes (audit fix 5) ──────────────────────────────────────────

    @Test
    @DisplayName("SALON_ADMIN is 403 on the owner row's weekly-schedule PUT/DELETE, override PUT/DELETE, "
            + "override-conflicts preview and deprecated PATCH working-hours; nothing is written")
    void should_forbidScheduleWrites_when_salonAdminEditsOwnerMasterSchedule() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("sched-admin");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("sched-admin"));
        ResponseEntity<String> created = createWeeklySchedule(owner.masterId(), owner.token());
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID scheduleId = UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
        LocalDate overrideDay = LocalDate.now(TimeZones.KYIV).plusDays(DAYS_AHEAD);
        String overrideUrl = "/api/v1/masters/" + owner.masterId() + "/overrides/" + overrideDay;
        ResponseEntity<String> ownerOverride = exchange(overrideUrl, HttpMethod.PUT, dayOffBody(overrideDay),
                owner.token());
        assertThat(ownerOverride.getStatusCode()).as(ownerOverride.getBody()).isEqualTo(HttpStatus.OK);
        String masterUrl = "/api/v1/masters/" + owner.masterId();
        List<Map<String, Object>> before = scheduleSnapshot(owner.masterId());

        Map<String, Integer> statuses = new LinkedHashMap<>();
        statuses.put("weekly PUT", exchange(masterUrl + "/weekly-schedules/" + scheduleId, HttpMethod.PUT,
                weeklyScheduleRequest(), adminToken).getStatusCode().value());
        statuses.put("weekly DELETE", exchange(masterUrl + "/weekly-schedules/" + scheduleId,
                HttpMethod.DELETE, null, adminToken).getStatusCode().value());
        statuses.put("override PUT", exchange(overrideUrl, HttpMethod.PUT,
                Map.of("date", overrideDay.toString(), "kind", "CUSTOM_HOURS",
                        "intervals", List.of(Map.of("startTime", "10:00", "endTime", "12:00"))),
                adminToken).getStatusCode().value());
        statuses.put("override DELETE", exchange(overrideUrl, HttpMethod.DELETE, null, adminToken).getStatusCode().value());
        statuses.put("override conflicts", exchange(masterUrl + "/overrides/conflicts", HttpMethod.POST,
                Map.of("from", overrideDay.toString(), "to", overrideDay.toString(), "kind", "DAY_OFF"),
                adminToken).getStatusCode().value());
        statuses.put("PATCH working-hours", exchange(masterUrl + "/working-hours", HttpMethod.PATCH,
                List.of(Map.of("dayOfWeek", 1, "startTime", "09:00", "endTime", "17:00", "isActive", true)),
                adminToken).getStatusCode().value());

        assertThat(statuses).as("every owner-row schedule write is refused").allSatisfy((op, status) ->
                assertThat(status).as(op).isEqualTo(HttpStatus.FORBIDDEN.value()));
        assertThat(scheduleSnapshot(owner.masterId()))
                .as("weekly schedule, its windows, the override and working hours are all untouched")
                .isEqualTo(before);
    }

    // ── 9. the owner-master row is permanent (Phase 346) ────────────────────────────────────

    @Test
    @DisplayName("Phase 346: POST and DELETE /salons/{id}/master are gone — never 2xx, row stays active")
    void should_notBeRoutable_when_ownerCallsRemovedToggleEndpoints() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("toggle-gone");
        String url = "/api/v1/salons/" + owner.salonId() + "/master";

        ResponseEntity<String> post = exchange(url, HttpMethod.POST, null, owner.token());
        ResponseEntity<String> delete = exchange(url, HttpMethod.DELETE, null, owner.token());

        assertThat(post.getStatusCode()).as("POST: %s", post.getBody()).isEqualTo(REMOVED_ENDPOINT_STATUS);
        assertThat(delete.getStatusCode()).as("DELETE: %s", delete.getBody()).isEqualTo(REMOVED_ENDPOINT_STATUS);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM masters WHERE id = ?",
                Boolean.class, owner.masterId()))
                .as("the owner-master row is untouched by either call").isTrue();
    }

    @Test
    @DisplayName("Phase 346: DELETE /salons/{id}/masters/{ownerMasterId} is still 409; the row stays active")
    void should_return409_when_ownerRemovesOwnMasterRow() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("remove-own");

        ResponseEntity<String> response = exchange(
                "/api/v1/salons/" + owner.salonId() + "/masters/" + owner.masterId(),
                HttpMethod.DELETE, null, owner.token());

        // GlobalExceptionHandler renders every 409 BusinessException with the generic conflict
        // envelope, so the reworded service message ("The salon owner's master profile cannot be
        // removed") is pinned in SalonServiceRemoveMasterTest, not over HTTP.
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readBody(response).path("message").asText())
                .isEqualTo(CONFLICT_MESSAGE);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM masters WHERE id = ?",
                Boolean.class, owner.masterId()))
                .as("the owner-master row stays active").isTrue();
    }

    @Test
    @DisplayName("Phase 346 audit-fix: DELETE /masters/{ownerMasterId} by the owner is 409; the row stays "
            + "active and the owner stays in «Команда»")
    void should_return409_when_ownerDeactivatesOwnMasterRowViaMastersEndpoint() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("deactivate-own");

        ResponseEntity<String> response = exchange(
                "/api/v1/masters/" + owner.masterId(), HttpMethod.DELETE, null, owner.token());

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readBody(response).path("message").asText()).isEqualTo(CONFLICT_MESSAGE);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM masters WHERE id = ?",
                Boolean.class, owner.masterId()))
                .as("the owner is always a master of their salon — no deactivation").isTrue();
        assertThat(staffMasterIds(owner)).as("«Команда»").contains(owner.masterId().toString());
    }

    @Test
    @DisplayName("Phase 346 audit-fix (no regression): DELETE /masters/{invitedSalonMasterId} by the owner "
            + "still deactivates an invited SALON_MASTER — 204")
    void should_deactivateInvitedSalonMaster_when_ownerDeletesViaMastersEndpoint() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("deactivate-staff");
        UUID staffMasterId = fixtures.createSalonMaster(owner.salonId());

        ResponseEntity<String> response = exchange(
                "/api/v1/masters/" + staffMasterId, HttpMethod.DELETE, null, owner.token());

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM masters WHERE id = ?",
                Boolean.class, staffMasterId))
                .as("the invited master is deactivated").isFalse();
    }

    @Test
    @DisplayName("Phase 346 QA: a SALON_ADMIN of the salon calling DELETE /masters/{ownerMasterId} gets 403 "
            + "(authorization before the type-revealing 409); the owner row stays active")
    void should_return403_when_salonAdminDeactivatesOwnerMasterRow() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("admin-deactivate-owner");
        String adminToken = fixtures.createSalonAdminAndGetToken(owner.salonId(),
                uniqueEmail("admin-deactivate-owner"));

        ResponseEntity<String> response = exchange(
                "/api/v1/masters/" + owner.masterId(), HttpMethod.DELETE, null, adminToken);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readBody(response).path("message").asText()).isEqualTo(FORBIDDEN_MESSAGE);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM masters WHERE id = ?",
                Boolean.class, owner.masterId()))
                .as("the admin cannot deactivate the owner's master row").isTrue();
    }

    @Test
    @DisplayName("Phase 346 QA: after both owner-row removals are refused (409), the configured owner is "
            + "still on the client salon page / salon search (warm caches) and a client can still book them")
    void should_keepOwnerVisibleAndBookable_when_ownerRowRemovalRefused() throws Exception {
        OwnerSalon owner = registerOwnerWithSalon("refused-still-bookable");
        UUID assignmentId = configureOwnerMaster(owner);
        String clientToken = newClient("refused-still-bookable");
        assertVisibleToClients(owner, clientToken);

        ResponseEntity<String> viaMasters = exchange(
                "/api/v1/masters/" + owner.masterId(), HttpMethod.DELETE, null, owner.token());
        ResponseEntity<String> viaSalon = exchange(
                "/api/v1/salons/" + owner.salonId() + "/masters/" + owner.masterId(),
                HttpMethod.DELETE, null, owner.token());
        assertThat(viaMasters.getStatusCode()).as(viaMasters.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(viaSalon.getStatusCode()).as(viaSalon.getBody()).isEqualTo(HttpStatus.CONFLICT);

        assertVisibleToClients(owner, clientToken);
        LocalDate day = LocalDate.now(TimeZones.KYIV).plusDays(DAYS_AHEAD);
        ResponseEntity<String> booking = exchange("/api/v1/bookings", HttpMethod.POST,
                new CreateBookingRequest(owner.masterId(), assignmentId,
                        ZonedDateTime.of(day, CLIENT_SLOT, TimeZones.KYIV), null, null, false),
                clientToken);
        assertThat(booking.getStatusCode()).as(booking.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(readBody(booking).path("data").path("masterId").asText())
                .as("the booking lands on the owner's own master row")
                .isEqualTo(owner.masterId().toString());
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────────

    private void assertHiddenFromClients(OwnerSalon owner, String clientToken, String step) throws Exception {
        assertThat(http.rosterIds(owner.salonId())).as("%s: /salons/{id}/masters", step).isEmpty();
        assertThat(bySalonIds(owner, clientToken)).as("%s: /masters/by-salon/{id}", step).isEmpty();
        assertThat(ids(http.get(SALON_SEARCH_URL), "salonId")).as("%s: salon search", step)
                .doesNotContain(owner.salonId().toString());
        assertNeverInMasterSearch(owner, step);
    }

    private void assertVisibleToClients(OwnerSalon owner, String clientToken) throws Exception {
        assertThat(http.rosterIds(owner.salonId())).as("/salons/{id}/masters")
                .containsExactly(owner.masterId().toString());
        assertThat(bySalonIds(owner, clientToken)).as("/masters/by-salon/{id}")
                .containsExactly(owner.masterId().toString());
        assertThat(ids(http.get(SALON_SEARCH_URL), "salonId")).as("salon search")
                .contains(owner.salonId().toString());
        assertNeverInMasterSearch(owner, "configured");
    }

    /**
     * {@code /search/masters} is {@code INDEPENDENT_MASTER}-only by locked Phase 19.7 decision 7
     * ({@code SearchService.ROLE_INDEPENDENT_MASTER}): every salon-employed master — the owner-master
     * included, exactly like a {@code SALON_MASTER} — is discovered through salon search and the salon
     * page, never on the master grid. Pinned so a change to that rule is a deliberate decision.
     */
    private void assertNeverInMasterSearch(OwnerSalon owner, String step) throws Exception {
        assertThat(ids(http.get(MASTER_SEARCH_URL), "masterId")).as("%s: master search", step)
                .doesNotContain(owner.masterId().toString());
    }

    // ── HTTP steps ──────────────────────────────────────────────────────────────────────────

    /** Real sign-up path: owner user + {@code POST /salons}, which auto-enrols the owner-master row. */
    private OwnerSalon registerOwnerWithSalon(String tag) throws Exception {
        String email = uniqueEmail("owner-" + tag);
        String token = fixtures.createSalonOwnerAndGetToken(email);
        UUID salonId = fixtures.createSalon(token, "Owner Self-Service " + tag);
        return new OwnerSalon(salonId, token, fixtures.resolveMasterIdForUserEmail(email));
    }

    /** Service + assignment + weekly hours, all as the owner over HTTP. @return the assignment id. */
    private UUID configureOwnerMaster(OwnerSalon owner) throws Exception {
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Owner Service");
        ResponseEntity<String> assigned = assign(owner, defId, owner.token());
        assertThat(assigned.getStatusCode()).as(assigned.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> schedule = createWeeklySchedule(owner.masterId(), owner.token());
        assertThat(schedule.getStatusCode()).as(schedule.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(assigned.getBody()).path("data").path("id").asText());
    }

    private ResponseEntity<String> assign(OwnerSalon owner, UUID defId, String actorToken) {
        return exchange("/api/v1/salons/" + owner.salonId() + "/masters/" + owner.masterId() + "/services",
                HttpMethod.POST, new AssignServiceToMasterRequest(defId, PriceType.FIXED, PRICE, null, null),
                actorToken);
    }

    /** Open-ended template from today (Kyiv), 09:00–17:00 on every ISO weekday. */
    private ResponseEntity<String> createWeeklySchedule(UUID masterId, String actorToken) {
        return exchange("/api/v1/masters/" + masterId + "/weekly-schedules", HttpMethod.POST,
                weeklyScheduleRequest(), actorToken);
    }

    private static WeeklyScheduleRequest weeklyScheduleRequest() {
        List<WeeklyScheduleDayRequest> days = IntStream.rangeClosed(1, 7)
                .mapToObj(d -> new WeeklyScheduleDayRequest(d, List.of(new WorkIntervalDto(OPEN, CLOSE))))
                .toList();
        return new WeeklyScheduleRequest(LocalDate.now(TimeZones.KYIV), null, days);
    }

    /** Assignment with NO price override — the row inherits the definition's base price. */
    private ResponseEntity<String> assignInheriting(OwnerSalon row, UUID defId) {
        return exchange("/api/v1/salons/" + row.salonId() + "/masters/" + row.masterId() + "/services",
                HttpMethod.POST, new AssignServiceToMasterRequest(defId, null, null, null, null), row.token());
    }

    /** The four catalogue-level writes, as {@code actorToken}; returns each operation's status. */
    private Map<String, ResponseEntity<String>> adminCatalogueWrites(UUID defId, String actorToken) {
        String url = "/api/v1/services/" + defId;
        Map<String, ResponseEntity<String>> responses = new LinkedHashMap<>();
        responses.put("PATCH", exchange(url, HttpMethod.PATCH, DEFINITION_PATCH, actorToken));
        responses.put("DELETE", exchange(url, HttpMethod.DELETE, null, actorToken));
        responses.put("photo POST", uploadPhoto(defId, actorToken));
        responses.put("photo DELETE", exchange(url + "/photo", HttpMethod.DELETE, null, actorToken));
        return responses;
    }

    /**
     * Every catalogue write is a 403 with the SAME error envelope — since the Phase 345 fix all four
     * routes pass a role-only {@code @PreAuthorize} and are refused by the service-layer
     * {@code enforceCanManageServiceDefinition} ({@code ForbiddenException}), so PATCH and the photo
     * routes can no longer diverge from DELETE. {@code GlobalExceptionHandler.handleForbidden} renders
     * every {@code ForbiddenException} as the generic {@value #FORBIDDEN_MESSAGE}.
     */
    private void assertCatalogueWritesRefused(Map<String, ResponseEntity<String>> responses) {
        assertThat(responses).as("every catalogue write is refused").allSatisfy((op, response) -> {
            assertThat(response.getStatusCode()).as(op).isEqualTo(HttpStatus.FORBIDDEN);
            JsonNode body = readBody(response);
            assertThat(body.path("success").asBoolean(true)).as("%s success flag", op).isFalse();
            assertThat(body.path("message").asText()).as("%s message", op).isEqualTo(FORBIDDEN_MESSAGE);
        });
    }

    private JsonNode readBody(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("403 body is not JSON: " + response.getBody(), e);
        }
    }

    private ResponseEntity<String> uploadPhoto(UUID defId, String token) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(JPEG_BYTES) {
            @Override
            public String getFilename() {
                return "a.jpg";
            }
        });
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/v1/services/" + defId + "/photo", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** Existing photo pointer, so a leaked photo DELETE would be observable as a cleared key. */
    private void seedPhotoKey(UUID defId) {
        jdbcTemplate.update("UPDATE service_definitions SET photo_r2_key = ? WHERE id = ?",
                "services/" + defId + "/seed.jpg", defId);
    }

    /** Every column a catalogue write could change — compared whole before/after. */
    private Map<String, Object> definitionRow(UUID defId) {
        return jdbcTemplate.queryForMap(
                "SELECT name, price_type, base_price, price_max, base_duration_minutes, is_active, "
                        + "photo_r2_key, photo_url, updated_at FROM service_definitions WHERE id = ?", defId);
    }

    private BigDecimal effectivePrice(UUID salonId, UUID masterId, UUID defId, String token) throws Exception {
        for (JsonNode row : http.get("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", token)) {
            if (row.path("serviceDefinition").path("id").asText().equals(defId.toString())) {
                return row.path("effectivePrice").decimalValue();
            }
        }
        throw new AssertionError("no assignment of " + defId + " on master " + masterId);
    }

    /**
     * Full text fingerprint of every schedule row the owner-row writes could touch (row-to-text
     * casts, so new columns are covered without editing this helper).
     */
    private List<Map<String, Object>> scheduleSnapshot(UUID masterId) {
        return jdbcTemplate.queryForList(
                "SELECT 'ws' AS t, s::text AS r FROM weekly_schedules s WHERE s.master_id = ? "
                        + "UNION ALL SELECT 'wdw', w::text FROM weekly_schedule_day_windows w "
                        + "JOIN weekly_schedules s ON s.id = w.schedule_id WHERE s.master_id = ? "
                        + "UNION ALL SELECT 'ex', e::text FROM schedule_exceptions e WHERE e.master_id = ? "
                        + "UNION ALL SELECT 'wh', h::text FROM working_hours h WHERE h.master_id = ? "
                        + "ORDER BY 1, 2",
                masterId, masterId, masterId, masterId);
    }

    /** Whole-day override body, serialised by Jackson (no hand-built JSON). */
    private static Map<String, Object> dayOffBody(LocalDate day) {
        return Map.of("date", day.toString(), "kind", "DAY_OFF");
    }

    /** One valid FIXED item on a seeded, selectable service type — the setup-flow bulk shape. */
    private BulkCreateServicesRequest bulkRequest() {
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        return new BulkCreateServicesRequest(
                List.of(new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, PRICE, null, null)));
    }

    private long salonDefinitionCount(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_type = 'SALON' AND owner_id = ?",
                Long.class, salonId);
    }

    private ResponseEntity<String> exchange(String url, HttpMethod method, Object body, String token) {
        return restTemplate.exchange(url, method, new HttpEntity<>(body, fixtures.bearerHeaders(token)),
                String.class);
    }

    private List<String> bySalonIds(OwnerSalon owner, String clientToken) throws Exception {
        return ids(http.get("/api/v1/masters/by-salon/" + owner.salonId(), clientToken), "masterId");
    }

    /** {@code GET /salons/{id}/services/{defId}/masters} — a plain list, not a page. */
    private List<String> coverageMasterIds(UUID salonId, UUID defId) throws Exception {
        List<String> masterIds = new ArrayList<>();
        http.get("/api/v1/salons/" + salonId + "/services/" + defId + "/masters")
                .forEach(row -> masterIds.add(row.path("masterId").asText()));
        return masterIds;
    }

    /** Master ids (rows carrying a masterId) of the unfiltered management roster «Команда». */
    private List<String> staffMasterIds(OwnerSalon owner) throws Exception {
        List<String> masterIds = new ArrayList<>();
        http.get("/api/v1/salons/" + owner.salonId() + "/staff", owner.token()).forEach(member -> {
            if (member.hasNonNull("masterId")) {
                masterIds.add(member.path("masterId").asText());
            }
        });
        return masterIds;
    }

    private String newClient(String tag) throws Exception {
        return fixtures.createClientAndGetToken(uniqueEmail("client-" + tag));
    }

    private static String uniqueEmail(String prefix) {
        return "oms-" + prefix + "-" + System.nanoTime() + "@beautica.test";
    }

    private record OwnerSalon(UUID salonId, String token, UUID masterId) {
    }
}
