package com.beautica.common;

/**
 * Shared constants for test fixtures.
 *
 * HASHED_TEST_PASSWORD is a real BCrypt hash of "test-password" encoded at cost 4.
 * Cost 4 is the minimum supported by BCrypt and is significantly faster than the
 * production cost of 10, keeping test suites quick while still exercising the
 * correct hash format.
 *
 * To regenerate: new BCryptPasswordEncoder(4).encode("test-password")
 */
public final class TestConstants {

    /**
     * BCrypt hash of "test-password" at cost factor 4.
     * Use this instead of structurally invalid strings like "$2a$10$hashedpassword" or "x".
     * Generated via: new BCryptPasswordEncoder(4).encode("test-password")
     */
    public static final String HASHED_TEST_PASSWORD =
            "$2b$04$hdRHhf50.zg190tE2wHX1OAdI5qUSG/zroaKUKvRr3IVLpexsXAR6";

    private TestConstants() {
    }
}
