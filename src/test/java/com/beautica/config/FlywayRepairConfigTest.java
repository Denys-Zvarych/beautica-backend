package com.beautica.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link FlywayRepairConfig}. Pure JUnit 5 + Mockito — no Spring
 * context: the bean is a plain {@link FlywayMigrationStrategy} lambda over
 * {@link Flyway}, so the strategy is exercised directly by instantiating
 * {@code new FlywayRepairConfig()} (package-private, same package as this test)
 * and calling {@code flywayMigrationStrategy(boolean)}.
 *
 * <p><strong>Default-contract approach (test 3):</strong> the production default
 * is declared by {@code @Value("${beautica.flyway.repair-on-migrate:false}")} —
 * an absent property is bound by Spring to {@code false}, which selects the
 * migrate-only path. Driving an {@link org.springframework.boot.test.context.runner.ApplicationContextRunner}
 * under the {@code prod} profile would be awkward here: it would still be unable
 * to feed a {@code Flyway} mock into the strategy, so the property-binding step
 * could not be observed end-to-end anyway. Instead this test exercises the
 * resolved default by invoking the factory with the documented default literal
 * ({@code false}) and asserting the migrate-only / never-repair contract — the
 * same "assert the documented default by exercising the factory's default path"
 * convention {@code S3ConfigTest} documents for its activation policy.
 *
 * <p>Log assertions use a Logback {@link ListAppender} attached to the
 * {@link FlywayRepairConfig} logger — the established log-capture precedent in
 * this repo ({@code R2StorageServiceTest}, {@code ReviewEventListenerTest});
 * no {@code OutputCaptureExtension} usage exists in the codebase.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlywayRepairConfig — migration strategy unit")
class FlywayRepairConfigTest {

    @Mock
    private Flyway flyway;

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachListAppender() {
        Logger configLogger = (Logger) LoggerFactory.getLogger(FlywayRepairConfig.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        configLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        Logger configLogger = (Logger) LoggerFactory.getLogger(FlywayRepairConfig.class);
        configLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @DisplayName("calls migrate() only when the repair flag is false")
    void should_callMigrateOnly_when_repairFlagFalse() {
        FlywayMigrationStrategy strategy = new FlywayRepairConfig().flywayMigrationStrategy(false);

        strategy.migrate(flyway);

        verify(flyway).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    @DisplayName("calls repair() strictly before migrate() when the repair flag is true")
    void should_callRepairThenMigrate_when_repairFlagTrue() {
        FlywayMigrationStrategy strategy = new FlywayRepairConfig().flywayMigrationStrategy(true);

        strategy.migrate(flyway);

        InOrder ordered = inOrder(flyway);
        ordered.verify(flyway).repair();
        ordered.verify(flyway).migrate();
    }

    @Test
    @DisplayName("defaults to migrate-only (never repair) per the @Value(:false) default contract")
    void should_defaultToMigrateOnly_when_flagAbsent() {
        // The @Value("${beautica.flyway.repair-on-migrate:false}") default binds
        // an absent property to false; exercise that resolved default literal.
        FlywayMigrationStrategy strategy = new FlywayRepairConfig().flywayMigrationStrategy(false);

        strategy.migrate(flyway);

        verify(flyway).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    @DisplayName("emits operator-visible WARN logs on the repair path and none on the migrate-only path")
    void should_logWarn_when_repairFlagTrue() {
        new FlywayRepairConfig().flywayMigrationStrategy(true).migrate(flyway);

        assertThat(listAppender.list)
                .as("repair path must emit operator-visible WARN logs")
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .hasSize(2)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("FLYWAY REPAIR ENABLED"))
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("FLYWAY REPAIR COMPLETE"));

        listAppender.list.clear();

        new FlywayRepairConfig().flywayMigrationStrategy(false).migrate(flyway);

        assertThat(listAppender.list)
                .as("migrate-only path must stay silent — no WARN noise on normal deploys")
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .isEmpty();
    }
}
