package com.beautica.booking.retention;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import com.beautica.notification.telegram.link.TelegramLinkOrphanBackstop;
import com.beautica.notification.telegram.link.TelegramLinkRetentionService;
import com.beautica.notification.telegram.link.TelegramLinkRows;
import com.beautica.notification.telegram.link.TelegramLinkStatus;
import com.beautica.notification.telegram.link.TelegramTokenState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static com.beautica.booking.retention.GuestPiiFixtures.OTHER_PHONE;
import static com.beautica.booking.retention.GuestPiiFixtures.PHONE;
import static com.beautica.booking.retention.GuestPiiFixtures.PHONE_LOCAL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 409 — Telegram link retention inside the guest-PII job and erase, against real Postgres:
 * D1 (link follows the phone's guest PII), D2 (BLOCKED 12 months after {@code status_changed_at}), D3 (erase deletes
 * the link and every token of the phone).
 */
@DisplayName("Guest PII retention + erase — Telegram links (phase 409)")
@ExtendWith(OutputCaptureExtension.class)
class GuestPiiAnonymisationServiceIT extends AbstractIntegrationTest {

    private static final long CHAT = 777_000_111L;
    private static final long OTHER_CHAT = 777_000_222L;

    @Autowired private GuestPiiAnonymisationService service;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private TelegramLinkRetentionService telegramLinkRetentionService;
    @Autowired private TelegramLinkOrphanBackstop backstop;

    private GuestPiiFixtures fx;
    private GuestPiiFixtures.Provider provider;
    private TelegramLinkRows rows;
    private Instant now;
    private Instant cutoff;
    private byte[] phoneHash;
    private byte[] otherPhoneHash;

    @BeforeEach
    void setUp() {
        fx = new GuestPiiFixtures(jdbcTemplate);
        provider = fx.createIndependentMaster();
        rows = new TelegramLinkRows(jdbcTemplate);
        now = Instant.now();
        cutoff = now.atOffset(ZoneOffset.UTC).minusMonths(12).toInstant();
        phoneHash = phoneHasher.hash(PHONE).hash();
        otherPhoneHash = phoneHasher.hash(OTHER_PHONE).hash();
    }

    private OffsetDateTime atCutoff(Duration offset) {
        return cutoff.plus(offset).atOffset(ZoneOffset.UTC);
    }

    private UUID activeLink(byte[] hash, long chatId) {
        return rows.link(hash, chatId, TelegramLinkStatus.ACTIVE, cutoff.minus(Duration.ofDays(30)));
    }

    @Nested
    @DisplayName("D1 — link follows the phone's guest PII")
    class FollowsGuestPii {

        @Test
        @DisplayName("phone whose only bookings ended before the cutoff → link deleted")
        void should_deleteLink_when_phoneHasOnlyOldBookings() {
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofMinutes(-1)), PHONE, null);
            fx.staffWalkIn(provider, "COMPLETED", atCutoff(Duration.ofDays(-40)), PHONE);
            UUID link = activeLink(phoneHash, CHAT);

            service.anonymiseDue(cutoff);

