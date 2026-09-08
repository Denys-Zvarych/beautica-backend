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
import com.beautica.booking.service.BookingService;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.MediaService;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.review.repository.ClientReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
     * {@code cancelBooking} cannot be batched (it interleaves per-booking reads/writes — the
     * per-visit header-collapse logic REQUIRES this; see the loop's own comment below, which the
     * fix must not touch) and issues ~4-6 statements per call, so an unbounded future-booking count
     * can approach or exceed {@link #DELETE_ACCOUNT_TIMEOUT_SECONDS}, turning a self-delete into a
     * bare 500 instead of a clean, actionable error. A client with more than this many future
     * CONFIRMED bookings is directed to cancel some first — a business rule, not a technical limit,
     * so it is enforced BEFORE any row is touched (see the guard right after the lookup below),
     * never mid-loop.
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
    private final NotificationOutboxRepository notificationOutboxRepository;
    private final ClientReviewRepository clientReviewRepository;
    private final MediaRepository mediaRepository;
    private final MediaService mediaService;
    private final TokensValidAfterCache tokensValidAfterCache;
    private final UserProfileCacheEvictor userProfileCacheEvictor;
    private final AuthService authService;
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

        // Step 4 — cancel every future CONFIRMED booking through the ORDINARY client cancel path
        // (D4). NEVER the salon/master bulk-decline seam: that cascade hardcodes DECLINED +
        // PROVIDER_UNAVAILABLE and writes provider_comment — wrong status, wrong actor, wrong
        // note field for a client-initiated self-delete. cancelBooking's own header lock/collapse
        // logic runs per booking exactly as it would for a normal client cancel, so a
        // partially-cancelled Appointment (one leg already COMPLETED, the other future and just
        // cancelled here) collapses correctly instead of being force-declined as a whole visit.
        List<UUID> futureBookingIds = bookingService.findFutureConfirmedBookingIdsForClient(clientUserId);
        if (futureBookingIds.size() > MAX_FUTURE_BOOKINGS_PER_SELF_DELETE) {
            // Fails BEFORE any write — no partial cancellation, nothing to roll back. 422 so the
            // client message is echoed verbatim (GlobalExceptionHandler#handleBusiness), matching
            // the existing UNPROCESSABLE_ENTITY convention for deliberate, user-facing domain copy
            // (e.g. BookingCancellationService's cancel-window-closed message).
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    ("Забагато активних записів (%d) для видалення акаунту. Спочатку скасуйте частину "
                            + "майбутніх записів (максимум %d) і спробуйте ще раз.")
                            .formatted(futureBookingIds.size(), MAX_FUTURE_BOOKINGS_PER_SELF_DELETE));
        }
        CancelBookingRequest cancelRequest =
                new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, SELF_DELETE_CANCELLATION_NOTE);
        for (UUID bookingId : futureBookingIds) {
            bookingService.cancelBooking(clientUserId, bookingId, cancelRequest);
        }

        // Step 5 — physically delete the now-CANCELLED future booking rows. D4: a future booking
        // is cancelled THEN deleted, never detached — there is no "future receipt" to preserve.
        //
        // The cancelBooking loop above (step 4) enqueued one STATUS_CHANGED notification_outbox row
        // per booking (BookingService#cancelBooking -> outboxService.enqueueStatusChanged). That
        // row's aggregate_id is a raw UUID with NO FK to bookings (V32) — nothing at the DB level
        // stops us deleting the very booking it points at. Left alone, the drain worker would claim
        // the orphaned row, fail to re-hydrate the booking, throw IllegalStateException, and
        // dead-letter it on every self-delete that cancels a future booking. So the just-enqueued
        // outbox rows for these ids are removed HERE, in the same transaction, immediately before
        // the booking rows themselves go — do not read this as redundant cleanup.
        if (!futureBookingIds.isEmpty()) {
            notificationOutboxRepository.deleteByAggregateIdIn(futureBookingIds);
            bookingRepository.deleteAllByIdInBatch(futureBookingIds);
        }

        // Step 6 — every visit header still attached to this client, now that its future legs are
        // gone: childless (every leg was a future CONFIRMED one, now deleted) is physically
        // deleted; a header with at least one surviving (past/terminal) child is detached, never
        // both, never inferred from the header's own status alone.
        //
        // Perf finding 1 (2026-09 audit, HIGH): survivorship for EVERY remaining header is resolved
        // in ONE set-based query (findAppointmentIdsWithSurvivingBookings), never a per-appointment
        // existsByAppointmentId probe in the loop — that was N sequential Neon round trips (~1.5-4.5s
        // for a 150-header client) held inside this transaction's row lock on the users row. The
        // partition below is pure in-memory Set membership.
        List<Appointment> remainingAppointments = appointmentRepository.findByClientId(clientUserId);
        List<UUID> remainingAppointmentIds = remainingAppointments.stream().map(Appointment::getId).toList();
        Set<UUID> appointmentIdsWithSurvivingBookings = remainingAppointmentIds.isEmpty()
                // Guard the empty case: appointment_id IN () is wasted work (or dialect-dependent
                // undefined behaviour) when the caller already knows the answer is "none".
                ? Set.of()
                : new HashSet<>(bookingRepository.findAppointmentIdsWithSurvivingBookings(remainingAppointmentIds));
        List<UUID> childlessAppointmentIds = new ArrayList<>();
        int detachedAppointments = 0;
        for (Appointment appointment : remainingAppointments) {
            if (appointmentIdsWithSurvivingBookings.contains(appointment.getId())) {
                appointment.detachClient(DETACHED_CLIENT_LABEL, now);
                detachedAppointments++;
            } else {
                childlessAppointmentIds.add(appointment.getId());
            }
        }
        if (!childlessAppointmentIds.isEmpty()) {
            appointmentRepository.deleteAllByIdInBatch(childlessAppointmentIds);
        }

        // Step 6 (bookings) — every booking row still attached to this client is, by
        // construction, past or terminal (every future CONFIRMED one was deleted in step 5).
        // ONE Hibernate UPDATE per row writing the sentinel, nulling client and stamping
        // clientDetachedAt together — mirrors Master#detach: the whole-row CHECK
        // (chk_bookings_guest_fields, V162) is evaluated against the result, so splitting this
        // into two statements would leave an intermediate row no arm of the CHECK accepts.
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
        registerBlobPurgeAfterCommit(clientUserId, avatarR2Key, mediaRows);

        // Step 13 — audit trail. Ids and counts only, never an email or any other PII (this repo's
        // logging convention) — a hard delete of the account is the single most consequential
        // mutation this service performs; it must leave a record even though the row it names is
        // gone.
        log.info("Client account self-delete: user {} deleted, {} future booking(s) cancelled+deleted, "
                        + "{} appointment header(s) deleted, {} appointment header(s) detached, "
                        + "{} booking(s) detached",
                clientUserId, futureBookingIds.size(), childlessAppointmentIds.size(),
                detachedAppointments, remainingBookings.size());
    }

    private void registerBlobPurgeAfterCommit(UUID clientUserId, String avatarR2Key, List<MediaFile> mediaRows) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mediaService.purgeUserBlobsAfterCommit(clientUserId, avatarR2Key, mediaRows);
                } catch (RuntimeException ex) {
                    log.warn("Client self-delete blob purge failed after commit for user {}: {}",
                            clientUserId, ex.getClass().getSimpleName());
                }
            }
        });
    }
}
