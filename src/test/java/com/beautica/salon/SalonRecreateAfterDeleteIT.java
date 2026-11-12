package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hotfix fix/owner-recreate-salon-master — SAL-04 "the owner is always a master of their salon"
 * must survive deleting the only salon and creating a new one. Before the fix createSalon counted
 * the deleted salon as "already has a salon", so the new salon was non-primary and the owner's
 * master row stayed inactive on the deleted salon: GET /masters/me answered 404.
 *
 * <p>Real HTTP end to end, so the {@code master-detail-by-user} / {@code user-profile} caches are on
 * the path (a negative entry cached between delete and re-create must not survive).
 *
 * <p>The V235 cases repair owners ALREADY stuck in production: the stuck shape is seeded through the
 * API (create A, delete A, create B) and then rewound by SQL to what the pre-fix code left behind,
 * after which V235's exact script is re-applied on one connection (as {@code OwnerMasterActiveMigrationIT}
 * does for V192 — session temp table, no DDL, so the shared container is fine).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon re-create after delete — owner-as-master re-homed onto the new salon")
class SalonRecreateAfterDeleteIT extends AbstractIntegrationTest {

    private static final String V235_SCRIPT = "db/migration/V235__rehome_stuck_owner_masters.sql";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("owner deletes the only salon A, creates B → /masters/me 200 on B, B primary, hasMasterProfile true")
    void should_rehomeOwnerMasterToNewSalon_when_onlySalonDeletedAndNewOneCreated() throws Exception {
        String token = ownerToken();
        UUID salonA = createSalon(token, "Recreate A");
        deleteSalon(token, salonA);
        // Warm the negative master-detail-by-user entry the bug report saw («Запис не знайдено»).
        restTemplate.exchange("/api/v1/masters/me", HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);

        UUID salonB = createSalon(token, "Recreate B");

        JsonNode me = get("/api/v1/masters/me", token);
        assertThat(me.path("salon").path("id").asText())
                .as("owner-as-master must be re-homed onto the new salon")
                .isEqualTo(salonB.toString());
        assertThat(get("/api/v1/users/me", token).path("hasMasterProfile").asBoolean())
                .as("/users/me must report the master profile").isTrue();
        assertThat(isPrimary(salonB)).as("new salon is the owner's primary salon").isTrue();
        assertThat(isPrimary(salonA)).as("deleted salon released is_primary").isFalse();
    }

    @Test
    @DisplayName("owner with an ACTIVE salon creates a second one → not primary, master stays on the first salon")
    void should_keepMasterOnFirstSalon_when_ownerWithActiveSalonCreatesSecond() throws Exception {
        String token = ownerToken();
        UUID salonA = createSalon(token, "Active A");

        UUID salonB = createSalon(token, "Second B");

        assertThat(isPrimary(salonA)).as("first salon stays primary").isTrue();
        assertThat(isPrimary(salonB)).as("second salon is not primary").isFalse();
        assertThat(get("/api/v1/masters/me", token).path("salon").path("id").asText())
                .as("owner-as-master is not moved by a second salon")
                .isEqualTo(salonA.toString());
    }

    @Test
    @DisplayName("owner deletes A, creates B, deletes B, creates C → master on C, C is the owner's only primary salon")
    void should_rehomeOwnerMasterAgain_when_recreatedSalonIsDeletedAndAnotherCreated() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Twice A");
        deleteSalon(token, salonA);
        UUID salonB = createSalon(token, "Twice B");
        deleteSalon(token, salonB);

        UUID salonC = createSalon(token, "Twice C");

