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
import com.beautica.moderation.entity.ClientBlock;
import com.beautica.moderation.dto.BlockResponse;
import com.beautica.moderation.dto.ClientBlockState;
import com.beautica.moderation.repository.ClientBlockKeyRow;
import com.beautica.moderation.repository.ClientBlockListRow;
import com.beautica.moderation.repository.ClientBlockRepository;
import com.beautica.moderation.service.ClientBlockScopeResolver.ActorScopes;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 397 — a provider blocks a client (registered account or LINK guest). Storage, list, unblock and the
 * {@link #isClientBlocked} / {@link #isGuestPhoneBlocked} probes phase 398 enforces with. The blocked party is never
 * notified of the block itself (D5); the future bookings a NEW block cancels ({@link ClientBlockFacade}, phase 398
 * user decision 2026-10-28) reach them only as the ordinary neutral provider-decline notice.
 *
 * <p>Target derivation, always from a booking in the caller's scope:
 * <ul>
 *   <li>registered client (APP booking, or — B527 — a guest / walk-in booking attached to the account by its verified
 *       phone) → {@code blocked_user_id} only; the account's VERIFIED phone is never stored on the row;</li>
 *   <li>LINK guest → the HMAC of the OTP-verified {@code guest_phone} + its last four digits;</li>
 *   <li>STAFF walk-in without an account, or a guest whose phone was already erased → 400 (no verified identity).</li>
 * </ul>
 * Matching (B527, D7 — one person, one answer): a registered client counts as blocked when its account row OR the
 * phone-hash row of its VERIFIED phone exists in the scope ({@link #isClientBlocked}, {@link #blockStateFor}, the list /
 * card flag in {@code ProviderClientService}); {@link #unblock} of a registered client deletes both. A GUEST target
 * matches GUEST rows by phone hash only. Rows are never updated. Names, phones and hashes are never logged.
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
    private final UserRepository userRepository;

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
    record BlockTarget(@Nullable UUID userId, @Nullable HashedPhone phone, String displayName,
                       @Nullable HashedPhone verifiedPhone) {

        BlockTarget {
            if ((userId == null) == (phone == null)) {
                throw new IllegalArgumentException("Exactly one of userId / phone must be set");
            }
        }

        /** {@code verifiedPhone}: the account's VERIFIED phone hash (B527 D7), {@code null} when it has none. */
        static BlockTarget client(UUID userId, String displayName, @Nullable HashedPhone verifiedPhone) {
            return new BlockTarget(Objects.requireNonNull(userId), null, displayName, verifiedPhone);
        }

        static BlockTarget guest(HashedPhone phone, String displayName) {
            return new BlockTarget(null, Objects.requireNonNull(phone), displayName, null);
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
        Optional<ClientBlock> row = Optional.empty();
        if (scopes.masterId() != null) {
            row = clientBlockRepository.findInMasterScope(blockId, scopes.masterId());
            deleted = clientBlockRepository.deleteInMasterScope(blockId, scopes.masterId());
        } else if (!scopes.salonIds().isEmpty()) {
            row = clientBlockRepository.findInSalonScopes(blockId, scopes.salonIds());
            deleted = clientBlockRepository.deleteInSalonScope(blockId, scopes.salonIds());
        }
        if (deleted > 0) {
            log.info("Client block {} removed", blockId);
            row.ifPresent(this::clearVerifiedPhoneRow);
        }
    }

    /**
     * B527 (D7) — unblocking a registered client also clears the phone-hash row of the account's verified phone in the
     * same scope, so one unblock lifts the person whichever of the two rows blocked them.
     */
    private void clearVerifiedPhoneRow(ClientBlock removed) {
        if (removed.getBlockedUserId() == null) {
            return;
        }
        userRepository.findVerifiedPhoneById(removed.getBlockedUserId())
                .flatMap(phoneHasher::tryHash)
                .ifPresent(phone -> clientBlockRepository.deleteGuestPhoneInScope(
                        removed.getBlockerMasterId(), removed.getBlockerSalonId(), phone.hash()));
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

    /**
     * Phase 398 (app create) + B527 D7 — is this registered account blocked in this scope: its account row, or (one
     * person, one answer) the phone-hash row of its VERIFIED phone. The second leg costs a PK lookup and one probe, and
     * only runs when the account row is absent.
     */
    @Transactional(readOnly = true)
    public boolean isClientBlocked(BlockScope scope, UUID userId) {
        if (clientBlockRepository.findIdByBlockedUser(scope.masterId(), scope.salonId(), userId).isPresent()) {
            return true;
        }
        return userRepository.findVerifiedPhoneById(userId)
                .flatMap(phoneHasher::tryHash)
                .map(phone -> clientBlockRepository
                        .findIdByGuestPhone(scope.masterId(), scope.salonId(), phone.hash()).isPresent())
                .orElse(false);
    }

    /**
     * Same answer as {@link #isClientBlocked(BlockScope, UUID)} for a caller that already holds the {@code client}
     * entity: the verified phone is read from it (no PK lookup) and the account row + phone-hash row are probed in ONE
     * statement; an account without a verified (hashable) phone costs the single account-row probe.
     */
    @Transactional(readOnly = true)
    public boolean isClientBlocked(BlockScope scope, User client) {
        Optional<HashedPhone> phone = client.getPhoneVerifiedAt() == null
                ? Optional.empty()
                : phoneHasher.tryHash(client.getPhoneNumber());
        if (phone.isEmpty()) {
            return clientBlockRepository.findIdByBlockedUser(scope.masterId(), scope.salonId(), client.getId())
                    .isPresent();
        }
        return clientBlockRepository.findIdByUserOrGuestPhone(
                scope.masterId(), scope.salonId(), client.getId(), phone.get().hash()).isPresent();
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

    /** B507 — the subset of a batch that is blocked: account ids, and the guest phones (as the caller passed them). */
    public record BlockedAmong(Set<UUID> userIds, Set<HashedPhone> phones) {

        static final BlockedAmong NONE = new BlockedAmong(Set.of(), Set.of());

        public BlockedAmong {
            userIds = Set.copyOf(userIds);
            phones = Set.copyOf(phones);
        }

        public boolean isUserBlocked(UUID userId) {
            return userIds.contains(userId);
        }

        public boolean isPhoneBlocked(HashedPhone phone) {
            return phones.contains(phone);
        }
    }

    /**
     * B507 (provider client list) — which of these accounts / guest phones are blocked in this scope, in ONE
     * {@code client_blocks} statement whatever the batch size (empty inputs: none). Per-type identity as everywhere
     * else: accounts match CLIENT rows by id, phones match GUEST rows by hash, never across. The single-lookup probes
     * above are unchanged.
     */
    @Transactional(readOnly = true)
    public BlockedAmong blockedAmong(BlockScope scope, Collection<UUID> userIds, Collection<HashedPhone> phones) {
        if (userIds.isEmpty() && phones.isEmpty()) {
            return BlockedAmong.NONE;
        }
        List<byte[]> hashes = phones.stream().map(HashedPhone::hash).toList();
        List<ClientBlockKeyRow> rows = clientBlockRepository.findBlockedAmong(
                scope.masterId(), scope.salonId(), userIds, hashes);
        Set<UUID> blockedUsers = new HashSet<>();
        Set<String> blockedHashes = new HashSet<>();
        HexFormat hex = HexFormat.of();
        for (ClientBlockKeyRow row : rows) {
            if (row.blockedUserId() != null) {
                blockedUsers.add(row.blockedUserId());
            } else if (row.phoneHash() != null) {
                blockedHashes.add(hex.formatHex(row.phoneHash()));
            }
        }
        Set<HashedPhone> blockedPhones = new HashSet<>();
        for (HashedPhone phone : phones) {
            if (blockedHashes.contains(hex.formatHex(phone.hash()))) {
                blockedPhones.add(phone);
            }
        }
        return new BlockedAmong(blockedUsers, blockedPhones);
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
        // B527: client first — a STAFF walk-in attached to a client account (CLI-62) is blockable as that ACCOUNT
        // (MOD-27 registered branch); a STAFF row without an account still has no verified identity (400).
        User client = booking.getClient();
        if (client != null) {
            return Optional.of(BlockTarget.client(client.getId(),
                    BlockDisplayNames.snapshot(client.getFirstName(), client.getLastName()),
                    verifiedPhoneHashOf(client)));
        }
        if (booking.getBookingSource() == BookingSource.STAFF) {
            return Optional.empty();
        }
        return phoneHasher.tryHash(booking.getGuestPhone())
                .map(phone -> BlockTarget.guest(phone,
                        BlockDisplayNames.snapshot(booking.getGuestName(), booking.getGuestSurname())));
    }

    /** B527 (D7) — the hash of the account's VERIFIED phone; an unverified legacy number was never a block identity. */
    private @Nullable HashedPhone verifiedPhoneHashOf(User client) {
        return client.isPhoneVerified() ? phoneHasher.tryHash(client.getPhoneNumber()).orElse(null) : null;
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
            Optional<UUID> account = clientBlockRepository.findIdByBlockedUser(
                    scope.masterId(), scope.salonId(), target.userId());
            if (account.isPresent() || target.verifiedPhone() == null) {
                return account;
            }
            // B527 D7: the account's verified-phone hash blocks the same person (account row preferred above).
            return clientBlockRepository.findIdByGuestPhone(
                    scope.masterId(), scope.salonId(), target.verifiedPhone().hash());
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
