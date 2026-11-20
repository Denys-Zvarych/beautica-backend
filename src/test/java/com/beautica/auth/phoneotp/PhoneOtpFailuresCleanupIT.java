package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone-first hardening AUTH-D26 (c) / V258 — the 03:00 sweep runs {@code deleteFailuresBefore} on real Postgres with
 * the 25 h cutoff: failures older than 25 h are purged, failures still inside (or just past) the 24 h cap window stay,
 * so the per-(phone, network) cap is never cut short.
 */
@DisplayName("PhoneOtpCleanupJob — phone_otp_failures sweep on Postgres (25 h retention)")
class PhoneOtpFailuresCleanupIT extends AbstractIntegrationTest {

    private static final String PHONE = "+380671112233";
    private static final String NET = "203.0.113.9";

    @Autowired
    private PhoneOtpCleanupJob cleanupJob;

    private void failureAged(String age) {
        jdbcTemplate.update("INSERT INTO phone_otp_failures (phone, client_net, failed_at) "
                + "VALUES (?, ?, now() - CAST(? AS interval))", PHONE, NET, age);
    }

    private int failuresOlderThan(String age) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otp_failures WHERE phone = ? "
                + "AND failed_at < now() - CAST(? AS interval)", Integer.class, PHONE, age);
    }

    private int failures() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otp_failures WHERE phone = ?", Integer.class,
                PHONE);
    }

    @Test
    @DisplayName("sweep deletes failures older than 25 h and keeps the 1 h, 23 h and 24.5 h old ones")
    void should_purgeOnlyFailuresOlderThan25h_when_sweepRuns() {
        failureAged("26 hours");
        failureAged("48 hours");
        failureAged("24 hours 30 minutes");
        failureAged("23 hours");
        failureAged("1 hour");

        cleanupJob.sweep();

        assertThat(failuresOlderThan("25 hours")).as("rows past the 25 h retention are gone").isZero();
        assertThat(failures()).as("rows inside the 25 h retention survive").isEqualTo(3);
    }
}
