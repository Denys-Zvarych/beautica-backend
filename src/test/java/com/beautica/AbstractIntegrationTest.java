package com.beautica;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton-container base class for all full-context integration tests.
 *
 * The PostgreSQL container is started ONCE per JVM in a static initialiser and
 * never stopped between test classes. Spring's test-context cache can therefore
 * reuse the same application context across classes that share the same
 * configuration — the datasource URL is stable for the entire test run.
 *
 * @DynamicPropertySource overrides the placeholder datasource values in
 * application-test.yml with the live Testcontainers port at context-load time,
 * regardless of how many Spring contexts are created (e.g. due to @MockBean).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
