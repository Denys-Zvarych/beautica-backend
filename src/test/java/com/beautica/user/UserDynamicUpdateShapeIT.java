package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence IT for the {@code @DynamicUpdate} optimisation on {@link User}.
 *
 * <p><b>What this pins.</b> {@code @DynamicUpdate} makes Hibernate emit an
 * {@code UPDATE users SET …} that lists <em>only the dirtied columns</em>
 * instead of the static all-column UPDATE that Hibernate generates by default.
 * That optimisation is invisible to every behavioural assertion (the row ends
 * up with the same values either way), so the only way to prove it is to inspect
 * the <em>shape</em> of the SQL Hibernate actually prepares.
 *
 * <p><b>Why a locality-only PATCH is the probe.</b> A CLIENT
 * {@link UserService#updateProfile} call that supplies only {@code street}
 * (with {@code cityId == null}) dirties exactly one column. The legacy
 * denormalised display columns {@code users.city} / {@code users.region} are
 * pre-populated on the saved row and are <em>not</em> touched by this PATCH
 * ({@code writeCityDisplayStrings} returns early when {@code cityId} is null).
 * <ul>
 *   <li>With {@code @DynamicUpdate} the flush UPDATE contains {@code street=…}
 *       and must NOT contain {@code city=…} / {@code region=…}.</li>
 *   <li>Without it, Hibernate's static UPDATE would rewrite every column —
 *       including {@code city=?} and {@code region=?} — and this assertion
 *       fails. That is the regression net: if the annotation is ever removed,
 *       this test goes red.</li>
 * </ul>
 *
 * <p><b>Harness.</b> Reuses the dependency-free Hibernate
 * {@link StatementInspector} capture pattern already proven in
 * {@code LocalityDiscoveryPerfHardeningTest}: a {@link TestConfiguration}
 * registers an observer that appends every prepared SQL string to a static
 * sink. The test clears the sink, invokes the real production service path
 * (non-{@code @Transactional}, so the commit flush actually fires), then
 * inspects the captured {@code UPDATE users …}.
 */
@DisplayName("User @DynamicUpdate — flush UPDATE lists only dirtied columns (SQL-shape evidence)")
@Import(UserDynamicUpdateShapeIT.SqlCaptureConfig.class)
class UserDynamicUpdateShapeIT extends AbstractIntegrationTest {

    static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearCapturedSql() {
        CAPTURED_SQL.clear();
    }

    @Test
    @DisplayName("locality-only PATCH (street only, cityId null) emits UPDATE users without city= / region=")
    void should_emitUpdateWithoutLegacyCityRegion_when_onlyStreetIsDirtied() {
        // Arrange — persist a CLIENT whose legacy denormalised display columns are
        // already populated. These columns must survive the PATCH untouched, and —
        // crucially — must NOT appear in the dynamic UPDATE statement.
        UUID userId = persistClientWithLegacyLocality(
                "dynupdate-client@beautica.test", "Київ", "Київська область");

        // Act — a locality-only PATCH that dirties exactly one column: street.
        // cityId is null, so writeCityDisplayStrings returns early and city/region
        // are never re-written by the service either.
        var request = new UpdateProfileRequest(
                null, null, null,           // firstName, lastName, phoneNumber
                null, null,                 // cityId, districtId
                "вул. Хрещатик",            // street  ← the only dirtied column
                null, null);                // buildingNo, locationNote
        userService.updateProfile(userId, request);

        // Assert — exactly one UPDATE on users was prepared, and its dynamic
        // column list contains street but NOT the legacy city/region columns.
        String updateSql = capturedUsersUpdate();
        assertThat(updateSql)
                .as("a flush UPDATE on users must have been prepared, captured=%s", CAPTURED_SQL)
                .isNotNull();
        assertThat(updateSql)
                .as("@DynamicUpdate must include the dirtied street column, sql=%s", updateSql)
                .contains("street=");
        assertThat(updateSql)
                .as("@DynamicUpdate must NOT re-write the untouched legacy city column "
                        + "(a static all-column UPDATE would — that is the regression), sql=%s", updateSql)
                .doesNotContain("city=");
        assertThat(updateSql)
                .as("@DynamicUpdate must NOT re-write the untouched legacy region column, sql=%s", updateSql)
                .doesNotContain("region=");

        // Behavioural cross-check: the legacy display columns are unchanged in the committed row.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT city, region, street FROM users WHERE id = ?", userId);
        assertThat(row.get("city"))
                .as("legacy city must be untouched by a street-only PATCH").isEqualTo("Київ");
        assertThat(row.get("region"))
                .as("legacy region must be untouched by a street-only PATCH").isEqualTo("Київська область");
        assertThat(row.get("street"))
                .as("street must be persisted by the PATCH").isEqualTo("вул. Хрещатик");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID persistClientWithLegacyLocality(String email, String city, String region) {
        var user = new User(email, passwordEncoder.encode("test-password-123"),
                Role.CLIENT, "Ірина", "Шевченко", "+380671234567");
        user.setCity(city);
        user.setRegion(region);
        return userRepository.save(user).getId();
    }

    /**
     * Returns the single {@code UPDATE users …} statement Hibernate prepared
     * (lower-cased for case-insensitive column matching), or {@code null} if none
     * was captured. We match on {@code "update users "} to ignore UPDATEs to other
     * tables and the SELECT/INSERT that precede the flush.
     */
    private String capturedUsersUpdate() {
        return CAPTURED_SQL.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("update users "))
                .findFirst()
                .orElse(null);
    }

    /**
     * Registers a pure-observer {@link StatementInspector} on the Hibernate
     * {@code SessionFactory}. Mirrors the proven, dependency-free capture pattern
     * from {@code LocalityDiscoveryPerfHardeningTest}.
     */
    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        HibernatePropertiesCustomizer userUpdateSqlCaptureCustomizer() {
            return (Map<String, Object> props) ->
                    props.put("hibernate.session_factory.statement_inspector",
                            new CapturingStatementInspector());
        }
    }

    static final class CapturingStatementInspector implements StatementInspector {
        @Override
        public String inspect(String sql) {
            if (sql != null) {
                CAPTURED_SQL.add(sql);
            }
            return sql;
        }
    }
}
