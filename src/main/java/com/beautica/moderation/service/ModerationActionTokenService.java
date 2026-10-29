package com.beautica.moderation.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.moderation.config.ModerationActionLinkProperties;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.entity.ModerationActionToken;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.repository.ModerationActionTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 414 — the transactional steps behind the e-mailed moderation links, split from the non-transactional
 * {@link ModerationActionLinkService} facade so every step runs in its own transaction through the Spring proxy (no
 * self-invocation). Tokens and nonces are 32 random bytes, base64url ({@link TokenGenerator#generateToken()}); only
 * their {@link TokenGenerator#hash lower-hex SHA-256} is stored. Nothing here logs a token, a nonce or a hash.
 */
@Service
@RequiredArgsConstructor
public class ModerationActionTokenService {

    /** D8: how long a confirm page's nonce stays valid. */
    static final Duration NONCE_TTL = Duration.ofMinutes(30);

    private final ModerationActionTokenRepository repository;
    private final ModerationService moderationService;
    private final TokenGenerator tokenGenerator;
    private final ModerationActionLinkProperties properties;
    private final Clock clock;

    /** A claimed (now used) token: what the POST must apply, and the claim instant {@link #releaseClaim} matches. */
    public record ClaimedToken(ReportTargetType targetType, UUID targetId, ModerationAction action,
                               Instant claimedAt) {}

    /** A live token whose confirm page may render: the fresh RAW nonce plus the summary the page shows. */
    public record IssuedNonce(String nonce, ReportTargetType targetType, ModerationAction action, long openReports) {}

    /** Result of opening a confirm page: either a nonce was issued, or the reason none was. */
    public sealed interface ConfirmPageState permits Issued, Unusable, NothingOpen {}

    public record Issued(IssuedNonce nonce) implements ConfirmPageState {}

    /** Unknown, used, revoked or expired token. */
    public record Unusable() implements ConfirmPageState {}

    /** The token is live but its target has no OPEN report left. */
    public record NothingOpen() implements ConfirmPageState {}

    /**
     * D4/D5: one row per action in {@code actions}, each with a fresh raw token, inserted in one {@code saveAll}
     * (each row is {@code Persistable#isNew}, so no SELECT-before-insert). Runs on {@code reportEmailExecutor}, where
     * REQUIRES_NEW is just a new transaction (no outer one to suspend); it stays correct when that executor is
     * synchronous (tests) and the call happens in the reporter's after-commit phase.
     *
     * @return raw tokens by action — the only place raw tokens ever exist outside the e-mail
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<ModerationAction, String> mint(ReportTargetType targetType, UUID targetId,
                                              List<ModerationAction> actions) {
        Instant now = now();
        Instant expiresAt = now.plus(properties.actionLinkTtl());
        Map<ModerationAction, String> rawTokens = new EnumMap<>(ModerationAction.class);
        List<ModerationActionToken> rows = new ArrayList<>(actions.size());
        for (ModerationAction action : actions) {
            String raw = tokenGenerator.generateToken();
            rows.add(ModerationActionToken.mint(targetType, targetId, action, tokenGenerator.hash(raw), expiresAt, now));
            rawTokens.put(action, raw);
        }
        repository.saveAll(rows);
        return rawTokens;
    }

    /** D7/D8: issues (or rotates) the confirm nonce of a live token whose target still has an OPEN report. */
    @Transactional
    public ConfirmPageState issueNonce(String rawToken) {
        Instant now = now();
        String tokenHash = tokenGenerator.hash(rawToken);
        Optional<ModerationActionToken> found = repository.findByTokenHash(tokenHash);
        if (found.isEmpty() || !found.get().isLiveAt(now)) {
            return new Unusable();
        }
        ModerationActionToken token = found.get();
        long open = moderationService.countOpenReports(token.getTargetType(), token.getTargetId());
        if (open == 0) {
            return new NothingOpen();
        }
        String nonce = tokenGenerator.generateToken();
        if (repository.setNonce(tokenHash, tokenGenerator.hash(nonce), now.plus(NONCE_TTL), now) == 0) {
            return new Unusable();
        }
        return new Issued(new IssuedNonce(nonce, token.getTargetType(), token.getAction(), open));
    }

    /**
     * D9 step 1, in its OWN transaction so a later 409 from resolve cannot roll the claim back (or poison a shared
     * transaction). The nonce is compared in constant time (D4) before the conditional single-use UPDATE, which
     * re-checks every condition atomically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedToken> claim(String rawToken, String rawNonce) {
        Instant now = now();
        String tokenHash = tokenGenerator.hash(rawToken);
        String nonceHash = tokenGenerator.hash(rawNonce);
        Optional<ModerationActionToken> found = repository.findByTokenHash(tokenHash);
        if (found.isEmpty() || !nonceMatches(found.get().getConfirmNonceHash(), nonceHash)) {
            return Optional.empty();
        }
        if (repository.claim(tokenHash, nonceHash, now) == 0) {
            return Optional.empty();
        }
        ModerationActionToken token = found.get();
        return Optional.of(new ClaimedToken(token.getTargetType(), token.getTargetId(), token.getAction(), now));
    }

    /**
     * Audit-fix: releases a claim after an unexpected (non-409) failure of the resolve that followed it, in its own
     * transaction, so the link can be retried (the consumed nonce is not restored: the next GET issues a fresh one).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(String rawToken, ClaimedToken claim) {
        repository.releaseClaim(tokenGenerator.hash(rawToken), claim.claimedAt());
    }

    private static boolean nonceMatches(String storedHash, String presentedHash) {
        return storedHash != null && MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.US_ASCII), presentedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
