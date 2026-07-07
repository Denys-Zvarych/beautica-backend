package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract test for V108__drop_salon_admin_uniqueness.sql (Phase 21.1 — multi-admin relaxation).
 *
 * <p>V108 drops {@code uq_users_salon_admin} — the partial unique index (created by
 * {@code V8__Add_salon_admin_role.sql}, {@code CREATE UNIQUE INDEX ... ON users (salon_id)
 * WHERE role = 'SALON_ADMIN'}) that previously enforced at most one SALON_ADMIN per salon at
 * the database level.
 *
 * <p>Every other test written for Phase 21.1 (service-layer {@code InviteServiceAdminTest},
 * HTTP-layer {@code InviteControllerIT}) exercises the invite-creation step only: an
 * {@code invite_tokens} row is created, but the second admin's {@code users} row is never
 * actually persisted in those flows (it only appears once the invite is *accepted*, and no
 * existing test carries a second admin through to acceptance). None of them fire a raw INSERT
 * against {@code users} with two SALON_ADMIN rows sharing the same {@code salon_id}, so none of
 * them actually prove the DB-level constraint is gone — per QA playbook Q21, a migration's
 * correctness IS its contract, and "Flyway applied with no error" proves nothing about the
 * data. This test closes that gap with a raw JDBC insert that bypasses the service/DTO layers
 * entirely (mirrors {@code V105SalonInstagramConstraintMigrationTest} / {@code
 * V102HardeningConstraintsMigrationTest}).
 *
 * <p>{@code users.salon_id} DOES carry an FK to {@code salons} —
 * {@code fk_users_salon_id (ON DELETE SET NULL)}, added by {@code V3__Create_salons_table.sql}
 * immediately after V2 introduced the bare column. A real {@code salons} row must therefore exist
 * before a {@code SALON_ADMIN} user can reference it, or the insert trips {@code fk_users_salon_id}
 * (23503) instead of exercising {@code uq_users_salon_admin} — this mirrors the
 * {@code insertUser()}/{@code insertSalon()} fixture pattern in
 * {@code V105SalonInstagramConstraintMigrationTest}.
 */
@DisplayName("V108 migration — uq_users_salon_admin partial unique index removed")
class V108DropSalonAdminUniquenessMigrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("uq_users_salon_admin no longer exists in pg_indexes after V108")
    void should_notExist_when_v108HasRun() {
        // The most direct proof V108 actually took effect at the DDL level — independent of
        // whether a subsequent INSERT happens to also succeed for unrelated reasons.
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_users_salon_admin'",
                Integer.class);

        assertThat(indexCount)
                .as("uq_users_salon_admin must have been dropped by V108")
                .isZero();
    }

    @Test
    @DisplayName("INSERT of a second SALON_ADMIN row for the same salon_id no longer throws a unique-constraint violation")
    void should_insertSecondSalonAdminForSameSalon_when_v108HasDroppedTheUniqueIndex() {
        UUID salonId = insertSalon(insertUser());

        // Pre-V108, the second of these two inserts would have thrown
        // DataIntegrityViolationException (23505 unique_violation on uq_users_salon_admin).
        assertThatCode(() -> {
            insertSalonAdmin(salonId);
            insertSalonAdmin(salonId);
        })
                .as("a second SALON_ADMIN row for the same salon_id must be accepted at the DB level")
                .doesNotThrowAnyException();

        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE salon_id = ? AND role = 'SALON_ADMIN'",
                Integer.class, salonId);

        assertThat(adminCount)
                .as("both SALON_ADMIN rows for salon_id=%s must actually be persisted, not silently deduplicated", salonId)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("INSERT of a THIRD SALON_ADMIN row for the same salon_id also succeeds (no residual at-most-two guard)")
    void should_insertThirdSalonAdminForSameSalon_when_v108HasDroppedTheUniqueIndex() {
        // Guards against a hypothetical incomplete fix that merely widened the index to allow
        // exactly two admins (e.g. a partial index keyed on something else) instead of removing
        // the cardinality constraint entirely.
        UUID salonId = insertSalon(insertUser());

        assertThatCode(() -> {
            insertSalonAdmin(salonId);
            insertSalonAdmin(salonId);
            insertSalonAdmin(salonId);
        })
                .as("a third SALON_ADMIN row for the same salon_id must also be accepted — the constraint is fully removed, not just relaxed to two")
                .doesNotThrowAnyException();

        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE salon_id = ? AND role = 'SALON_ADMIN'",
                Integer.class, salonId);

        assertThat(adminCount).isEqualTo(3);
    }

    // ── insert helpers (raw JDBC, bypassing the service/DTO layer) ────────────────

    /** Owner row for the salon FK — role is irrelevant to this test, SALON_OWNER is conventional. */
    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                id, "v108-owner-" + id + "@beautica.test",
                "$2a$04$placeholder.for.migration.test.only", "SALON_OWNER");
        return id;
    }

    private UUID insertSalon(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active) VALUES (?, ?, ?, true)",
                id, ownerId, "V108 Test Salon");
        return id;
    }

    private void insertSalonAdmin(UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id) VALUES (?, ?, ?, 'SALON_ADMIN', ?)",
                id, "v108-" + id + "@beautica.test",
                "$2a$04$placeholder.for.migration.test.only", salonId);
    }
}
