package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phone-first hardening audit-fix — V258 on real Postgres: {@code phone_otp_failures} accepts the three network key
 * shapes ({@code ClientIpResolver.prefixKey}: IPv4, IPv6 /64, {@code unknown}), refuses a malformed phone or key,
 * defaults {@code failed_at}, and carries the two indexes the cap read and the sweep use.
 */
@DisplayName("V258 migration — phone_otp_failures (per phone + network failed-verify log)")
class V258PhoneOtpFailuresMigrationTest extends AbstractIntegrationTest {

    private static final String PHONE = "+380501234567";

    private int insert(String phone, String clientNet) {
        return jdbcTemplate.update("INSERT INTO phone_otp_failures (phone, client_net) VALUES (?, ?)",
                phone, clientNet);
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.7", "2001:db8:aa:1:0:0:0:0/64", "0:0:0:0:0:0:0:1", "unknown"})
    @DisplayName("should_acceptRow_when_networkKeyWellFormed")
    void should_acceptRow_when_networkKeyWellFormed(String clientNet) {
        int inserted = insert(PHONE, clientNet);

        assertThat(inserted).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT failed_at IS NOT NULL FROM phone_otp_failures "
                + "WHERE client_net = ?", Boolean.class, clientNet)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "203.0.113.7; DROP", "evil.example.com", "2001:db8::/48"})
    @DisplayName("should_rejectRow_when_networkKeyMalformed")
    void should_rejectRow_when_networkKeyMalformed(String clientNet) {
        assertThatThrownBy(() -> insert(PHONE, clientNet))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_phone_otp_failures_client_net");
    }

    @Test
    @DisplayName("should_rejectRow_when_phoneNotE164Ua")
    void should_rejectRow_when_phoneNotE164Ua() {
        assertThatThrownBy(() -> insert("0501234567", "unknown"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_phone_otp_failures_phone");
    }

    @Test
    @DisplayName("should_haveCapAndSweepIndexes_when_migrated")
    void should_haveCapAndSweepIndexes_when_migrated() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'phone_otp_failures'", String.class);

        assertThat(indexes).anyMatch(d -> d.contains("ix_phone_otp_failures_phone_net_at")
                        && d.contains("(phone, client_net, failed_at)"))
                .anyMatch(d -> d.contains("ix_phone_otp_failures_at") && d.contains("(failed_at)"));
    }
}
