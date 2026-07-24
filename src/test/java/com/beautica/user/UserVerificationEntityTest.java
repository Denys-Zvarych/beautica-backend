package com.beautica.user;

import com.beautica.auth.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1.2 — unit tests for verification fields on {@link User} entity
 * and {@link UserProfileResponse} DTO.
 *
 * No Spring context, no Testcontainers — pure unit tests.
 */
@DisplayName("User — verification fields (phase 1.2)")
class UserVerificationEntityTest {

    // ── 1. Field defaults ─────────────────────────────────────────────────────

    @Test
    @DisplayName("emailVerified defaults to false and verificationAttempts defaults to 0 after construction")
    void should_defaultEmailVerifiedToFalse_when_userConstructed() {
        var user = new User("e@example.com", "hash", Role.CLIENT, "A", "B", "123");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getVerificationAttempts()).isZero();
    }

    // ── 2. @JsonIgnore prevents serialisation of sensitive verification fields ─

    @Test
    @DisplayName("verificationCodeHash and verificationCodeExpiresAt are not serialised to JSON")
    void should_notSerializeVerificationFields_when_userSerializedToJson() throws Exception {
        var user = new User("e@example.com", "hash", Role.CLIENT, "A", "B", "123");
        user.setVerificationCodeHash("a".repeat(64));
        user.setVerificationCodeExpiresAt(Instant.now());
        user.setVerificationAttempts((short) 1);

        var json = new ObjectMapper().writeValueAsString(user);

        assertThat(json).doesNotContain("verificationCodeHash");
        assertThat(json).doesNotContain("verificationCodeExpiresAt");
        assertThat(json).doesNotContain("verificationAttempts");
    }

    // ── 3. UserProfileResponse.from() includes emailVerified ─────────────────

    @Test
    @DisplayName("emailVerified=true on User is propagated into UserProfileResponse")
    void should_includeEmailVerified_when_userProfileResponseBuilt() {
        var user = new User("e@example.com", "hash", Role.CLIENT, "A", "B", "123");
        user.setEmailVerified(true);

        var response = UserProfileResponse.from(user);

        assertThat(response.emailVerified()).isTrue();
    }

    // ── 4. OTP fields never leak into UserProfileResponse JSON ───────────────

    @Test
    @DisplayName("verificationCodeHash and verificationCodeExpiresAt do not appear in UserProfileResponse JSON")
    void should_notIncludeOtpFieldsInProfileResponse_when_responseBuilt() throws Exception {
        var user = new User("e@example.com", "hash", Role.CLIENT, "A", "B", "123");
        user.setVerificationCodeHash("a".repeat(64));
        user.setVerificationCodeExpiresAt(Instant.now());

        var json = new ObjectMapper().writeValueAsString(UserProfileResponse.from(user));

        assertThat(json).doesNotContain("verificationCodeHash");
        assertThat(json).doesNotContain("verificationCodeExpiresAt");
    }
}
