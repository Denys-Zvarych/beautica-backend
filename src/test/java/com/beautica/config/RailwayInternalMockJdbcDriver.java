package com.beautica.config;

/**
 * {@link MockJdbcDriver} that answers the normalised Railway private-network URL
 * ({@code jdbc:postgresql://x.railway.internal/...}) instead of the {@code jdbc:mock-flyway:} scheme.
 *
 * <p>Lets {@link FlywayDataSourceConfigTest} run the REAL {@link DatabaseUrlNormalizerPostProcessor} output
 * through both pools (the eager {@code flyway-pool} opens a connection in its constructor) with zero network
 * I/O. Deliberately NOT registered with {@link java.sql.DriverManager}: HikariCP instantiates it from the
 * explicit {@code driver-class-name}, so it can never shadow the real pgjdbc driver for Testcontainers URLs
 * elsewhere in the same JVM.
 */
public class RailwayInternalMockJdbcDriver extends MockJdbcDriver {

    static final String URL_PREFIX = "jdbc:postgresql://x.railway.internal/";

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }
}
