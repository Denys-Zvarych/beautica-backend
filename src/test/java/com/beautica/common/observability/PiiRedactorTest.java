package com.beautica.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiRedactor — every pattern shape (Phase 371 D4, audit-fix cycle 1)")
class PiiRedactorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "+380501112233", "380501112233", "0501112233",
            "+380 50 111 22 33", "+38 (050) 111 22 33", "+38 (050) 111-22-33", "+38-050-111-22-33",
            "050 111 22 33", "(050) 111-22-33", "050-111-22-33", "(050)1112233",
            "1234567890123", "+48 512 345 678", "+1-202-555-0143", "+44 20 7946 0958"})
    @DisplayName("phone shapes — whole number replaced by [phone], surrounding text kept")
    void should_redactPhone_when_anyUkrainianShape(String phone) {
        String redacted = PiiRedactor.redact("call " + phone + " now");

        assertThat(redacted).isEqualTo("call " + PiiRedactor.PHONE + " now");
    }

    @ParameterizedTest
    @ValueSource(strings = {"year 2025", "price 1500 UAH", "salon 12", "duration 90 min"})
    @DisplayName("short numbers that are not phones or OTP-adjacent — kept (no over-reach on prices/years)")
    void should_keepShortNumbers_when_notPhoneShaped(String text) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "canary@x.ua|[email]",
            "first.last+tag@mail.example.com|[email]",
            "тест@приклад.укр|[email]",
            "ольга.коваль@gmail.com|[email]",
            "canary%40x.ua|[email]",
            "user%40Mail.Example.com|[email]"})
    @DisplayName("email shapes (ASCII, Cyrillic/IDN, %40-encoded) — replaced by [email]")
    void should_redactEmail_when_anyShape(String email, String expected) {
        assertThat(PiiRedactor.redact("to " + email + " ok")).isEqualTo("to " + expected + " ok");
    }

    @ParameterizedTest
    @ValueSource(strings = {"code 482913", "OTP: 4829", "Ваш код 482913", "КОД:48291", "482913 is your code",
            "otp=123456"})
    @DisplayName("OTP near otp/code/код (either side, any case) — redacted to [otp]")
    void should_redactOtp_when_digitsNearKeyword(String text) {
        String redacted = PiiRedactor.redact(text);

        assertThat(redacted).contains(PiiRedactor.OTP).doesNotContainPattern("\\d{4,6}");
    }

    @Test
    @DisplayName("JWT — replaced by [jwt]")
    void should_redactJwt_when_present() {
        // JWT-shaped fixture split at the dots so secret scanners (gitleaks jwt) do not flag the source; runtime value unchanged.
        String jwt = "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiJ4In0" + "." + "c2ln-_bmF0dXJl";

        assertThat(PiiRedactor.redact("t=" + jwt + ";")).isEqualTo("t=" + PiiRedactor.JWT + ";");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer abc", "bearer abc.def", "BEARER x-y_z"})
    @DisplayName("Bearer credential — replaced by 'Bearer [redacted]'")
    void should_redactBearer_when_present(String header) {
        assertThat(PiiRedactor.redact("Authorization: " + header)).isEqualTo("Authorization: " + PiiRedactor.BEARER);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/api/v1/book/cancel/3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19|/api/v1/book/cancel/[token]",
            "/review/Zx9_kLmN0pQrStUvWxYz12345|/review/[token]",
            "https://api.beautica.ua/review/Zx9_kLmN0pQrStUvWxYz12345?x=1|https://api.beautica.ua/review/[token]",
            "GET /api/v1/book/cancel/3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19|GET /api/v1/book/cancel/[token]",
            "GET /api/v1/book/cancel/{token}|GET /api/v1/book/cancel/{token}",
            "/api/v1/salons/my/services|/api/v1/salons/my/services"})
    @DisplayName("redactPath — UUID / opaque ≥20-char segments masked; templates and plain paths kept")
    void should_maskTokenSegments_when_redactingPath(String url, String expected) {
        assertThat(PiiRedactor.redactPath(url)).isEqualTo(expected);
    }

    @Test
    @DisplayName("redactPath also strips query/fragment and redacts PII in plain segments")
    void should_redactPiiInPath_when_segmentIsAPhone() {
        assertThat(PiiRedactor.redactPath("/api/v1/users/+380501112233#x")).isEqualTo("/api/v1/users/[phone]");
    }

    @Test
    @DisplayName("input over 4 KB — truncated with a marker, cut never inside a word")
    void should_truncate_when_inputExceedsCap() {
        String redacted = PiiRedactor.redact("word ".repeat(1_000));

        assertThat(redacted).endsWith(PiiRedactor.TRUNCATED);
        String body = redacted.substring(0, redacted.length() - PiiRedactor.TRUNCATED.length());
        assertThat(body.length()).isLessThanOrEqualTo(PiiRedactor.MAX_LENGTH);
        assertThat(body).endsWith(" ").doesNotEndWith(" w");
    }

    /** Places {@code secret} so it starts a few characters before the 4 KB cut and straddles it. */
    private static String straddlingCut(String secret) {
        String filler = "ab ".repeat(PiiRedactor.MAX_LENGTH / 3);
        return filler.substring(0, PiiRedactor.MAX_LENGTH - 5) + " " + secret + " tail " + "z ".repeat(400);
    }

    @Test
    @DisplayName("e-mail straddling the 4 KB cut — no partial local part survives")
    void should_notLeakPartialEmail_when_truncatedMidAddress() {
        String redacted = PiiRedactor.redact(straddlingCut("olena.kovalenko.canary@example.ua"));

        assertThat(redacted).doesNotContain("olena").doesNotContain("kovalenko").doesNotContain("canary")
                .endsWith(PiiRedactor.TRUNCATED);
    }

    @Test
    @DisplayName("JWT straddling the 4 KB cut — no partial token survives")
    void should_notLeakPartialJwt_when_truncatedMidToken() {
        String redacted = PiiRedactor.redact(straddlingCut(
                // JWT-shaped fixture split at the dots so secret scanners (gitleaks jwt) do not flag the source; runtime value unchanged.
                "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiJjYW5hcnkifQ" + "." + "c2lnbmF0dXJlLWNhbmFyeS0zNzE"));

        assertThat(redacted).doesNotContain("eyJ").doesNotContain("eyJhbG");
    }

    @Test
    @DisplayName("phone straddling the 4 KB cut — no partial number survives")
    void should_notLeakPartialPhone_when_truncatedMidNumber() {
        String redacted = PiiRedactor.redact(straddlingCut("+380501112233"));

        assertThat(redacted).doesNotContain("+38").doesNotContain("3805");
    }

    @Test
    @DisplayName("path token straddling the 4 KB cut — no partial token survives")
    void should_notLeakPartialPathToken_when_truncatedMidSegment() {
        String path = "/" + "seg/".repeat((PiiRedactor.MAX_LENGTH - 6) / 4) + "3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19/x";

        String redacted = PiiRedactor.redactPath(path);

        assertThat(redacted).doesNotContain("3f2b").doesNotContain("8c1e");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/api/v1/book/cancel/3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19;jsessionid=x|/api/v1/book/cancel/[token]",
            "/api/v1/book/cancel/3f2b8c1e%2D9a7d%2D4e21%2D8b6f%2D0c5d4e3a2b19|/api/v1/book/cancel/[token]",
            "/review/prefix-Zx9_kLmN0pQrStUvWxYz12345|/review/[token]",
            "/api/v1/book/cancel/3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19.json|/api/v1/book/cancel/[token].json",
            "/review/Zx9_kLmN0pQrStUvWxYz12345.json|/review/[token].json",
            "/review/v.Zx9_kLmN0pQrStUvWxYz12345|/review/v.[token]"})
    @DisplayName("token with matrix param / percent-encoding / prefix / suffix — still masked")
    void should_maskToken_when_segmentHasMatrixParam(String url, String expected) {
        assertThat(PiiRedactor.redactPath(url)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /api/v1/auth/verify-password-reset-otp", "/api/v1/salons/my/service-type-suggestions",
            "GET /api/v1/internal/diagnostics/sentry-smoke"})
    @DisplayName("long static route words (no digit) — never masked")
    void should_keepStaticRouteSegment_when_longHyphenatedWord(String route) {
        assertThat(PiiRedactor.redactPath(route)).isEqualTo(route);
    }

    @Test
    @DisplayName("?token= / &key= / ;sig= in free text — value replaced, parameter name kept")
    void should_redactQueryToken_when_inExceptionValue() {
        String value = "I/O error on GET https://api.example.ua/v1/send?token=abc123&key=K9x&lang=uk;sig=zz9 failed";

        assertThat(PiiRedactor.redact(value)).isEqualTo(
                "I/O error on GET https://api.example.ua/v1/send?token=[token]&key=[token]&lang=uk;sig=[token] failed");
    }

    @Test
    @DisplayName("FCM-like device token and other ≥32-char letter+digit runs — masked; long plain words kept")
    void should_redactOpaqueToken_when_fcmLikeTokenInFreeText() {
        String fcm = "dQw4w9WgXcQ:APA91bHPRgkF3JUikC4ENAHEeMrd41Zxv3hVZjC9KtT8OvPVGJ-hQMRKRrZuJAEcl7B338qju59zJMjw2DEL";
        String opaque = "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5bC7d";

        assertThat(PiiRedactor.redact("push to " + fcm + " failed")).isEqualTo("push to [token] failed");
        assertThat(PiiRedactor.redact("key " + opaque)).isEqualTo("key [token]");
        assertThat(PiiRedactor.redact("NotificationOutboxDrainWorkerPersistenceFailure"))
                .isEqualTo("NotificationOutboxDrainWorkerPersistenceFailure");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("100 KB adversarial input (long e-mail-ish / JWT-ish / digit runs) — redacts in bounded time")
    void should_redactQuickly_when_inputIsHugeAndAdversarial() {
        String adversarial = "a".repeat(30_000) + "@" + "b.".repeat(15_000) + " eyJ" + "c".repeat(20_000)
                + " " + "1 ".repeat(10_000);

        for (int i = 0; i < 50; i++) {
            PiiRedactor.redact(adversarial);
            PiiRedactor.redactPath("/x/" + adversarial);
        }

        assertThat(adversarial.length()).isGreaterThan(100_000);
    }

    @Test
    @DisplayName("sensitive keys — matched case-insensitively; harmless keys pass")
    void should_matchSensitiveKeys() {
        assertThat(PiiRedactor.isSensitiveKey("clientComment")).isTrue();
        assertThat(PiiRedactor.isSensitiveKey("PASSPORT_allergies")).isTrue();
        assertThat(PiiRedactor.isSensitiveKey("apiKey")).isTrue();
        assertThat(PiiRedactor.isSensitiveKey("thread_name")).isFalse();
        assertThat(PiiRedactor.isSensitiveKey("request_id")).isFalse();
        assertThat(PiiRedactor.isSensitiveKey(null)).isFalse();
    }

    @Test
    @DisplayName("null and empty — returned as is")
    void should_passThrough_when_nullOrEmpty() {
        assertThat(PiiRedactor.redact(null)).isNull();
        assertThat(PiiRedactor.redact("")).isEmpty();
        assertThat(PiiRedactor.redactPath(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"access_token", "api_key", "refresh_token", "password", "client_secret", "x-api-key"})
    @DisplayName("prefixed secret query params (access_token, api_key, refresh_token, password...) — value masked")
    void should_redact_when_prefixedSecretQueryParam(String name) {
        String redacted = PiiRedactor.redact("GET https://h.example/p?a=1&" + name + "=hunter2&b=2 failed");

        assertThat(redacted).doesNotContain("hunter2")
                .contains("&" + name + "=" + PiiRedactor.TOKEN + "&b=2");
    }

    @Test
    @DisplayName("43-char base64url path token with no digit — still masked")
    void should_maskToken_when_pathTokenHasNoDigit() {
        String token = "AbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIjKlMnOpQ";

        String redacted = PiiRedactor.redactPath("/api/v1/bookings/guest/cancel/" + token);

        assertThat(token).hasSize(43).doesNotContainPattern("\\d");
        assertThat(redacted).isEqualTo("/api/v1/bookings/guest/cancel/" + PiiRedactor.TOKEN);
    }

    @Test
    @DisplayName("a long 'eyJ' run with no dots — completes quickly (JWT pattern anchored, no quadratic backtracking)")
    void should_completeQuickly_when_longEyJRunHasNoDots() {
        String hostile = "eyJ".repeat(2000);

        String redacted = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> PiiRedactor.redact(hostile));

        assertThat(redacted).doesNotContain(PiiRedactor.JWT);
    }

    @Test
    @DisplayName("'eyJ' glued to a preceding word character is not a JWT start")
    void should_notStartJwtMatch_when_eyJFollowsWordCharacter() {
        assertThat(PiiRedactor.redact("xeyJab.cd.ef")).isEqualTo("xeyJab.cd.ef");
        assertThat(PiiRedactor.redact("t=eyJab.cd.ef")).isEqualTo("t=" + PiiRedactor.JWT);
    }
}
