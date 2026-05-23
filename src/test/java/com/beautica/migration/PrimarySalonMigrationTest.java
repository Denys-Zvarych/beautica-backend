package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that V56__add_primary_salon_and_owner_master_type.sql and
 * V57__add_salons_owner_id_index.sql produce the correct schema state.
 *
 * <p>Q21 (migration content assertion): "Flyway applied with no error" proves nothing
 * about the data — this test asserts the actual outcome: column presence, nullability,
 * default value, index existence (by name), and the uniqueness invariant enforced by
 * idx_salons_owner_primary.
 *
 * <p>Note: The idx_masters_user_owner_type partial unique index (also in V56) is
 * verified separately by the masters/master_type uniqueness assertion below.
 */
@DisplayName("V56 + V57 migration — primary salon schema and indexes")
class PrimarySalonMigrationTest extends AbstractIntegrationTest {

    // ── salons.is_primary column ──────────────────────────────────────────────

    @Test
    @DisplayName("V56 — salons.is_primary column exists with type BOOLEAN")
    void should_addIsPrimaryColumn_when_v56Applied() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = 'salons' AND column_name = 'is_primary'",
                String.class);

        assertThat(dataType)
                .as("salons.is_primary must be of type boolean")
                .isEqualTo("boolean");
    }

    @Test
    @DisplayName("V56 — salons.is_primary is NOT NULL")
    void should_makeIsPrimaryNotNullable_when_v56Applied() {
        String isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_name = 'salons' AND column_name = 'is_primary'",
                String.class);

        assertThat(isNullable)
                .as("salons.is_primary must be NOT NULL per V56 DDL")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V56 — existing salon rows receive is_primary=false as the default")
    void should_defaultIsPrimaryToFalse_when_existingRowsMigrated() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                userId,
                "migration-v56-test-" + userId + "@beautica.test",
                "$2a$10$placeholder.hash.only.for.migration.schema.test",
                "SALON_OWNER");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active) VALUES (?, ?, ?, ?)",
                salonId, userId, "Migration Test Salon", true);

        Boolean isPrimary = jdbcTemplate.queryForObject(
                "SELECT is_primary FROM salons WHERE id = ?", Boolean.class, salonId);

        assertThat(isPrimary)
                .as("is_primary must default to false for rows inserted without explicit value")
                .isFalse();
    }

    // ── idx_salons_owner_primary partial unique index ─────────────────────────

    @Test
    @DisplayName("V56 — idx_salons_owner_primary partial unique index exists on salons")
    void should_createIdxSalonsOwnerPrimary_when_v56Applied() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'salons' AND indexname = 'idx_salons_owner_primary'",
                String.class);

        assertThat(indexes)
                .as("idx_salons_owner_primary must be created by V56")
                .containsExactly("idx_salons_owner_primary");
    }

    @Test
    @DisplayName("V56 — idx_salons_owner_primary is a unique index")
    void should_makeIdxSalonsOwnerPrimaryUnique_when_v56Applied() {
        Boolean isUnique = jdbcTemplate.queryForObject(
                "SELECT ix.indisunique " +
                "FROM pg_index ix " +
                "JOIN pg_class c ON c.oid = ix.indexrelid " +
                "WHERE c.relname = 'idx_salons_owner_primary'",
                Boolean.class);

        assertThat(isUnique)
                .as("idx_salons_owner_primary must be a UNIQUE index")
                .isTrue();
    }

    @Test
    @DisplayName("V56 — idx_salons_owner_primary is partial (only covers is_primary=true rows)")
    void should_makeIdxSalonsOwnerPrimaryPartial_when_v56Applied() {
        // pg_indexes.indexdef contains the WHERE clause for partial indexes.
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'salons' AND indexname = 'idx_salons_owner_primary'",
                String.class);

        assertThat(indexDef)
                .as("idx_salons_owner_primary must be a partial index with WHERE is_primary = true")
                .containsIgnoringCase("where")
                .containsIgnoringCase("is_primary");
    }

    @Test
    @DisplayName("V56 — idx_salons_owner_primary rejects a second is_primary=true row for the same owner")
    void should_rejectSecondPrimaryRow_when_ownerAlreadyHasPrimary() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                userId,
                "uniq-primary-" + userId + "@beautica.test",
                "$2a$10$placeholder.hash.only.for.migration.schema.test",
                "SALON_OWNER");

        UUID firstSalonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, is_primary) VALUES (?, ?, ?, ?, ?)",
                firstSalonId, userId, "First Primary Salon", true, true);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO salons (id, owner_id, name, is_active, is_primary) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(), userId, "Second Primary Salon", true, true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .as("idx_salons_owner_primary must reject a second is_primary=true row for the same owner");
    }

    @Test
    @DisplayName("V56 — idx_salons_owner_primary allows multiple is_primary=false rows for the same owner")
    void should_allowMultipleNonPrimaryRows_when_OwnerHasSeveralNonPrimary() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                userId,
                "multi-non-primary-" + userId + "@beautica.test",
                "$2a$10$placeholder.hash.only.for.migration.schema.test",
                "SALON_OWNER");

        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, is_primary) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "Non-Primary One", true, false);
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, is_primary) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "Non-Primary Two", true, false);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM salons WHERE owner_id = ? AND is_primary = false",
                Integer.class, userId);

        assertThat(count)
                .as("two non-primary salons for the same owner must be permitted by the partial unique index")
                .isEqualTo(2);
    }

    // ── idx_masters_salon_owner_active partial index ──────────────────────────

    @Test
    @DisplayName("V56 — idx_masters_salon_owner_active partial index exists on masters")
    void should_createIdxMastersSalonOwnerActive_when_v56Applied() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'masters' AND indexname = 'idx_masters_salon_owner_active'",
                String.class);

        assertThat(indexes)
                .as("idx_masters_salon_owner_active must be created by V56")
                .containsExactly("idx_masters_salon_owner_active");
    }

    @Test
    @DisplayName("V56 — idx_masters_salon_owner_active is partial (WHERE master_type=SALON_OWNER AND is_active=true)")
    void should_makeIdxMastersSalonOwnerActivePartial_when_v56Applied() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'masters' AND indexname = 'idx_masters_salon_owner_active'",
                String.class);

        assertThat(indexDef)
                .as("idx_masters_salon_owner_active must be a partial index filtering by master_type and is_active")
                .containsIgnoringCase("SALON_OWNER")
                .containsIgnoringCase("is_active");
    }

    // ── idx_masters_user_owner_type partial unique index ─────────────────────

    @Test
    @DisplayName("V56 — idx_masters_user_owner_type partial unique index exists on masters")
    void should_createIdxMastersUserOwnerType_when_v56Applied() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'masters' AND indexname = 'idx_masters_user_owner_type'",
                String.class);

        assertThat(indexes)
                .as("idx_masters_user_owner_type must be created by V56")
                .containsExactly("idx_masters_user_owner_type");
    }

    @Test
    @DisplayName("V56 — idx_masters_user_owner_type is a unique partial index (WHERE master_type=SALON_OWNER)")
    void should_makeIdxMastersUserOwnerTypeUniqueAndPartial_when_v56Applied() {
        // Verify both uniqueness and partiality in one assertion — the WHERE clause
        // restricts uniqueness only to SALON_OWNER rows, not all master_type values.
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'masters' AND indexname = 'idx_masters_user_owner_type'",
                String.class);
        Boolean isUnique = jdbcTemplate.queryForObject(
                "SELECT ix.indisunique FROM pg_index ix " +
                "JOIN pg_class c ON c.oid = ix.indexrelid " +
                "WHERE c.relname = 'idx_masters_user_owner_type'",
                Boolean.class);

        assertThat(isUnique)
                .as("idx_masters_user_owner_type must be UNIQUE")
                .isTrue();
        assertThat(indexDef)
                .as("idx_masters_user_owner_type must be partial (WHERE master_type = 'SALON_OWNER')")
                .containsIgnoringCase("SALON_OWNER");
    }

    // ── V57: idx_salons_owner_id non-partial index ────────────────────────────

    @Test
    @DisplayName("V57 — idx_salons_owner_id non-partial index exists on salons")
    void should_createIdxSalonsOwnerId_when_v57Applied() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'salons' AND indexname = 'idx_salons_owner_id'",
                String.class);

        assertThat(indexes)
                .as("idx_salons_owner_id must be created by V57")
                .containsExactly("idx_salons_owner_id");
    }

    @Test
    @DisplayName("V57 — idx_salons_owner_id is non-partial (no WHERE clause)")
    void should_makeIdxSalonsOwnerIdNonPartial_when_v57Applied() {
        // Non-partial indexes do not have a WHERE clause in their indexdef.
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'salons' AND indexname = 'idx_salons_owner_id'",
                String.class);

        assertThat(indexDef)
                .as("idx_salons_owner_id must not contain a WHERE clause — it is non-partial by V57 design " +
                    "to serve existsByOwnerId for active AND inactive salons")
                .doesNotContainIgnoringCase(" where ");
    }

    // ── MasterType.SALON_OWNER enum string representation ────────────────────

    @Test
    @DisplayName("MasterType.SALON_OWNER — serialises to string value SALON_OWNER")
    void should_serializeToCorrectString_when_salonOwnerEnumUsed() {
        com.beautica.master.entity.MasterType type = com.beautica.master.entity.MasterType.SALON_OWNER;

        assertThat(type.name())
                .as("MasterType.SALON_OWNER must serialise to the string 'SALON_OWNER' " +
                    "as stored in master_type VARCHAR column")
                .isEqualTo("SALON_OWNER");
    }

    @Test
    @DisplayName("MasterType — enum has exactly three values: SALON_MASTER, INDEPENDENT_MASTER, SALON_OWNER")
    void should_haveThreeValues_when_masterTypeEnumInspected() {
        com.beautica.master.entity.MasterType[] values = com.beautica.master.entity.MasterType.values();

        assertThat(values)
                .as("MasterType must have exactly SALON_MASTER, INDEPENDENT_MASTER, SALON_OWNER")
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("SALON_MASTER", "INDEPENDENT_MASTER", "SALON_OWNER");
    }
}
