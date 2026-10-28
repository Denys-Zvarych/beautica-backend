package com.beautica.moderation.service;

import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.review.service.ReviewModerationService.LockedReview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Phase 396 audit-fix D20 — per-provider serialisation of moderation writes on reviews. Takes a transaction-scoped
 * advisory lock on the review's master AND (when present) its salon, because every provider-wide rule (burst count,
 * auto-hide cap) matches "same master OR same salon".
 *
 * <p><b>Lock order</b> (deadlock freedom): always AFTER the review's own row lock ({@code lockReview}), and the provider
 * keys in a fixed (lexicographic) order. Intake and resolve both follow it.
 */
@Component
@RequiredArgsConstructor
public class ProviderModerationLock {

    /** Namespace of the two-int advisory key space — the phase number, so it cannot collide with another feature. */
    static final int NAMESPACE = 396;

    private final ContentReportRepository contentReportRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(LockedReview review) {
        Stream.of(key("master", review.masterId()), key("salon", review.salonId()))
                .filter(Objects::nonNull)
                .sorted()
                .forEach(key -> contentReportRepository.lockProviderKey(NAMESPACE, key));
    }

    private static String key(String kind, UUID id) {
        return id == null ? null : kind + ":" + id;
    }
}
