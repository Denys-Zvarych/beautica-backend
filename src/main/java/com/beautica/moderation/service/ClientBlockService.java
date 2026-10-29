package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBlockRateLimitedException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.common.util.UkrainianPhoneNormalizer;
import com.beautica.config.RateLimitConfig;
import com.beautica.moderation.dto.BlockDisplayNames;
import com.beautica.moderation.dto.BlockResponse;
import com.beautica.moderation.dto.ClientBlockState;
import com.beautica.moderation.repository.ClientBlockListRow;
import com.beautica.moderation.repository.ClientBlockRepository;
import com.beautica.moderation.service.ClientBlockScopeResolver.ActorScopes;
import com.beautica.user.User;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 397 — a provider blocks a client (registered account or LINK guest). Storage, list, unblock and the
 * {@link #isClientBlocked} / {@link #isGuestPhoneBlocked} probes phase 398 enforces with. The blocked party is never
 * notified of the block itself (D5); the future bookings a NEW block cancels ({@link ClientBlockFacade}, phase 398
 * user decision 2026-10-28) reach them only as the ordinary neutral provider-decline notice.
 *
 * <p>Target derivation, always from a booking in the caller's scope — ONE identity per type (user decision
 * 2026-10-28, supersedes D2/D5 phone part):
 * <ul>
 *   <li>registered client (APP booking) → {@code blocked_user_id} only. The profile phone is unverified and is
 *       never hashed or stored (no {@code phone_hash}, no {@code phone_last4});</li>
 *   <li>LINK guest → the HMAC of the OTP-verified {@code guest_phone} + its last four digits;</li>
 *   <li>STAFF walk-in, or a guest whose phone was already erased → 400 (no verified identity).</li>
 * </ul>
 * Matching follows the same key: a CLIENT target matches CLIENT rows by user id, a GUEST target matches GUEST rows by
 * phone hash, and nothing links one type to the other — on POST idempotency, on the detail block state and in the
 * phase-398 probes alike. Names, phones and hashes are never logged.
 *
 * <p>POST and DELETE each spend one token of the caller's {@code clientBlockWriteBuckets} budget (429 when empty).
 */
@Service
@RequiredArgsConstructor
public class ClientBlockService {

    private static final Logger log = LoggerFactory.getLogger(ClientBlockService.class);

    /** Hard cap on {@code GET /blocks} — the list is a settings screen, not a feed. */
    static final int MAX_LIST_SIZE = 500;

    /** {@code Retry-After} for an exhausted block/unblock budget — the bucket's own refill window. */
    static final long RATE_LIMIT_RETRY_AFTER_SECONDS = RateLimitConfig.CLIENT_BLOCK_WRITE_WINDOW.toSeconds();

    private final ClientBlockRepository clientBlockRepository;
    private final BookingRepository bookingRepository;
    private final ClientBlockScopeResolver scopeResolver;
    private final PhoneHasher phoneHasher;

    /** Per-user block/unblock budget; bound by bean name ({@code RateLimitConfig#clientBlockWriteBuckets}). */
    private final LoadingCache<String, Bucket> clientBlockWriteBuckets;

    private final Clock clock;

    /**
     * Outcome of {@link #block}: the row and whether this call created it (201) or found it (200). {@code newBlock}
     * is set only when {@code created} — the identity {@link ClientBlockFacade} cancels future bookings for.
     */
    public record CreateBlockResult(BlockResponse block, boolean created, @Nullable NewBlock newBlock) {

        public CreateBlockResult(BlockResponse block, boolean created) {
            this(block, created, null);
        }
    }

    /**
     * Phase 398 (user decision 2026-10-28) — the scope and the per-type identity of a block THIS call created:
     * exactly one of {@code clientId} (CLIENT row) / {@code guestPhone} (GUEST row, canonical E.164 — the pre-image
     * of the stored {@code phone_hash}). Never serialised, never logged.
     */
    public record NewBlock(BlockScope scope, @Nullable UUID clientId, @Nullable String guestPhone) {

        public NewBlock {
            Objects.requireNonNull(scope);
            if ((clientId == null) == (guestPhone == null)) {
                throw new IllegalArgumentException("Exactly one of clientId / guestPhone must be set");
            }
        }

        @Override
        public String toString() {
            return "NewBlock[scope=" + scope + ", kind=" + (clientId != null ? "CLIENT" : "GUEST") + "]";
        }
    }

