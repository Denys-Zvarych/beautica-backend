package com.beautica.review.invite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.common.security.PhoneHasher;
import com.beautica.moderation.service.ClientBlockService;
import com.beautica.review.invite.entity.SmsOptOut;
import com.beautica.review.invite.entity.SmsOptOutSource;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import com.beautica.review.support.GuestReviewFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 399 D4 — {@code sms_opt_outs} is keyed by the SAME phone key as phase-397 guest blocks: one
 * {@link PhoneHasher} (HMAC-SHA-256 of the E.164 phone, pepper {@code PHONE_HASH_PEPPER}), no second pepper. The
 * block is created through the real {@link ClientBlockService} path, the opt-out through the entity factory, from two
 * different spellings of the same number.
 */
@DisplayName("sms_opt_outs phone key == client_blocks phone key — phase 399 IT")
class SmsOptOutHashIT extends AbstractIntegrationTest {

    private static final String BOOKING_PHONE = "+380501234567";
    private static final String OPT_OUT_SPELLING = "050 123 45 67";
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @Autowired
    private SmsOptOutRepository smsOptOutRepository;

    @Autowired
    private ClientBlockService clientBlockService;

    @Autowired
    private PhoneHasher phoneHasher;

    private GuestReviewFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new GuestReviewFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("an opt-out stores exactly the hash a guest block of the same phone stores")
    void should_storeSameHashAsGuestBlock_when_samePhoneSpelledDifferently() {
        UUID masterId = fixtures.independentMaster();
        UUID bookingId = fixtures.guestBooking(masterId, fixtures.masterService(masterId), BOOKING_PHONE);
        clientBlockService.block(fixtures.masterUserId(masterId), Role.INDEPENDENT_MASTER, bookingId);

        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(OPT_OUT_SPELLING), SmsOptOutSource.REVIEW_INVITE, NOW));

        byte[] blockHash = jdbcTemplate.queryForObject("SELECT phone_hash FROM client_blocks", byte[].class);
        Map<String, Object> optOut = jdbcTemplate.queryForMap("SELECT phone_hash, source FROM sms_opt_outs");
        assertThat(blockHash).hasSize(32);
        assertThat((byte[]) optOut.get("phone_hash")).isEqualTo(blockHash);
        assertThat(optOut.get("source")).isEqualTo("REVIEW_INVITE");
    }

    @Test
    @DisplayName("the eligibility lookup finds the opt-out by any spelling of the phone, and only that phone")
    void should_findOptOut_when_queriedWithSamePhoneKey() {
        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(OPT_OUT_SPELLING), SmsOptOutSource.REVIEW_INVITE, NOW));

        boolean samePhone = smsOptOutRepository.existsByPhoneHash(phoneHasher.hash(BOOKING_PHONE).hash());
        boolean otherPhone = smsOptOutRepository.existsByPhoneHash(phoneHasher.hash("+380671112233").hash());

        assertThat(samePhone).isTrue();
        assertThat(otherPhone).isFalse();
    }

    @Test
    @DisplayName("no raw phone is stored: the row's key is the 32-byte HMAC, not the number")
    void should_notStoreRawPhone_when_optingOut() {
        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(OPT_OUT_SPELLING), SmsOptOutSource.REVIEW_INVITE, NOW));

        byte[] stored = jdbcTemplate.queryForObject("SELECT phone_hash FROM sms_opt_outs", byte[].class);
        assertThat(stored).hasSize(32);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("501234567");
    }
}
