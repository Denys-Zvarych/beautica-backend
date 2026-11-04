package com.beautica.booking.service;

import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Phase 435 — the guest's OWN booking page {@code {FRONTEND_BASE_URL}/book/cancel/{token}}, rebuilt after create from
 * the sealed token (V230, {@link GuestCancelTokenCipher}) for the provider-cancellation, reschedule and reminder SMS.
 *
 * <p><b>Which token.</b> A booking inside a visit links the VISIT header's token — the only one any lookup accepts
 * (a LINK child's own token is never resolvable, and a STAFF child has none). A standalone booking links its own.
 *
 * <p><b>Empty</b> when there is nothing to link: a row created before V230 (no sealed copy — no backfill), a consumed
 * or anonymised token (the sealed copy is nulled with the hash), or a sealed value that does not open (wrong key,
 * corruption). The last case logs the booking id and the exception class only — never the token, the sealed value
 * or the exception message. The URL itself is never logged.
 *
 * <p>A visit header that is not loaded is read by one scalar primary-key probe, never by touching the proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestBookingPageLinks {

    private final GuestCancelTokenCipher cipher;
    private final GuestCancelUrlBuilder urlBuilder;
    private final AppointmentRepository appointmentRepository;

    /** The guest's own booking page for {@code booking}, or empty when it cannot be built (see class doc). */
    public Optional<String> forBooking(Booking booking) {
        if (booking == null) {
            return Optional.empty();
        }
        Optional<SealedCopy> sealed = sealedToken(booking);
        if (sealed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(urlBuilder.build(cipher.open(sealed.get().value(), sealed.get().row())));
        } catch (IllegalStateException e) {
            log.warn("Guest booking page link dropped: sealed cancel token did not open, bookingId={} ({})",
                    booking.getId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * A visit's header token: read off the header when it is loaded, else by one primary-key scalar probe — never
     * by initialising a proxy, which would throw outside a session (the outbox drain runs non-transactionally).
     * Audit-fix: returned with the row it was read from, the AES-GCM associated data it must open against.
     */
    private Optional<SealedCopy> sealedToken(Booking booking) {
        Appointment visit = booking.getAppointment();
        if (visit == null) {
            return copyOf(booking.getCancelTokenSealed(), booking.getId(), GuestCancelTokenCipher.Row::booking);
        }
        if (Hibernate.isInitialized(visit)) {
            return copyOf(visit.getCancelTokenSealed(), visit.getId(), GuestCancelTokenCipher.Row::appointment);
        }
        return appointmentRepository.findCancelTokenSealedById(visit.getId())
                .map(value -> new SealedCopy(value, GuestCancelTokenCipher.Row.appointment(visit.getId())));
    }

    /** No row id (never persisted) → no link: nothing could have been sealed for it. */
    private static Optional<SealedCopy> copyOf(String value, UUID id,
                                               Function<UUID, GuestCancelTokenCipher.Row> row) {
        if (value == null || id == null) {
            return Optional.empty();
        }
        return Optional.of(new SealedCopy(value, row.apply(id)));
    }

    private record SealedCopy(String value, GuestCancelTokenCipher.Row row) {
    }
}
