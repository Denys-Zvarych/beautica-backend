package com.beautica.notification.telegram.link;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Outcome of {@link TelegramLinkService#proveContactForBooking}: the {@link ProofResult} plus, on
 * {@link ProofResult#LINKED} only, the booking the proven token was issued for and the proven phone's hash (so the
 * details follow-up can re-check that the booking still belongs to that phone).
 *
 * @param result    the proof outcome
 * @param bookingId the token's booking on {@code LINKED}; {@code null} for every other result
 * @param phoneHash the proven phone's {@code PhoneHasher} HMAC on {@code LINKED}; {@code null} otherwise
 */
public record ContactProof(ProofResult result, UUID bookingId, byte[] phoneHash) {

    public ContactProof {
        Objects.requireNonNull(result, "result");
        boolean linked = result == ProofResult.LINKED;
        if (linked != (bookingId != null) || linked != (phoneHash != null)) {
            throw new IllegalArgumentException("bookingId and phoneHash are set exactly when the result is LINKED");
        }
        phoneHash = phoneHash == null ? null : phoneHash.clone();
    }

    public static ContactProof of(ProofResult result) {
        return new ContactProof(result, null, null);
    }

    public static ContactProof linked(UUID bookingId, byte[] phoneHash) {
        return new ContactProof(ProofResult.LINKED, bookingId, phoneHash);
    }

    /** The linked booking; empty unless {@link #result()} is {@code LINKED}. */
    public Optional<UUID> linkedBookingId() {
        return Optional.ofNullable(bookingId);
    }

    /** Defensive copy of the proven phone hash; {@code null} unless {@link #result()} is {@code LINKED}. */
    @Override
    public byte[] phoneHash() {
        return phoneHash == null ? null : phoneHash.clone();
    }

    /** Never prints the phone hash. */
    @Override
    public String toString() {
        return "ContactProof[result=" + result + "]";
    }
}
