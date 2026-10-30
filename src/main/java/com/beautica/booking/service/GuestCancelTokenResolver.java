package com.beautica.booking.service;

import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.dto.GuestCancelTokenTarget;
import com.beautica.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 407 — resolves a guest cancel token to the facts the guest Telegram link API needs, over the SAME hash-at-rest
 * columns and partial-unique indexes the public cancel page uses ({@link CancelTokenHasher}). No parallel token scheme,
 * no status filter: eligibility is the caller's rule. Scalar projections only (phase 407 perf audit) — no entity graph.
 */
@Service
@RequiredArgsConstructor
public class GuestCancelTokenResolver {

    private final BookingRepository bookingRepository;

    /** {@link #resolveByHash} for a raw token. */
    @Transactional(readOnly = true)
    public Optional<GuestCancelTokenTarget> resolve(UUID cancelToken) {
        return resolveByHash(CancelTokenHasher.hash(cancelToken));
    }

    /**
     * Visit header first, then a standalone booking — the order {@link BookingCancellationService#getInfo} applies.
     * A standalone token therefore costs two index probes (the visit miss, then the hit); kept for parity with
     * {@code getInfo}.
     *
     * @param cancelTokenHash the {@link CancelTokenHasher#hash} of the token, computed once by the caller
     * @return the token's visit / booking, or empty when no row carries the hash (unknown, consumed, or anonymised)
     */
    @Transactional(readOnly = true)
    public Optional<GuestCancelTokenTarget> resolveByHash(String cancelTokenHash) {
        return bookingRepository.findGuestVisitTokenTargetByCancelTokenHash(cancelTokenHash)
                .or(() -> bookingRepository.findGuestTokenTargetByCancelTokenHash(cancelTokenHash));
    }
}
