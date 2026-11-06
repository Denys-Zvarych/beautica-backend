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
    @ValueSource(strings = {
            "050 111 22 33", "(050) 111-22-33", "050-111-22-33", "050.111.22.33",
            "+38 050 111 22 33", "+380 50 111 22 33", "+38.050.111.22.33", "+380.50.111.22.33",
            "(050) 111.22.33", "050.111-22 33"})
    @DisplayName("separator-formatted Ukrainian phones (space / dash / dot / parens, mixed) — redacted to [phone]")
    void should_redactPhone_when_separatorFormatted(String phone) {
        String redacted = PiiRedactor.redact("call " + phone + " now");

        assertThat(redacted).isEqualTo("call " + PiiRedactor.PHONE + " now");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9.30.1", "v9.30.1", "2026-11-06 10:00", "2026-11-06T10:00:00.123Z",
            "1.050.111.22.33", "050.111.22.33.4", "a050.111.22.33", "build 0.123.456.78"})
    @DisplayName("versions, timestamps, dotted runs glued to a word/version — not taken for a phone")
    void should_keepText_when_versionOrTimestampOrGluedRun(String text) {
        assertThat(PiiRedactor.redact("at " + text + " end")).isEqualTo("at " + text + " end");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0a1b2c3d4e5f60718293a4b5c6d7e8f9", "3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19"})
    @DisplayName("32-hex request id / UUID — never reported as [phone] (masked as [token] by the opaque-token rule)")
    void should_notRedactAsPhone_when_hexIdOrUuid(String id) {
        String redacted = PiiRedactor.redact("request " + id + " failed");

        assertThat(redacted).doesNotContain(PiiRedactor.PHONE).isEqualTo("request " + PiiRedactor.TOKEN + " failed");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "login failed password=hunter2 for x|login failed password=[redacted] for x",
            "payload pin: 1234|payload pin=[redacted]",
            "cfg secret = 's3 cr3t' loaded|cfg secret=[redacted] loaded",
            "body {\"client_secret\":\"abc def\"}|body {\"client_secret=[redacted]}",
            "refreshToken: \"r1 r2\" expired|refreshToken=[redacted] expired",
            "PASSWORD_HASH=abc|PASSWORD_HASH=[redacted]",
            "accessToken=abc|accessToken=[redacted]"})
    @DisplayName("secret key-values in free text (quoted or bare, : or =, any case) — value replaced, key kept")
    void should_redactSecretValue_when_keyValueInFreeText(String text, String expected) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Invalid input: shipping", "spin 3", "no tokens here", "POST /api/v1/auth/refresh"})
    @DisplayName("words merely containing or naming a secret key, without a key=value — kept")
    void should_keepText_when_noSecretKeyValue(String text) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(text);
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/api/v1/public/review-invites/AbCdEfGhIjKlMnOp|/api/v1/public/review-invites/[token]",
            "POST /api/v1/public/review-invites/AbCdEfGhIjKlMnOpQrStUvWx/reviews|POST /api/v1/public/review-invites/[token]/reviews",
            "/api/v1/public/review-invites/abc-def_ghi/opt-out?x=1|/api/v1/public/review-invites/[token]/opt-out",
            "GET /api/v1/public/review-invites/{token}|GET /api/v1/public/review-invites/{token}"})
    @DisplayName("redactPath — the segment after /review-invites/ is always masked, even with no digit; template kept")
    void should_maskReviewInviteToken_when_tokenHasNoDigit(String url, String expected) {
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

    @Test
    @DisplayName("16-char guest cancel token — masked after /c/ and /book/cancel/, a bare 16-char word untouched")
    void should_maskShortCancelToken_inFreeTextAndCPath() {
        String token = "aB3dE5fG7hJ9kL1m";
        String word = "configurationKey";

        String freeText = PiiRedactor.redact("link https://beautica.app/c/" + token + " failed");
        String apiFreeText = PiiRedactor.redact("POST /api/v1/book/cancel/" + token + " -> 404");
        String apiPath = PiiRedactor.redactPath("/api/v1/book/cancel/" + token);
        String cPath = PiiRedactor.redactPath("/c/" + token);
        String telegramPath = PiiRedactor.redactPath("/api/v1/book/telegram/link-token");
        String unrelated = PiiRedactor.redact("missing " + word + " in /api/v1/" + word);
        String unrelatedPath = PiiRedactor.redactPath("/api/v1/" + word);

        assertThat(token).hasSize(16);
        assertThat(word).hasSize(16);
        assertThat(freeText).isEqualTo("link https://beautica.app/c/" + PiiRedactor.TOKEN + " failed");
        assertThat(apiFreeText).isEqualTo("POST /api/v1/book/cancel/" + PiiRedactor.TOKEN + " -> 404");
        assertThat(apiPath).isEqualTo("/api/v1/book/cancel/" + PiiRedactor.TOKEN);
        assertThat(cPath).isEqualTo("/c/" + PiiRedactor.TOKEN);
        assertThat(telegramPath).isEqualTo("/api/v1/book/telegram/link-token");
        assertThat(unrelated).isEqualTo("missing " + word + " in /api/v1/" + word);
        assertThat(unrelatedPath).isEqualTo("/api/v1/" + word);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " ", " ", "‐", "‑", "‒", "–", "—", "―"})
    @DisplayName("phones grouped with Unicode spaces / dashes (NBSP, thin, narrow NBSP, U+2010–U+2015) — redacted")
    void should_redactPhone_when_unicodeSeparator(String sep) {
        String international = "+380" + sep + "50" + sep + "111" + sep + "22" + sep + "33";
        String national = "050" + sep + "111" + sep + "22" + sep + "33";
        String dotted = "050." + "111" + sep + "22" + sep + "33";
        String foreign = "+48" + sep + "512" + sep + "345" + sep + "678";

        assertThat(PiiRedactor.redact("call " + international + " now")).isEqualTo("call [phone] now");
        assertThat(PiiRedactor.redact("call " + national + " now")).isEqualTo("call [phone] now");
        assertThat(PiiRedactor.redact("call " + dotted + " now")).isEqualTo("call [phone] now");
        assertThat(PiiRedactor.redact("call " + foreign + " now")).isEqualTo("call [phone] now");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "refresh-token: abc123|refresh-token=[redacted]",
            "X-Refresh-Token: abc|X-Refresh-Token=[redacted]",
            "hdr x-api-secret=abc end|hdr x-api-secret=[redacted] end",
            "x-password=abc|x-password=[redacted]"})
    @DisplayName("hyphenated / header-shaped secret keys — value replaced, key kept")
    void should_redactSecretValue_when_hyphenatedKey(String text, String expected) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GET /p?pin=1234 failed|GET /p?pin=[token] failed",
            "GET /p?a=1&pin=1234 failed|GET /p?a=1&pin=[token] failed",
            "GET /p?token=abc&pin=1234 failed|GET /p?token=[token]&pin=[token] failed",
            "GET /p?refresh=abc&user_pin=99 failed|GET /p?refresh=[token]&user_pin=[token] failed",
            "GET /p?shipping=fast&mapping=on&spin=3 ok|GET /p?shipping=fast&mapping=on&spin=3 ok"})
    @DisplayName("?pin= / &refresh= query params — value masked; shipping= / mapping= / spin= untouched")
    void should_redactQueryPin_when_exactKey(String text, String expected) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "your PIN 1234 ok|your PIN [redacted] ok",
            "pin 98765|pin [redacted]",
            "spin 1234|spin 1234",
            "pinned 1234|pinned 1234"})
    @DisplayName("spaced 'PIN 1234' — digits redacted; spin / pinned untouched")
    void should_redactSpacedPin_when_pinKeywordThenDigits(String text, String expected) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(expected);
    }

    @Test
    @DisplayName("escaped quote inside a quoted secret value — whole value redacted, no tail leaks")
    void should_redactWholeQuotedValue_when_escapedQuoteInside() {
        assertThat(PiiRedactor.redact("password: \"pa\\\"ss rest\" end")).isEqualTo("password=[redacted] end");
        assertThat(PiiRedactor.redact("secret='a\\'b c' end")).isEqualTo("secret=[redacted] end");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "shipping: x|shipping: x",
            "mapping=y|mapping=y",
            "opinion: z|opinion: z",
            "user_pin: 1|user_pin=[redacted]",
            "pinCode=2|pinCode=[redacted]",
            "pin_code: 3|pin_code=[redacted]"})
    @DisplayName("pin as a key segment only — shipping/mapping/opinion kept, user_pin/pinCode/pin_code redacted")
    void should_redactPinOnlyAsKeySegment(String text, String expected) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(expected);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("100 KB adversarial input for the key/pin/quote/Unicode-separator rules — bounded time")
    void should_redactQuickly_when_adversarialForKeyPinQuoteRules() {
        String adversarial = "x-".repeat(10_000) + "pin" + "\"\\".repeat(10_000) + " pin " + "1".repeat(10_000)
                + " " + "0 ".repeat(10_000) + "?" + "a_".repeat(10_000) + "pin" + " password: '" + "\\'".repeat(10_000);

        String redacted = "";
        for (int i = 0; i < 50; i++) {
            redacted = PiiRedactor.redact(adversarial);
        }

        // Only the 6 KB window is scanned here; per-rule worst cases: should_redactQuickly_when_singleAdversarialFragmentFillsWindow.
        assertThat(redacted).as("truncated to the cap, actual length=%s", redacted.length())
                .endsWith(PiiRedactor.TRUNCATED)
                .hasSizeLessThanOrEqualTo(PiiRedactor.MAX_LENGTH + PiiRedactor.TRUNCATED.length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u2000", "\u2007", "\u200A", "\u200B", "\u205F", "\u3000", "\u2212"})
    @DisplayName("phones grouped with the remaining Unicode spaces / minus sign (en quad, figure, hair, ZWSP, ideographic, U+2212) — redacted")
    void should_redactPhone_when_rareUnicodeSeparator(String sep) {
        String national = "050" + sep + "111" + sep + "22" + sep + "33";
        String international = "+380" + sep + "50" + sep + "111" + sep + "22" + sep + "33";

        assertThat(PiiRedactor.redact("call " + national + " now")).isEqualTo("call [phone] now");
        assertThat(PiiRedactor.redact("call " + international + " now")).isEqualTo("call [phone] now");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "pin 123 ok|pin 123 ok",
            "pin 1234 ok|pin [redacted] ok",
            "PIN\t\t5678|PIN [redacted]"})
    @DisplayName("spaced PIN boundary — 3 digits kept, exactly 4 redacted, tab-separated redacted")
    void should_redactSpacedPin_onlyFromFourDigits(String text, String expected) {
        String input = text.replace("\\t", "\t");

        assertThat(PiiRedactor.redact(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("unterminated quoted secret value — everything to the end redacted, no tail leaks")
    void should_redactToEnd_when_quotedSecretValueUnterminated() {
        assertThat(PiiRedactor.redact("password: \"abc def ghi")).isEqualTo("password=[redacted]");
        assertThat(PiiRedactor.redact("token='abc def")).isEqualTo("token=[redacted]");
    }

    @Test
    @DisplayName("hyphenated query key — later params kept; a hyphenated header key is still redacted")
    void should_keepLaterQueryParams_when_hyphenatedSecretKeyInQuery() {
        String query = PiiRedactor.redact("?a=1&x-refresh-token=SECRET&b=2");
        String header = PiiRedactor.redact("X-Refresh-Token: abc");

        assertThat(query).isEqualTo("?a=1&x-refresh-token=[token]&b=2").doesNotContain("SECRET");
        assertThat(header).isEqualTo("X-Refresh-Token=[redacted]").doesNotContain("abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-", "\"\\", "1", "0 ", "a_", "\\'", "\u00A0"})
    @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("each adversarial fragment under the 4 KB cap (no truncation hides it) — bounded time")
    void should_redactQuickly_when_singleAdversarialFragmentFillsWindow(String unit) {
        String fragment = unit.repeat(1_000 / unit.length());
        String adversarial = "pin " + fragment + " password: '" + fragment + "?a_pin=" + fragment + " 050" + fragment;

        String redacted = "";
        for (int i = 0; i < 50; i++) {
            redacted = PiiRedactor.redact(adversarial);
        }

        assertThat(adversarial.length()).as("fragment must stay inside the pattern window").isLessThanOrEqualTo(
                PiiRedactor.MAX_LENGTH);
        assertThat(redacted).as("the password value is always redacted").contains("password=[redacted]");
    }
}
