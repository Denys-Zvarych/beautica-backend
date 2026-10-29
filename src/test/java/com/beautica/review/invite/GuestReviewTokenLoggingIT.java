package com.beautica.review.invite;

import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 401 audit-fix cycle 1 (sec LOW) — the raw review-invite token never reaches the process OUTPUT (the real
 * console appender, {@code com.beautica} at TRACE in tests), on the happy paths AND on the error paths: GET, submit
 * (201, then 409 / 400 / 404), opt-out. Complements the per-class root-logger {@code ListAppender} checks with the
 * formatted lines exactly as an operator would see them (MDC, logger names, request ids). Same
 * {@code @TestPropertySource} as the other review-invite ITs, so the Spring context is shared.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review page — raw token never in the log output (phase 401 audit-fix)")
class GuestReviewTokenLoggingIT extends AbstractReviewInviteIT {

    private static final String BASE = "/api/v1/public/review-invites/";
    /** Digit-free on purpose: the shape the generic path-token masks used to miss. */
    private static final String TOKEN = "LogCaptureTokenNoDigitsHere";
    private static final String CAPTURE_MARKER = "guest-review-token-logging-it-capture-marker";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PhoneHasher phoneHasher;

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        int n = IP_SEQ.incrementAndGet();
        headers.set("X-Forwarded-For", "198.20." + (n / 250) + "." + (n % 250 + 1));
        return headers;
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body) {
        return restTemplate.exchange(BASE + TOKEN + path, method, new HttpEntity<>(body, headers()), String.class);
    }

    private static String items(String... items) {
        return "{\"turnstileToken\":\"t\",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String item(UUID bookingId, int rating) {
        return "{\"bookingId\":\"" + bookingId + "\",\"rating\":" + rating + "}";
    }

    private void sentBookingInvite(UUID bookingId) {
        Instant sentAt = Instant.now().minus(Duration.ofHours(1));
        jdbcTemplate.update("INSERT INTO review_invites (id, booking_id, phone_hash, token_hash, status, send_after, "
                        + "sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                UUID.randomUUID(), bookingId, phoneHasher.hash(GUEST_PHONE).hash(), ReviewInviteTokenHasher.hash(TOKEN),
                Timestamp.from(sentAt), Timestamp.from(sentAt), Timestamp.from(sentAt.plus(Duration.ofDays(14))),
                Timestamp.from(sentAt));
    }

    @Test
    @DisplayName("GET, submit 409 / 400 / 201 / 404 and opt-out → the raw token appears in no output line")
    void should_neverPrintRawToken_when_everyRouteAndErrorPathRuns(CapturedOutput output) {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        sentBookingInvite(bookingId);
        LoggerFactory.getLogger(GuestReviewTokenLoggingIT.class).info(CAPTURE_MARKER);

        ResponseEntity<String> read = exchange(HttpMethod.GET, "", null);
        ResponseEntity<String> notInInvite = exchange(HttpMethod.POST, "/reviews", items(item(UUID.randomUUID(), 5)));
        ResponseEntity<String> invalid = exchange(HttpMethod.POST, "/reviews", items(item(bookingId, 9)));
        ResponseEntity<String> created = exchange(HttpMethod.POST, "/reviews", items(item(bookingId, 5)));
        ResponseEntity<String> replay = exchange(HttpMethod.POST, "/reviews", items(item(bookingId, 5)));
        ResponseEntity<String> optOut = exchange(HttpMethod.POST, "/opt-out", "{\"turnstileToken\":\"t\"}");

        assertThat(output.getAll()).as("console capture is live").contains(CAPTURE_MARKER);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notInInvite.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(optOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(output.getAll()).doesNotContain(TOKEN);
        assertThat(logLines()).noneMatch(line -> line.contains(TOKEN));
    }
}