            assertThat(rows.linkExists(link)).isFalse();
        }

        @Test
        @DisplayName("phone with one booking ending 1 min after the cutoff → link kept, old booking still anonymised")
        void should_keepLink_when_phoneHasOneRecentBooking() {
            UUID old = fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-3)), PHONE, null);
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofMinutes(1)), PHONE, null);
            UUID link = activeLink(phoneHash, CHAT);

            service.anonymiseDue(cutoff);

            assertThat(fx.booking(old).get("guest_pii_anonymised_at")).isNotNull();
            assertThat(rows.linkExists(link)).isTrue();
        }

        @Test
        @DisplayName("phone with an upcoming booking → link kept")
        void should_keepLink_when_phoneHasUpcomingBooking() {
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-3)), PHONE, null);
            fx.link(provider, "CONFIRMED", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2), PHONE, UUID.randomUUID());
            UUID link = activeLink(phoneHash, CHAT);

            service.anonymiseDue(cutoff);

            assertThat(rows.linkExists(link)).isTrue();
        }

        @Test
        @DisplayName("only the batch's freed phones lose their link; a phone not in the batch keeps its link")
        void should_leaveOtherPhonesLink_when_notInBatch() {
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-3)), PHONE, null);
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(5)), OTHER_PHONE, null);
            UUID link = activeLink(phoneHash, CHAT);
            UUID otherLink = activeLink(otherPhoneHash, OTHER_CHAT);

            service.anonymiseDue(cutoff);

            assertThat(rows.linkExists(link)).isFalse();
            assertThat(rows.linkExists(otherLink)).isTrue();
        }

        @Test
        @DisplayName("BLOCKED link of a freed phone is deleted too (D1 is status-blind)")
        void should_deleteBlockedLink_when_phoneFreedByRetention() {
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-3)), PHONE, null);
            UUID link = rows.link(phoneHash, CHAT, TelegramLinkStatus.BLOCKED, cutoff.plus(Duration.ofDays(200)));

            service.anonymiseDue(cutoff);

            assertThat(rows.linkExists(link)).isFalse();
        }

        @Test
        @DisplayName("batch size 1: the phone's first batch keeps the link (another booking still open), the last deletes it")
        void should_deleteLinkInLastBatch_when_phoneSpansBatches() {
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-9)), PHONE, null);
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-2)), PHONE, null);
            UUID link = activeLink(phoneHash, CHAT);

            GuestPiiAnonymisationResult first = service.anonymiseDue(cutoff, 1, 1);
            boolean afterFirst = rows.linkExists(link);
            service.anonymiseDue(cutoff, 1, 1);

            assertThat(first.bookingsAnonymised()).isEqualTo(1);
            assertThat(afterFirst).isTrue();
            assertThat(rows.linkExists(link)).isFalse();
        }
    }

    @Nested
    @DisplayName("D2 — BLOCKED links 12 months after status_changed_at")
    class BlockedPurge {

        @Test
        @DisplayName("BLOCKED 1 min before the cutoff → deleted; 1 min after → kept; old ACTIVE → kept")
        void should_purgeOnlyLongBlocked_when_straddlingCutoff() {
            UUID oldBlocked = rows.link(phoneHash, CHAT, TelegramLinkStatus.BLOCKED,
                    cutoff.minus(Duration.ofMinutes(1)));
            UUID youngBlocked = rows.link(otherPhoneHash, OTHER_CHAT, TelegramLinkStatus.BLOCKED,
                    cutoff.plus(Duration.ofMinutes(1)));
            UUID oldActive = rows.link(phoneHasher.hash("+380931234567").hash(), 777_000_333L,
                    TelegramLinkStatus.ACTIVE, cutoff.minus(Duration.ofDays(90)));

            telegramLinkRetentionService.purgeBlockedLinks(now, 500, 200);

            assertThat(rows.linkExists(oldBlocked)).isFalse();
            assertThat(rows.linkExists(youngBlocked)).isTrue();
            assertThat(rows.linkExists(oldActive)).isTrue();
        }

        @Test
        @DisplayName("purges a backlog larger than one batch")
        void should_purgeAcrossBatches_when_backlogExceedsBatch() {
            for (int i = 0; i < 5; i++) {
                rows.link(phoneHasher.hash("+38050000000" + i).hash(), 900L + i, TelegramLinkStatus.BLOCKED,
                        cutoff.minus(Duration.ofDays(i + 1)));
            }

            int deleted = telegramLinkRetentionService.purgeBlockedLinks(now, 2, 10);

            assertThat(deleted).isEqualTo(5);
            assertThat(rows.count("SELECT count(*) FROM telegram_links")).isZero();
        }

        @Test
        @DisplayName("the D2 period is a fixed 12 months, not retention-months")
        void should_useFixedTwelveMonths_when_purgingBlocked() {
            assertThat(TelegramLinkRetentionService.BLOCKED_RETENTION_MONTHS).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("D1 backstop — weekly orphan sweep")
    class OrphanBackstop {

        @Test
        @DisplayName("deletes a >1-day-old link whose phone has no open booking; keeps one with an open booking "
                + "and a fresh unbooked one")
        void should_deleteOnlyOldUnbookedLinks_when_backstopRuns() {
            fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(10)), OTHER_PHONE, null);
            UUID orphan = rows.link(phoneHash, CHAT, TelegramLinkStatus.ACTIVE, now.minus(Duration.ofDays(1))
                    .minusSeconds(60));
            UUID booked = rows.link(otherPhoneHash, OTHER_CHAT, TelegramLinkStatus.ACTIVE,
                    now.minus(Duration.ofDays(400)));
            UUID fresh = rows.link(phoneHasher.hash("+380931234567").hash(), 777_000_333L,
                    TelegramLinkStatus.ACTIVE, now.minus(Duration.ofHours(23)));

            int deleted = backstop.deleteOrphanLinks(now);

            assertThat(deleted).isEqualTo(1);
            assertThat(rows.linkExists(orphan)).isFalse();
            assertThat(rows.linkExists(booked)).isTrue();
            assertThat(rows.linkExists(fresh)).isTrue();
        }

        @Test
        @DisplayName("an anonymised-only phone (its last booking wiped by any path) loses its link")
        void should_deleteLink_when_phonesOnlyBookingsAreAnonymised() {
            UUID booking = fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-2)), PHONE, null);
            // The anonymiser's own SET clause, applied without the D1 link step (a path that bypassed it).
            jdbcTemplate.update("UPDATE bookings SET guest_name = ?, guest_surname = NULL, guest_phone = NULL, "
                    + "client_comment = NULL, client_cancellation_note = NULL, cancel_token_hash = NULL, "
                    + "guest_pii_anonymised_at = now() WHERE id = ?", GuestPii.ANONYMISED_NAME, booking);
            UUID lowestId = new UUID(0L, 1L); // below every random id: the keyset must start at the true minimum
            jdbcTemplate.update("UPDATE telegram_links SET id = ? WHERE id = ?", lowestId, activeLink(phoneHash, CHAT));

            backstop.deleteOrphanLinks(now);

            assertThat(rows.linkExists(lowestId)).isFalse();
        }
    }

    @Nested
    @DisplayName("Bookings are anonymised in place, never hard-deleted (the D1 link step relies on surviving rows)")
    class NeverHardDeletes {

        private int bookingRows() {
            return rows.count("SELECT count(*) FROM bookings");
        }

        @Test
        @DisplayName("retention run with a linked phone keeps every booking row (and its tokens); only PII is wiped")
        void should_keepEveryBookingRow_when_retentionUnlinksPhone() {
            UUID old = fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(-3)), PHONE, null);
            UUID walkIn = fx.staffWalkIn(provider, "COMPLETED", atCutoff(Duration.ofDays(-40)), PHONE);
            byte[] token = rows.token(old, phoneHash, TelegramTokenState.LINKED, now.plus(Duration.ofMinutes(20)));
            UUID link = activeLink(phoneHash, CHAT);
            int before = bookingRows();

            service.anonymiseDue(cutoff);

            assertThat(rows.linkExists(link)).as("D1 ran: the link is gone").isFalse();
            assertThat(bookingRows()).as("booking rows after retention").isEqualTo(before);
            assertThat(fx.booking(old).get("guest_phone")).isNull();
            assertThat(fx.booking(old).get("guest_pii_anonymised_at")).isNotNull();
            assertThat(fx.booking(walkIn).get("guest_pii_anonymised_at")).isNotNull();
            assertThat(rows.tokenExists(token))
                    .as("token rows cascade only on a booking delete, so a surviving token proves the row survived")
                    .isTrue();
        }

        @Test
        @DisplayName("erase keeps every booking row of the phone; only PII, the link and the tokens are removed")
        void should_keepEveryBookingRow_when_erasingByPhone() {
            UUID past = fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(60)), PHONE, null);
            UUID upcoming = fx.link(provider, "CONFIRMED", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2), PHONE,
                    UUID.randomUUID());
            UUID link = activeLink(phoneHash, CHAT);
            int before = bookingRows();

            service.eraseByPhone(PHONE, "SUP-409-ROWS");

            assertThat(rows.linkExists(link)).isFalse();
            assertThat(bookingRows()).as("booking rows after erase").isEqualTo(before);
            assertThat(fx.booking(past).get("guest_phone")).isNull();
            assertThat(fx.booking(upcoming).get("guest_phone")).isNull();
        }
    }

    @Nested
    @DisplayName("D3 — erase by phone")
    class Erase {

        @Test
        @DisplayName("erase deletes the phone's link and every token (any state, any age); other phone untouched; "
                + "logs counts only")
        void should_deleteLinkAndAllTokens_when_erasingByPhone(CapturedOutput output) {
            UUID booking = fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(60)), PHONE, null);
            UUID otherBooking = fx.link(provider, "COMPLETED", atCutoff(Duration.ofDays(30)), OTHER_PHONE, null);
            Instant soon = Instant.now().plus(Duration.ofMinutes(20));
            for (TelegramTokenState state : TelegramTokenState.values()) {
                rows.token(booking, phoneHash, state, soon);
            }
            rows.token(booking, phoneHash, TelegramTokenState.LINKED, Instant.now().minus(Duration.ofDays(1)));
            UUID link = activeLink(phoneHash, CHAT);
            UUID otherLink = activeLink(otherPhoneHash, OTHER_CHAT);
            byte[] otherToken = rows.token(otherBooking, otherPhoneHash, TelegramTokenState.ISSUED, soon);

            service.eraseByPhone(PHONE_LOCAL, "SUP-409");

            assertThat(rows.linkExists(link)).isFalse();
            assertThat(rows.tokensOfPhone(phoneHash)).isZero();
            assertThat(rows.linkExists(otherLink)).isTrue();
            assertThat(rows.tokenExists(otherToken)).isTrue();
            assertThat(output.getAll())
                    .contains("deleted 0 OTP rows, 1 Telegram links, 5 Telegram link tokens")
                    .doesNotContain(PHONE)
                    .doesNotContain(PHONE_LOCAL)
                    .doesNotContain(HexFormat.of().formatHex(phoneHash))
                    .doesNotContain(Long.toString(CHAT));
        }

        @Test
        @DisplayName("erase of a phone without Telegram data succeeds and deletes nothing else")
        void should_succeed_when_phoneHasNoTelegramData() {
            UUID otherLink = activeLink(otherPhoneHash, OTHER_CHAT);

            GuestPiiAnonymisationResult result = service.eraseByPhone(PHONE, null);

            assertThat(result.bookingsAnonymised()).isZero();
            assertThat(rows.linkExists(otherLink)).isTrue();
        }
    }
}
