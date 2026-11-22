package com.beautica.client.service;

import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.AttachReason;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.client.event.ClientBookingsAttachedEvent;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.UUID;

/**
 * B527 (CLI-62, D1) — the ONE attach step: a guest (LINK) or walk-in (STAFF) booking whose E.164 phone equals the
 * VERIFIED phone of an active CLIENT account belongs to that account. Two entry points share one match predicate
 * ({@link UserRepository#findAttachableClientByVerifiedPhone}; V261 mirrors it in SQL) and one lock:
 * <ul>
 *   <li><b>At booking time</b> ({@link AttachReason#BOOKING}) — {@link #lockAndFindAccount} resolves the account for a
 *       NEW booking, which the create path then stamps with {@link #attachNew} before its first persist;</li>
 *   <li><b>At phone verification</b> ({@link AttachReason#PHONE_VERIFIED}) — {@link #attachByPhone} attaches every
 *       existing row of the phone (any status, not anonymised, not detached) after registration / add / change phone.</li>
 * </ul>
 * Every attach takes the per-phone advisory lock the walk-in create already uses. The booking-time methods
 * ({@link #lockAndFindAccount}, {@link #findAccountUnderHeldLock}, {@link #attachByPhone}) are {@code MANDATORY}: they
 * run in the CALLER's transaction. {@link #attachAfterCommit} and {@link #sweep} are NOT — each attach there runs in its
 * own {@code REQUIRES_NEW} transaction (after-commit ones on {@code clientPhoneAttachExecutor}), re-resolving the owning
 * account under the lock. A walk-in or link booking racing a verification either sees the verified phone (and attaches
 * itself) or commits before the verification's UPDATE runs (and is swept by it) — it can never miss both; an attach that
 * fails or is rejected is retried by the 30-minute {@link #sweep}.
 * Lock order is phone -&gt; master everywhere; the verification paths take no master lock.
 *
  * <p>Provider accounts never match (D6). A phone the account no longer owns never matches (D4): rows already attached
 * stay, nothing is ever un-attached. Phones are never logged.
 */
@Service
public class ClientPhoneAttachService {

    private static final Logger log = LoggerFactory.getLogger(ClientPhoneAttachService.class);

    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final TaskExecutor attachExecutor;

    public ClientPhoneAttachService(BookingRepository bookingRepository,
                                    AppointmentRepository appointmentRepository,
                                    UserRepository userRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    Clock clock,
                                    PlatformTransactionManager transactionManager,
                                    @Qualifier("clientPhoneAttachExecutor") TaskExecutor attachExecutor) {
        this.bookingRepository = bookingRepository;
        this.appointmentRepository = appointmentRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionManager = transactionManager;
        this.attachExecutor = attachExecutor;
    }

    /**
     * Booking time — takes the per-phone lock, then returns the CLIENT account that owns {@code phoneE164} as a
     * verified phone, if any. {@code phoneE164} must already be E.164 (the guest token / the normaliser).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<User> lockAndFindAccount(String phoneE164) {
        bookingRepository.acquireWalkInPhoneLock(phoneE164);
        return userRepository.findAttachableClientByVerifiedPhone(phoneE164);
    }

    /**
     * Booking time, for a caller that ALREADY holds this phone's per-phone lock in the current transaction (the
     * walk-in create takes it for its SMS budget before this call) — the same lookup as {@link #lockAndFindAccount}
     * without a redundant second lock round trip. Calling this without holding the lock forfeits the verification race
     * guarantee; the one caller documents where it took the lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<User> findAccountUnderHeldLock(String phoneE164) {
        return userRepository.findAttachableClientByVerifiedPhone(phoneE164);
    }

    /** Stamps a NEW (not yet persisted) booking as attached at create; clears its {@code guest_phone}. */
    public void attachNew(User account, Booking booking) {
        booking.attachToClient(account, AttachReason.BOOKING, clock.instant());
    }

    /** Stamps a NEW (not yet persisted) visit header as attached at create; clears its {@code guest_phone}. */
    public void attachNew(User account, Appointment appointment) {
        appointment.attachToClient(account, AttachReason.BOOKING, clock.instant());
    }

