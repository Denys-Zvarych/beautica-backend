package com.beautica.review.invite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.entity.SmsOptOut;
import com.beautica.review.invite.entity.SmsOptOutSource;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 399 D4 — {@code sms_opt_outs} schema contract (V213): {@code phone_hash} is the PRIMARY KEY (one opt-out per
 * phone), {@code source} is NOT NULL and limited to {@code REVIEW_INVITE}, and the key must be a 32-byte digest. The
 * hash key equality with {@code client_blocks} lives in {@link SmsOptOutHashIT}.
 */
@DisplayName("V213 sms_opt_outs schema — PK uniqueness, source CHECK, digest length (phase 399 IT)")
class SmsOptOutSchemaIT extends AbstractIntegrationTest {

    private static final String PHONE = "+380509876543";
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Autowired private SmsOptOutRepository smsOptOutRepository;
    @Autowired private PhoneHasher phoneHasher;

    @Test
    @DisplayName("a second raw INSERT of the same phone hash violates the primary key")
    void should_rejectSecondOptOut_when_samePhoneHashInsertedTwice() {
        byte[] hash = phoneHasher.hash(PHONE).hash();
        insert(hash, "REVIEW_INVITE");

        assertThatThrownBy(() -> insert(hash, "REVIEW_INVITE"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("sms_opt_outs_pkey");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("two spellings of the same phone through the entity factory still leave exactly one opt-out row")
    void should_keepOneRow_when_samePhoneOptsOutTwiceThroughRepository() {
        smsOptOutRepository.saveAndFlush(SmsOptOut.of(phoneHasher.hash(PHONE), SmsOptOutSource.REVIEW_INVITE, NOW));

        smsOptOutRepository.saveAndFlush(SmsOptOut.of(phoneHasher.hash("050 987 65 43"),
                SmsOptOutSource.REVIEW_INVITE, NOW.plusSeconds(60)));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM sms_opt_outs", java.sql.Timestamp.class)
                .toInstant())
                .as("created_at is updatable=false — the first opt-out's timestamp is kept")
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName("an unknown source is rejected by chk_sms_opt_outs_source")
    void should_rejectOptOut_when_sourceUnknown() {
        assertThatThrownBy(() -> insert(phoneHasher.hash(PHONE).hash(), "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_sms_opt_outs_source");
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("a NULL source is rejected (NOT NULL)")
    void should_rejectOptOut_when_sourceNull() {
        assertThatThrownBy(() -> insert(phoneHasher.hash(PHONE).hash(), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("source");
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("a key that is not a 32-byte digest (e.g. the raw phone bytes) is rejected")
    void should_rejectOptOut_when_phoneHashNotDigestLength() {
        byte[] rawPhone = PHONE.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> insert(rawPhone, "REVIEW_INVITE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_sms_opt_outs_phone_hash_len");
        assertThat(rowCount()).isZero();
    }

    private void insert(byte[] phoneHash, String source) {
        jdbcTemplate.update("INSERT INTO sms_opt_outs (phone_hash, source) VALUES (?, ?)", phoneHash, source);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_opt_outs", Integer.class);
    }
}
