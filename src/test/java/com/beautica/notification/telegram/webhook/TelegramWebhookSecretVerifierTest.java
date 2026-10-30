package com.beautica.notification.telegram.webhook;

import com.beautica.config.TelegramMode;
import com.beautica.config.TelegramProperties;
import com.beautica.notification.telegram.webhook.TelegramWebhookSecretVerifier.Outcome;
import com.beautica.support.MutableTestClock;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TelegramWebhookSecretVerifier — secret_token check + reject throttle (phase 406 D2/D4)")
class TelegramWebhookSecretVerifierTest {

    private static final String SECRET = "s3cr3t-webhook-value_" + "x".repeat(24);
    private static final int CAPACITY = 3;

    private static TelegramWebhookSecretVerifier verifier(String configuredSecret) {
        return verifier(configuredSecret, new MutableTestClock(Instant.parse("2026-10-30T10:00:00Z")));
    }

    private static TelegramWebhookSecretVerifier verifier(String configuredSecret, MutableTestClock clock) {
        TelegramProperties properties = new TelegramProperties(false, TelegramMode.CAPTURE, "", "",
                configuredSecret, "https://api.telegram.org", Duration.ofMinutes(30), List.of());
        LoadingCache<String, Bucket> buckets = Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(BandwidthBuilder.builder().capacity(CAPACITY)
                        .refillIntervally(CAPACITY, Duration.ofMinutes(1)).build())
                .build());
        return new TelegramWebhookSecretVerifier(properties, buckets, clock);
    }

    private static MockHttpServletRequest request(String secretHeader, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/telegram/webhook");
        if (secretHeader != null) {
            request.addHeader(TelegramWebhookSecretVerifier.SECRET_HEADER, secretHeader);
        }
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    @DisplayName("accept when header equals secret")
    void should_accept_when_headerEqualsSecret() {
        Outcome outcome = verifier(SECRET).verify(request(SECRET, "10.0.0.1"));

        assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    }

    @Test
    @DisplayName("reject when header missing wrong prefix or too long")
    void should_reject_when_headerMissingWrongPrefixOrTooLong() {
        TelegramWebhookSecretVerifier verifier = verifier(SECRET);

        assertThat(verifier.verify(request(null, "10.0.0.2"))).isEqualTo(Outcome.REJECTED);
        assertThat(verifier.verify(request("", "10.0.0.3"))).isEqualTo(Outcome.REJECTED);
        assertThat(verifier.verify(request(SECRET.substring(0, SECRET.length() - 1), "10.0.0.4")))
                .isEqualTo(Outcome.REJECTED);
        assertThat(verifier.verify(request(SECRET + "x", "10.0.0.5"))).isEqualTo(Outcome.REJECTED);
        assertThat(verifier.verify(request("y".repeat(257), "10.0.0.6"))).isEqualTo(Outcome.REJECTED);
    }

    @Test
    @DisplayName("a secret of the right length that differs in one character (first, middle or last) is rejected")
    void should_reject_when_headerSameLengthButOneCharDiffers() {
        TelegramWebhookSecretVerifier verifier = verifier(SECRET);

        for (int position : new int[] {0, SECRET.length() / 2, SECRET.length() - 1}) {
            char[] forged = SECRET.toCharArray();
            forged[position] = forged[position] == 'a' ? 'b' : 'a';

            assertThat(verifier.verify(request(new String(forged), "10.0.3." + position)))
                    .as("one char flipped at %d", position).isEqualTo(Outcome.REJECTED);
        }
    }

    @Test
    @DisplayName("the secret is compared with MessageDigest.isEqual on UTF-8 bytes, on success and on mismatch")
    void should_compareWithConstantTimeIsEqual_when_headerPresent() {
        TelegramWebhookSecretVerifier verifier = verifier(SECRET);
        String forged = SECRET.substring(0, SECRET.length() - 1) + "#";
        byte[] expected = SECRET.getBytes(StandardCharsets.UTF_8);

        try (MockedStatic<MessageDigest> digest = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
            Outcome accepted = verifier.verify(request(SECRET, "10.0.4.1"));
            Outcome rejected = verifier.verify(request(forged, "10.0.4.2"));

            assertThat(accepted).isEqualTo(Outcome.ACCEPTED);
            assertThat(rejected).isEqualTo(Outcome.REJECTED);
            digest.verify(() -> MessageDigest.isEqual(aryEq(expected), aryEq(expected)));
            digest.verify(() -> MessageDigest.isEqual(aryEq(expected),
                    aryEq(forged.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    @DisplayName("reject everything when configured secret blank")
    void should_rejectEverything_when_configuredSecretBlank() {
        TelegramWebhookSecretVerifier verifier = verifier("");

        assertThat(verifier.verify(request("", "10.0.0.7"))).isEqualTo(Outcome.REJECTED);
        assertThat(verifier.verify(request(null, "10.0.0.8"))).isEqualTo(Outcome.REJECTED);
    }

    @Test
    @DisplayName("throttle only rejected calls when ip exceeds capacity")
    void should_throttleOnlyRejectedCalls_when_ipExceedsCapacity() {
        TelegramWebhookSecretVerifier verifier = verifier(SECRET);
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(verifier.verify(request("wrong", "10.0.1.1"))).isEqualTo(Outcome.REJECTED);
        }

        Outcome overCap = verifier.verify(request("wrong", "10.0.1.1"));
        Outcome genuine = verifier.verify(request(SECRET, "10.0.1.1"));
        Outcome otherIp = verifier.verify(request("wrong", "10.0.1.2"));

        assertThat(overCap).isEqualTo(Outcome.THROTTLED);
        assertThat(genuine).isEqualTo(Outcome.ACCEPTED);
        assertThat(otherIp).isEqualTo(Outcome.REJECTED);
    }

    @Test
    @DisplayName("never charge bucket when secret matches")
    void should_neverChargeBucket_when_secretMatches() {
        TelegramWebhookSecretVerifier verifier = verifier(SECRET);
        for (int i = 0; i < CAPACITY * 5; i++) {
            verifier.verify(request(SECRET, "10.0.2.1"));
        }

        Outcome firstBad = verifier.verify(request("wrong", "10.0.2.1"));

        assertThat(firstBad).isEqualTo(Outcome.REJECTED);
    }

    @Test
    @DisplayName("write one summary line when 200 bad secret calls in one window")
    void should_writeOneSummaryLine_when_200BadSecretCallsInOneWindow(CapturedOutput output) {
        MutableTestClock clock = new MutableTestClock(Instant.parse("2026-10-30T10:00:00Z"));
        TelegramWebhookSecretVerifier verifier = verifier(SECRET, clock);

        for (int i = 0; i < 200; i++) {
            verifier.verify(request("wrong", "10.0.4.1"));
        }

        assertThat(countOf(output.getAll(), "event=telegram_webhook_rejected")).isEqualTo(1);
        assertThat(output.getAll()).contains("event=telegram_webhook_rejected rejected=1 throttled=0");
    }

    @Test
    @DisplayName("flush pending counts when next window opens on any call")
    void should_flushPendingCounts_when_nextWindowOpensOnAnyCall(CapturedOutput output) {
        MutableTestClock clock = new MutableTestClock(Instant.parse("2026-10-30T10:00:00Z"));
        TelegramWebhookSecretVerifier verifier = verifier(SECRET, clock);
        for (int i = 0; i < 200; i++) {
            verifier.verify(request("wrong", "10.0.4." + (i % 2)));
        }
        clock.advance(TelegramWebhookSecretVerifier.REJECT_LOG_WINDOW);

        Outcome genuine = verifier.verify(request(SECRET, "10.0.4.9"));
        verifier.verify(request(SECRET, "10.0.4.9"));

        assertThat(genuine).isEqualTo(Outcome.ACCEPTED);
        // two IPs x capacity 3 = 6 rejected, 194 throttled; the first rejection was logged at once.
        assertThat(output.getAll()).contains("event=telegram_webhook_rejected rejected=1 throttled=0")
                .contains("event=telegram_webhook_rejected rejected=5 throttled=194");
        assertThat(countOf(output.getAll(), "event=telegram_webhook_rejected")).isEqualTo(2);
    }

    @Test
    @DisplayName("flush on schedule when no further call arrives")
    void should_flushOnSchedule_when_noFurtherCallArrives(CapturedOutput output) {
        MutableTestClock clock = new MutableTestClock(Instant.parse("2026-10-30T10:00:00Z"));
        TelegramWebhookSecretVerifier verifier = verifier(SECRET, clock);
        verifier.verify(request("wrong", "10.0.6.1"));
        clock.advance(Duration.ofSeconds(10));
        verifier.verify(request("wrong", "10.0.6.1"));
        clock.advance(TelegramWebhookSecretVerifier.REJECT_LOG_WINDOW);

        verifier.flushRejectLogIfDue();

        long secondCallEpoch = Instant.parse("2026-10-30T10:00:10Z").getEpochSecond();
        assertThat(output.getAll()).contains("rejected=1 throttled=0 since="
                        + Instant.parse("2026-10-30T10:00:00Z").getEpochSecond())
                .contains("rejected=1 throttled=0 since=" + secondCallEpoch);
    }

    @Test
    @DisplayName("flush exactly n times m when threads reject concurrently")
    void should_flushExactlyNTimesM_when_threadsRejectConcurrently(CapturedOutput output) throws Exception {
        int threads = 8;
        int perThread = 250;
        MutableTestClock clock = new MutableTestClock(Instant.parse("2026-10-30T10:00:00Z"));
        TelegramWebhookSecretVerifier verifier = verifier(SECRET, clock);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String ip = "10.0.7." + t;
            done.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    verifier.verify(request("wrong", ip));
                }
                return null;
            }));
        }

        go.countDown();
        for (Future<?> f : done) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        clock.advance(TelegramWebhookSecretVerifier.REJECT_LOG_WINDOW);
        verifier.flushRejectLogIfDue();

        Matcher line = Pattern.compile("rejected=(\\d+) throttled=(\\d+)").matcher(output.getAll());
        long rejected = 0;
        long throttled = 0;
        while (line.find()) {
            rejected += Long.parseLong(line.group(1));
            throttled += Long.parseLong(line.group(2));
        }
        assertThat(rejected + throttled).isEqualTo((long) threads * perThread);
        assertThat(rejected).as("capacity %d per IP", CAPACITY).isEqualTo((long) threads * CAPACITY);
    }

    @Test
    @DisplayName("log nothing when only genuine calls")
    void should_logNothing_when_onlyGenuineCalls(CapturedOutput output) {
        TelegramWebhookSecretVerifier verifier = verifier(SECRET);

        verifier.verify(request(SECRET, "10.0.5.1"));

        assertThat(output.getAll()).doesNotContain("event=telegram_webhook_rejected");
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("log rejection without secret or ip when rejected")
    void should_logRejectionWithoutSecretOrIp_when_rejected(CapturedOutput output) {
        verifier(SECRET).verify(request("guess-" + SECRET, "10.0.3.77"));

        assertThat(output.getAll()).contains("event=telegram_webhook_rejected rejected=1 throttled=0")
                .doesNotContain(SECRET).doesNotContain("10.0.3.77");
    }
}
