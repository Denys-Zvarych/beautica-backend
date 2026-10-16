package com.beautica.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FlywayRepairConfig}. Pure JUnit 5 + Mockito, no Spring context. The strategy
 * validates once on a pending/future-tolerant Flyway and classifies by structured error codes: valid ->
 * {@code migrate()} with no repair; applied-migration checksum drift -> ERROR + {@code repair()} +
 * {@code migrate()} in prod-only, boot failure (no repair) when {@code staging} is active.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlywayRepairConfig — migration strategy unit")
class FlywayRepairConfigTest {

    @Mock
    private Flyway flyway;

    @Mock
    private Flyway tolerant;

    @Mock
    private Flyway nonValidating;

    private FlywayMigrationStrategy build(MockEnvironment env, DeployEnvironment de, Optional<String> stamp) {
        return FlywayRepairConfig.strategy(env, de, f -> stamp, f -> tolerant, f -> nonValidating);
    }

    private FlywayMigrationStrategy prodStrategy() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return build(env, DeployEnvironment.PROD, Optional.of("prod"));
    }

    private FlywayMigrationStrategy stagingStrategy() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "staging");
        return build(env, DeployEnvironment.STAGING, Optional.of("staging"));
    }

    private static ValidateOutput invalid(String version, CoreErrorCode code) {
        return new ValidateOutput(version, "desc", "V" + version + "__desc.sql", new ErrorDetails(code, "detail"));
    }

    private static ValidateResult failed(ValidateOutput... outputs) {
        return new ValidateResult("11", "db", new ErrorDetails(CoreErrorCode.VALIDATE_ERROR, "failed"),
                false, outputs.length, List.of(outputs), List.of());
    }

    private static ValidateResult ok() {
        return new ValidateResult("11", "db", null, true, 3, List.of(), List.of());
    }

    @Test
    @DisplayName("healthy DB: validation passes -> migrate() once, repair() never")
    void should_skipRepair_when_validateSucceeds() {
        when(tolerant.validateWithResult()).thenReturn(ok());

        prodStrategy().migrate(flyway);

        verify(nonValidating, times(1)).migrate();
        verify(flyway, never()).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    @DisplayName("failed result with empty invalidMigrations (top-level error only): validating migrate() fails fast, no unvalidated migrate")
    void should_useValidatingMigrate_when_failedWithEmptyInvalidMigrations() {
        when(tolerant.validateWithResult()).thenReturn(failed());

        prodStrategy().migrate(flyway);

        verify(flyway, times(1)).migrate();
        verify(flyway, never()).repair();
        verify(nonValidating, never()).migrate();
    }

    @Test
    @DisplayName("checksum drift in prod-only: repair() then migrate()")
    void should_repairThenMigrate_when_checksumDriftInProd() {
        when(tolerant.validateWithResult()).thenReturn(failed(invalid("42", CoreErrorCode.CHECKSUM_MISMATCH)));

        prodStrategy().migrate(flyway);

        InOrder ordered = inOrder(flyway);
        ordered.verify(flyway).repair();
        ordered.verify(flyway).migrate();
        verify(flyway, times(1)).repair();
        verify(nonValidating, never()).migrate();
    }

    @Test
    @DisplayName("pure checksum drift on several versions in prod: repair() then migrate()")
    void should_repairThenMigrate_when_multiplePureChecksumDriftsInProd() {
        when(tolerant.validateWithResult()).thenReturn(failed(
                invalid("42", CoreErrorCode.CHECKSUM_MISMATCH),
                invalid("43", CoreErrorCode.CHECKSUM_MISMATCH)));

        prodStrategy().migrate(flyway);

        InOrder ordered = inOrder(flyway);
        ordered.verify(flyway).repair();
        ordered.verify(flyway).migrate();
        verify(nonValidating, never()).migrate();
    }

    @Test
    @DisplayName("checksum drift mixed with another problem in prod: no repair, validating migrate() fails fast")
    void should_useValidatingMigrateWithoutRepair_when_driftAndOtherProblemMixed() {
        when(tolerant.validateWithResult()).thenReturn(failed(
                invalid("42", CoreErrorCode.CHECKSUM_MISMATCH),
                invalid("7", CoreErrorCode.APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED)));

        prodStrategy().migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway, times(1)).migrate();
        verify(nonValidating, never()).migrate();
    }

    @Test
    @DisplayName("checksum drift with staging active: boot fails, no repair, no migrate")
    void should_failBoot_when_checksumDriftInStaging() {
        when(tolerant.validateWithResult()).thenReturn(failed(invalid("42", CoreErrorCode.CHECKSUM_MISMATCH)));

        assertThatThrownBy(() -> stagingStrategy().migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42").hasMessageContaining("staging");

        verify(flyway, never()).repair();
        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("out-of-order/ignored migration reported as not-applied: not treated as pending, validating migrate() fails fast")
    void should_useValidatingMigrate_when_ignoredMigrationReported() {
        when(tolerant.validateWithResult())
                .thenReturn(failed(invalid("5", CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED)));

        prodStrategy().migrate(flyway);

        verify(flyway, times(1)).migrate();
        verify(nonValidating, never()).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    @DisplayName("non-drift, non-pending failure (applied migration missing): no repair, migrate() re-validates and fails fast")
    void should_notRepair_when_failureIsNotChecksumDrift() {
        when(tolerant.validateWithResult())
                .thenReturn(failed(invalid("7", CoreErrorCode.APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED)));

        prodStrategy().migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway, times(1)).migrate();
    }

    @Test
    @DisplayName("happy path validates exactly once: migrate() runs on the non-validating Flyway, never on the validating one")
    void should_validateOnce_when_validationSucceeds() {
        when(tolerant.validateWithResult()).thenReturn(ok());

        prodStrategy().migrate(flyway);

        verify(tolerant, times(1)).validateWithResult();
        verify(flyway, never()).validateWithResult();
        verify(flyway, never()).migrate();
        verify(nonValidating, times(1)).migrate();
    }

    @Test
    @DisplayName("stamp belongs to another environment: fixed-text failure before validate, repair or migrate")
    void should_failBeforeAnyFlywayCall_when_stampMismatch() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "staging");
        FlywayMigrationStrategy strategy = build(env, DeployEnvironment.STAGING, Optional.of("prod"));

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deployment environment stamp mismatch")
                .message().doesNotContain("staging'").doesNotContain("=prod");

        verify(tolerant, never()).validateWithResult();
        verify(flyway, never()).repair();
        verify(flyway, never()).migrate();
        verify(nonValidating, never()).migrate();
    }

    @Test
    @DisplayName("unstamped database without BEAUTICA_DB_STAMP_INIT: fails before any Flyway call")
    void should_failBeforeAnyFlywayCall_when_unstampedAndNoInit() {
        MockEnvironment env = new MockEnvironment();
        FlywayMigrationStrategy strategy = build(env, DeployEnvironment.PROD, Optional.empty());

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BEAUTICA_DB_STAMP_INIT");

        verify(flyway, never()).validateWithResult();
        verify(nonValidating, never()).migrate();
    }

    @Test
    @DisplayName("unstamped database with a matching BEAUTICA_DB_STAMP_INIT: proceeds to migrate")
    void should_migrate_when_unstampedAndInitMatches() {
        MockEnvironment env = new MockEnvironment().withProperty("BEAUTICA_DB_STAMP_INIT", "prod");
        when(tolerant.validateWithResult()).thenReturn(ok());

        build(env, DeployEnvironment.PROD, Optional.empty()).migrate(flyway);

        verify(nonValidating, times(1)).migrate();
    }

    @Test
    @DisplayName("unstamped database with BEAUTICA_DB_STAMP_INIT for the other environment: fails")
    void should_fail_when_unstampedAndInitIsOtherEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("BEAUTICA_DB_STAMP_INIT", "staging");

        assertThatThrownBy(() -> build(env, DeployEnvironment.PROD, Optional.empty()).migrate(flyway))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BEAUTICA_DB_STAMP_INIT");

        verify(tolerant, never()).validateWithResult();
    }

    @Test
    @DisplayName("is scoped to the prod profile only")
    void should_beScopedToProdProfile() {
        Profile profile = FlywayRepairConfig.class.getAnnotation(Profile.class);

        assertThat(profile).as("@Profile must be present").isNotNull();
        assertThat(profile.value()).containsExactly("prod");
    }

    @Test
    @DisplayName("self-heal is NOT env-flag-gated: no @Value-bound toggle field exists")
    void should_haveNoEnvFlagGate_onAnyField() {
        // Gating repair() behind an unset Railway variable previously left prod in a crash-loop on the
        // very deploy that needed it. The staging/prod split is decided by the active profile, not a flag.
        Field[] fields = FlywayRepairConfig.class.getDeclaredFields();

        boolean hasValueAnnotatedField = Arrays.stream(fields)
                .anyMatch(f -> f.isAnnotationPresent(Value.class));
        assertThat(hasValueAnnotatedField)
                .as("FlywayRepairConfig must not carry any @Value-bound gating field, found: %s", Arrays.toString(fields))
                .isFalse();

        boolean hasBooleanToggleField = Arrays.stream(fields)
                .anyMatch(f -> f.getType() == boolean.class || f.getType() == Boolean.class);
        assertThat(hasBooleanToggleField)
                .as("FlywayRepairConfig must not carry a boolean toggle field, found: %s", Arrays.toString(fields))
                .isFalse();
    }
}
