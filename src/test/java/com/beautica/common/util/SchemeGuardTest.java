package com.beautica.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SchemeGuard.isAllowedScheme")
class SchemeGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://app.beautica.ua",
        "https://example.com/path?query=1",
        "http://localhost",
        "http://localhost/",
        "http://localhost/invite/accept?token=abc",
        "http://localhost:3000",
        "http://localhost:3000/path"
    })
    @DisplayName("Accepts https:// and http://localhost (exact, slash, or port boundary)")
    void should_returnTrue_when_urlIsHttpsOrLocalhost(String url) {
        assertThat(SchemeGuard.isAllowedScheme(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost.attacker.com",
        "http://localhost.attacker.com/path",
        "http://localhostXYZ",
        "http://localhost-evil.com",
        "http://example.com",
        "javascript:void(0)",
        "data:text/html,<script>alert(1)</script>",
        "file:///etc/passwd",
        "ftp://example.com",
        "//host/path",
        "HTTP://LOCALHOST",
        "HTTPS://app.beautica.ua",
        ""
    })
    @DisplayName("Rejects prefix-spoofs, alternative schemes, scheme-relative, uppercase, empty")
    void should_returnFalse_when_urlIsAnyDisallowedShape(String url) {
        assertThat(SchemeGuard.isAllowedScheme(url)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("Returns false for null input (null-safe)")
    void should_returnFalse_when_urlIsNull(String url) {
        assertThat(SchemeGuard.isAllowedScheme(url)).isFalse();
    }
}
