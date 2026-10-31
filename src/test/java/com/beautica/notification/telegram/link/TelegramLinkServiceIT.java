package com.beautica.notification.telegram.link;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.BOT_USERNAME;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.OTHER_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.T0;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.rawToken;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 405 — {@link TelegramLinkService} against real Postgres (V224): issue → claim → contact proof, the claim /
 * proof state machine (D7), the per-phone cap (D6), link-row operations, and the no-raw-data rules.
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = TelegramLinkTestSupport.BOT_USERNAME_PROPERTY)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TelegramLinkService — integration (phase 405)")
class TelegramLinkServiceIT extends AbstractIntegrationTest {

    private static final long CHAT = 7_000_000_001L;
    private static final long OTHER_CHAT = 7_000_000_002L;
    private static final String CONTACT_PLUS = "+380501234567";
    private static final String CONTACT_BARE = "380501234567";

    @Autowired private TelegramLinkService service;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PhoneHasher phoneHasher;

    private TelegramLinkTestSupport support;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        support = new TelegramLinkTestSupport(jdbcTemplate, passwordEncoder);
    }

    private String issueToken(String phone) {
        return rawToken(service.issueForBooking(support.guestBooking(phone), phone));
    }

    private String tokenState(String rawToken) {
        return jdbcTemplate.queryForObject("SELECT state FROM telegram_link_tokens "
                + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", String.class, rawToken);
    }

    private int linkCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links", Integer.class);
    }

    @Nested
    @DisplayName("issue")
    class Issue {

        @Test
        @DisplayName("deep link = t.me/<bot>?start=<43-char base64url>, expiry = now + 30 min, only SHA-256 stored")
        void should_issueDeepLinkAndStoreOnlyHash_when_issued() {
            UUID bookingId = support.guestBooking(GUEST_PHONE);

            IssuedLink link = service.issueForBooking(bookingId, GUEST_PHONE);

            String token = rawToken(link);
            assertThat(link.url()).isEqualTo("https://t.me/" + BOT_USERNAME + "?start=" + token);
            assertThat(token).hasSize(43).matches("^[A-Za-z0-9_-]+$");
            assertThat(link.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
            assertThat(link.toString()).doesNotContain(token);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens WHERE booking_id = ? "
                            + "AND token_hash = sha256(convert_to(?, 'UTF8')) AND state = 'ISSUED' "
                            + "AND octet_length(phone_hash) = 32", Integer.class, bookingId, token))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens t "
                    + "WHERE row_to_json(t)::text LIKE ?", Integer.class, "%" + token + "%")).isZero();
        }

        @Test
        @DisplayName("6th live token for one phone evicts the oldest")
        void should_evictOldest_when_sixthTokenIssued() {
            String oldest = issueToken(GUEST_PHONE);
            for (int i = 0; i < 4; i++) {
                clock.advance(Duration.ofSeconds(1));
                issueToken(GUEST_PHONE);
            }
            clock.advance(Duration.ofSeconds(1));

            String sixth = issueToken(GUEST_PHONE);

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class))
                    .isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens "
                    + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, oldest)).isZero();
            assertThat(tokenState(sixth)).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("cap evicts ISSUED before CLAIMED: an older claimed token survives five newer issues")
        void should_keepClaimedToken_when_capEvicts() {
            String claimed = issueToken(GUEST_PHONE);
            service.claim(claimed, CHAT);
            clock.advance(Duration.ofSeconds(1));
            String oldestIssued = issueToken(GUEST_PHONE);
            for (int i = 0; i < 4; i++) {
                clock.advance(Duration.ofSeconds(1));
                issueToken(GUEST_PHONE);
            }

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class))
                    .isEqualTo(5);
            assertThat(tokenState(claimed)).isEqualTo("CLAIMED");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens "
                    + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, oldestIssued)).isZero();
        }

        @Test
        @DisplayName("deleting the booking cascades its tokens")
        void should_cascadeTokens_when_bookingDeleted() {
            UUID bookingId = support.guestBooking(GUEST_PHONE);
            service.issueForBooking(bookingId, GUEST_PHONE);

            jdbcTemplate.update("DELETE FROM bookings WHERE id = ?", bookingId);

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("claim + proveContact")
    class Proof {

        @Test
        @DisplayName("matching '+380…' contact → LINKED, link ACTIVE, token LINKED; no raw phone or chat in logs")
        void should_link_when_contactMatchesWithPlus(CapturedOutput output) {
            String token = issueToken(GUEST_PHONE);

            ClaimResult claim = service.claim(token, CHAT);
            ProofResult proof = service.proveContact(CHAT, CONTACT_PLUS, CHAT, CHAT);

            assertThat(claim).isEqualTo(ClaimResult.CLAIMED);
            assertThat(proof).isEqualTo(ProofResult.LINKED);
            assertThat(tokenState(token)).isEqualTo("LINKED");
            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
            assertThat(service.findActiveForPhone(GUEST_PHONE)).get()
                    .extracting(ActiveLink::chatId).isEqualTo(CHAT);
            assertThat(output.getOut()).contains("event=telegram_link op=prove result=LINKED")
                    .doesNotContain("501234567").doesNotContain(String.valueOf(CHAT)).doesNotContain(token);
        }

        @Test
        @DisplayName("matching bare '380…' contact (Telegram's usual form) → LINKED")
        void should_link_when_contactMatchesWithoutPlus() {
            String token = issueToken(GUEST_PHONE);
            service.claim(token, CHAT);

            ProofResult proof = service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);

            assertThat(proof).isEqualTo(ProofResult.LINKED);
            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        @DisplayName("different phone → MISMATCH, no link, token terminal; second share → NO_PENDING; no raw phone kept")
        void should_mismatch_when_contactPhoneDiffers(CapturedOutput output) {
            String token = issueToken(GUEST_PHONE);
            service.claim(token, CHAT);

            ProofResult first = service.proveContact(CHAT, "380671112233", CHAT, CHAT);
            ProofResult second = service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);

            assertThat(first).isEqualTo(ProofResult.MISMATCH);
            assertThat(second).isEqualTo(ProofResult.NO_PENDING);
            assertThat(tokenState(token)).isEqualTo("MISMATCH");
            assertThat(linkCount()).isZero();
            assertThat(service.claim(token, CHAT)).isEqualTo(ClaimResult.INVALID);
            assertThat(output.getOut()).contains("result=MISMATCH").doesNotContain("671112233");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens t "
                    + "WHERE row_to_json(t)::text LIKE '%671112233%'", Integer.class)).isZero();
        }

        @Test
        @DisplayName("unparseable contact phone → MISMATCH")
        void should_mismatch_when_contactPhoneUnparseable() {
            service.claim(issueToken(GUEST_PHONE), CHAT);

            ProofResult proof = service.proveContact(CHAT, "not a phone", CHAT, CHAT);

            assertThat(proof).isEqualTo(ProofResult.MISMATCH);
        }

        @Test
        @DisplayName("forwarded card (user_id != from.id) → NOT_OWN_CONTACT, token stays CLAIMED, own share then links")
        void should_rejectForwardedCard_then_linkOwnShare() {
            String token = issueToken(GUEST_PHONE);
            service.claim(token, CHAT);

            ProofResult forwarded = service.proveContact(CHAT, CONTACT_BARE, CHAT + 1, CHAT);

            assertThat(forwarded).isEqualTo(ProofResult.NOT_OWN_CONTACT);
            assertThat(tokenState(token)).isEqualTo("CLAIMED");
            assertThat(service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT)).isEqualTo(ProofResult.LINKED);
        }

        @Test
        @DisplayName("own contact but chat.id != from.id (not a private chat) → NOT_OWN_CONTACT, token stays CLAIMED")
        void should_rejectProof_when_chatIdDiffersFromFromId() {
            String token = issueToken(GUEST_PHONE);
            service.claim(token, CHAT);

            ProofResult proof = service.proveContact(CHAT, CONTACT_BARE, OTHER_CHAT, OTHER_CHAT);

            assertThat(proof).isEqualTo(ProofResult.NOT_OWN_CONTACT);
            assertThat(tokenState(token)).isEqualTo("CLAIMED");
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("contact without user_id → NOT_OWN_CONTACT")
        void should_rejectContact_when_userIdNull() {
            service.claim(issueToken(GUEST_PHONE), CHAT);

            ProofResult proof = service.proveContact(CHAT, CONTACT_BARE, null, CHAT);

            assertThat(proof).isEqualTo(ProofResult.NOT_OWN_CONTACT);
        }

        @Test
        @DisplayName("contact without a claim → NO_PENDING")
        void should_returnNoPending_when_noClaim() {
            issueToken(GUEST_PHONE);

            ProofResult proof = service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);

            assertThat(proof).isEqualTo(ProofResult.NO_PENDING);
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("claim expired before the share → NO_PENDING")
        void should_returnNoPending_when_claimExpired() {
            service.claim(issueToken(GUEST_PHONE), CHAT);
            clock.advance(Duration.ofMinutes(30));

            ProofResult proof = service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);

            assertThat(proof).isEqualTo(ProofResult.NO_PENDING);
        }

        @Test
        @DisplayName("claim twice by same chat → CLAIMED both times")
        void should_stayClaimed_when_sameChatClaimsTwice() {
            String token = issueToken(GUEST_PHONE);

            ClaimResult first = service.claim(token, CHAT);
            ClaimResult second = service.claim(token, CHAT);

            assertThat(first).isEqualTo(ClaimResult.CLAIMED);
            assertThat(second).isEqualTo(ClaimResult.CLAIMED);
        }

        @Test
        @DisplayName("claim by a second chat while CLAIMED → INVALID, first chat keeps it")
        void should_rejectSecondChat_when_tokenClaimed() {
            String token = issueToken(GUEST_PHONE);
            service.claim(token, CHAT);

            ClaimResult result = service.claim(token, OTHER_CHAT);

            assertThat(result).isEqualTo(ClaimResult.INVALID);
            assertThat(jdbcTemplate.queryForObject("SELECT claimed_chat_id FROM telegram_link_tokens", Long.class))
                    .isEqualTo(CHAT);
        }

        @Test
        @DisplayName("claim after LINKED by same chat → ALREADY_LINKED; by another chat → INVALID")
        void should_returnAlreadyLinked_when_reclaimedAfterLink() {
            String token = issueToken(GUEST_PHONE);
            service.claim(token, CHAT);
            service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);

            ClaimResult same = service.claim(token, CHAT);
            ClaimResult other = service.claim(token, OTHER_CHAT);

            assertThat(same).isEqualTo(ClaimResult.ALREADY_LINKED);
            assertThat(other).isEqualTo(ClaimResult.INVALID);
        }

        @Test
        @DisplayName("claim after LINKED but the chat has since unlinked → INVALID (no false 'linked' reply)")
        void should_returnInvalid_when_reclaimedAfterUnlink() {
            String token = issueToken(GUEST_PHONE);
            service.claim(token, CHAT);
            service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);
            service.unlinkChat(CHAT);

            ClaimResult result = service.claim(token, CHAT);

            assertThat(result).isEqualTo(ClaimResult.INVALID);
        }

        @Test
        @DisplayName("expired / unknown / malformed token → INVALID")
        void should_returnInvalid_when_expiredOrUnknown() {
            String token = issueToken(GUEST_PHONE);
            clock.advance(Duration.ofMinutes(30));

            ClaimResult expired = service.claim(token, CHAT);
            ClaimResult unknown = service.claim("A".repeat(43), CHAT);
            ClaimResult malformed = service.claim("short", CHAT);
            ClaimResult nullToken = service.claim(null, CHAT);

            assertThat(expired).isEqualTo(ClaimResult.INVALID);
            assertThat(unknown).isEqualTo(ClaimResult.INVALID);
            assertThat(malformed).isEqualTo(ClaimResult.INVALID);
            assertThat(nullToken).isEqualTo(ClaimResult.INVALID);
            assertThat(tokenState(token)).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("chat claims token B while holding A → A released to ISSUED, proof uses B")
        void should_releasePreviousClaim_when_chatClaimsAnotherToken() {
            String tokenA = issueToken(OTHER_PHONE);
            String tokenB = issueToken(GUEST_PHONE);
            service.claim(tokenA, CHAT);

            ClaimResult result = service.claim(tokenB, CHAT);

            assertThat(result).isEqualTo(ClaimResult.CLAIMED);
            assertThat(tokenState(tokenA)).isEqualTo("ISSUED");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens "
                    + "WHERE state = 'ISSUED' AND claimed_chat_id IS NULL AND claimed_at IS NULL", Integer.class))
                    .isEqualTo(1);
            assertThat(service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT)).isEqualTo(ProofResult.LINKED);
            assertThat(service.claim(tokenA, OTHER_CHAT)).isEqualTo(ClaimResult.CLAIMED);
        }
    }

    @Nested
    @DisplayName("link rows")
    class Links {

        private void link(String phone, long chatId) {
            String token = issueToken(phone);
            service.claim(token, chatId);
            assertThat(service.proveContact(chatId, phone, chatId, chatId)).isEqualTo(ProofResult.LINKED);
        }

        @Test
        @DisplayName("re-link of a phone from a new chat rebinds the one row to the new chat")
        void should_rebindChat_when_phoneRelinkedFromNewChat() {
            link(GUEST_PHONE, CHAT);

            link(GUEST_PHONE, OTHER_CHAT);

            assertThat(linkCount()).isEqualTo(1);
            assertThat(service.findActiveForPhone(GUEST_PHONE)).get()
                    .extracting(ActiveLink::chatId).isEqualTo(OTHER_CHAT);
        }

        @Test
        @DisplayName("unlinkChat deletes every link of the chat → NOT_LINKED")
        void should_deleteLinks_when_unlinkChat() {
            link(GUEST_PHONE, CHAT);
            link(OTHER_PHONE, CHAT);

            int removed = service.unlinkChat(CHAT);

            assertThat(removed).isEqualTo(2);
            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.NOT_LINKED);
        }

        @Test
        @DisplayName("unlinkPhone deletes that phone only; repeat → false; bad phone → false")
        void should_deletePhoneLink_when_unlinkPhone() {
            link(GUEST_PHONE, CHAT);
            link(OTHER_PHONE, CHAT);

            boolean removed = service.unlinkPhone(GUEST_PHONE);

            assertThat(removed).isTrue();
            assertThat(service.unlinkPhone(GUEST_PHONE)).isFalse();
            assertThat(service.unlinkPhone("garbage")).isFalse();
            assertThat(service.statusForPhone(OTHER_PHONE)).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        @DisplayName("markBlocked(chat) → findActive empty, status BLOCKED; reactivate → ACTIVE")
        void should_blockAndReactivate_when_chatBlocksAndUnblocks() {
            link(GUEST_PHONE, CHAT);

            service.markBlocked(CHAT);

            assertThat(service.findActiveForPhone(GUEST_PHONE)).isEmpty();
            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.BLOCKED);
            service.reactivate(CHAT);
            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        @DisplayName("markBlocked(linkId, chat) → findActive empty")
        void should_block_when_markBlockedByLinkId() {
            link(GUEST_PHONE, CHAT);
            UUID linkId = service.findActiveForPhone(GUEST_PHONE).orElseThrow().linkId();

            service.markBlocked(linkId, CHAT);

            assertThat(service.findActiveForPhone(GUEST_PHONE)).isEmpty();
        }

        @Test
        @DisplayName("phase 408 audit-fix: a late 403 from the OLD chat does not block the phone re-linked to a new chat")
        void should_notBlockRelinkedChat_when_staleChatReturns403() {
            link(GUEST_PHONE, CHAT);
            UUID linkId = service.findActiveForPhone(GUEST_PHONE).orElseThrow().linkId();
            long newChat = CHAT + 1;
            link(GUEST_PHONE, newChat);

            service.markBlocked(linkId, CHAT);

            assertThat(service.findActiveForPhone(GUEST_PHONE)).hasValueSatisfying(active -> {
                assertThat(active.linkId()).isEqualTo(linkId);
                assertThat(active.chatId()).isEqualTo(newChat);
            });
        }

        @Test
        @DisplayName("reactivate on a chat without a link creates nothing")
        void should_notCreateLink_when_reactivateWithoutLink() {
            service.reactivate(CHAT);

            assertThat(linkCount()).isZero();
            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.NOT_LINKED);
        }

        @Test
        @DisplayName("touchLastSent stamps last_sent_at from the clock")
        void should_stampLastSent_when_touched() {
            link(GUEST_PHONE, CHAT);
            UUID linkId = service.findActiveForPhone(GUEST_PHONE).orElseThrow().linkId();
            clock.advance(Duration.ofMinutes(5));

            service.touchLastSent(linkId);

            assertThat(jdbcTemplate.queryForObject("SELECT last_sent_at = ?::timestamptz FROM telegram_links",
                    Boolean.class, T0.plus(Duration.ofMinutes(5)).toString())).isTrue();
        }

        @Test
        @DisplayName("no column of telegram_links holds the raw phone")
        void should_storeNoRawPhone_when_linked() {
            link(GUEST_PHONE, CHAT);

            Integer leaking = jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links l "
                    + "WHERE row_to_json(l)::text LIKE '%501234567%'", Integer.class);

            assertThat(leaking).isZero();
        }
    }

    @Nested
    @DisplayName("ALREADY_LINKED vs INVALID on a re-tap of a LINKED token")
    class ReTap {

        private String linkedToken(String phone, long chatId) {
            String token = issueToken(phone);
            service.claim(token, chatId);
            assertThat(service.proveContact(chatId, phone, chatId, chatId)).isEqualTo(ProofResult.LINKED);
            return token;
        }

        @Test
        @DisplayName("after unlinkPhone → INVALID; token row stays LINKED (re-tap never revives a deleted link)")
        void should_returnInvalid_when_reclaimedAfterUnlinkPhone() {
            String token = linkedToken(GUEST_PHONE, CHAT);
            service.unlinkPhone(GUEST_PHONE);

            ClaimResult result = service.claim(token, CHAT);

            assertThat(result).isEqualTo(ClaimResult.INVALID);
            assertThat(tokenState(token)).isEqualTo("LINKED");
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("phone rebound to another chat → old chat's token INVALID, new chat's token ALREADY_LINKED")
        void should_answerPerChat_when_phoneReboundToNewChat() {
            String oldToken = linkedToken(GUEST_PHONE, CHAT);
            String newToken = linkedToken(GUEST_PHONE, OTHER_CHAT);

            ClaimResult oldChat = service.claim(oldToken, CHAT);
            ClaimResult newChat = service.claim(newToken, OTHER_CHAT);

            assertThat(oldChat).isEqualTo(ClaimResult.INVALID);
            assertThat(newChat).isEqualTo(ClaimResult.ALREADY_LINKED);
        }

        @Test
        @DisplayName("link BLOCKED → re-tap INVALID (only an ACTIVE link answers ALREADY_LINKED); link stays BLOCKED")
        void should_returnInvalid_when_reclaimedWhileBlocked() {
            String token = linkedToken(GUEST_PHONE, CHAT);
            service.markBlocked(CHAT);

            ClaimResult result = service.claim(token, CHAT);

            assertThat(result).isEqualTo(ClaimResult.INVALID);
            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.BLOCKED);
        }

        @Test
        @DisplayName("unlink, then relink the same phone+chat with a new token → the old LINKED token answers ALREADY_LINKED again")
        void should_returnAlreadyLinked_when_relinkedAfterUnlink() {
            String oldToken = linkedToken(GUEST_PHONE, CHAT);
            service.unlinkChat(CHAT);
            linkedToken(GUEST_PHONE, CHAT);

            ClaimResult result = service.claim(oldToken, CHAT);

            assertThat(result).isEqualTo(ClaimResult.ALREADY_LINKED);
        }

        @Test
        @DisplayName("LINKED token past its expiry, same chat, link ACTIVE → still ALREADY_LINKED (expiry gates claims, not the re-tap answer)")
        void should_returnAlreadyLinked_when_linkedTokenExpired() {
            String token = linkedToken(GUEST_PHONE, CHAT);
            clock.advance(Duration.ofHours(1));

            ClaimResult result = service.claim(token, CHAT);

            assertThat(result).isEqualTo(ClaimResult.ALREADY_LINKED);
        }
    }

    @Nested
    @DisplayName("expiry boundaries")
    class Expiry {

        @Test
        @DisplayName("claim 1 s before expiry → CLAIMED; at expiry → INVALID")
        void should_claimUntilExpiry_when_clockNearsExpiresAt() {
            String early = issueToken(GUEST_PHONE);
            String late = issueToken(OTHER_PHONE);
            clock.advance(Duration.ofMinutes(30).minusSeconds(1));

            ClaimResult justBefore = service.claim(early, CHAT);
            clock.advance(Duration.ofSeconds(1));
            ClaimResult atExpiry = service.claim(late, OTHER_CHAT);

            assertThat(justBefore).isEqualTo(ClaimResult.CLAIMED);
            assertThat(atExpiry).isEqualTo(ClaimResult.INVALID);
            assertThat(tokenState(late)).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("contact share 1 s before the claimed token expires → LINKED")
        void should_link_when_proofOneSecondBeforeExpiry() {
            service.claim(issueToken(GUEST_PHONE), CHAT);
            clock.advance(Duration.ofMinutes(30).minusSeconds(1));

            ProofResult proof = service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);

            assertThat(proof).isEqualTo(ProofResult.LINKED);
        }

        @Test
        @DisplayName("6th token evicts only LIVE tokens: an expired older one is neither counted nor needed for the cap")
        void should_notCountExpiredTokens_when_capApplied() {
            String expired = issueToken(GUEST_PHONE);
            clock.advance(Duration.ofMinutes(31));
            for (int i = 0; i < 5; i++) {
                clock.advance(Duration.ofSeconds(1));
                issueToken(GUEST_PHONE);
            }

            assertThat(tokenState(expired)).as("expired row left for 409's purge, not evicted").isEqualTo("ISSUED");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens WHERE expires_at > ?::timestamptz",
                    Integer.class, clock.instant().toString())).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("erasure lookups by phone hash (409)")
    class Erasure {

        private void link(String phone, long chatId) {
            service.claim(issueToken(phone), chatId);
            assertThat(service.proveContact(chatId, phone, chatId, chatId)).isEqualTo(ProofResult.LINKED);
        }

        @Test
        @DisplayName("token and link rows carry exactly PhoneHasher.hash(guest phone) — the key 409's erasure joins on")
        void should_storePhoneHasherDigest_when_linked() {
            link(GUEST_PHONE, CHAT);
            String expected = HexFormat.of().formatHex(phoneHasher.hash(GUEST_PHONE).hash());

            String tokenHash = jdbcTemplate.queryForObject(
                    "SELECT DISTINCT encode(phone_hash, 'hex') FROM telegram_link_tokens", String.class);
            String linkHash = jdbcTemplate.queryForObject(
                    "SELECT encode(phone_hash, 'hex') FROM telegram_links", String.class);

            assertThat(tokenHash).isEqualTo(expected);
            assertThat(linkHash).isEqualTo(expected);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens t "
                    + "JOIN bookings b ON b.id = t.booking_id WHERE b.guest_phone = ? AND t.phone_hash = "
                    + "decode(?, 'hex')", Integer.class, GUEST_PHONE, expected)).isEqualTo(1);
        }

        @Test
        @DisplayName("every spelling of the phone (0…, spaced +380…) finds and erases the same link")
        void should_findAndErase_when_phoneSpelledDifferently() {
            link(GUEST_PHONE, CHAT);

            LinkStatus byLocal = service.statusForPhone("0501234567");
            boolean activeBySpaced = service.findActiveForPhone("+380 50 123 45 67").isPresent();
            boolean erased = service.unlinkPhone("050 123 45 67");

            assertThat(byLocal).isEqualTo(LinkStatus.ACTIVE);
            assertThat(activeBySpaced).isTrue();
            assertThat(erased).isTrue();
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("lookups with an unparseable / null phone → NOT_LINKED, empty, false — no exception, no row touched")
        void should_returnNotLinked_when_phoneUnparseable() {
            link(GUEST_PHONE, CHAT);

            assertThat(service.statusForPhone("garbage")).isEqualTo(LinkStatus.NOT_LINKED);
            assertThat(service.findActiveForPhone(null)).isEmpty();
            assertThat(service.unlinkPhone(null)).isFalse();
            assertThat(linkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("unlinkPhone erases only the link row; the phone's token rows stay for the cascade / 409 purge")
        void should_keepTokens_when_unlinkPhone() {
            link(GUEST_PHONE, CHAT);

            service.unlinkPhone(GUEST_PHONE);

            assertThat(linkCount()).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens WHERE state = 'LINKED'",
                    Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("markBlocked / reactivate by chat touch only that chat's links; reactivate on ACTIVE is a no-op")
        void should_scopeTransitionsToChat_when_twoChatsLinked() {
            link(GUEST_PHONE, CHAT);
            link(OTHER_PHONE, OTHER_CHAT);
            clock.advance(Duration.ofMinutes(1));

            service.markBlocked(CHAT);
            service.reactivate(OTHER_CHAT);

            assertThat(service.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.BLOCKED);
            assertThat(service.statusForPhone(OTHER_PHONE)).isEqualTo(LinkStatus.ACTIVE);
            assertThat(jdbcTemplate.queryForObject("SELECT status_changed_at = ?::timestamptz FROM telegram_links "
                    + "WHERE chat_id = ?", Boolean.class, T0.toString(), OTHER_CHAT))
                    .as("no-op reactivate does not restamp status_changed_at").isTrue();
        }
    }

    @Test
    @DisplayName("no phone, chat id or token in the logs for any claim / proof result")
    void should_logNoRawData_when_everyResultProduced(CapturedOutput output) {
        String token = issueToken(GUEST_PHONE);
        service.claim("A".repeat(43), CHAT);
        service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);
        service.claim(token, CHAT);
        service.proveContact(CHAT, CONTACT_BARE, null, CHAT);
        service.proveContact(CHAT, CONTACT_BARE, CHAT, CHAT);
        service.claim(token, CHAT);
        String other = issueToken(OTHER_PHONE);
        service.claim(other, OTHER_CHAT);
        service.proveContact(OTHER_CHAT, "380991234567", OTHER_CHAT, OTHER_CHAT);

        assertThat(output.getOut()).contains("op=issue result=ISSUED", "op=claim result=INVALID",
                "op=claim result=CLAIMED", "op=claim result=ALREADY_LINKED", "op=prove result=NO_PENDING",
                "op=prove result=NOT_OWN_CONTACT", "op=prove result=LINKED", "op=prove result=MISMATCH");
        assertThat(output.getAll()).doesNotContain("501234567", "671112233", "991234567",
                String.valueOf(CHAT), String.valueOf(OTHER_CHAT), token, other);
    }
}
