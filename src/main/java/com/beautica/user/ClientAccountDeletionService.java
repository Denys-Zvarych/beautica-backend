package com.beautica.user;

import com.beautica.auth.AuthService;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.SalonClosureBookingCandidate;
import com.beautica.booking.service.BookingService;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.repository.MediaRepository;
import com.beautica.review.repository.ClientReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates CLIENT account self-deletion (Phase 300): {@code DELETE /api/v1/users/me}.
 *
 * <p><b>D1 — full hard delete.</b> The caller's {@code users} row is physically removed. There is
 * no PII scrub (that machinery was deleted by phase 295 / V158), no soft delete, no {@code
 * deleted_at}. Everything that must survive for the PROVIDER's sake is detached first — see the
 * per-step notes below and the {@code ## Decisions} block in
 * {@code docs/backend-phases/phase-300-client-account-self-deletion.md}.
 *
 * <p><b>Phase 338 — REVERSES Phase 300 D4.</b> D4 read "a future booking is cancelled THEN
 * deleted, never detached — there is no 'future receipt' to preserve." Per the 2026-09-28 product
 * decision reversing it ("during deletion of ... client — all future bookings should be cancelled
 * and the notification should be sent"), every future {@code CONFIRMED} booking is now CANCELLED
 * and KEPT, detached with the SAME sentinel a past booking already gets — mirroring the master
 * self-delete reversal Phase 337 already made for DECLINED bookings. See {@code
 * docs/backend-phases/phase-338-client-self-delete-keeps-cancelled-future-bookings.md}.
 *
 * <p>Mirrors the disposal ORDER of {@code SalonService#disposeStaffAccounts} exactly (REUSE-FIRST):
 * pre-read external-storage pointers, settle every row that references the account being deleted,
 * flush explicitly, THEN bulk-delete the {@code users} row, THEN the after-commit cache
 * evictions and the after-commit R2 sweep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientAccountDeletionService {

    private static final int DELETE_ACCOUNT_TIMEOUT_SECONDS = 30;

    /**
     * Perf finding 2 (2026-09 audit, MEDIUM — raised independently by both security and perf).
     * The per-booking TRANSITION itself cannot be batched into fewer statements — the two-phase
     * header lock/collapse and the freshness recheck each require their own per-booking round trip
     * (see {@code BookingService#cancelBookingCore}'s own Javadoc) — and still issue several
     * statements per booking. Phase 338's own perf follow-up
     * ({@code BookingService#cancelFutureConfirmedBookingsForClientSelfDelete}) collapsed the ONE
     * batchable part — the initial full-graph load, previously one {@code findByIdWithFullGraph}
     * round trip PER booking — into a single {@code findAllByIdsWithGraph} query up front, which
     * measurably lowers the per-booking cost but does not make it O(1) for the whole cascade. An
     * unbounded future-booking count can therefore still approach or exceed
     * {@link #DELETE_ACCOUNT_TIMEOUT_SECONDS}, turning a self-delete into a bare 500 instead of a
     * clean, actionable error. A client with more than this many future CONFIRMED bookings is
     * directed to cancel some first — a business rule, not a technical limit, so it is enforced
     * BEFORE any row is touched (see the guard right after the candidate read below), never
     * mid-loop.
     */
    static final int MAX_FUTURE_BOOKINGS_PER_SELF_DELETE = 50;

    /**
     * The Ukrainian sentinel written to a detached booking/appointment's {@code guest_name}
     * («Видалений клієнт» — "deleted client"). Named after {@code
     * Master.DETACHED_FALLBACK_FIRST_NAME} ({@code "Майстер"}) — the analogous fallback label for
     * a detached PROVIDER account. Deliberately the SAME literal {@code
     * ReviewResponse#DETACHED_CLIENT_LABEL} / {@code SalonReviewResponse#DETACHED_CLIENT_LABEL}
     * render for a detached review author — one consistent signal across the app — but declared as
     * its own constant here too, mirroring how {@code DETACHED_FALLBACK_FIRST_NAME} and {@code
     * SalonReviewResponse.DETACHED_MASTER_LABEL} independently agree on {@code "Майстер"}.
     */
    static final String DETACHED_CLIENT_LABEL = "Видалений клієнт";

    private static final String SELF_DELETE_CANCELLATION_NOTE = "Клієнт видалив акаунт.";

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final BookingService bookingService;
    private final ClientReviewRepository clientReviewRepository;
    private final MediaRepository mediaRepository;
    private final TokensValidAfterCache tokensValidAfterCache;
    private final UserProfileCacheEvictor userProfileCacheEvictor;
    private final AuthService authService;
    private final AccountBlobPurgeRegistrar accountBlobPurgeRegistrar;
    private final Clock clock;

    /**
     * Deletes the calling CLIENT's own account. See the class javadoc and the phase doc's
     * {@code ## Decisions} block for the full design; this method's body is the numbered
     * disposal order those documents describe.
     *
     * @param clientUserId the deleting CLIENT's own id (from the JWT — never a path/body value)
     * @param accessToken  the caller's raw bearer token, for denylisting (step 11) — may be
     *                     {@code null} if it could not be extracted (defensive only, mirroring
     *                     {@code AuthService#logout}'s contract)
     * @throws ForbiddenException the user row does not exist (should be unreachable — the caller
     *                            just authenticated as this id)
     * @throws BusinessException  the caller is not a {@code CLIENT}, or holds a {@code salonId}
     *                            (defence in depth against an irreversible delete of the wrong
     *                            kind of account — the {@code @PreAuthorize("hasRole('CLIENT')")}
     *                            role gate is the primary guard, this is the secondary one)
     */
    @Transactional(timeout = DELETE_ACCOUNT_TIMEOUT_SECONDS)
    public void deleteOwnAccount(UUID clientUserId, String accessToken) {
        // Step 1 — load and guard. findByIdForUpdate takes a PESSIMISTIC_WRITE row lock, released
        // on commit/rollback — the same lock SalonService's staff hard-delete never needed because
        // it targets OTHER users' rows; here the actor deletes their OWN, so the lock closes a
        // concurrent-request race on the same account (e.g. a double-submit of this endpoint).
        User user = userRepository.findByIdForUpdate(clientUserId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        if (user.getRole() != Role.CLIENT || user.getSalonId() != null) {
            // The ONLY guard against deleting the wrong kind of account, besides the controller's
            // own @PreAuthorize("hasRole('CLIENT')") role gate (defence in depth — Phase 300 §3).
            throw new BusinessException("Only a CLIENT account with no salon affiliation can self-delete");
        }

        // Step 3 — pre-read external-storage pointers BEFORE anything cascades them away. Mirrors
        // SalonService's own pre-read-before-cascade pattern for salon media rows.
        List<MediaFile> mediaRows = mediaRepository.findByUploaderId(clientUserId);
        String avatarR2Key = user.getAvatarR2Key();

        Instant now = clock.instant();

        // Step 3.5 — client advisory lock (salt 1), Phase 338 race fix. Acquired BEFORE the
        // future-booking candidate read below and held across it, the cap check, and every
        // cancelBooking call that follows — mirroring disposeFutureConfirmedForMasterSelfDelete's
        // acquireMasterLockForSelfDelete precondition (Phase 337) exactly, salt 1 instead of salt 0.
        // Without this, a concurrent POST /bookings/POST /appointments for this SAME client — which
        // already takes this SAME lock before its own write — is invisible to this method's row lock
        // on `users` (a SELECT ... FOR UPDATE and a pg_advisory_xact_lock do not serialize against
        // each other). See acquireClientLockForSelfDelete's own Javadoc for the full mechanism and
        // why the widened V162 CHECK already rules out silent corruption either way.
        bookingService.acquireClientLockForSelfDelete(clientUserId);

        // Step 4 — cancel every future CONFIRMED booking through the ORDINARY client cancel path
        // (Phase 338 — REVERSES D4). NEVER the salon/master bulk-decline seam: that cascade
        // hardcodes DECLINED + PROVIDER_UNAVAILABLE and writes provider_comment — wrong status,
        // wrong actor, wrong note field for a client-initiated self-delete. cancelBooking's own
        // header lock/collapse logic runs per booking exactly as it would for a normal client
        // cancel, so a partially-cancelled Appointment (one leg already COMPLETED, the other
        // future and just cancelled here) collapses correctly instead of being force-declined as a
        // whole visit.
        //
        // Perf audit (2026-09, item 2 — MEDIUM): routed through the BATCHED entry point
        // (BookingService#cancelFutureConfirmedBookingsForClientSelfDelete), never a per-booking
        // loop calling the ordinary cancelBooking(UUID, UUID, CancelBookingRequest) — that overload
        // re-queries the booking's full graph on every call; the batched entry point preloads every
        // candidate's graph in ONE query and feeds each preloaded entity into the SAME per-booking
        // transition body (REUSE-FIRST — see that method's own Javadoc for the full mechanism).
        List<SalonClosureBookingCandidate> futureCandidates =
                bookingService.findFutureConfirmedBookingCandidatesForClient(clientUserId);
        if (futureCandidates.size() > MAX_FUTURE_BOOKINGS_PER_SELF_DELETE) {
            // Fails BEFORE any write — no partial cancellation, nothing to roll back. 422 so the
            // client message is echoed verbatim (GlobalExceptionHandler#handleBusiness), matching
            // the existing UNPROCESSABLE_ENTITY convention for deliberate, user-facing domain copy
            // (e.g. BookingCancellationService's cancel-window-closed message).
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    ("Забагато активних записів (%d) для видалення акаунту. Спочатку скасуйте частину "
                            + "майбутніх записів (максимум %d) і спробуйте ще раз.")
                            .formatted(futureCandidates.size(), MAX_FUTURE_BOOKINGS_PER_SELF_DELETE));
        }
        List<UUID> futureBookingIds = futureCandidates.stream()
                .map(SalonClosureBookingCandidate::bookingId).toList();
        CancelBookingRequest cancelRequest =
                new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, SELF_DELETE_CANCELLATION_NOTE);
        bookingService.cancelFutureConfirmedBookingsForClientSelfDelete(
                clientUserId, futureBookingIds, cancelRequest);

        // Step 5 — Phase 338: the just-cancelled future bookings are KEPT, never physically
        // deleted (REVERSES D4's "cancelled THEN deleted, never detached"). They are detached
        // together with the past bookings in step 6 below.
        //
        // Perf audit (2026-09, item 3 — LOW, resolves item 1's security finding by construction):
        // the batched cancel entry point above never writes a per-booking STATUS_CHANGED outbox
        // row in the first place (see its own Javadoc) — unlike the old per-booking cancelBooking
        // loop, which enqueued one PER BOOKING and required deleting them all again here with a
        // scope-free deleteByAggregateIdIn that would ALSO have destroyed any other still-PENDING
        // outbox row addressed to one of these same bookings (e.g. an undrained
        // BOOKING_RESCHEDULED). Nothing to clean up any more — only the ONE CLIENT_CANCELLED row
        // per affected VISIT is enqueued (enqueueClientCancelledPerVisit, reusing the SAME event
        // type GuestVisitCancellationService writes for a guest's whole-visit cancel).
        if (!futureBookingIds.isEmpty()) {
            bookingService.enqueueClientCancelledPerVisit(futureCandidates);
        }

        // Step 6 — every visit header still attached to this client is unconditionally DETACHED,
        // never deleted (Phase 338). Since future CONFIRMED legs are now KEPT rather than deleted
        // (step 4/5 above), no header can ever end up CHILDLESS via this flow any more — every leg
        // it ever had still exists, cancelled or otherwise — so the Phase 300 D4 survivorship
        // partition (findAppointmentIdsWithSurvivingBookings) is gone along with the childless-delete
        // branch it fed.
        List<Appointment> remainingAppointments = appointmentRepository.findByClientId(clientUserId);
        for (Appointment appointment : remainingAppointments) {
            appointment.detachClient(DETACHED_CLIENT_LABEL, now);
        }

        // Step 6 (bookings) — every booking row still attached to this client — past/terminal rows
        // unchanged since Phase 300, plus the just-cancelled future rows Phase 338 now keeps — is
        // detached here, together, in the same uniform loop. ONE Hibernate UPDATE per row writing
        // the sentinel, nulling client and stamping clientDetachedAt together — mirrors
        // Master#detach: the whole-row CHECK (chk_bookings_guest_fields, V162) is evaluated against
        // the result, so splitting this into two statements would leave an intermediate row no arm
        // of the CHECK accepts.
        List<Booking> remainingBookings = bookingRepository.findByClientId(clientUserId);
        for (Booking booking : remainingBookings) {
            booking.detachClient(DETACHED_CLIENT_LABEL, now);
        }

        // Step 7 — client_reviews (provider→client) are DELETED, not detached (D3): they rate the
        // client, the aggregate they feed dies with the row anyway, and the client is the only
        // reader of their own rating, so a detached row would have no subject, no aggregate and no
        // reader. Must run before the users row goes (its FK stays NO ACTION — no migration).
        clientReviewRepository.deleteBySubjectClientId(clientUserId);

        // Step 8 — MANDATORY, not defensive. deleteAllByIdInBatch below is a bulk JPQL DELETE, and
        // Hibernate's AUTO flush only flushes pending work whose query space overlaps the
        // statement's own (users, not bookings/appointments). Without this the detachClient()
        // UPDATEs above would still be sitting in the persistence context when the users row
        // disappears, the FK's ON DELETE SET NULL would fire first, and the flush that followed
        // would try to update rows that no longer satisfy chk_bookings_guest_fields /
        // chk_appointment_guest_fields. Mirrors SalonService#disposeStaffAccounts's identical
        // masterRepository.flush() call verbatim — flush() on any repository flushes the whole
        // EntityManager.
        bookingRepository.flush();

        // Step 9 — the hard delete (D1). reviews.client_id / bookings.client_id /
        // appointments.client_id are all ON DELETE SET NULL (V162) but every row that FK would
        // touch has already been detached above, so the CHECK is already satisfied and the FK
        // fires as a pure no-op RI action. Every other FK on users (refresh_tokens, device_tokens,
        // media_files, password_reset_tickets, favorites) is a plain CASCADE and needs no explicit
        // call here.
        userRepository.deleteAllByIdInBatch(List.of(clientUserId));

        // Step 10 — LOAD-BEARING FOR SECURITY, not a perf nicety (mirrors the phase 295 audit
        // finding on the staff hard-delete path verbatim). TokensValidAfterCache is what
        // JwtAuthenticationFilter consults on every authenticated request; until it is evicted the
        // deleted account's already-issued access token could keep authenticating for up to the
        // cache's 60s TTL. userProfileCacheEvictor backs GET /users/me. Both run strictly after
        // commit — see TokensValidAfterCache#invalidateAfterCommit's own javadoc for the
        // read-through-race this ordering closes.
        tokensValidAfterCache.invalidateAfterCommit(clientUserId);
        userProfileCacheEvictor.evictAfterCommit(clientUserId);

        // Step 11 — denylist the caller's OWN access token, exactly as AuthService#logout does.
        // Refresh-token cleanup needs no separate call here (unlike logout): every refresh_tokens
        // row for this user is removed by ON DELETE CASCADE the instant step 9's DELETE commits.
        authService.denylistAccessToken(accessToken);

        // Step 12 — R2 blob sweep, registered to run strictly after commit (D "external-storage
        // cleanup contract", Anti-Bug Playbook §O8). Never called inline: MediaService#deleteByUploader
        // opens its own PROPAGATION_REQUIRES_NEW transactions, so an outer rollback here would leave
        // blobs already destroyed. See MediaService#purgeUserBlobsAfterCommit's own Javadoc for why
        // this is R2-only (the DB rows are already gone via CASCADE by the time this callback runs).
        // Phase 301: promoted to AccountBlobPurgeRegistrar so the staff/independent-master
        // self-delete flow can share the identical after-commit registration shape.
        accountBlobPurgeRegistrar.registerAfterCommit(clientUserId, avatarR2Key, mediaRows);

        // Step 13 — audit trail. Ids and counts only, never an email or any other PII (this repo's
        // logging convention) — a hard delete of the account is the single most consequential
        // mutation this service performs; it must leave a record even though the row it names is
        // gone.
        log.info("Client account self-delete: user {} deleted, {} future booking(s) cancelled+kept, "
                        + "{} appointment header(s) detached, {} booking(s) detached",
                clientUserId, futureBookingIds.size(), remainingAppointments.size(), remainingBookings.size());
    }
}
