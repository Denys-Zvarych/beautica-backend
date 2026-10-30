package com.beautica.notification.telegram.link;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phase 405 — Telegram link lifecycle for guest phones: issue a deep-link token, claim it from a chat, prove the chat
 * owns the booking's phone by a contact share, and the link-row operations phases 406–409 call.
 *
 * <p><b>Data handling.</b> The raw token lives only in the returned {@link IssuedLink}; only its SHA-256 is stored.
 * The contact phone is normalised, hashed, compared once in constant time, then dropped: never stored, logged or
 * returned. Log lines carry the operation and result only, never a phone, chat id or token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramLinkService {

    /** D6 — at most this many live tokens per phone; issuing one more deletes the oldest. */
    static final int MAX_LIVE_TOKENS_PER_PHONE = 5;
    /** D6 — 32 random bytes, base64url without padding. */
    static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final String DEEP_LINK = "https://t.me/%s?start=%s";
    /** Cheap ceiling before scanning a Telegram-supplied phone string. */
    private static final int MAX_CONTACT_PHONE_LENGTH = 32;
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    private final TelegramLinkRepository linkRepository;
    private final TelegramLinkTokenRepository tokenRepository;
    private final PhoneHasher phoneHasher;
    private final TokenGenerator tokenGenerator;
    private final TelegramProperties properties;
    private final Clock clock;

    @Transactional
    public IssuedLink issueForBooking(UUID bookingId, String guestPhoneE164) {
        if (properties.botUsername().isEmpty()) {
            throw new IllegalStateException("app.telegram.bot-username is not configured");
        }
        byte[] phoneHash = phoneHasher.hash(guestPhoneE164).hash();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.linkTokenTtl());
        String rawToken = tokenGenerator.generateToken();
        tokenRepository.acquirePhoneIssueLock(phoneHash);
        tokenRepository.deleteLiveBeyondNewest(phoneHash, now, MAX_LIVE_TOKENS_PER_PHONE - 1);
        tokenRepository.save(TelegramLinkToken.issue(hashToken(rawToken), bookingId, phoneHash, now, expiresAt));
        log.info("event=telegram_link op=issue result=ISSUED");
        return new IssuedLink(DEEP_LINK.formatted(properties.botUsername(), rawToken), expiresAt);
    }

    @Transactional
    public ClaimResult claim(String rawToken, long chatId) {
        ClaimResult result = doClaim(rawToken, chatId);
        log.info("event=telegram_link op=claim result={}", result);
        return result;
    }

    private ClaimResult doClaim(String rawToken, long chatId) {
        if (rawToken == null || !TOKEN_SHAPE.matcher(rawToken).matches()) {
            return ClaimResult.INVALID;
        }
        tokenRepository.acquireChatClaimLock(chatId);
        byte[] tokenHash = hashToken(rawToken);
        List<TelegramLinkToken> locked = tokenRepository.lockClaimRows(tokenHash, chatId);
        TelegramLinkToken token = locked.stream().filter(t -> t.hasTokenHash(tokenHash)).findFirst().orElse(null);
        Instant now = clock.instant();
        if (token == null) {
            return ClaimResult.INVALID;
        }
        if (token.getState() == TelegramTokenState.LINKED && token.isClaimedBy(chatId)) {
            return linkRepository.existsByPhoneHashAndChatIdAndStatus(
                    token.getPhoneHash(), chatId, TelegramLinkStatus.ACTIVE)
                    ? ClaimResult.ALREADY_LINKED : ClaimResult.INVALID;
        }
        boolean claimable = token.getState() == TelegramTokenState.ISSUED
                || (token.getState() == TelegramTokenState.CLAIMED && token.isClaimedBy(chatId));
        if (!claimable || token.isExpired(now)) {
            return ClaimResult.INVALID;
        }
        // D7: a chat holds at most one claim — release the others, all already locked above.
        locked.stream().filter(t -> t != token && t.getState() == TelegramTokenState.CLAIMED && t.isClaimedBy(chatId))
                .forEach(TelegramLinkToken::release);
        token.claim(chatId, now);
        return ClaimResult.CLAIMED;
    }

    @Transactional
    public ProofResult proveContact(long chatId, String contactPhone, Long contactUserId, long fromId) {
        ProofResult result = doProve(chatId, contactPhone, contactUserId, fromId);
        log.info("event=telegram_link op=prove result={}", result);
        return result;
    }

    private ProofResult doProve(long chatId, String contactPhone, Long contactUserId, long fromId) {
        // Own contact only (not a forwarded card); and in a private chat chat.id == from.id, so any other pairing is
        // not a guest's own private chat (defence in depth behind 406's chat.type filter).
        if (contactUserId == null || contactUserId != fromId || chatId != fromId) {
            return ProofResult.NOT_OWN_CONTACT;
        }
        Instant now = clock.instant();
        Optional<TelegramLinkToken> pending = tokenRepository.findPendingClaimForUpdate(chatId, now);
        if (pending.isEmpty()) {
            return ProofResult.NO_PENDING;
        }
        TelegramLinkToken token = pending.get();
        if (!token.phoneMatches(hashContactPhone(contactPhone))) {
            token.finish(TelegramTokenState.MISMATCH, now);
            return ProofResult.MISMATCH;
        }
        token.finish(TelegramTokenState.LINKED, now);
        linkRepository.upsert(UUID.randomUUID(), token.getPhoneHash(), chatId, now);
        return ProofResult.LINKED;
    }

    @Transactional
    public int unlinkChat(long chatId) {
        return linkRepository.deleteByChat(chatId);
    }

    @Transactional
    public boolean unlinkPhone(String phoneE164) {
        return phoneHasher.tryHash(phoneE164)
                .map(phone -> linkRepository.deleteByPhone(phone.hash()) > 0)
                .orElse(false);
    }

    @Transactional
    public void markBlocked(long chatId) {
        linkRepository.transitionByChat(chatId, TelegramLinkStatus.ACTIVE, TelegramLinkStatus.BLOCKED,
                clock.instant());
    }

    /** Phase 408 calls this after a failed send inside its own flow, so it commits on its own (REQUIRES_NEW). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBlocked(UUID linkId) {
        linkRepository.transitionById(linkId, TelegramLinkStatus.ACTIVE, TelegramLinkStatus.BLOCKED,
                clock.instant());
    }

    /** BLOCKED → ACTIVE only; a chat without a link stays unlinked. */
    @Transactional
    public void reactivate(long chatId) {
        linkRepository.transitionByChat(chatId, TelegramLinkStatus.BLOCKED, TelegramLinkStatus.ACTIVE,
                clock.instant());
    }

    @Transactional
    public void touchLastSent(UUID linkId) {
        linkRepository.touchLastSent(linkId, clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<ActiveLink> findActiveForPhone(String phoneE164) {
        return findLink(phoneE164)
                .filter(link -> link.getStatus() == TelegramLinkStatus.ACTIVE)
                .map(link -> new ActiveLink(link.getId(), link.getChatId()));
    }

    @Transactional(readOnly = true)
    public LinkStatus statusForPhone(String phoneE164) {
        return findLink(phoneE164)
                .map(link -> link.getStatus() == TelegramLinkStatus.ACTIVE ? LinkStatus.ACTIVE : LinkStatus.BLOCKED)
                .orElse(LinkStatus.NOT_LINKED);
    }

    private Optional<TelegramLink> findLink(String phoneE164) {
        return phoneHasher.tryHash(phoneE164).flatMap(phone -> linkRepository.findByPhoneHash(phone.hash()));
    }

    /** D7 — strip everything but digits, prefix {@code +}, keyed hash; {@code null} when it does not parse. */
    private byte[] hashContactPhone(String contactPhone) {
        if (contactPhone == null || contactPhone.length() > MAX_CONTACT_PHONE_LENGTH) {
            return null;
        }
        String digits = NON_DIGITS.matcher(contactPhone).replaceAll("");
        if (digits.isEmpty()) {
            return null;
        }
        return phoneHasher.tryHash("+" + digits).map(PhoneHasher.HashedPhone::hash).orElse(null);
    }

    /** Same digest as every other stored token in the codebase ({@link TokenGenerator#hash}), as raw bytes. */
    private byte[] hashToken(String rawToken) {
        return HexFormat.of().parseHex(tokenGenerator.hash(rawToken));
    }
}
