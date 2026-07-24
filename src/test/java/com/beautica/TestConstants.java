package com.beautica;

/**
 * Shared constants for the test suite.
 *
 * <p>Centralises values that appear in multiple test classes so that a single
 * change here propagates everywhere and divergence is caught at compile time.
 *
 * <h2>JWT secret</h2>
 * {@link #TEST_JWT_SECRET} is the HS-256 signing secret used in unit tests that
 * construct a {@code JwtTokenProvider} directly (e.g. {@code AuthServiceTest}).
 * It is intentionally long enough to satisfy the HS-256 minimum key-length
 * requirement (256 bits / 32 bytes) while remaining obviously non-production.
 *
 * <p>Integration tests that load the full Spring context derive the secret from
 * {@code application-test.yml} via {@code app.jwt.secret}. The two values are
 * kept separate: integration tests rely on the property file, unit tests use
 * this constant. If you need them to match, update both this constant and
 * {@code app.jwt.secret} in {@code src/test/resources/application-test.yml}.
 */
public final class TestConstants {

    private TestConstants() {
        // utility class — do not instantiate
    }

    /**
     * HS-256 signing secret for unit tests that wire a {@code JwtTokenProvider}
     * without loading the Spring context.
     *
     * <p>This is the single source of truth for the inline JWT secret used in
     * {@code AuthServiceTest}. Any test that previously hard-coded this value
     * directly should reference this constant instead.
     */
    public static final String TEST_JWT_SECRET =
            "test-secret-that-is-long-enough-for-hs256-ok-padding-here";

    /**
     * BCrypt hash of "test-password" at cost factor 4.
     *
     * <p>Cost 4 is the minimum supported by BCrypt and is significantly faster than the
     * production cost of 10, keeping test suites quick while still exercising the correct
     * hash format. Use this instead of structurally invalid strings like
     * {@code "$2a$10$hashedpassword"} or {@code "x"}.
     *
     * <p>To regenerate: {@code new BCryptPasswordEncoder(4).encode("test-password")}
     */
    public static final String HASHED_TEST_PASSWORD =
            "$2b$04$hdRHhf50.zg190tE2wHX1OAdI5qUSG/zroaKUKvRr3IVLpexsXAR6";
}
