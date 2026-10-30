package com.beautica.notification.telegram.link;

import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.dto.GuestCancelTokenTarget;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.GuestCancelTokenResolver;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.TelegramLinkRateLimitedException;
import com.beautica.config.RateLimitConfig;
import com.beautica.config.TelegramProperties;
import com.beautica.notification.telegram.link.dto.GuestTelegramLinkResponse;
import com.beautica.notification.telegram.link.dto.GuestTelegramStatusResponse;
import com.beautica.notification.telegram.link.dto.TelegramAvailability;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 407 — the guest Telegram link API behind {@link GuestTelegramLinkController}: resolves the booking from its
 * cancel token ({@link GuestCancelTokenResolver}, hash-at-rest, hashed ONCE here), applies D2 eligibility, and
 * delegates the link lifecycle to {@link TelegramLinkService}. The client never sends or receives a phone (D1).
 *
 * <p><b>404 everywhere (D2).</b> Channel unavailable, unknown / consumed token, APP booking, anonymised phone, and —
 * for link-token — a booking that is not CONFIRMED or already started: one {@link NotFoundException}, the same body as
 * every other 404, so the endpoints are no state oracle.
 *
 * <p><b>Transactions.</b> link-token: ONE read-only transaction for the token lookup + the ACTIVE check, then
 * {@link TelegramLinkService#issueForBooking} (3 s {@code lock_timeout} + per-phone advisory lock) in its own — never
 * inside a booking transaction. The eligibility verdict is not re-checked inside the issue transaction: the link is
 * per phone and chat ownership is proven by the contact share (406), so a booking cancelled in between only leaves a
 * token that can link that same phone (accepted, 407 Decisions). status: one read-only transaction. unlink: the
 * lookup, then the fused 3 s {@code lock_timeout} delete. A {@code 55P03} surfaces as the global 409.
 *
 * <p><b>Availability</b> = flag on AND a bot username: {@code issueForBooking} cannot build a deep link without one
 * (capture mode may run with it blank). unlink needs the flag only, so a guest can always return to SMS.
 *
 * <p><b>Logging (D7).</b> {@code event=telegram_link_api op=… result=…} plus the booking id when one resolved (404s at
 * DEBUG); never the token, url, phone or chat id.
 */
@Slf4j
@Service
public class GuestTelegramLinkService {

    private final GuestCancelTokenResolver tokenResolver;
    private final TelegramLinkService linkService;
    private final TelegramProperties properties;
    private final LoadingCache<String, Bucket> tokenBuckets;
    private final TransactionTemplate readOnlyTx;
    private final Clock clock;

    public GuestTelegramLinkService(
            GuestCancelTokenResolver tokenResolver,
            TelegramLinkService linkService,
            TelegramProperties properties,
            @Qualifier("telegramLinkTokenBuckets") LoadingCache<String, Bucket> tokenBuckets,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.tokenResolver = tokenResolver;
        this.linkService = linkService;
        this.properties = properties;
        this.tokenBuckets = tokenBuckets;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.clock = clock;
    }

    /** D3 link-token: a fresh deep link, or {@code alreadyLinked} when the booking phone has an ACTIVE link. */
    public GuestTelegramLinkResponse issueLinkToken(String cancelToken) {
        requireLinkable("issue");
        String tokenHash = CancelTokenHasher.hash(UUID.fromString(cancelToken));
        consumeTokenBudget(tokenHash);
        IssueCheck check = Objects.requireNonNull(readOnlyTx.execute(status -> checkIssuable(tokenHash)));
        if (check.alreadyLinked()) {
            log.info("event=telegram_link_api op=issue result=ALREADY_LINKED bookingId={}", check.target().bookingId());
            return GuestTelegramLinkResponse.linked();
        }
        GuestCancelTokenTarget target = check.target();
        IssuedLink link = linkService.issueForBooking(target.bookingId(), target.guestPhone());
        log.info("event=telegram_link_api op=issue result=ISSUED bookingId={}", target.bookingId());
        return GuestTelegramLinkResponse.issued(link);
    }

    /** D3/D4 status: any booking state; {@code offerChoice} only for a walk-in (STAFF) not yet linked. */
    @Transactional(readOnly = true)
    public GuestTelegramStatusResponse status(String cancelToken) {
        requireLinkable("status");
        GuestCancelTokenTarget target = resolveEligible(hashOf(cancelToken), "status");
        boolean linked = linkService.statusForPhone(target.guestPhone()) == LinkStatus.ACTIVE;
        boolean offerChoice = target.source() == BookingSource.STAFF && !linked;
        log.info("event=telegram_link_api op=status result={} bookingId={}", linked ? "LINKED" : "NOT_LINKED",
                target.bookingId());
        return new GuestTelegramStatusResponse(true, linked, offerChoice);
    }

    /** D3 unlink: deletes the booking phone's link (back to SMS); idempotent. Gated on the flag only. */
    public void unlink(String cancelToken) {
        if (!properties.enabled()) {
            throw notFound("unlink");
        }
        GuestCancelTokenTarget target = resolveEligible(hashOf(cancelToken), "unlink");
        boolean removed = linkService.unlinkPhone(target.guestPhone());
        log.info("event=telegram_link_api op=unlink result={} bookingId={}", removed ? "UNLINKED" : "NOT_LINKED",
                target.bookingId());
    }

    /**
     * D6 — the {@code telegram} block of a guest booking response for the booker's OTP-verified phone. Called by
     * {@code GuestBookingService} AFTER the booking transaction committed; a data-access or transaction-start failure
     * degrades to {@link TelegramAvailability#UNAVAILABLE} (the website then simply hides the choice). No query while
     * unavailable.
     */
    public TelegramAvailability availabilityForPhone(String guestPhoneE164) {
        if (!isLinkable()) {
            return TelegramAvailability.UNAVAILABLE;
        }
        try {
            return new TelegramAvailability(true, linkService.statusForPhone(guestPhoneE164) == LinkStatus.ACTIVE);
        } catch (DataAccessException | TransactionException e) {
            // TransactionException: the read's own read-only transaction could not even start (e.g. pool exhausted →
            // CannotCreateTransactionException). The booking is already committed; never turn that into a 500.
            log.warn("event=telegram_link_api op=availability result=READ_FAILED cause={}",
                    e.getClass().getSimpleName());
            return TelegramAvailability.UNAVAILABLE;
        }
    }

    /** Runs inside {@link #readOnlyTx}: eligibility + CONFIRMED / future start + the ACTIVE check. */
    private IssueCheck checkIssuable(String tokenHash) {
        GuestCancelTokenTarget target = resolveEligible(tokenHash, "issue");
        if (target.status() != BookingStatus.CONFIRMED || !target.startsAt().isAfter(OffsetDateTime.now(clock))) {
            throw notFound("issue");
        }
        return new IssueCheck(target, linkService.statusForPhone(target.guestPhone()) == LinkStatus.ACTIVE);
    }

    private boolean isLinkable() {
        return properties.enabled() && !properties.botUsername().isEmpty();
    }

    private void requireLinkable(String op) {
        if (!isLinkable()) {
            throw notFound(op);
        }
    }

    private static String hashOf(String cancelToken) {
        return CancelTokenHasher.hash(UUID.fromString(cancelToken));
    }

    /** D2 — a LINK booking, or a STAFF walk-in (412: it holds a cancel token only with a guest phone); phone kept. */
    private GuestCancelTokenTarget resolveEligible(String tokenHash, String op) {
        GuestCancelTokenTarget target = tokenResolver.resolveByHash(tokenHash).orElseThrow(() -> notFound(op));
        boolean eligibleSource = target.source() == BookingSource.LINK || target.source() == BookingSource.STAFF;
        if (!eligibleSource || target.guestPhone() == null || target.guestPhone().isBlank()) {
            throw notFound(op);
        }
        return target;
    }

    /** D5 — 3 / 10 min per cancel-token hash; keyed on the at-rest hash, never the raw token. */
    private void consumeTokenBudget(String tokenHash) {
        ConsumptionProbe probe = tokenBuckets.get(tokenHash).tryConsumeAndReturnRemaining(1L);
        if (!probe.isConsumed()) {
            log.info("event=telegram_link_api op=issue result=RATE_LIMITED");
            throw new TelegramLinkRateLimitedException(retryAfterSeconds(probe));
        }
    }

    private static long retryAfterSeconds(ConsumptionProbe probe) {
        long nanos = probe.getNanosToWaitForRefill();
        if (nanos <= 0 || nanos == Long.MAX_VALUE) {
            return RateLimitConfig.TELEGRAM_LINK_WINDOW.toSeconds();
        }
        return Math.max(1L, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    private static NotFoundException notFound(String op) {
        log.debug("event=telegram_link_api op={} result=NOT_FOUND", op);
        return new NotFoundException("Telegram link target not found");
    }

    private record IssueCheck(GuestCancelTokenTarget target, boolean alreadyLinked) {
    }
}
