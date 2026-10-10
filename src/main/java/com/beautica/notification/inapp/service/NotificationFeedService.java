package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.notification.inapp.dto.MarkAllReadResponse;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.UnreadCountResponse;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read path for the in-app notification feed (phase 334) — {@code NotificationFeedController}'s
 * only collaborator. No SQL here; every query lives in {@link InAppNotificationRepository} or
 * {@link NotificationViewAssembler}'s batched collaborators.
 */
@Service
@RequiredArgsConstructor
public class NotificationFeedService {

    /**
     * The wire-level cap on {@link UnreadCountResponse#count()} — see
     * {@link InAppNotificationRepository#countUnreadCapped} for the matching SOURCE-side LIMIT
     * that keeps a recipient with an unbounded backlog from paying for more than a 100-row scan.
     */
    static final int MAX_UNREAD_COUNT = 99;

    /**
     * Audit-fix cycle 1, finding 3 (LOW, security) — {@code upTo} was unbounded, so a caller could
     * mark every item read arbitrarily far into the future (pre-empting notifications that have not
     * been written yet, or simply an oversized/garbage timestamp reaching the DB as a valid but
     * absurd cutoff). A small forward tolerance (rather than rejecting every future value outright)
     * absorbs ordinary client/server clock skew for the legitimate "now" default and near-now values
     * a real client sends; anything further out is rejected. Past values are always fine — the
     * cutoff only ever narrows what gets marked read.
     */
    static final Duration MAX_UP_TO_CLOCK_SKEW = Duration.ofMinutes(5);

    private final InAppNotificationRepository repository;
    private final NotificationViewAssembler assembler;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listFeed(UUID recipientId, Role recipientRole, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<InAppNotification> result =
                repository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(recipientId, pageable);
        List<NotificationResponse> content = assembler.assemble(result.getContent(), recipientId, recipientRole);
        return PageResponse.of(
                content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(UUID recipientId) {
        long capped = repository.countUnreadCapped(recipientId);
        return new UnreadCountResponse((int) Math.min(capped, MAX_UNREAD_COUNT));
    }

    /**
     * Marks one notification read. Idempotent: an already-read row is a no-op success, never a
     * conflict. Missing id OR an id belonging to someone else are indistinguishable on the wire
     * (both 404 via {@link NotFoundException}) — see {@link InAppNotificationRepository#markRead}'s
     * javadoc: this is the deliberate no-enumeration-oracle contract, not an accident.
     */
    @Transactional
    public void markRead(UUID recipientId, UUID notificationId) {
        int updated = repository.markRead(notificationId, recipientId, clock.instant());
        if (updated == 1) {
            return;
        }
        if (repository.existsByIdAndRecipientUserId(notificationId, recipientId)) {
            return; // already read — idempotent success
        }
        throw new NotFoundException("Notification not found");
    }

    /**
     * Marks every still-unread item created at or before {@code upTo} read. {@code upTo} defaults
     * to "now" when null — the client's page-load cutoff, so an item that arrives after the feed
     * was opened stays unread.
     *
     * <p>Audit-fix cycle 1, finding 3 — {@code upTo} more than {@link #MAX_UP_TO_CLOCK_SKEW} ahead
     * of the server clock is rejected with a 400 via {@link BusinessException} (the existing
     * {@code handleBusiness} → {@code GlobalExceptionHandler} path, same as every other domain
     * validation failure); a past value is always accepted.
     */
    @Transactional
    public MarkAllReadResponse markAllRead(UUID recipientId, Instant upTo) {
        Instant now = clock.instant();
        if (upTo != null && upTo.isAfter(now.plus(MAX_UP_TO_CLOCK_SKEW))) {
            throw new BusinessException("upTo must not be far in the future");
        }
        Instant cutoff = upTo != null ? upTo : now;
        int updated = repository.markAllRead(recipientId, cutoff, now);
        return new MarkAllReadResponse(updated);
    }
}
