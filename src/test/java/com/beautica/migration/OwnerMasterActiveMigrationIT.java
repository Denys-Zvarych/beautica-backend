package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 346 — V192 reactivates stray inactive {@code SALON_OWNER} master rows on live salons, so
 * "owner of a live salon ⇒ active owner-master row" holds once the toggle endpoints are gone.
 *
 * <p>Same approach as {@code V177SalonsCityBackfillMigrationTest}: the shared container boots fully
 * migrated on an empty schema, so each test seeds the pre-fix shape and re-applies V192's exact SQL
 * script. The script uses a session-scoped temp table, so it is executed statement by statement on
 * ONE connection ({@link ScriptUtils#executeSqlScript}), exactly as Flyway runs it; it touches no
 * permanent DDL, so no private container is needed (Playbook §M.2). Outcomes are asserted on row
 * state, not update counts, because a multi-statement script has no single count.
 */
@DisplayName("V192 migration — inactive owner-master rows on live salons are reactivated")
class OwnerMasterActiveMigrationIT extends AbstractIntegrationTest {

    private static final String V192_SCRIPT = "db/migration/V192__owner_master_active_on_live_salons.sql";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    private static final OffsetDateTime PAST = OffsetDateTime.parse("2026-01-15T10:00:00Z");

    @Test
    @DisplayName("an inactive SALON_OWNER row on the owner's live salon is reactivated")
    void should_reactivateOwnerRow_when_salonIsLive() {
        UUID ownerId = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(ownerId, true);
        UUID masterId = insertMaster(ownerId, salonId, "SALON_OWNER", false);

        applyV192();

        assertThat(isActive(masterId)).isTrue();
    }

    @Test
    @DisplayName("an inactive SALON_OWNER row on a deleted (inactive) salon is left untouched")
    void should_leaveOwnerRowInactive_when_salonIsDeleted() {
        UUID ownerId = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(ownerId, false);
        UUID masterId = insertMaster(ownerId, salonId, "SALON_OWNER", false);

        applyV192();

        assertThat(isActive(masterId)).isFalse();
    }

    @Test
    @DisplayName("rows outside the predicate stay as they are: SALON_MASTER type, or salon owned by someone else")
    void should_leaveRowsUntouched_when_notOwnersOwnRow() {
        UUID ownerId = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(ownerId, true);
        UUID salonMasterId = insertMaster(insertUser("SALON_MASTER"), salonId, "SALON_MASTER", false);
        UUID foreignOwnerRowId = insertMaster(insertUser("SALON_OWNER"), salonId, "SALON_OWNER", false);

        applyV192();

        assertThat(isActive(salonMasterId)).as("an invited SALON_MASTER row is not the owner's").isFalse();
        assertThat(isActive(foreignOwnerRowId)).as("s.owner_id must equal m.user_id").isFalse();
    }

    @Test
    @DisplayName("idempotent: a second application touches zero rows")
    void should_updateNothing_when_appliedTwice() {
        UUID ownerId = insertUser("SALON_OWNER");
        UUID masterId = insertMaster(ownerId, insertSalon(ownerId, true), "SALON_OWNER", false);

        applyV192();
        Timestamp afterFirstPass = updatedAt(masterId);
        applyV192();

        assertThat(isActive(masterId)).isTrue();
        assertThat(updatedAt(masterId))
                .as("a second pass must not re-select the row (updated_at would move to the new now())")
                .isEqualTo(afterFirstPass);
    }

    @Test
    @DisplayName("a disabled owner's (users.is_active = false) row on a live salon stays inactive")
    void should_leaveOwnerRowInactive_when_ownerAccountIsDisabled() {
        UUID ownerId = insertUser("SALON_OWNER");
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", ownerId);
        UUID masterId = insertMaster(ownerId, insertSalon(ownerId, true), "SALON_OWNER", false);

        applyV192();

        assertThat(isActive(masterId)).isFalse();
    }

    @Test
    @DisplayName("the reactivated owner's reviews are counted in the salon rating aggregate")
    void should_countOwnerReviewsInSalonRating_when_ownerRowIsReactivated() {
        UUID ownerId = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(ownerId, true);
        UUID ownerMasterId = insertMaster(ownerId, salonId, "SALON_OWNER", false);
        UUID staffMasterId = insertMaster(insertUser("SALON_MASTER"), salonId, "SALON_MASTER", true);
        UUID serviceDefId = insertServiceDefinition(salonId);
        UUID clientId = insertUser("CLIENT");
        insertReview(clientId, ownerMasterId, serviceDefId, salonId, 5);
        insertReview(clientId, staffMasterId, serviceDefId, salonId, 3);
        // Stale pre-V192 aggregate: only the active staff master's review was counted.
        jdbcTemplate.update("UPDATE salons SET avg_rating = 3.00, review_count = 1 WHERE id = ?", salonId);

        applyV192();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_count FROM salons WHERE id = ?", Integer.class, salonId))
                .as("the owner's review joins the count once the row is active")
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM salons WHERE id = ?", BigDecimal.class, salonId))
                .as("per-master average of 5 and 3")
                .isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("V192 is recorded as a successful Flyway migration")
    void should_recordV192AsSuccessful_when_containerBoots() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '192'", Boolean.class);

        assertThat(success).isTrue();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private void applyV192() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(V192_SCRIPT));
            return null;
        });
    }

    private UUID insertServiceDefinition(UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'V192 Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                id, salonId, resolveUnusedServiceTypeId("SALON", salonId));
        return id;
    }

    /** A COMPLETED past booking (own master_service row) plus its review. */
    private void insertReview(UUID clientId, UUID masterId, UUID serviceDefId, UUID salonId, int rating) {
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, PAST, PAST.plusMinutes(60));
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId, rating);
    }

    private Timestamp updatedAt(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM masters WHERE id = ?", Timestamp.class, masterId);
    }

    private UUID insertUser(String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, true, true)",
                id, "v192-" + id + "@beautica.test", PASSWORD_HASH, role);
        return id;
    }

    private UUID insertSalon(UUID ownerId, boolean active) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, city_id) VALUES (?, ?, 'V192', ?, ?)",
                id, ownerId, active, testCityId());
        return id;
    }

    private UUID insertMaster(UUID userId, UUID salonId, String masterType, boolean active) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                id, userId, salonId, masterType, active);
        return id;
    }

    private boolean isActive(UUID masterId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = ?", Boolean.class, masterId));
    }
}
