package com.beautica;

import com.beautica.config.TestAsyncConfig;
import com.beautica.notification.EmailService;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.support.SlowTestExtension;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
@ExtendWith(SlowTestExtension.class)
@Import(TestAsyncConfig.class)
public abstract class AbstractIntegrationTest {

    @MockBean
    protected EmailNotificationService emailNotificationService;

    @MockBean
    protected EmailService emailService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TestRestTemplate baseRestTemplate;

    @AfterEach
    void cleanDb() {
        jdbcTemplate.execute("DELETE FROM notification_outbox");
        jdbcTemplate.execute("DELETE FROM reviews");
        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM media_files");
        jdbcTemplate.execute("DELETE FROM master_services");
        jdbcTemplate.execute("DELETE FROM service_definitions");
        jdbcTemplate.execute("DELETE FROM working_hours");
        jdbcTemplate.execute("DELETE FROM schedule_exceptions");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        // Phase 11.1: password_reset_tokens has FK to users (ON DELETE CASCADE) but we
        // delete it explicitly before users to stay consistent with FK ordering and make
        // the cleanup intent clear. Must precede the users DELETE.
        jdbcTemplate.execute("DELETE FROM password_reset_tokens");
        jdbcTemplate.execute("DELETE FROM device_tokens");
        jdbcTemplate.execute("DELETE FROM users");

        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });

        // Reset to a fresh Apache HttpClient after every test so context-sharing
        // classes never inherit a stale/closed connection pool from a previous test.
        // Finite response timeout (10 s) + zero retries: a rate-limit 429 that resets
        // the socket will fail fast instead of hanging the suite for 27 minutes.
        var httpClient = HttpClients.custom()
                .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS))
                .build();
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectionRequestTimeout(10_000);
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        baseRestTemplate.getRestTemplate().setRequestFactory(factory);
    }

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
