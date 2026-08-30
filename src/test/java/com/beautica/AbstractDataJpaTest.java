package com.beautica;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base class for {@code @DataJpaTest} slice tests that need a real PostgreSQL container.
 *
 * <p>Wires a JVM-wide singleton {@link PostgreSQLContainer} declared as a {@code static}
 * field on this class. The container is started exactly once per JVM by the {@code static}
 * initialiser block and never stopped between test classes — JVM exit handles cleanup
 * via Testcontainers' Ryuk reaper.
 *
 * <p>Datasource properties are bound via {@link DynamicPropertySource} rather than
 * Spring Boot's {@code @ServiceConnection} / Testcontainers' {@code @Container}
 * annotations: those two trigger per-subclass container lifecycle re-initialisation
 * even when the underlying static field is shared, which defeats reuse and starts a
 * separate container for every {@code @DataJpaTest} subclass. {@code @DynamicPropertySource}
 * is purely a property hook — it never spawns a new container — so all subclasses
 * resolve to the same JDBC URL on the singleton's published port.
 *
 * <p>Eliminates the per-class container spinup cost (~3–4s) that previously affected
 * {@code NotificationOutboxRepositoryTest} and {@code DeviceTokenRepositoryTest}.
 *
 * <p>Note on cross-slice sharing: {@code @DataJpaTest} (slice context) and
 * {@code @SpringBootTest} (full context, see {@link AbstractIntegrationTest}) cannot
 * share a Spring application context, so they intentionally use separate static
 * container holders. Within each slice family the singleton is shared.
 *
 * <p>Subclasses retain {@code @DataJpaTest} slice semantics: transactional rollback per
 * test, repository beans only, fast bootstrap.
 *
 * <p>Also imports {@link MethodValidationPostProcessor} so subclasses testing
 * {@code @Validated} repositories see real {@link jakarta.validation.ConstraintViolationException}
 * propagation (otherwise {@code @Validated} is silently a no-op in the slice context).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(MethodValidationPostProcessor.class)
public abstract class AbstractDataJpaTest {

    @SuppressWarnings("resource") // Singleton — never closed; JVM exit handles cleanup via Testcontainers Ryuk.
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    // @DataJpaTest autoconfigures a JdbcTemplate bean when spring-jdbc is on the classpath —
    // used only to resolve a real cities.id row for salon fixtures (see #testCityId()).
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Resolves a real, persisted {@code cities.id} row for {@code @DataJpaTest} fixtures that
     * {@code em.persist()} a {@link com.beautica.salon.entity.Salon}.
     *
     * <p>{@code salons.city_id} carries {@code fk_salons_city_id} (V54) to {@code cities(id)} and,
     * as of V150, is {@code NOT NULL} — a literal {@code UUID.randomUUID()} fails the FK check on
     * flush, so every fixture that persists a salon must resolve a seeded row instead of inventing
     * one. Vinnytsia is used everywhere for consistency with
     * {@link com.beautica.service.ServiceTestFixtures#createSalon} and
     * {@link com.beautica.AbstractIntegrationTest#testCityId()} (the {@code @SpringBootTest}-side
     * sibling of this method — the two slice families cannot share a base class, see this class's
     * own javadoc on cross-slice sharing).
     */
    protected UUID testCityId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);
    }
}
