package com.beautica.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link FlywayRepairConfig}. Pure JUnit 5 + Mockito — no Spring
 * context: the bean is a plain {@link FlywayMigrationStrategy} lambda over
 * {@link Flyway}, so the strategy is exercised directly by instantiating
 * {@code new FlywayRepairConfig()} (package-private, same package as this test)
 * and calling the no-arg {@code flywayMigrationStrategy()}.
 *
 * <p>The strategy is intentionally <em>not</em> env-flag-gated (the old
 * {@code @Value}-bound {@code repair-on-migrate} boolean is gone). Its contract
 * is now purely reactive: {@code validate()} first; on
 * {@link FlywayValidateException} run a one-shot {@code repair()} + re-validate;
 * always {@code migrate()} at the end. The two behavioural cases below pin the
 * healthy fast path (== Spring Boot default, no repair) and the self-healing
 * checksum-drift path (validate → repair → validate → migrate).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlywayRepairConfig — migration strategy unit")
class FlywayRepairConfigTest {

    @Mock
    private Flyway flyway;

    private FlywayMigrationStrategy strategy() {
        return new FlywayRepairConfig().flywayMigrationStrategy();
    }

    private static FlywayValidateException validateException() {
        return new FlywayValidateException(
                new ErrorDetails(null, "Migration checksum mismatch for migration version 42"),
                "validate failed");
    }

    @Test
    @DisplayName("healthy DB: validate() succeeds → migrate() once, repair() never")
    void should_skipRepair_when_validateSucceeds() {
        // validate() does not throw (default Mockito void no-op)

        strategy().migrate(flyway);

        InOrder ordered = inOrder(flyway);
        ordered.verify(flyway).validate();
        ordered.verify(flyway).migrate();
        verify(flyway, times(1)).validate();
        verify(flyway, times(1)).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    @DisplayName("checksum drift: validate() throws once → repair(), re-validate, then migrate()")
    void should_repairAndRevalidate_when_firstValidateThrows() {
        doThrow(validateException())
                .doNothing()
                .when(flyway).validate();

        strategy().migrate(flyway);

        InOrder ordered = inOrder(flyway);
        ordered.verify(flyway).validate();
        ordered.verify(flyway).repair();
        ordered.verify(flyway).validate();
        ordered.verify(flyway).migrate();
        verify(flyway, times(2)).validate();
        verify(flyway, times(1)).repair();
        verify(flyway, times(1)).migrate();
    }

    @Test
    @DisplayName("is scoped to the prod profile only")
    void should_beScopedToProdProfile() {
        Profile profile = FlywayRepairConfig.class.getAnnotation(Profile.class);

        assertThat(profile).as("@Profile must be present").isNotNull();
        assertThat(profile.value()).containsExactly("prod");
    }
}
