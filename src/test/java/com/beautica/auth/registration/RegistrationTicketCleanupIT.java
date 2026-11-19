package com.beautica.auth.registration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B514 / phone-first hardening — {@link RegistrationTicketCleanupJob} against real Postgres: a ticket expired more
 * than a day ago is deleted, an expired-today and a live ticket are kept. Same context as
 * {@code PhoneOtpVerifyConcurrencyTest}.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
@Import(TestSecurityConfig.class)
@DisplayName("RegistrationTicketCleanupJob — real Postgres sweep")
class RegistrationTicketCleanupIT extends AbstractIntegrationTest {

    @Autowired
    private RegistrationTicketCleanupJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private OtpSmsSender otpSmsSender;

    private String insertTicket(String expiresAtSql) {
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO phone_registration_tickets (id, ticket_hash, phone, expires_at, used) "
                + "VALUES (?, ?, '+380671130001', " + expiresAtSql + ", false)", UUID.randomUUID(), hash);
        return hash;
    }

    private List<String> remainingHashes() {
        return jdbc.queryForList("SELECT ticket_hash FROM phone_registration_tickets", String.class);
    }

    @Test
    @DisplayName("should_deleteLongExpiredAndKeepRecentOrLive_when_sweepRuns")
    void should_deleteLongExpiredAndKeepRecentOrLive_when_sweepRuns() {
        String longExpired = insertTicket("now() - interval '2 days'");
        String expiredToday = insertTicket("now() - interval '1 hour'");
        String live = insertTicket("now() + interval '10 minutes'");

        int deleted = job.sweep();

        assertThat(deleted).isOne();
        assertThat(remainingHashes()).containsExactlyInAnyOrder(expiredToday, live).doesNotContain(longExpired);
    }
}
