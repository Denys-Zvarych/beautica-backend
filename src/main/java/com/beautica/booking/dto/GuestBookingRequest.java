package com.beautica.booking.dto;

import com.beautica.booking.service.SlotCalculationService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import com.beautica.common.validation.PolicyVersionDate;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/book/{slug}/booking} (Phase 13.3).
 *
 * <p>The phone is deliberately NOT in the body — it is the OTP-verified {@code sub}
 * claim of the guest JWT and is extracted server-side, so a caller cannot book on
 * behalf of an arbitrary phone.
 *
 * <p>{@code serviceId} is the {@code MasterServiceAssignment} id (the slot key
 * surfaced by {@code GET /book/{slug}/info}); it is a UUID in the actual schema,
 * not the {@code Long} the phase doc assumed.
 *
 * <p><b>Single- or multi-service (BE-7).</b> A guest may book either ONE service (legacy
 * {@code serviceId}) or SEVERAL services performed back-to-back as one visit
 * ({@code masterServiceIds}, mirroring BE-3's {@link CreateAppointmentRequest}). Exactly one of the
 * two must be supplied; {@link GuestBookingService} rejects a request that carries neither with a 400.
 * A {@code masterServiceIds} request (even a single-element one) creates a multi-service visit
 * ({@code Appointment} + N chained bookings, one cancel token, one reminder); a legacy
 * {@code serviceId} request keeps the byte-for-byte single-booking path ({@code appointment_id} NULL).
 * The list is {@code @Size}-bounded to {@value SlotCalculationService#MAX_SERVICES_PER_VISIT} — the
 * same cap BE-2 enforces on availability — so an oversized id list can never reach the per-id lookups;
 * it is NOT {@code @NotEmpty} because a legacy {@code serviceId}-only request legitimately omits it.
 *
 * <p>Anti-Bug §A: every field carries its boundary validation — {@code startsAt}
 * is {@code @NotNull @Future}, free-text name/surname are {@code @NotBlank @Size}
 * matched to the {@code VARCHAR(100)} columns plus a control-char {@code @Pattern}
 * (single-line: NUL/C0/DEL/TAB/LF/CR are rejected — a name is never multi-line and
 * these would be an SMS / log-injection surface). Mirrors the {@code RegisterRequest}
 * name guards.
 */
public record GuestBookingRequest(

        // Legacy single-service key (a MasterServiceAssignment id) — optional since BE-7. Nullable so a
        // multi-service masterServiceIds request may omit it; GuestBookingService enforces exactly-one-of.
        UUID serviceId,

        @Size(max = SlotCalculationService.MAX_SERVICES_PER_VISIT,
                message = "At most 10 services can be booked in a single visit")
        List<@NotNull(message = "Service ID must not be null") UUID> masterServiceIds,

        @NotNull
        @Future
        OffsetDateTime startsAt,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Surname must not contain control characters")
        String surname,

        // Phase 381 — privacy consent, required on every guest booking. Persisted (with a server
        // timestamp) on the booking/visit rows; see GuestConsent. false or missing -> 400.
        @NotNull(message = "Privacy policy must be accepted")
        @AssertTrue(message = "Privacy policy must be accepted")
        Boolean privacyAccepted,

        // The policy's effective date as published on /privacy (e.g. 2026-10-20, optional .N revision).
        // Format check only: the website owns the policy text, a new policy deploy must never break bookings.
        @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}(\\.\\d{1,3})?$",
                message = "Privacy policy version must look like 2026-10-20")
        @PolicyVersionDate
        String privacyPolicyVersion,

        // Phase 385 — optional «Коментар для майстра». EXACTLY the constraints of CreateBookingRequest#clientComment
        // (same 1000 cap, same control-character ban that still allows line breaks and tabs); blank is normalised
        // to null by the service via BookingComments#normalize, as on the app path. Never logged: see toString().
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String clientComment
) {

    /**
     * The pre-phase-385 shape (no note) — what an old client sends. Keeps every existing call site and the JSON
     * contract valid: {@code clientComment} is simply absent.
     */
    public GuestBookingRequest(UUID serviceId, List<UUID> masterServiceIds, OffsetDateTime startsAt, String name,
                               String surname, Boolean privacyAccepted, String privacyPolicyVersion) {
        this(serviceId, masterServiceIds, startsAt, name, surname, privacyAccepted, privacyPolicyVersion, null);
    }

    /**
     * Phase 385 D3 — the free-text note (and the guest's identity, which is likewise PII) must never reach a
     * log line: the default record {@code toString} would print every component, including {@code clientComment}.
     */
    @Override
    public String toString() {
        return "GuestBookingRequest[serviceId=" + serviceId + ", masterServiceIds=" + masterServiceIds
                + ", startsAt=" + startsAt + ", privacyAccepted=" + privacyAccepted + "]";
    }

    /**
     * Legacy 4-arg factory for a single-service guest booking ({@code serviceId} only, no
     * {@code masterServiceIds}) — preserves the pre-BE-7 call-site. Since phase 381 it carries NO consent
     * ({@code null}/{@code null}), so a request built this way fails {@code @Valid} over HTTP exactly as a
     * client that omits the fields would.
     */
    public GuestBookingRequest(UUID serviceId, OffsetDateTime startsAt, String name, String surname) {
        this(serviceId, null, startsAt, name, surname, null, null, null);
    }
}
