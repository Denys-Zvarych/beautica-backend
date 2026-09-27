package com.beautica;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The JVM-wide singleton PostgreSQL container shared by the {@code @DataJpaTest} slice family.
 *
 * <p>Extracted from {@link AbstractDataJpaTest} for the per-class Testcontainers finding
 * (backend-QA 2026-09-20) for the one
 * reason an extraction here is worth anything: a test can now reach the container <b>without
 * inheriting a Spring context</b>. {@code SalonSearchTodayResolutionIT} used only
 * {@code POSTGRES.getJdbcUrl()} — it opens its own unpooled {@link java.sql.DriverManager}
 * connections on purpose, so that {@code SET TIME ZONE} can never touch a pooled one — yet extending
 * {@link AbstractDataJpaTest} booted a whole {@code @DataJpaTest} slice context (EntityManagerFactory,
 * every repository bean, a transaction manager) that it then never touched.
 *
 * <p><b>This is a holder, not a fork.</b> {@link AbstractDataJpaTest#POSTGRES} is now a reference to
 * {@link #INSTANCE}, not a second declaration, so there is exactly ONE container and all thirty-odd
 * existing subclasses keep reading it through the inherited field with no change. Duplicating the
 * declaration would start a second Postgres and silently double every CI run's container cost, which
 * is precisely the outcome this class is shaped to make impossible.
 *
 * <p>Started exactly once per JVM by the static initialiser and never stopped; JVM exit handles
 * cleanup through Testcontainers' Ryuk reaper.
 *
 * <p><b>Not shared with {@link AbstractIntegrationTest}'s container, deliberately.</b>
 * {@code @DataJpaTest} (slice) and {@code @SpringBootTest} (full context) cannot share a Spring
 * application context, so the two families keep separate holders — see {@link AbstractDataJpaTest}'s
 * own javadoc on cross-slice sharing. Within each family the singleton is shared.
 */
public final class DataJpaPostgresContainer {

    @SuppressWarnings("resource") // Singleton — never closed; JVM exit handles cleanup via Ryuk.
    public static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        INSTANCE.start();
    }

    private DataJpaPostgresContainer() {
    }
}
