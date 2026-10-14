package com.beautica.support;

/**
 * Shared low-entropy test fixtures that satisfy production guards (e.g. the 32-char minimum for
 * {@code INTERNAL_API_KEY}) without tripping the secret scanner's entropy heuristic.
 *
 * <p>A compile-time constant so it is usable in annotation attributes ({@code @TestPropertySource},
 * {@code @SpringBootTest(properties = ...)}). Never a real credential.
 */
public final class TestSecrets {

    /** 42 chars, entropy well below the scanner threshold. */
    public static final String INTERNAL_API_KEY = "test-internal-api-key-aaaaaaaaaaaaaaaaaaaa";

    private TestSecrets() {
    }
}