        assertThat(get("/api/v1/masters/me", token).path("salon").path("id").asText())
                .as("owner-as-master follows the owner onto the third salon")
                .isEqualTo(salonC.toString());
        assertThat(primarySalonIds(ownerId, false))
                .as("exactly one primary row across ALL the owner's salons, active or deleted")
                .containsExactly(salonC);
    }

    /**
     * Audit finding (b). Owner holds A (primary, carries the owner-master) and an active non-primary B, deletes A
     * (the staff cascade deactivates the owner-master on A), then creates C. createSalon sees one ACTIVE salon (B);
     * before the fix that skipped createMasterForOwner and left nothing primary (/masters/me 404). Now the delete
     * already re-homed the owner-master onto B and made B primary, so creating C takes the unchanged later-salon path.
     */
    @Test
    @DisplayName("owner with A (primary, master) + active B deletes A, creates C → master stays on B (re-homed at delete), B the only primary, C not primary")
    void should_keepOwnerMasterOnActiveSalonAndOnePrimary_when_primaryDeletedWhileSecondSalonActive() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Primary A");
        UUID salonB = createSalon(token, "Second B");
        deleteSalon(token, salonA);

        UUID salonC = createSalon(token, "Third C");

        ResponseEntity<String> me = restTemplate.exchange("/api/v1/masters/me", HttpMethod.GET,
                new HttpEntity<>(headers(token)), String.class);
        assertThat(me.getStatusCode()).as("GET /masters/me — body: %s", me.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(me.getBody()).path("data").path("salon").path("id").asText())
                .as("re-homed onto B at delete time; creating C must not move it again")
                .isEqualTo(salonB.toString());
        assertThat(masterIsActive(ownerId)).as("owner-master row is active").isTrue();
        assertThat(primarySalonIds(ownerId, false))
                .as("B took primary at delete time; C is created non-primary — one primary row in total")
                .containsExactly(salonB);
        assertThat(isPrimary(salonC)).as("C is a later salon, not primary").isFalse();
    }

    // ── Delete-time re-home (SAL-04): the owner-master never waits for a new salon ───────

    @Test
    @DisplayName("owner with A (primary, master) + active B deletes A → immediately /masters/me 200 on B, B the only primary, hasMasterProfile true")
    void should_rehomeOwnerMasterOntoRemainingSalon_when_primarySalonCarryingMasterDeleted() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Delete-time A");
        UUID salonB = createSalon(token, "Delete-time B", "Franka St", "42");
        // Warm the positive master-detail-by-user / user-profile entries pointing at A.
        get("/api/v1/masters/me", token);
        get("/api/v1/users/me", token);

        deleteSalon(token, salonA);

        assertThat(get("/api/v1/masters/me", token).path("salon").path("id").asText())
                .as("owner-as-master re-homed onto the remaining salon at delete time")
                .isEqualTo(salonB.toString());
        assertThat(masterIsActive(ownerId)).as("owner-master row reactivated").isTrue();
        assertThat(primarySalonIds(ownerId, false))
                .as("B is the only primary row across ALL the owner's salons, active or deleted")
                .containsExactly(salonB);
        JsonNode userMe = get("/api/v1/users/me", token);
        assertThat(userMe.path("hasMasterProfile").asBoolean())
                .as("/users/me must report the master profile").isTrue();
        assertThat(List.of(userMe.path("street").asText(), userMe.path("buildingNo").asText()))
                .as("/users/me address follows the new primary salon B (was A's Shevchenka St 7)")
                .containsExactly("Franka St", "42");
    }

    /**
     * Pins the derived query's ORDER BY (isPrimary DESC, createdAt ASC) — the SalonServiceTest units mock the
     * repository, so only a real DB proves the oldest active salon wins — and the ownerSalons cache eviction:
     * GET /salons/mine is warmed before the delete and must show the hand-over afterwards.
     */
    @Test
    @DisplayName("owner with A (primary, master) + active B, C (C older) deletes A → master and primary land on the OLDEST active salon C, /salons/mine shows it")
    void should_rehomeOntoOldestActiveSalon_when_primaryDeletedWithSeveralNonPrimaryLeft() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Oldest-first A");
        UUID salonB = createSalon(token, "Oldest-first B", "Franka St", "42");
        UUID salonC = createSalon(token, "Oldest-first C", "Lysenka St", "9");
        // C created last by the API, backdated: the target must follow created_at, not insertion order or id.
        jdbcTemplate.update("UPDATE salons SET created_at = now() - interval '2 days' WHERE id = ?", salonC);
        jdbcTemplate.update("UPDATE salons SET created_at = now() - interval '1 day' WHERE id = ?", salonB);
        get("/api/v1/salons/mine", token);

        deleteSalon(token, salonA);

        assertThat(masterSalonId(ownerId)).as("re-homed onto the oldest active salon").isEqualTo(salonC);
        assertThat(masterIsActive(ownerId)).as("owner-master row reactivated").isTrue();
        assertThat(primarySalonIds(ownerId, false))
                .as("C is the only primary row across ALL the owner's salons, active or deleted")
                .containsExactly(salonC);
        JsonNode mine = get("/api/v1/salons/mine", token);
        Map<String, Boolean> primaryById = new HashMap<>();
        mine.forEach(s -> primaryById.put(s.path("id").asText(), s.path("isPrimary").asBoolean()));
        assertThat(primaryById)
                .as("ownerSalons cache evicted — /salons/mine reflects the hand-over, actual=%s", mine)
                .containsEntry(salonC.toString(), true)
                .containsEntry(salonB.toString(), false)
                .doesNotContainKey(salonA.toString());
        JsonNode userMe = get("/api/v1/users/me", token);
        assertThat(List.of(userMe.path("street").asText(), userMe.path("buildingNo").asText()))
                .as("/users/me address follows the new primary C")
                .containsExactly("Lysenka St", "9");
    }

    @Test
    @DisplayName("owner with B (primary, master) + A deletes A → owner-master untouched (same row, same salon, same updated_at)")
    void should_leaveOwnerMasterUntouched_when_deletedSalonDidNotCarryIt() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonB = createSalon(token, "Master B");
        UUID salonA = createSalon(token, "Plain A");
        Timestamp before = masterUpdatedAt(ownerId);

        deleteSalon(token, salonA);

        assertThat(masterIsActive(ownerId)).isTrue();
        assertThat(masterSalonId(ownerId)).isEqualTo(salonB);
        assertThat(masterUpdatedAt(ownerId)).as("row not written").isEqualTo(before);
        assertThat(primarySalonIds(ownerId, false)).as("primary unchanged").containsExactly(salonB);
    }

    @Test
    @DisplayName("single-salon owner deletes it → unchanged: row inactive on A, no active primary, /masters/me 404")
    void should_leaveOwnerMasterInactive_when_onlySalonDeleted() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Only A");

        deleteSalon(token, salonA);

        assertThat(masterIsActive(ownerId)).as("row stays inactive").isFalse();
        assertThat(masterSalonId(ownerId)).as("row stays on the deleted salon").isEqualTo(salonA);
        assertThat(primarySalonIds(ownerId, true)).as("no active primary").isEmpty();
        assertThat(restTemplate.exchange("/api/v1/masters/me", HttpMethod.GET,
                new HttpEntity<>(headers(token)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("re-home at delete time keeps the deleted salon's cascade: its future CONFIRMED booking is DECLINED, its services deactivated")
    void should_keepDeletedSalonCascade_when_ownerMasterRehomed() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Cascade A");
        UUID salonB = createSalon(token, "Cascade B");
        UUID masterId = masterIdOf(ownerId);
        UUID masterServiceId = insertMasterService(masterId, salonA);
        UUID bookingId = insertBooking(insertClient(), masterId, masterServiceId, salonA,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), "CONFIRMED");

        deleteSalon(token, salonA);

        assertThat(masterSalonId(ownerId)).as("re-homed onto B").isEqualTo(salonB);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("SAL-20 — future CONFIRMED booking at the deleted salon is declined")
                .isEqualTo("DECLINED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM service_definitions WHERE owner_type = 'SALON' AND owner_id = ? AND is_active",
                Integer.class, salonA))
                .as("SAL-19 — the deleted salon's services are deactivated").isZero();
    }

    // ── V235 — repair of owners already stuck before the fix ───────────────────────

    @Test
    @DisplayName("V235 re-homes a stuck owner-master onto the active salon, makes it primary → /masters/me 200 on B")
    void should_repairStuckOwner_when_v235Applied() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Stuck A");
        deleteSalon(token, salonA);
        UUID salonB = createSalon(token, "Stuck B");
        rewindToPreFixStuckState(ownerId, salonA, salonB);
        jdbcTemplate.update("UPDATE salons SET street = 'Franka St', building_no = '42' WHERE id = ?", salonB);

        applyV235();

        assertThat(get("/api/v1/masters/me", token).path("salon").path("id").asText())
                .isEqualTo(salonB.toString());
        assertThat(jdbcTemplate.queryForList("SELECT street, building_no FROM users WHERE id = ?", ownerId).get(0))
                .as("V235 copies the newly-primary salon's address onto the owner")
                .containsEntry("street", "Franka St").containsEntry("building_no", "42");
        assertThat(isPrimary(salonB)).as("oldest active salon becomes primary").isTrue();
        assertThat(isPrimary(salonA)).as("deleted salon released is_primary").isFalse();
    }

    @Test
    @DisplayName("V235 leaves an owner with no active salon untouched, and a second pass changes nothing")
    void should_leaveOwnerWithoutActiveSalonAndBeIdempotent_when_v235Applied() throws Exception {
        String goneToken = ownerToken();
        UUID goneSalon = createSalon(goneToken, "Gone A");
        deleteSalon(goneToken, goneSalon);
        String stuckToken = ownerToken();
        UUID stuckOwner = ownerIdOf(stuckToken);
        UUID stuckA = createSalon(stuckToken, "Idem A");
        deleteSalon(stuckToken, stuckA);
        UUID stuckB = createSalon(stuckToken, "Idem B");
        rewindToPreFixStuckState(stuckOwner, stuckA, stuckB);

        applyV235();
        Timestamp afterFirstPass = masterUpdatedAt(stuckOwner);
        applyV235();

        assertThat(masterIsActive(ownerIdOf(goneToken))).as("no active salon → row stays inactive").isFalse();
        assertThat(isPrimary(goneSalon)).as("no active salon → is_primary untouched").isTrue();
        assertThat(masterUpdatedAt(stuckOwner)).as("second pass selects nothing").isEqualTo(afterFirstPass);
    }

    @Test
    @DisplayName("V235 reactivates an inactive owner-master in place when its salon is still active — no re-home, primary unchanged")
    void should_reactivateInPlace_when_v235FindsInactiveMasterOnActiveSalon() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "InPlace A");
        UUID salonB = createSalon(token, "InPlace B");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE user_id = ?", ownerId);

        applyV235();

        assertThat(masterIsActive(ownerId)).as("row reactivated").isTrue();
        assertThat(masterSalonId(ownerId)).as("keep_salon_id branch — stays on its own active salon")
                .isEqualTo(salonA);
        assertThat(primarySalonIds(ownerId, false)).as("primary untouched").containsExactly(salonA);
        assertThat(isPrimary(salonB)).isFalse();
    }

    @Test
    @DisplayName("V235 makes the OLDEST active salon (created_at) primary and re-homes the master there when none is primary")
    void should_pickOldestActiveSalonAsPrimary_when_v235FindsNoActivePrimary() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Oldest A");
        deleteSalon(token, salonA);
        UUID salonB = createSalon(token, "Oldest B");
        UUID salonC = createSalon(token, "Oldest C");
        rewindToPreFixStuckState(ownerId, salonA, salonB);
        // C created later by the API, but backdated: ordering must follow created_at, not insertion order.
        jdbcTemplate.update("UPDATE salons SET created_at = now() - interval '2 days' WHERE id = ?", salonC);
        jdbcTemplate.update("UPDATE salons SET created_at = now() - interval '1 day' WHERE id = ?", salonB);

        applyV235();

        assertThat(primarySalonIds(ownerId, false)).as("oldest active salon is the one primary row")
                .containsExactly(salonC);
        assertThat(masterSalonId(ownerId)).as("master re-homed onto the new primary").isEqualTo(salonC);
        assertThat(masterIsActive(ownerId)).isTrue();
    }

    @Test
    @DisplayName("V235 does not resurrect the owner-master of a DISABLED owner account (users.is_active = false)")
    void should_leaveDisabledOwnerUntouched_when_v235Applied() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Disabled A");
        deleteSalon(token, salonA);
        UUID salonB = createSalon(token, "Disabled B");
        rewindToPreFixStuckState(ownerId, salonA, salonB);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", ownerId);

        applyV235();

        assertThat(masterIsActive(ownerId)).as("disabled owner — row stays inactive").isFalse();
        assertThat(masterSalonId(ownerId)).as("disabled owner — row not re-homed").isEqualTo(salonA);
        assertThat(isPrimary(salonA)).as("disabled owner — is_primary untouched").isTrue();
        assertThat(isPrimary(salonB)).isFalse();
    }

    /**
     * Audit finding (a). V235 step 4 must recompute the salon rating with {@code r.moderation_status = 'VISIBLE'},
     * else a HIDDEN_PENDING review re-enters the public avg_rating/review_count. Must match
     * ReviewRepository#recalculateSalonRating.
     */
    @Test
    @DisplayName("V235 rating recompute counts only VISIBLE reviews — a HIDDEN_PENDING review is excluded")
    void should_excludeNonVisibleReviewsFromRating_when_v235RecomputesTargetSalon() throws Exception {
        String token = ownerToken();
        UUID ownerId = ownerIdOf(token);
        UUID salonA = createSalon(token, "Rated A");
        deleteSalon(token, salonA);
        UUID salonB = createSalon(token, "Rated B");
        rewindToPreFixStuckState(ownerId, salonA, salonB);
        UUID masterId = masterIdOf(ownerId);
        UUID masterServiceId = insertMasterService(masterId, salonB);
        UUID clientId = insertClient();
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
        insertReview(insertCompletedBooking(clientId, masterId, masterServiceId, salonB, base),
                clientId, masterId, salonB, 5, "VISIBLE");
        insertReview(insertCompletedBooking(clientId, masterId, masterServiceId, salonB, base.plusDays(1)),
                clientId, masterId, salonB, 1, "HIDDEN_PENDING");

        applyV235();

        Map<String, Object> rating = jdbcTemplate.queryForMap(
                "SELECT review_count, avg_rating FROM salons WHERE id = ?", salonB);
        assertThat(((Number) rating.get("review_count")).intValue())
                .as("review_count must exclude the HIDDEN_PENDING review, actual=%s", rating)
                .isEqualTo(1);
        assertThat((BigDecimal) rating.get("avg_rating"))
                .as("avg_rating must be the VISIBLE review only, actual=%s", rating)
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("V235 is recorded as a successful Flyway migration")
    void should_recordV235AsSuccessful_when_containerBoots() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '235'", Boolean.class);

        assertThat(success).isTrue();
    }

    /** What the pre-fix createSalon left behind: master inactive on deleted A, A primary, B not. */
    private void rewindToPreFixStuckState(UUID ownerId, UUID deletedSalon, UUID newSalon) {
        // Order matters: idx_salons_owner_primary (V56) allows one primary row per owner.
        jdbcTemplate.update("UPDATE salons SET is_primary = false WHERE id = ?", newSalon);
        jdbcTemplate.update("UPDATE salons SET is_primary = true WHERE id = ?", deletedSalon);
        jdbcTemplate.update("UPDATE masters SET salon_id = ?, is_active = false WHERE user_id = ?",
                deletedSalon, ownerId);
    }

    private void applyV235() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(V235_SCRIPT));
            return null;
        });
    }

    private UUID ownerIdOf(String token) {
        return jwtTokenProvider.getUserIdFromToken(token);
    }

    private boolean masterIsActive(UUID ownerId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE user_id = ?", Boolean.class, ownerId));
    }

    private Timestamp masterUpdatedAt(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM masters WHERE user_id = ?", Timestamp.class, ownerId);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private UUID masterIdOf(UUID ownerId) {
        return jdbcTemplate.queryForObject("SELECT id FROM masters WHERE user_id = ?", UUID.class, ownerId);
    }

    private UUID masterSalonId(UUID ownerId) {
        return jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE user_id = ?", UUID.class, ownerId);
    }

    private List<UUID> primarySalonIds(UUID ownerId, boolean activeOnly) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM salons WHERE owner_id = ? AND is_primary = true"
                        + (activeOnly ? " AND is_active = true" : ""),
                UUID.class, ownerId);
    }

    private UUID insertClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', 'Cl', 'Ient', true, true)",
                id, "recreate-client-" + id + "@beautica.test", PASSWORD_HASH);
        return id;
    }

    private UUID insertMasterService(UUID masterId, UUID salonId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Rated Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID insertCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                        OffsetDateTime startsAt) {
        return insertBooking(clientId, masterId, masterServiceId, salonId, startsAt, "COMPLETED");
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                               OffsetDateTime startsAt, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status, startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    private void insertReview(UUID bookingId, UUID clientId, UUID masterId, UUID salonId, int rating,
                              String moderationStatus) {
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, moderation_status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId, rating, moderationStatus);
    }


    private String ownerToken() {
        UUID id = UUID.randomUUID();
        String email = "recreate-" + id + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', 'Fn', 'Ln', true, true)",
                id, email, PASSWORD_HASH);
        return jwtTokenProvider.generateAccessToken(id, email, Role.SALON_OWNER);
    }

    private UUID createSalon(String token, String name) throws Exception {
        return createSalon(token, name, "Shevchenka St", "7");
    }

    private UUID createSalon(String token, String name, String street, String buildingNo) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name, "cityId", testCityId(), "street", street, "buildingNo", buildingNo));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, headers(token)), String.class);
        assertThat(response.getStatusCode()).as("POST /salons — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(response.getBody()).path("data").path("id").asText());
    }

    private void deleteSalon(String token, UUID salonId) {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/salons/" + salonId, HttpMethod.DELETE,
                new HttpEntity<>(headers(token)), String.class);
        assertThat(response.getStatusCode()).as("DELETE /salons — body: %s", response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private JsonNode get(String url, String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertThat(response.getStatusCode()).as("GET %s — body: %s", url, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private boolean isPrimary(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_primary FROM salons WHERE id = ?", Boolean.class, salonId));
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
