package com.beautica.moderation;

import com.beautica.TestPostgresImage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V211 over a database holding rows written under the V210 model: a CLIENT row that also carries the (unverified)
 * profile-phone hash + last4 — which V211's {@code chk_client_blocks_identity_kind} forbids. V211 must null that
 * hash/last4 BEFORE adding the CHECK (else the migration aborts), keep the account, and leave GUEST rows and plain
 * CLIENT rows untouched. A private container is migrated to V210, seeded, then migrated to V211 once (pattern of
 * {@link V209OrphanReportCommentMigrationIT}).
 */
@DisplayName("V211 migration — CLIENT block rows lose the profile-phone hash; GUEST rows untouched")
class V211ClientBlocksIdentityMigrationIT {

    private static final String MIGRATIONS = "classpath:db/migration";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static UUID clientWithHashRow;
    private static UUID clientWithHashUser;
    private static UUID plainClientRow;
    private static UUID guestRow;
    private static UUID masterId;
    private static final byte[] PROFILE_HASH = filled((byte) 0x11);
    private static final byte[] GUEST_HASH = filled((byte) 0x22);

    @BeforeAll
    static void migrateTo210SeedV210ModelRowsThenMigrateTo211() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        migrateTo(dataSource, "210");

        masterId = insertIndependentMaster();
        clientWithHashUser = insertUser("CLIENT");
        clientWithHashRow = insertBlock(clientWithHashUser, PROFILE_HASH, "1234");
        plainClientRow = insertBlock(insertUser("CLIENT"), null, null);
        guestRow = insertBlock(null, GUEST_HASH, "4567");

        migrateTo(dataSource, "211");
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("V211 applies cleanly over a V210 CLIENT row that carries a phone hash")
    void should_recordV211Successful_when_clientRowCarriesPhoneHash() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '211'", Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("the CLIENT row keeps its account but loses the profile-phone hash and last4")
    void should_clearHashAndLast4_when_clientRowCarriedThem() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT blocked_user_id, phone_hash, phone_last4, blocker_master_id FROM client_blocks WHERE id = ?",
                clientWithHashRow);

        assertThat(row.get("blocked_user_id")).isEqualTo(clientWithHashUser);
        assertThat(row.get("phone_hash")).as("unverified profile phone must not survive V211").isNull();
        assertThat(row.get("phone_last4")).isNull();
        assertThat(row.get("blocker_master_id")).isEqualTo(masterId);
    }

    @Test
    @DisplayName("a GUEST row keeps its phone hash and last4")
    void should_keepGuestHash_when_rowIsGuest() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT blocked_user_id, phone_hash, phone_last4 FROM client_blocks WHERE id = ?", guestRow);

        assertThat(row.get("blocked_user_id")).isNull();
        assertThat((byte[]) row.get("phone_hash")).isEqualTo(GUEST_HASH);
        assertThat(row.get("phone_last4")).isEqualTo("4567");
    }

    @Test
    @DisplayName("no row is deleted by V211")
    void should_keepEveryRow_when_migrating() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM client_blocks WHERE id IN (?, ?, ?)", Integer.class,
                clientWithHashRow, plainClientRow, guestRow);

        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("after V211 a CLIENT row with a phone hash is rejected by chk_client_blocks_identity_kind")
    void should_rejectClientRowWithHash_when_insertedAfterV211() {
        UUID user = insertUser("CLIENT");

        assertThatThrownBy(() -> insertBlock(user, filled((byte) 0x33), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_blocks_identity_kind");
    }

    private static void migrateTo(DataSource dataSource, String version) {
        Flyway.configure().dataSource(dataSource).locations(MIGRATIONS)
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private static UUID insertUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, ?, true, true, 'Ірина', 'Коваль')",
                id, "v211-" + id + "@beautica.test", PASSWORD_HASH, role);
        return id;
    }

    private static UUID insertIndependentMaster() {
        UUID userId = insertUser("INDEPENDENT_MASTER");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())", id, userId);
        return id;
    }

    private static UUID insertBlock(UUID userId, byte[] hash, String last4) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO client_blocks (id, blocker_master_id, blocked_user_id, phone_hash, phone_last4, "
                + "display_name) VALUES (?, ?, ?, ?, ?, 'Ірина К.')", id, masterId, userId, hash, last4);
        return id;
    }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