    /** The identity a booking resolves to: exactly one of {@code userId} (CLIENT) / {@code phone} (GUEST). */
    record BlockTarget(@Nullable UUID userId, @Nullable HashedPhone phone, String displayName) {

        BlockTarget {
            if ((userId == null) == (phone == null)) {
                throw new IllegalArgumentException("Exactly one of userId / phone must be set");
            }
        }

        static BlockTarget client(UUID userId, String displayName) {
            return new BlockTarget(Objects.requireNonNull(userId), null, displayName);
        }

        static BlockTarget guest(HashedPhone phone, String displayName) {
            return new BlockTarget(null, Objects.requireNonNull(phone), displayName);
        }

        byte[] phoneHash() {
            return phone == null ? null : phone.hash();
        }

        /** D4 — last digits only for a guest, whose phone the provider already sees on the booking. */
        String phoneLast4() {
            return phone == null ? null : phone.last4();
        }
    }

    @Transactional
    public CreateBlockResult block(UUID actorId, Role role, UUID bookingId) {
        requireBlockerRole(role);
        consumeRateLimitToken(actorId);
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        BlockScope scope = scopeResolver.forBooking(actorId, role, booking)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        BlockTarget target = targetOf(booking)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "This client cannot be blocked"));

        Optional<UUID> existing = findBlockId(scope, target);
        if (existing.isPresent()) {
            return new CreateBlockResult(BlockResponse.from(loadRow(existing.get())), false);
        }
        UUID id = UUID.randomUUID();
        int inserted = clientBlockRepository.insertIgnoringDuplicate(id, scope.masterId(), scope.salonId(),
                target.userId(), target.phoneHash(), target.phoneLast4(), target.displayName(), actorId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (inserted == 0) {
            // A concurrent block of the same client in the same scope committed first — answer as a repeat.
            UUID winner = findBlockId(scope, target)
                    .orElseThrow(() -> new IllegalStateException("Conflicting client block not visible"));
            return new CreateBlockResult(BlockResponse.from(loadRow(winner)), false);
        }
        log.info("Client block {} created from booking {}", id, bookingId);
        return new CreateBlockResult(BlockResponse.from(loadRow(id)), true, newBlockOf(scope, target, booking));
    }

    /** One statement for the rows (constructor projection, no {@code User} hydrated) whatever their count. */
    @Transactional(readOnly = true)
    public List<BlockResponse> list(UUID actorId, Role role) {
        requireBlockerRole(role);
        ActorScopes scopes = scopeResolver.forActor(actorId, role);
        PageRequest cap = PageRequest.of(0, MAX_LIST_SIZE);
        List<ClientBlockListRow> rows;
        if (scopes.masterId() != null) {
            rows = clientBlockRepository.findListRowsByMasterScope(scopes.masterId(), cap);
        } else if (!scopes.salonIds().isEmpty()) {
            rows = clientBlockRepository.findListRowsBySalonScope(scopes.salonIds(), cap);
        } else {
            rows = List.of();
        }
        return rows.stream().map(BlockResponse::from).toList();
    }

    /** D5 — idempotent: a missing block, or one outside the caller's scope, is a silent no-op (204, no oracle). */
    @Transactional
    public void unblock(UUID actorId, Role role, UUID blockId) {
        requireBlockerRole(role);
        consumeRateLimitToken(actorId);
        ActorScopes scopes = scopeResolver.forActor(actorId, role);
        int deleted = 0;
        if (scopes.masterId() != null) {
            deleted = clientBlockRepository.deleteInMasterScope(blockId, scopes.masterId());
        } else if (!scopes.salonIds().isEmpty()) {
            deleted = clientBlockRepository.deleteInSalonScope(blockId, scopes.salonIds());
        }
        if (deleted > 0) {
            log.info("Client block {} removed", blockId);
        }
    }

    /**
     * The provider booking detail's block state (mobile 425). {@code role} is the viewer's role, or null when
     * unknown; non-blocker roles always read {@link ClientBlockState#NOT_BLOCKABLE} without a statement. A
     * blocker-role viewer in scope costs one {@code LIMIT 1} id lookup by the booking's own identity type.
     */
    @Transactional(readOnly = true)
    public ClientBlockState blockStateFor(UUID actorId, @Nullable Role role, Booking booking) {
        if (!ClientBlockScopeResolver.isBlockerRole(role)) {
            return ClientBlockState.NOT_BLOCKABLE;
        }
        Optional<BlockScope> scope = scopeResolver.forBooking(actorId, role, booking);
        Optional<BlockTarget> target = targetOf(booking);
        if (scope.isEmpty() || target.isEmpty()) {
            return ClientBlockState.NOT_BLOCKABLE;
        }
        return new ClientBlockState(true, findBlockId(scope.get(), target.get()).orElse(null));
    }

    /** Phase 398 (app create) — is this registered account blocked in this scope. CLIENT rows only. */
    @Transactional(readOnly = true)
    public boolean isClientBlocked(BlockScope scope, UUID userId) {
        return clientBlockRepository.findIdByBlockedUser(scope.masterId(), scope.salonId(), userId).isPresent();
    }

    /**
     * Phase 398 (guest create) — is this OTP-verified phone blocked in this scope. GUEST rows only. Typed
     * {@link HashedPhone} (397 re-audit): it is a final class whose only constructor is private to
     * {@link PhoneHasher} (398 security LOW — it used to be a record, whose canonical constructor is public), so
     * only {@link PhoneHasher#hash} / {@link PhoneHasher#tryHash} can mint one and a raw or foreign digest cannot be
     * passed by mistake.
     */
    @Transactional(readOnly = true)
    public boolean isGuestPhoneBlocked(BlockScope scope, HashedPhone phone) {
        return clientBlockRepository.findIdByGuestPhone(scope.masterId(), scope.salonId(), phone.hash()).isPresent();
    }

    /**
     * Phase 398 D7 — guest PII erase by phone: every GUEST block of this phone keeps its hash + last 4 (the block
     * stays effective) and gets {@code anonymisedName} as its display name. Joins the caller's erase transaction.
     *
     * @return rows renamed (0 for a phone that does not normalise — such a phone was never blockable)
     */
    @Transactional
    public int anonymiseGuestBlocks(String rawPhone, String anonymisedName) {
        return phoneHasher.tryHash(rawPhone)
                .map(phone -> clientBlockRepository.renameGuestBlocksByPhone(phone.hash(), anonymisedName))
                .orElse(0);
    }

    private Optional<BlockTarget> targetOf(Booking booking) {
        if (booking.getBookingSource() == BookingSource.STAFF) {
            return Optional.empty();
        }
        User client = booking.getClient();
        if (client != null) {
            return Optional.of(BlockTarget.client(client.getId(),
                    BlockDisplayNames.snapshot(client.getFirstName(), client.getLastName())));
        }
        return phoneHasher.tryHash(booking.getGuestPhone())
                .map(phone -> BlockTarget.guest(phone,
                        BlockDisplayNames.snapshot(booking.getGuestName(), booking.getGuestSurname())));
    }

    /** A GUEST target's phone already normalised in {@link #targetOf} (tryHash), so toE164 cannot throw here. */
    private static NewBlock newBlockOf(BlockScope scope, BlockTarget target, Booking booking) {
        return target.userId() != null
                ? new NewBlock(scope, target.userId(), null)
                : new NewBlock(scope, null, UkrainianPhoneNormalizer.toE164(booking.getGuestPhone()));
    }

    /** The per-type key: CLIENT target → CLIENT rows by user id; GUEST target → GUEST rows by phone hash. */
    private Optional<UUID> findBlockId(BlockScope scope, BlockTarget target) {
        if (target.userId() != null) {
            return clientBlockRepository.findIdByBlockedUser(scope.masterId(), scope.salonId(), target.userId());
        }
        return clientBlockRepository.findIdByGuestPhone(scope.masterId(), scope.salonId(), target.phoneHash());
    }

    private ClientBlockListRow loadRow(UUID id) {
        return clientBlockRepository.findListRowById(id)
                .orElseThrow(() -> new IllegalStateException("Client block not visible"));
    }

    private void consumeRateLimitToken(UUID actorId) {
        if (!clientBlockWriteBuckets.get(actorId.toString()).tryConsume(1)) {
            throw new ClientBlockRateLimitedException(RATE_LIMIT_RETRY_AFTER_SECONDS);
        }
    }

    private static void requireBlockerRole(Role role) {
        if (!ClientBlockScopeResolver.isBlockerRole(role)) {
            throw new ForbiddenException("Only providers can block clients");
        }
    }
}