    /**
     * Verification time, test / maintenance entry — attaches every existing guest / walk-in booking of
     * {@code phoneE164} to the CLIENT account that owns it, inside the caller's transaction. Re-reads the account; the
     * verification flows use {@link #attachAfterCommit} instead.
     *
     * @return booking rows attached (0 when nothing matched)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int attachByPhone(String phoneE164, AttachReason reason) {
        Optional<User> account = lockAndFindAccount(phoneE164);
        return account.isEmpty() ? 0 : attachRows(account.get().getId(), phoneE164, reason);
    }

    /**
     * Verification time (registration / add phone / change phone) — attaches every existing guest / walk-in booking of
     * the account's just-verified phone, AFTER the caller's transaction commits and in its OWN
     * {@code REQUIRES_NEW} transaction. {@code verified} is the caller's already-loaded account: no re-read.
     *
     * <p>Why after commit + new transaction + executor (audit LOW, B527): the per-phone lock sets a transaction-local
     * {@code lock_timeout = 3s} that then also governs the attach UPDATEs, and a 55P03 there used to roll back the whole
     * registration / verification after the OTP was spent. Now the verification commits first; the attach runs on
     * {@code clientPhoneAttachExecutor} (an after-commit callback still holds the committing thread's pooled connection,
     * so a {@code REQUIRES_NEW} there would pin a second one). A lock failure or a rejected hand-off is logged at WARN
     * and the rows are picked up by {@link #sweep} — it never fails the user's request. An after-commit attach is also
     * the only order in which a REQUIRES_NEW transaction can see (and FK-reference) a freshly registered user row. The
     * race guarantee is unchanged: once the verification commits, every booking create sees the verified phone and
     * attaches itself, and every create that committed before is swept by this UPDATE under the same per-phone lock.
     * Phase doc § 3 said "same transaction"; this is the deliberate deviation (orchestrator decision 2026-11-20).
     */
    public void attachAfterCommit(User verified, AttachReason reason) {
        // Every caller verifies the phone inside a transaction (UserPhoneService#verifyLocked via TransactionTemplate,
        // PhoneRegistrationService#register MANDATORY). Without one there is no commit to wait for, and attaching at
        // once could reference a user row no other transaction can see yet - fail loudly instead.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("attachAfterCommit requires an active transaction");
        }
        UUID clientId = verified.getId();
        String phone = verified.getPhoneNumber();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitAttach(clientId, phone, reason);
            }
        });
    }

    private void submitAttach(UUID clientId, String phoneE164, AttachReason reason) {
        try {
            attachExecutor.execute(() -> {
                if (attachInNewTransaction(clientId, phoneE164, reason) == ATTACH_FAILED) {
                    log.warn("After-commit attach for client {} ({}) failed; the retry sweep will attach the rows",
                            clientId, reason);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("After-commit attach for client {} ({}) rejected (executor saturated); left for the retry sweep",
                    clientId, reason);
        }
    }

    /**
     * Retry sweep (audit LOW, B527) — attaches the guest / walk-in rows that an after-commit attach failed or was rejected
     * before reaching. Looks up at most {@code maxPhones} phones that still carry attachable rows and belong to a verified
     * CLIENT account, and runs the attach for each in its own {@code REQUIRES_NEW} transaction under the per-phone lock,
     * re-resolving the owner there. A failing phone is counted and skipped; the rest still run. Counts only are logged.
     *
     * @return booking rows attached across all phones
     */
    public int sweep(int maxPhones) {
        List<String> phones = bookingRepository.findAttachableGuestPhones(maxPhones);
        int attached = 0;
        int failed = 0;
        int processed = 0;
        Instant deadline = clock.instant().plus(SWEEP_WALL_CLOCK_CAP);
        for (String phone : phones) {
            if (processed > 0 && !clock.instant().isBefore(deadline)) {
                log.info("Client phone attach sweep: wall-clock cap reached after {} of {} phone(s); the rest wait for the next run",
                        processed, phones.size());
                break;
            }
            processed++;
            int n = attachInNewTransaction(null, phone, AttachReason.PHONE_VERIFIED);
            if (n == ATTACH_FAILED) {
                failed++;
            } else {
                attached += n;
            }
        }
        if (!phones.isEmpty()) {
            log.info("Client phone attach sweep: {} phone(s) scanned, {} row(s) attached, {} failed",
                    processed, attached, failed);
        }
        return attached;
    }

    private static final int ATTACH_FAILED = -1;
    /** Wall-clock budget of one sweep run; unprocessed phones are picked up (randomly rotated) by the next run. */
    private static final java.time.Duration SWEEP_WALL_CLOCK_CAP = java.time.Duration.ofSeconds(60);

    /**
     * One attach in its own {@code REQUIRES_NEW} transaction. After taking the per-phone lock the owner is RE-RESOLVED:
     * the verification committed before this ran, so the phone may since have moved to another account, or the account
     * been deactivated — rows are attached only to the account that owns the phone NOW, and only when it is
     * {@code expectedClientId} (a sweep passes {@code null}: whoever owns it). Otherwise nothing is attached.
     *
     * @return rows attached, or {@link #ATTACH_FAILED} when the transaction failed (already rolled back)
     */
    private int attachInNewTransaction(UUID expectedClientId, String phoneE164, AttachReason reason) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            Integer attached = template.execute(status -> {
                bookingRepository.acquireWalkInPhoneLock(phoneE164);
                Optional<User> owner = userRepository.findAttachableClientByVerifiedPhone(phoneE164);
                if (owner.isEmpty() || (expectedClientId != null && !expectedClientId.equals(owner.get().getId()))) {
                    log.info("Retroactive attach for client {} ({}) skipped: the phone no longer belongs to it",
                            expectedClientId, reason);
                    return 0;
                }
                return attachRows(owner.get().getId(), phoneE164, reason);
            });
            return attached == null ? 0 : attached;
        } catch (RuntimeException e) {
            // Best effort by design: the verification is already committed. Class name only — the message may carry
            // SQL with the phone.
            log.warn("Retroactive attach for client {} ({}) failed ({}); the retry sweep will attach the rows",
                    expectedClientId, reason, e.getClass().getSimpleName());
            return ATTACH_FAILED;
        }
    }

    /** Headers first, then rows, under the per-phone lock the caller already holds; writes no notification. */
    private int attachRows(UUID clientId, String phoneE164, AttachReason reason) {
        Instant at = clock.instant();
        appointmentRepository.attachGuestVisitsByPhone(phoneE164, clientId, reason.name(), at);
        int attached = bookingRepository.attachGuestRowsByPhone(phoneE164, clientId, reason.name(), at);
        if (attached > 0) {
            log.info("Attached {} guest booking row(s) to client {} ({})", attached, clientId, reason);
            eventPublisher.publishEvent(new ClientBookingsAttachedEvent(clientId));
        }
        return attached;
    }
}
