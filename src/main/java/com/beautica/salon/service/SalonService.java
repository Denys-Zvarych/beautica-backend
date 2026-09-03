package com.beautica.salon.service;

import org.springframework.data.domain.Sort;
import java.util.Set;
import com.beautica.common.web.SortWhitelist;
import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.SalonDeletionBlockedException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.service.LocationQueryService;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.beautica.salon.audit.AuditOutcome;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.audit.StaffClientReferenceViolation;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.SalonAdminResponse;
import com.beautica.salon.dto.SalonInviteHistoryResponse;
import com.beautica.salon.dto.SalonInviteResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.SalonStaffMemberResponse;
import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.service.repository.MasterServiceCountProjection;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.InviteHistoryRow;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.PasswordResetTicketRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalonService {

    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final InviteService inviteService;
    private final InviteTokenRepository inviteTokenRepository;
    private final MasterRepository masterRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final LocalityWriteValidator localityWriteValidator;
    private final MasterService masterService;
    private final CityRepository cityRepository;
    private final LocationQueryService locationQueryService;
    private final CacheManager cacheManager;
    private final AuthorizationService authorizationService;
    private final Clock clock;
    // Audit-fix cycle 2 (LOW — GET /users/me caching). Three methods in this class mutate a
    // `users` row this service does not own: createSalon syncs the owner's locality columns and
    // (on first salon) creates the owner-master row that hasMasterProfile is derived from;
    // removeAdmin and rotateAdmin rewrite users.salon_id. All three stale the user-profile cache.
    private final com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;

    // ── Phase 290 — salon-deletion staff-deactivation cascade ─────────────────────────────────
    // StaffClientReferenceAuditService backs both the fail-closed precondition
    // (runAuditForSalon) and staff-id resolution (resolveSalonStaffUserIds) that
    // deactivateSalon's cascade uses — see that method's javadoc. The remaining four back the
    // per-staff-user session/notification purge: RefreshTokenRepository/DeviceTokenRepository/
    // PasswordResetTicketRepository mirror the exact precedent PasswordResetService.resetPassword
    // already establishes for "revoke everything tied to this account", and
    // TokensValidAfterCache is the read-through cache JwtAuthenticationFilter checks — without
    // evicting it, a just-deactivated staff member's already-issued access token would keep
    // working for up to the cache's TTL despite tokensValidAfter being stamped.
    private final StaffClientReferenceAuditService staffClientReferenceAuditService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PasswordResetTicketRepository passwordResetTicketRepository;
    private final TokensValidAfterCache tokensValidAfterCache;

    // ── Phase 269/293 — salon-deletion booking cascade ────────────────────────────────────────
    // BookingService#declineFutureConfirmedBookingsForSalonClosure is the REUSE-FIRST seam this
    // cascade calls into — it decides which future CONFIRMED bookings need declining and routes
    // each through the existing declineBookingForBatch / AppointmentTransitionService
    // #declineAppointmentItems paths, then enqueues one SALON_CLOSED notification per visit. This
    // is a NEW bean edge (salon.service -> booking.service) but not a circular one: nothing in
    // BookingService's own dependency graph injects SalonService (verified — grep for
    // "salon.service.SalonService" under booking/, common/, master/, notification/ before adding
    // this field turned up only SalonController).
    private final com.beautica.booking.service.BookingService bookingService;

    /**
     * Tombstone email prefix (Phase 291). Combined with {@link #SCRUB_EMAIL_DOMAIN} and the
     * user's own {@code id}, this is the ENTIRE fix for "owner deletes a salon, then cannot
     * re-invite the master who was in it" — see {@link User#scrubPii}'s javadoc for the full
     * mechanism.
     */
    private static final String SCRUB_EMAIL_PREFIX = "deleted+";

    /**
     * RFC 2606 reserves the {@code .invalid} TLD specifically for addresses that are guaranteed
     * to never resolve — unlike {@code .example}/{@code .test}, which are for documentation, this
     * is exactly the "known-dead address" semantics a tombstone needs. A domain ending in
     * {@code .beautica.local} would NOT carry that guarantee (nothing stops a future internal
     * DNS zone from resolving it); this must end in the reserved TLD itself.
     */
    private static final String SCRUB_EMAIL_DOMAIN = "@beautica-deleted.invalid";

    /**
     * Single shared BCrypt hash rotated onto EVERY scrubbed staff row, in every salon deletion,
     * for the lifetime of this JVM (Phase 291 perf audit — CRITICAL). Computed exactly ONCE, here,
     * rather than once per staff member inside {@link #deactivateSalonStaff}'s loop.
     *
     * <p><strong>The bug this replaces.</strong> The original code called
     * {@code passwordEncoder.encode(UUID.randomUUID().toString())} per staff member, sequentially,
     * on the request thread inside the {@code @Transactional} cascade backing
     * {@code DELETE /salons/{salonId}} — measured at 56.04 ms/encode with this deployment's
     * {@code BCryptPasswordEncoder} strength (10, see {@code SecurityConfig#passwordEncoder}):
     * 1.12 s at N=20 staff, 2.80 s at N=50, pure CPU, holding a Hikari connection and a Tomcat
     * worker the whole time. It did not parallelise, cache, or benefit from an index — it got
     * strictly worse with every additional staff member on the salon being deleted.
     *
     * <p><strong>Per-user freshness bought no security property.</strong> Nobody can ever present
     * the plaintext for a tombstoned account either way — the "password" rotated in was a
     * {@code UUID.randomUUID()} value generated and immediately discarded, never stored or
     * transmitted anywhere. A single shared hash is exactly as unauthenticatable as N distinct
     * ones, so computing N of them bought nothing.
     *
     * <p><strong>This MUST stay real, valid BCrypt — never a non-BCrypt sentinel.</strong>
     * {@link com.beautica.auth.AuthService#login} calls {@code passwordEncoder.matches()}
     * <em>before</em> checking {@code user.isActive()} — the comparison against a scrubbed row's
     * hash genuinely executes on every login attempt against a tombstoned address, it is not
     * short-circuited away. A non-BCrypt sentinel (e.g. a literal {@code "SCRUBBED"} string) would
     * return from {@code matches()} in microseconds, while a real BCrypt comparison costs ~56 ms —
     * a timing side-channel that would let a prober distinguish "this address belongs to a
     * scrubbed account" from "this address never existed", fingerprinting tombstoned accounts.
     * Valid-format BCrypt preserves verify-time parity with every other login attempt in the
     * system, closing that channel.
     *
     * <p><strong>Why a fresh per-JVM-boot random value, encoded once here, rather than a
     * hardcoded source literal.</strong> Both are equally safe from a security standpoint — this
     * hash protects nothing (there is no plaintext anyone could ever present that it would
     * accept), so "identical across deployments" carries no exploitable weakness the way it would
     * for a real credential. The random-per-boot form was chosen anyway: it avoids a fixed BCrypt
     * string sitting in version control that a future reviewer has to separately re-derive "this
     * is intentionally not a secret" for, and this repo's own convention is "secrets via env vars,
     * never hardcoded" — a literal here would look identical to a violation of that rule on sight,
     * even though it isn't one. The cost is paid exactly once per JVM lifetime (~56 ms at server
     * boot), not once per scrubbed user, so the O(1)-per-deletion property this fix exists for is
     * unaffected either way.
     *
     * <p>Deliberately constructed with a locally-owned {@link BCryptPasswordEncoder} rather than
     * the shared {@link PasswordEncoder} bean this class used to inject solely for this call: a
     * {@code static final} field initializer runs at class-load time, before Spring has built any
     * bean graph, so the DI container has nothing to hand over yet. That was the class's ONLY use
     * of an injected {@code PasswordEncoder} — with the per-user {@code encode()} call gone, the
     * dependency had nothing left to do, so it was removed from the constructor entirely rather
     * than kept unused (see the phase doc for the corresponding test-fixture cleanup). Strength is
     * pinned to {@code 10} to match {@code SecurityConfig#passwordEncoder}; there is no
     * compile-time link between the two, so if that strength ever changes this literal must be
     * updated by hand.
     */
    private static final String SCRUBBED_PASSWORD_HASH =
            new BCryptPasswordEncoder(10).encode(UUID.randomUUID().toString());

    /**
     * Hard ceiling on rows returned by {@link #listSalonInvites}.
     *
     * <p>The invite-history endpoint is deliberately NOT paginated — a salon's realistic invite
     * count is single- to low-double-digit, so a page cursor would be ceremony the client has to
     * carry for no benefit. But {@code invite_tokens} has no cleanup job, so the underlying table
     * only grows; returning it unbounded would be exactly the unbounded-collection-at-a-public-
     * service-boundary defect Anti-Bug §E3 forbids. This cap is the bound.
     *
     * <p>Trade-off, stated plainly: invite number {@value #MAX_INVITE_HISTORY}+ is dropped from
     * the response. It is NOT dropped silently — {@code SalonInviteHistoryResponse.truncated} is
     * set whenever that happens, so the client can tell an incomplete audit trail from a complete
     * one. Acceptable at current scale; the fix, if a salon ever approaches the cap, is real
     * pagination, not a bigger number.
     */
    private static final int MAX_INVITE_HISTORY = 200;

    /**
     * Ceiling on how many ACTIVE salons one {@code SALON_OWNER} may hold (Perf LOW-3).
     *
     * <p>Nothing bounded the portfolio before, and three unbounded reads hang off it:
     * {@code GET /salons/mine}, {@code GET /{salonId}/sibling-salons}, and — worst — the
     * {@code ownerSalons} cache, whose 1000 entries each hold a whole portfolio in heap. The fix
     * belongs on the WRITE side: paginating the reads would push a picker/hub UI into
     * infinite-scroll for a list that is realistically single-digit, and would not shrink the
     * cached value at all.
     *
     * <p><b>Why 50.</b> The largest real salon chains in the Ukrainian market run on the order of
     * 20–30 branches, and this platform's owner-per-chain model means one {@code users} row per
     * chain, not per branch. 50 sits comfortably above the largest plausible legitimate
     * portfolio — roughly double it — so no real owner reaches it, while still capping the
     * sibling payload, the
     * {@code /mine} response and each cache entry at a bounded size. It is a business ceiling, not
     * a schema constraint: raising it is a one-constant change requiring no migration.
     *
     * <p>Counts ACTIVE salons only, so deactivating a salon frees its slot — a chain that closes a
     * branch can open another.
     */
    public static final int MAX_ACTIVE_SALONS_PER_OWNER = 50;

    @Transactional
    public SalonResponse createSalon(UUID ownerId, CreateSalonRequest request) {
        // PESSIMISTIC_WRITE, not a plain findById (Perf LOW-A). The portfolio cap below is a
        // read-then-write check, and nothing in the schema backs it: N concurrent POST /salons for
        // the same owner each read `count < 50` and each insert, landing the owner arbitrarily far
        // above the ceiling and voiding the bound that findActiveSiblingsBySalonId's plan relies on.
        // Locking the OWNER row first serialises every create for that owner on a row this method
        // already had to load, so the count is taken under the lock and the whole check-then-insert
        // is atomic. The lock is released at commit/rollback; two DIFFERENT owners never contend.
        // See lockOwnerForCreate for why this shape (and not a DB constraint) was chosen.
        var owner = lockOwnerForCreate(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (owner.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may create a salon");
        }

        // Portfolio cap (Perf LOW-3) — checked before anything is validated or persisted, so a
        // capped owner never writes a row. 409, not 400: the request is well-formed; it conflicts
        // with the owner's current state. See MAX_ACTIVE_SALONS_PER_OWNER for the limit's rationale.
        // Runs under the owner row lock taken above, so the count cannot go stale before the insert.
        if (salonRepository.countByOwnerIdAndIsActiveTrue(owner.getId()) >= MAX_ACTIVE_SALONS_PER_OWNER) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "Salon limit reached: an owner may hold at most "
                            + MAX_ACTIVE_SALONS_PER_OWNER + " active salons");
        }

        boolean isFirstSalon = !salonRepository.existsByOwnerId(owner.getId());

        // Phase 12.1: a salon is a discoverable provider — its locality must satisfy the
        // most-specific-node rule before anything is persisted. Validate UNCONDITIONALLY
        // (mirroring updateSalon): an absent cityId is itself a violation (CITY_REQUIRED),
        // so the previous cityId != null guard let an invalid/absent locality slip through
        // at creation only to be blocked later at update. Runs before save() so nothing is
        // persisted on rejection.
        localityWriteValidator.validateProviderLocality(request.toLocalityInput());

        var salon = Salon.builder()
                .owner(owner)
                .name(request.name())
                .description(request.description())
                .city(request.city())
                .region(request.region())
                .address(request.address())
                .cityId(request.cityId())
                .districtId(request.districtId())
                .street(request.street())
                .buildingNo(request.buildingNo())
                .locationNote(request.locationNote())
                .phone(request.phone())
                .instagramUrl(normalizeInstagram(request.instagramUrl()))
                .isActive(true)
                .isPrimary(isFirstSalon)
                .build();

        Salon savedSalon = salonRepository.save(salon);

        // Phase 10.3: sync location to owner's User row so /users/me reflects the salon
        // address. Locality validation already ran unconditionally above (Phase 12.1) —
        // this guard now only governs the User-row sync. userRepository.save is intentionally
        // scoped inside the guard: when no structured location is provided there is nothing to
        // sync, and the multi-salon test asserts that save(owner) is never called unconditionally.
        if (request.cityId() != null) {
            owner.setCityId(request.cityId());
            owner.setDistrictId(request.districtId());
            owner.setStreet(request.street());
            owner.setBuildingNo(request.buildingNo());
            owner.setLocationNote(request.locationNote());
            userRepository.save(owner);
        }

        // Evict ownerSalons cache after commit so a concurrent reader cannot repopulate
        // with stale data inside the commit window (Anti-Bug Playbook §F rule 2).
        //
        // Keyed off the persisted ROW, not off the `ownerId` parameter (Phase 283). The two are
        // provably equal here — the SALON_OWNER role check above rejects any other caller and the
        // row is built with `.owner(owner)` — but deriving every ownerSalons eviction in this class
        // from `salon.getOwner()` means the key cannot silently diverge from the @Cacheable key if
        // this method's gate is ever widened the way updateSalon's was.
        evictOwnerSalonsCacheAfterCommit(ownerIdOf(savedSalon));

        // Auto-create the owner's SALON_OWNER-type master profile on first-salon creation.
        // Passes already-loaded entities to avoid redundant DB round-trips (Finding 3/4).
        // Runs inside the same @Transactional boundary: if master creation fails, the
        // salon row is rolled back too (atomicity per Phase 12.2 architecture decision).
        if (isFirstSalon) {
            masterService.createMasterForOwner(owner, savedSalon);
        }

        // Audit-fix cycle 2 — this method writes the owner's own `users` row twice over, and
        // GET /users/me is cached as of this cycle:
        //   1. the locality sync above (cityId/districtId/street/buildingNo/locationNote — five
        //      fields carried verbatim by UserProfileResponse), and
        //   2. on first salon, createMasterForOwner, which flips hasMasterProfile to true.
        // Unconditional rather than mirroring the `request.cityId() != null` / `isFirstSalon`
        // guards: an evict that fires when nothing changed costs one recompute of a 5-minute key,
        // whereas re-deriving those two guards on every future edit of this method is how the
        // eviction goes missing. (createMasterForOwner also evicts on its own path — both are
        // idempotent per-key evicts.)
        userProfileCacheEvictor.evictAfterCommit(ownerIdOf(savedSalon));

        return SalonResponse.from(savedSalon, resolveOblastId(savedSalon.getCityId()));
    }

    /**
     * Resolves the parent oblast id of a single city by its id, for the {@code oblastId}
     * surfaced on {@link SalonResponse}. Delegates to the SHARED cached resolver
     * {@link LocationQueryService#resolveCityOblastId(UUID)} (Phase 240 perf MEDIUM finding)
     * rather than querying {@link CityRepository} directly — that method mirrors
     * {@code MasterService#resolveOblastId(UUID)}, which delegates to the exact same shared
     * resolver (REUSE-FIRST: one cached implementation, not two private per-service copies).
     * Single-row by PK — the create/update paths touch exactly one salon, so this is not the §E
     * "per-row in a collection" concern; {@link #getOwnerSalons} uses the batch
     * {@link CityRepository#findOblastIdsByIdIn(java.util.Collection)} instead.
     *
     * <p>The {@code cityId == null} guard MUST stay here, in front of the call: the shared
     * resolver's {@code @Cacheable} proxy cannot accept a {@code null} Caffeine key.
     *
     * @param cityId the salon's {@code cityId}, possibly {@code null}
     * @return the resolved oblast id, or {@code null} when {@code cityId} is {@code null}
     *         or does not resolve to a known city
     */
    private UUID resolveOblastId(UUID cityId) {
        if (cityId == null) {
            return null;
        }
        return locationQueryService.resolveCityOblastId(cityId);
    }

    // Eviction helpers are registered as post-commit callbacks rather than via @CacheEvict.
    // @CacheEvict fires before the transaction commits, allowing a concurrent reader
    // to repopulate the cache with stale data within the commit window (Anti-Bug §F rule 2).

    /**
     * The {@code ownerSalons} cache key for a salon write — <b>always the salon's OWNER</b>,
     * never the actor who performed the write (Phase 283).
     *
     * <p>{@link #getOwnerSalons} is {@code @Cacheable(value = "ownerSalons", key = "#ownerId")},
     * so an eviction keyed on anything else evicts nothing at all. {@link #createSalon} and
     * {@link #deactivateSalon} are structurally safe (both reject a non-{@code SALON_OWNER}
     * caller, so their {@code actorId} <em>is</em> the owner), but {@link #updateSalon} is gated
     * by {@code @authz.canManageSalon}, which also admits the {@code SALON_ADMIN} assigned to the
     * salon. Passing that actor's id evicted a key nobody reads, and {@code GET /salons/mine}
     * kept serving the owner the pre-edit name/address for the full 5-minute TTL.
     *
     * <p>Costs no extra statement: {@code salons.owner_id} is {@code NOT NULL REFERENCES users(id)}
     * (V3, never relaxed since), and Hibernate reads the identifier straight off the uninitialised
     * {@code @ManyToOne} proxy without a {@code users} select — the same read {@code
     * SalonResponse.from} already performs on every one of these paths. Never {@code null} for a
     * persisted row; a broken invariant fails the write loudly rather than silently under-evicting.
     */
    private static UUID ownerIdOf(Salon salon) {
        return salon.getOwner().getId();
    }

    private void evictOwnerSalonsCacheAfterCommit(UUID ownerId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache cache = cacheManager.getCache("ownerSalons");
                if (cache != null) {
                    cache.evict(ownerId);
                }
            }
        });
    }

    private void evictSalonDetailCacheAfterCommit(UUID salonId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache cache = cacheManager.getCache("salon-detail");
                if (cache != null) {
                    cache.evict(salonId);
                }
            }
        });
    }

    /**
     * Evicts <b>both halves</b> of the salon discovery cache after commit —
     * {@code search:salons:browse} and {@code search:salons:q}.
     *
     * <p>Iterates {@link SearchCacheNames#SALONS_ALL} rather than naming the caches
     * inline: the population split (browse vs free-text) is a caching-layer concern
     * and clearing only one half would leave the deactivated salon reachable through
     * the other, which no test on this path would notice.</p>
     *
     * <p>Blanket eviction (not per-key) is intentional: search results are a filtered subset of
     * all active salons. When a salon is deactivated the cached page may contain it, and the only
     * safe invalidation strategy without re-querying is to drop the whole cache so the next
     * request rebuilds it from the DB. The cache TTL is short and this path is write-rare,
     * so thundering-herd risk is negligible (PERF-HIGH-2).</p>
     */
    private void evictSearchSalonsCacheAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String cacheName : SearchCacheNames.SALONS_ALL) {
                    Cache cache = cacheManager.getCache(cacheName);
                    if (cache != null) {
                        cache.clear();
                    }
                }
            }
        });
    }

    @Transactional
    public SalonResponse updateSalon(UUID actorId, UUID salonId, UpdateSalonRequest request) {
        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        // Ownership already enforced by @PreAuthorize("... @authz.canManageSalon(...)") on
        // the controller — no redundant DB round-trip needed here.

        // Phase 10.6: a salon is a discoverable provider — its persisted
        // locality must satisfy the most-specific-node rule (city mandatory;
        // district mandatory iff the city has urban districts; district a child
        // of the city). The legacy free-text city/region/address are NO LONGER
        // written (kept nullable per Phase 10.3, no longer the source of truth).
        //
        // PATCH semantics: a null cityId means "locality not included in this update", NOT
        // "clear my city". Validating/writing the FK pair unconditionally against the raw
        // (possibly-omitted) request reproduced the reported bug: a PATCH that only touches
        // name/description/phone/etc. and omits cityId (the mobile client does not always
        // resend it) got a 400 BusinessException ("City is required") even though the salon
        // already has a valid city — V150/V151 guarantee every existing salon row does. Unlike
        // CLIENT (optional locality), a salon's city is mandatory, so there is always a valid
        // existing value to fall back to; only (re)validate and (re)write cityId/districtId when
        // the caller actually supplies a cityId. Mirrors the CLIENT/INDEPENDENT_MASTER fix in
        // UserService#writeLocalityFields (commit 9ed0559).
        // Same-shape PATCH-drop bug as locationNote below, but at the FK-pair level:
        // districtId without cityId can never be validated (the taxonomy lookup needs
        // both) nor safely written (writing districtId alone could orphan it against the
        // salon's EXISTING city). Silently dropping it would return a 200 while the
        // caller believes their district change applied — fail loud instead.
        if (request.cityId() == null && request.districtId() != null) {
            throw new BusinessException(
                    "Changing districtId requires supplying cityId in the same request");
        }
        if (request.cityId() != null) {
            localityWriteValidator.validateProviderLocality(request.toLocalityInput());
            salon.setCityId(request.cityId());
            salon.setDistrictId(request.districtId());
        }

        if (request.name() != null) {
            salon.setName(request.name());
        }
        if (request.description() != null) {
            salon.setDescription(request.description());
        }
        // street/buildingNo are @NotBlank on UpdateSalonRequest, so they can never be
        // absent from a validated request — the locationNote bug below is unreachable here,
        // no null-guard needed.
        salon.setStreet(request.street());
        salon.setBuildingNo(request.buildingNo());
        // locationNote is OPTIONAL on the DTO: null means "not included in this PATCH,
        // leave unchanged" (mirrors the cityId contract above), while "" is the explicit
        // clear signal — see UpdateSalonRequest#locationNote javadoc. An unconditional
        // write here previously destroyed a saved note on any PATCH that omitted it
        // (e.g. a name-only edit, or an address edit that only touched street/building).
        if (request.locationNote() != null) {
            salon.setLocationNote(request.locationNote());
        }
        if (request.phone() != null) {
            salon.setPhone(request.phone());
        }
        if (request.instagramUrl() != null) {
            salon.setInstagramUrl(normalizeInstagram(request.instagramUrl()));
        }

        // `salon` was loaded via findById in THIS @Transactional, so it is a managed entity —
        // Hibernate dirty-checking flushes the setter mutations on commit. The explicit save()
        // was a redundant no-op write (PERF-LOW); save() returned the same managed instance, so
        // mapping the in-memory `salon` is equivalent. The findById load is retained (existence).
        SalonResponse result = SalonResponse.from(salon, resolveOblastId(salon.getCityId()));

        // Evict after commit so a concurrent reader cannot repopulate stale data within the
        // commit window. Replaces the @CacheEvict annotations that fired pre-commit (PERF-MEDIUM-2).
        //
        // Keyed on the SALON'S OWNER, never on `actorId` — see ownerIdOf. This gate
        // (@authz.canManageSalon) admits the assigned SALON_ADMIN as well as the owner, so
        // `actorId` was the wrong cache key on every admin PATCH (Phase 283).
        evictOwnerSalonsCacheAfterCommit(ownerIdOf(salon));
        evictSalonDetailCacheAfterCommit(salonId);

        return result;
    }

    /**
     * Plain (uncached) entity fetch — the graph query behind {@link #getPublicSalon(UUID)} and
     * a reusable load point for any future internal caller that needs the raw {@link Salon}
     * entity with its owner association initialised.
     *
     * <p><b>Deliberately NOT {@code @Cacheable} (Phase 240 CRITICAL fix).</b> It previously
     * carried {@code @Cacheable(value = "salon-detail", ...)}, but its only production caller was
     * {@link #getPublicSalon(UUID)} calling it as a plain in-class {@code this.getSalonEntity(...)}
     * — a Spring self-invocation. {@code CacheConfig} wires proxy-based {@code @EnableCaching}
     * (no AspectJ mode, see {@code CacheConfig:54}), so a self-invoked call never crosses the
     * CGLIB proxy and the cache annotation was silently inert on the one path that mattered:
     * {@code GET /salons/{salonId}} ran {@code findByIdAndIsActiveTrueWithOwner} on EVERY request,
     * and the {@code sync = true} thundering-herd guard never engaged. It stayed green because
     * {@code SalonServiceCacheTest}/{@code ReviewCacheEvictionIT} called this method directly
     * through the injected (proxied) bean reference — exactly the one call shape that still
     * worked, and exactly the shape production traffic never takes.
     *
     * <p>Fix: the {@code @Cacheable} boundary moved to {@link #getPublicSalon(UUID)} itself (see
     * its Javadoc) rather than (a) pushing DTO assembly into {@code SalonController} — every
     * controller in this codebase stays a thin HTTP-only shim with zero {@code Response.from(...)}
     * calls, and moving it here would be the first exception — or (c) self-injecting a {@code @Lazy}
     * proxy of this same bean — no self-injection precedent exists anywhere in this codebase, and
     * it was unnecessary once caching could just live on the method that is actually called
     * externally. Eviction is untouched: {@link #evictSalonDetailCacheAfterCommit(UUID)},
     * {@code ReviewEventListener}, and {@code SalonStaffRatingListener} all evict the
     * {@code "salon-detail"} cache by name + {@code salonId} key, agnostic of whether the cached
     * value is a {@link Salon} entity or a {@link PublicSalonResponse} DTO.
     */
    @Transactional(readOnly = true)
    public Salon getSalonEntity(UUID salonId) {
        return salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));
    }

    /**
     * Builds the unauthenticated public view of a salon for {@code GET /salons/{salonId}} — the
     * highest-traffic {@code permitAll} endpoint in this set.
     *
     * <p>DTO assembly is kept in the service (not the controller) precisely because it needs a
     * second collaborator call ({@link #resolveOblastId}) beyond the entity fetch — mirrors
     * {@link #createSalon}/{@link #updateSalon}, which likewise resolve {@code oblastId} before
     * handing back the response. Single-row: this endpoint returns exactly one salon, so the
     * per-row {@link #resolveOblastId(UUID)} is correct here — {@link #getOwnerSalons} is the
     * only caller that needs the batch {@link #resolveOblastIdsByCityIds(Set)} sibling.
     *
     * <p>{@code @Cacheable} lives HERE, not on {@link #getSalonEntity(UUID)} (Phase 240 CRITICAL
     * fix — see that method's Javadoc for the self-invocation bug this replaces). This is the
     * method the controller actually calls through the Spring proxy, so caching the whole DTO here
     * — rather than the entity one level down — needs no self-injection and introduces no new
     * pattern: it is just an ordinary {@code @Cacheable} on the method an external caller invokes.
     * {@code sync = true} (unchanged from the prior placement) collapses the thundering herd on a
     * popular salon when {@code ReviewEventListener#onReviewCreated} evicts this entry by key
     * (Anti-Bug §F-7); {@code unless}/{@code condition} are absent, which {@code sync = true}
     * requires.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "salon-detail", key = "#salonId", sync = true)
    public PublicSalonResponse getPublicSalon(UUID salonId) {
        Salon salon = getSalonEntity(salonId);
        return PublicSalonResponse.from(salon, resolveOblastId(salon.getCityId()));
    }

    @Transactional
    public InviteResponse inviteMaster(UUID actorId, UUID salonId, String email, Role role) {
        // Ownership already enforced by @PreAuthorize("... @authz.canManageSalon(...)") on
        // the controller — no redundant DB round-trip needed here.
        salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        var inviteRequest = new InviteRequest(email, salonId, role);
        return inviteService.sendInvite(inviteRequest, actorId);
    }

    /**
     * Properties a caller may sort a salon's public master list by. Shares a repository method
     * (and therefore a {@code Master} root with a {@code JOIN FETCH}ed {@code user}) with
     * {@code MasterService#getMastersByPage}, so the two whitelists are deliberately identical —
     * an unguarded dotted sort like {@code user.passwordHash} would otherwise resolve here and
     * order rows by a credential hash (see {@link SortWhitelist}).
     */
    private static final Set<String> SORTABLE_MASTER_PROPERTIES =
            Set.of("avgRating", "reviewCount", "createdAt");

    /** Applied when the caller supplies no {@code sort}; the query itself has no {@code ORDER BY}. */
    private static final Sort DEFAULT_MASTER_SORT = Sort.by(Sort.Direction.DESC, "avgRating");

    /** Mandatory unique trailing column, so OFFSET paging cannot duplicate or skip tied rows. */
    private static final Sort MASTER_ID_TIEBREAKER = Sort.by(Sort.Direction.ASC, "id");

    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersBySalon(UUID salonId, Pageable pageable) {
        Pageable safePageable = SortWhitelist.apply(
                pageable, SORTABLE_MASTER_PROPERTIES, DEFAULT_MASTER_SORT, MASTER_ID_TIEBREAKER);
        return masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, safePageable)
                .map(MasterSummaryResponse::from);
    }

    /**
     * Management-scoped staff roster for {@code GET /{salonId}/staff} (Phase 21.5) — masters
     * (any type bound to the salon) AND {@code SALON_ADMIN}s in one read, so the mobile Персонал
     * tab and staff-detail screen never need two round trips or two response shapes.
     *
     * <p>REUSE, not a parallel read path: masters are sourced via the SAME
     * {@link MasterRepository#findBySalonIdAndIsActiveTrueWithUser} query
     * {@link #getMastersBySalon} already uses (called with {@link Pageable#unpaged()} — a salon's
     * staff roster is bounded by the salon's actual headcount, never the unbounded-collection
     * concern §E-3 guards against; this is the same reasoning that already lets
     * {@link #listSalonInvites} returns a capped {@code List} for one salon). Admins are
     * sourced via {@link UserRepository#findBySalonIdAndRoleAndIsActiveTrue}. {@code serviceCount} per master
     * comes from {@link MasterServiceRepository#countActiveByMasterIdIn} — one batch
     * {@code GROUP BY} query for the whole roster, never a per-master count (Anti-Bug §E-3).
     */
    @Transactional(readOnly = true)
    public List<SalonStaffMemberResponse> getSalonStaff(UUID salonId) {
        List<Master> masters = masterRepository
                .findBySalonIdAndIsActiveTrueWithUser(salonId, Pageable.unpaged())
                .getContent();

        Map<UUID, Long> serviceCountByMasterId = resolveServiceCounts(masters);

        List<SalonStaffMemberResponse> staff = masters.stream()
                .map(master -> SalonStaffMemberResponse.fromMaster(
                        master, serviceCountByMasterId.getOrDefault(master.getId(), 0L)))
                .collect(Collectors.toCollection(ArrayList::new));

        userRepository.findBySalonIdAndRoleAndIsActiveTrue(salonId, Role.SALON_ADMIN).stream()
                .map(SalonStaffMemberResponse::fromAdmin)
                .forEach(staff::add);

        return staff;
    }

    /**
     * Batch-resolves {@link #getSalonStaff}'s per-master {@code serviceCount} in ONE query
     * (Anti-Bug §E-3) — never a per-master {@code COUNT} call inside the mapping loop above.
     */
    private Map<UUID, Long> resolveServiceCounts(List<Master> masters) {
        if (masters.isEmpty()) {
            return Map.of();
        }
        List<UUID> masterIds = masters.stream().map(Master::getId).toList();
        return masterServiceRepository.countActiveByMasterIdIn(masterIds).stream()
                .collect(Collectors.toMap(
                        MasterServiceCountProjection::getMasterId,
                        MasterServiceCountProjection::getServiceCount));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "ownerSalons", key = "#ownerId")
    public List<SalonResponse> getOwnerSalons(UUID ownerId) {
        return toSalonResponses(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId));
    }

    /**
     * Active salons sharing {@code salonId}'s owner, <b>excluding {@code salonId} itself</b>
     * (Phase 21.3b — {@code GET /salons/{salonId}/sibling-salons}). Backs the mobile
     * "rotate an admin to another salon" destination picker (mobile Phase 21.6): without it the
     * client has no way to enumerate the legal destinations of
     * {@code PATCH /salons/{salonId}/admins/{userId}/salon} — {@code GET /salons/mine} is
     * {@code SALON_OWNER}-only (403 for the admin who may legitimately perform the rotation) and
     * is scoped to the <em>caller's</em> portfolio, not to a particular salon's owner.
     *
     * <p><b>Self is EXCLUDED, by design.</b> The picker chooses a rotation <em>destination</em>,
     * and {@link #rotateAdmin} rejects {@code destinationSalonId.equals(salonId)} with a 400
     * ("Already assigned to this salon"). Returning the source salon would render an option that
     * is guaranteed to fail. The exclusion lives in the query predicate
     * ({@link SalonRepository#findActiveSiblingsBySalonId}), not in a caller-side filter,
     * so it cannot be skipped by a future second caller.
     *
     * <p><b>Authorization is the controller's, not this method's.</b> The identical
     * {@code hasAnyRole('SALON_OWNER','SALON_ADMIN') and canManageSalon(authentication, #salonId)}
     * {@code @PreAuthorize} gate that guards
     * {@code GET /{salonId}/staff} is reused verbatim, so a denied caller never reaches here
     * (§E-4 — this method is deliberately unscoped and must never be called from an ungated path).
     * That gate places the caller inside exactly the trust boundary {@link #rotateAdmin} already
     * operates in: an actor able to move an admin into a sibling salon can already learn that
     * salon's identity by attempting the mutation.
     *
     * <p><b>The read is coupled to self-rotation staying legal.</b> The paragraph above justifies
     * the disclosure by "the caller could learn this by attempting the mutation" — and for the
     * {@code SALON_ADMIN} half of the gate that argument rests on ONE specific shipped behaviour:
     * {@link #rotateAdmin} has <b>no self-guard</b>, unlike its sibling {@link #removeAdmin} (which
     * bans {@code actorId.equals(userId)} outright), so an admin may move their <em>own</em>
     * assignment into a sibling salon. That is intentional and pinned by
     * {@code SalonAdminRotationIntegrationTest#should_return200_when_adminRotatesOwnSalonToAnotherSalonOfSameOwner};
     * it is also the invariant {@code ActorSalonAssignmentMemo} (see its class Javadoc, "No
     * authorization decision is taken after the value could change within a request") documents.
     * <b>If self-rotation is ever restricted</b> — a self-guard added to {@code rotateAdmin}, or
     * the admin arm dropped from its {@code @PreAuthorize} — this endpoint stops being a view onto
     * something the caller could already reach and becomes a real portfolio-enumeration leak for an
     * admin assigned to a single salon. Revisit the admin arm of this endpoint's gate in the same
     * change; nothing else points at that dependency.
     *
     * <p><b>Deliberately NOT {@code @Cacheable}</b>, unlike {@link #getOwnerSalons}. Its natural key
     * is {@code salonId}, but the result changes on a write to <em>any</em> sibling
     * (create/update/deactivate), so a correct {@code @CacheEvict} would have to fan out across the
     * whole portfolio on every salon write (§F-1). One index-served query per picker open is
     * cheaper than that eviction surface.
     *
     * <p><b>Nor is it served off the {@code ownerSalons} cache</b> (re-assessed and rejected a
     * second time, Phase 283). {@link #getOwnerSalons}'s cached value IS a strict superset of this
     * result, and resolving the owner from {@code salonId} is sound for the admin caller too (the
     * owner is a column on the salon row the caller has already been authorized against).
     *
     * <p>Staleness — the original objection — is no longer the blocker. It used to be:
     * {@link #updateSalon} evicted {@code ownerSalons} under the <b>actor's</b> id, and the actor
     * may be a {@code SALON_ADMIN} whose id is not the cache key, so an admin renaming a salon left
     * the owner's entry stale for the full 5-minute TTL. Phase 283 fixed that at the source — every
     * {@code salons} write in this class now evicts under {@code ownerIdOf(salon)} — and those three
     * methods ({@link #createSalon}, {@link #updateSalon}, {@link #deactivateSalon}) are the ONLY
     * writers of the table: no {@code delete} path exists, and no other service holds a
     * {@code Salon} setter call. Every field {@code SiblingSalonOption} carries
     * ({@code name}/{@code street}/{@code buildingNo}) plus row membership ({@code isActive}) is
     * therefore fully covered.
     *
     * <p><b>What blocks it now is that it is not actually cheaper for THIS endpoint's caller.</b>
     * The reuse shape is "resolve owner ({@link SalonRepository#findOwnerIdById}), then read the
     * cached portfolio" — one statement on a cache HIT, three on a MISS
     * ({@code findOwnerIdById} + {@code findAllByOwnerIdAndIsActiveTrue} + the batch oblast
     * resolution inside {@link #toSalonResponses}). Today's shape is unconditionally ONE statement.
     * The hit rate decides it, and it is structurally poor here: {@code ownerSalons} is populated
     * almost exclusively by {@code GET /salons/mine}, which is {@code SALON_OWNER}-only — while the
     * caller this picker exists for is the {@code SALON_ADMIN} (see the gate paragraph above), for
     * whom the owner's entry is cold unless that owner happened to open their own hub inside the
     * last 5 minutes. So the primary caller would trade one query for three. It would also move the
     * {@code s.id <> :salonId} exclusion out of the query predicate into a caller-side filter — the
     * exact coupling {@link SalonRepository#findActiveSiblingsBySalonId} and
     * {@code SalonSiblingRotationParityIT} exist to keep tight — and require an {@code @Lazy self}
     * reference to cross the caching proxy, the self-invocation hazard that already produced one
     * CRITICAL cache bug on this class (see {@link #getSalonEntity}).
     */
    @Transactional(readOnly = true)
    public List<SiblingSalonOption> getSiblingSalons(UUID salonId) {
        // No mapping step: the repository query is a JPQL constructor projection that builds
        // SiblingSalonOption in the database round trip itself (Perf LOW-B), so there is no Salon
        // entity here to map from — and therefore no association a future edit could dereference.
        return salonRepository.findActiveSiblingsBySalonId(salonId);
    }

    /**
     * {@code Salon -> SalonResponse} mapping for {@link #getOwnerSalons} ({@code GET /salons/mine}).
     *
     * <p>Batch-resolves {@code oblastId} for the whole list in ONE query rather than one
     * {@code findByIdWithOblast} per salon (§E — never a per-element repository call in a loop).
     *
     * <p>Kept as a named method rather than inlined back into its single caller: it also documents
     * the batch-vs-per-row contract in one place. {@link #getSiblingSalons} no longer routes
     * through it — its response narrowed to {@link SiblingSalonOption}, which carries no
     * {@code oblastId}, so that path now issues NO oblast query at all (one statement per picker
     * open instead of two).
     */
    private List<SalonResponse> toSalonResponses(List<Salon> salons) {
        Set<UUID> cityIds = salons.stream()
                .map(Salon::getCityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UUID> oblastIdByCityId = resolveOblastIdsByCityIds(cityIds);

        return salons.stream()
                .map(salon -> SalonResponse.from(salon, oblastIdByCityId.get(salon.getCityId())))
                .toList();
    }

    /**
     * Batch sibling of {@link #resolveOblastId(UUID)} — resolves every distinct city id in
     * one {@code IN (...)} query via {@link CityRepository#findOblastIdsByIdIn(java.util.Collection)}
     * instead of one {@code findByIdWithOblast} per salon, so {@link #getOwnerSalons} issues
     * exactly one oblast-resolution query regardless of how many salons the owner has.
     *
     * @param cityIds distinct, non-null city ids appearing across the owner's salons
     * @return a {@code cityId -> oblastId} map; empty when {@code cityIds} is empty
     */
    private Map<UUID, UUID> resolveOblastIdsByCityIds(Set<UUID> cityIds) {
        // A plain HashMap throughout — including the empty-input short-circuit — is deliberate:
        // Map.of() rejects a null get() key with an NPE (Objects.requireNonNull on lookup), and
        // salon.getCityId() is null for any salon with no locality set, so the caller's
        // oblastIdByCityId.get(salon.getCityId()) must tolerate a null key.
        Map<UUID, UUID> result = new HashMap<>();
        if (cityIds.isEmpty()) {
            return result;
        }
        for (Object[] row : cityRepository.findOblastIdsByIdIn(cityIds)) {
            result.put((UUID) row[0], (UUID) row[1]);
        }
        return result;
    }

    /**
     * Deactivates a salon (Phase 290 — the first destructive write in the salon-deletion
     * cascade). Beyond the {@code salons} row itself, this also deactivates the salon's own
     * staff — see {@link #deactivateSalonStaff(UUID, UUID)} for the full scope — and, as of
     * Phase 269/293, declines every future {@code CONFIRMED} booking at the salon and notifies
     * the affected clients — see {@link com.beautica.booking.service.BookingService
     * #declineFutureConfirmedBookingsForSalonClosure} for the full scope (D1-D12 of that phase).
     * Catalogue/favourites cleanup, media purge and the rest of the cascade remain separate,
     * later phases (294-298) — see {@code docs/backend-phases/phase-290-*.md}
     * {@code ## Out of scope}.
     *
     * @throws NotFoundException             if {@code ownerId} does not resolve to a user, or if
     *                                        {@code salonId} does not resolve to a salon owned by
     *                                        {@code ownerId}
     * @throws ForbiddenException             if the caller is not a {@code SALON_OWNER}
     * @throws SalonDeletionBlockedException if Phase 289's salon-scoped staff-as-client safety
     *                                        audit finds a violation for this salon — the whole
     *                                        deletion aborts before any mutation runs
     */
    @Transactional
    public void deactivateSalon(UUID ownerId, UUID salonId) {
        var caller = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (caller.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may deactivate a salon");
        }

        var salon = salonRepository.findByIdAndOwnerId(salonId, ownerId)
                .orElseThrow(() -> new NotFoundException("Salon not found or access denied"));

        // Idempotency guard (Phase 290, pulled forward from the future Phase 298 transaction
        // contract because double-scrubbing becomes POSSIBLE the moment this phase ships a
        // second mutation beyond the salon flag). A second DELETE on an already-inactive salon
        // must be a no-op: without this guard, every repeat call would re-run the staff cascade
        // below against already-deactivated masters/users — re-purging refresh/device tokens and
        // re-stamping tokensValidAfter for no behavioural change, plus a wasted audit query.
        if (!salon.isActive()) {
            return;
        }

        // Fail-closed precondition (Phase 290) — Phase 289's SALON-SCOPED audit only, never the
        // platform-wide sweep (StaffClientReferenceAuditRepository's class javadoc: the
        // platform-wide shape Seq Scans `bookings` and must never run on a request path). A
        // VIOLATIONS_FOUND outcome aborts the ENTIRE deletion before any mutation below runs.
        StaffClientReferenceAuditResult audit = staffClientReferenceAuditService.runAuditForSalon(salonId);
        if (audit.outcome() == AuditOutcome.VIOLATIONS_FOUND) {
            // Correlated, not independent (phase 289 finding): reviews/client_reviews FK back to
            // the same booking, so one bad row can trip more than one reference-type check for
            // the SAME staff member. Report distinct staff members implicated, not violation rows.
            long affectedStaffCount = audit.violations().stream()
                    .map(StaffClientReferenceViolation::userId)
                    .distinct()
                    .count();
            throw new SalonDeletionBlockedException((int) affectedStaffCount);
        }

        // `salon` was loaded via findByIdAndOwnerId in THIS @Transactional, so it is a managed
        // entity — Hibernate dirty-checking flushes the isActive mutation on commit. The explicit
        // save() was a redundant no-op write (PERF-LOW). The findByIdAndOwnerId load is retained:
        // it enforces existence + ownership scoping and cannot be dropped.
        salon.setActive(false);

        deactivateSalonStaff(ownerId, salonId);

        // Phase 269/293 — decline every future CONFIRMED booking at this salon and notify the
        // affected clients (one SALON_CLOSED entry per VISIT, D12). Runs inside THIS transaction,
        // after the idempotency guard and the fail-closed Phase 289 audit precondition above — a
        // second DELETE on an already-inactive salon never reaches this line, and neither does a
        // salon a VIOLATIONS_FOUND audit blocked.
        bookingService.declineFutureConfirmedBookingsForSalonClosure(ownerId, salonId);

        // Evict after commit — replaces pre-commit @CacheEvict annotations (PERF-MEDIUM-2).
        // Also evicts search:salons because a deactivated salon must not appear in discovery
        // results for the remaining TTL window (PERF-HIGH-2).
        //
        // Keyed off the loaded ROW, not off the `ownerId` parameter (Phase 283) — equal here by
        // construction (findByIdAndOwnerId scopes the load, and only a SALON_OWNER reaches this
        // line), but uniform with createSalon/updateSalon so no eviction in this class is ever
        // keyed on an actor again.
        evictOwnerSalonsCacheAfterCommit(ownerIdOf(salon));
        evictSalonDetailCacheAfterCommit(salonId);
        evictSearchSalonsCacheAfterCommit();
    }

    /**
     * Deactivates {@code salonId}'s own staff — both the {@code masters} rows AND the backing
     * {@code users} accounts — as part of {@link #deactivateSalon}'s cascade (Phase 290).
     *
     * <h3>Masters</h3>
     * Reuses {@link MasterService#deactivateMasters} over EVERY currently-active master row for
     * this salon (REUSE-FIRST — this does not reimplement that method's cache-eviction and
     * rating-recalculation side effects). That one batch call uniformly covers two distinct rows:
     * <ul>
     *   <li>{@code SALON_MASTER}-type rows — the salon's actual staff.</li>
     *   <li>The owner's OWN {@code SALON_OWNER}-type master row, if "I also work as a master"
     *       (Phase 12.x) is enabled in THIS salon.</li>
     * </ul>
     * Neither row type is excluded from the batch — deactivating the owner's own row too is
     * correct and intended (Phase 290 D3 in the phase doc). {@code masters.salon_id} is retained
     * by {@code deactivateMasters} — it is never nulled, which is exactly what this cascade
     * needs: nulling it would read as promotion to {@code INDEPENDENT_MASTER}, the opposite of
     * the deletion intent.
     *
     * <p>Perf pass (Phase 290 findings #2/#3, see {@code MasterService#deactivateMasters}'
     * javadoc for the mechanism): passing the already-loaded {@code salonMasters} list straight
     * to the batch overload avoids a redundant {@code findByIdWithUserAndSalon} SELECT per master
     * (the query below already JOIN-FETCHed {@code user}) AND collapses what was N
     * {@code SalonStaffChangedEvent} publishes — each triggering its own
     * {@code REQUIRES_NEW} salon-rating recalculation and {@code reviews-by-salon} cache scan —
     * into exactly one for the whole batch.
     *
     * <h3>Users</h3>
     * {@code SALON_MASTER}/{@code SALON_ADMIN} accounts ONLY, resolved via
     * {@link StaffClientReferenceAuditService#resolveSalonStaffUserIds} — Phase 289's
     * {@code findSalonStaffUserIds} reused, not re-derived. That resolution structurally can
     * NEVER include the salon's owner: the owner's {@code role} is {@code SALON_OWNER}, which
     * satisfies neither the {@code masters.salon_id}-joined {@code SALON_MASTER} predicate nor
     * the {@code users.salon_id}-scoped {@code SALON_ADMIN} predicate. The owner-account
     * exemption (Phase 290 D3, locked — the owner's {@code users} row is NEVER touched here) is
     * therefore a structural property of the query, not a branch this method has to remember.
     *
     * <p>For each resolved staff user: {@code isActive = false}; {@code tokensValidAfter} is
     * stamped so an already-issued access token stops working immediately rather than merely at
     * its natural TTL expiry ({@code JwtAuthenticationFilter} has no {@code isActive} check —
     * only the {@code tokensValidAfter} one, via {@link TokensValidAfterCache}); refresh tokens
     * are purged; any outstanding password-reset ticket is invalidated (defence in depth — a
     * deactivated account should not be able to complete a reset that was in flight); and device
     * tokens are purged so the scrubbed account stops receiving push notifications addressed to a
     * salon it no longer belongs to. As of Phase 291, each resolved staff user's PII is also
     * scrubbed via {@link User#scrubPii} — see that method's javadoc for the full column-by-column
     * contract; this is the change that actually fixes the "cannot re-invite" bug.
     *
     * <p>Perf pass (Phase 290 finding #1): the {@code isActive}/{@code tokensValidAfter} field
     * mutations and their per-user cache-eviction registrations stay in the per-user loop — each
     * cache key is a different person, so that fan-out is correctly proportionate to N. The
     * session/notification purge below it is NOT per-user DML any more: {@code deleteByUserId} /
     * {@code deleteByUserId} / {@code markAllUsedByUserId} each issued their own JDBC round trip
     * per staff member (3N total). {@link RefreshTokenRepository#deleteByUserIdIn},
     * {@link DeviceTokenRepository#deleteByUserIdIn} and
     * {@link PasswordResetTicketRepository#markAllUsedByUserIdIn} replace them with exactly THREE
     * bulk statements against the full {@code staffUserIds} list, called once after the loop.
     */
    private void deactivateSalonStaff(UUID ownerId, UUID salonId) {
        // Phase 290 perf pass (findings #2/#3): the masters here are already JOIN-FETCHed with
        // `user` by findBySalonIdAndIsActiveTrueWithUser — passed straight to the batch overload
        // rather than re-fetched one-by-one via deactivateMaster(ownerId, master.getId()), and
        // that overload fires exactly ONE SalonStaffChangedEvent for the whole list instead of
        // one per master (see MasterService#deactivateMasters' javadoc for the full mechanism —
        // it also collapses the redundant per-master reviews-by-salon cache scan, finding #4).
        List<Master> salonMasters = masterRepository
                .findBySalonIdAndIsActiveTrueWithUser(salonId, Pageable.unpaged())
                .getContent();
        masterService.deactivateMasters(ownerId, salonMasters, salonId);

        List<UUID> staffUserIds = staffClientReferenceAuditService.resolveSalonStaffUserIds(salonId);
        if (staffUserIds.isEmpty()) {
            return;
        }

        // Perf audit MEDIUM fix: this used to be a per-staff-member call INSIDE the loop below —
        // N round trips, one per staff member, in the same method whose own comment two lines down
        // claims the O(1)-in-N property Phase 290 established for the session/notification purges.
        // Collapsed to ONE bulk statement for the whole staffUserIds list via a native
        // InviteToken-to-User join (see InviteTokenRepository#redactEmailsBySalonIdAndStaffUserIds
        // for why a join, not a plain WHERE email IN (...), is required — every staff member needs
        // a DIFFERENT tombstone).
        //
        // MUST run BEFORE the loop below calls User#scrubPii on any of these users. The join
        // matches invite_tokens.email against users.email AS IT IS RIGHT NOW — once scrubPii has
        // rewritten a user's email to their tombstone, the join can no longer find that user's
        // original address and the redaction silently no-ops for them. Do NOT move this below the
        // loop: SalonStaffPiiScrubIT#should_redactInviteTokenEmail_when_salonDeactivated and
        // #should_preserveUnrelatedSalonsPendingInvite_when_salonDeactivated both pin the
        // resulting end state and go red the moment this call trails scrubPii.
        inviteTokenRepository.redactEmailsBySalonIdAndStaffUserIds(
                salonId, staffUserIds, SCRUB_EMAIL_PREFIX, SCRUB_EMAIL_DOMAIN);

        // Per-user mutations and per-user cache-eviction registrations stay in the loop — each
        // key is a different person (finding #4's "correctly proportionate" call). Only the
        // session/notification-purge DML below is batched (finding #1): 3N single-row round
        // trips collapsed to exactly 3 bulk statements against the full staffUserIds list.
        Instant now = clock.instant();
        for (User staffUser : userRepository.findAllById(staffUserIds)) {
            // `staffUser` is a managed entity loaded within THIS @Transactional boundary, so
            // Hibernate dirty-checking flushes every mutation below on commit — no explicit save().
            staffUser.setActive(false);
            staffUser.setTokensValidAfter(now);

            // Phase 291 — email tombstone + PII scrub. This is the actual fix for "owner deletes
            // a salon, then cannot re-invite the master who was in it": InviteService.sendInvite/
            // acceptInvite both gate on the GLOBAL, salon-agnostic userRepository.existsByEmail —
            // intentionally left unchanged (relaxing it would let acceptInvite insert a second row
            // with the same email and hit users.email's UNIQUE constraint) — so the fix has to be
            // data-side. Guarded on scrubbedAt == null so a hypothetical re-entry into this loop
            // for an already-scrubbed row never re-does this work; deactivateSalon's own
            // idempotency guard (`if (!salon.isActive()) return;`) already prevents this loop from
            // running twice for the same salon, but this check makes per-user idempotency a
            // property of scrubPii's own call site, not an accident of the caller two levels up.
            if (staffUser.getScrubbedAt() == null) {
                String tombstoneEmail = SCRUB_EMAIL_PREFIX + staffUser.getId() + SCRUB_EMAIL_DOMAIN;
                // Perf audit CRITICAL fix: was passwordEncoder.encode(UUID.randomUUID()...) HERE,
                // per staff member, sequentially, on the request thread — 56.04 ms/encode measured,
                // 1.12 s at N=20, 2.80 s at N=50. Replaced with SCRUBBED_PASSWORD_HASH, a single
                // BCrypt hash computed exactly once per JVM boot (see that constant's javadoc for
                // the full reasoning, including why it must stay real BCrypt rather than a
                // sentinel). This drops the per-deletion cost of this line to O(1) regardless of N.
                staffUser.scrubPii(tombstoneEmail, SCRUBBED_PASSWORD_HASH, now);
            }

            UUID staffUserId = staffUser.getId();
            evictTokensValidAfterCacheAfterCommit(staffUserId);
            // isActive is surfaced on UserProfileResponse (GET /users/me) — this cascade is a
            // NEW writer of that DTO's field; see UserProfileCacheEvictor's "complete writer
            // set" javadoc, updated alongside this change.
            userProfileCacheEvictor.evictAfterCommit(staffUserId);
        }

        refreshTokenRepository.deleteByUserIdIn(staffUserIds);
        deviceTokenRepository.deleteByUserIdIn(staffUserIds);
        passwordResetTicketRepository.markAllUsedByUserIdIn(staffUserIds);

        // Security audit LOW fix: this cascade previously logged nothing at all — rotateAdmin
        // logs an INFO line for a far less consequential mutation (SalonService#rotateAdmin).
        // One line per BATCH, not per staff member — ids and a count only, exactly this repo's
        // PII-in-logs convention (mirrors PhoneMask's "never the raw value" rule for phone
        // numbers); the tombstone email, the original email, and any other scrubbed value are
        // NEVER logged, here or anywhere else in this method.
        log.info("Salon deletion PII scrub: {} staff member(s) scrubbed for salon {} by actor {}",
                staffUserIds.size(), salonId, ownerId);
    }

    /**
     * Evicts {@code userId}'s {@link TokensValidAfterCache} entry once the current transaction
     * commits, never before — mirrors {@code PasswordResetService.evictTokensValidAfterCache}
     * exactly, and for the identical reason: that cache is a read-through cache backed by the
     * {@code users.tokens_valid_after} row this method just updated, so an eviction that fires
     * before commit reopens the stale-read race window {@link TokensValidAfterCache#invalidate}
     * documents.
     */
    private void evictTokensValidAfterCacheAfterCommit(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            tokensValidAfterCache.invalidate(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tokensValidAfterCache.invalidate(userId);
            }
        });
    }

    /**
     * Unassigns a {@code SALON_ADMIN} from a salon (Phase 21.2). {@code actorId} is a
     * SALON_OWNER acting on any salon they own, or a SALON_ADMIN acting on their own salon —
     * both halves already enforced by {@code @PreAuthorize} on the controller
     * ({@code canManageSalon} for salon scoping, {@code adminBelongsToSalon} for confirming
     * {@code userId} is actually an admin of {@code salonId}).
     *
     * <p>This only clears the admin's {@code salon_id} — it is NOT an account deactivation.
     * {@code role} stays {@code SALON_ADMIN} and {@code isActive} stays {@code true}, so the
     * user can be invited to (and reassigned to) a salon again later.
     *
     * <p>Removing the last admin from a salon is intentionally unguarded: owner access is
     * ownership-based, never admin-count-based, so a salon is never left "unmanageable."
     *
     * @throws NotFoundException   if {@code userId} does not resolve to a user
     * @throws ForbiddenException  if the reloaded user is not a SALON_ADMIN assigned to
     *                             {@code salonId} (defense-in-depth re-check of the
     *                             {@code @PreAuthorize} gate — see {@link
     *                             com.beautica.common.security.AuthorizationService#adminBelongsToSalon}),
     *                             or if the actor attempts to remove themselves
     */
    @Transactional
    public void removeAdmin(UUID actorId, UUID salonId, UUID userId) {
        if (actorId.equals(userId)) {
            throw new ForbiddenException("Cannot remove yourself — ask another admin or the owner");
        }

        var admin = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        // Defense-in-depth: redundant with @authz.adminBelongsToSalon on the controller,
        // but matches the existing pattern of service-layer re-validation (e.g.
        // enforceCanManageMaster) rather than trusting the SpEL gate alone.
        if (admin.getRole() != Role.SALON_ADMIN || !salonId.equals(admin.getSalonId())) {
            throw new ForbiddenException("User is not an admin of this salon");
        }

        // `admin` was loaded via findById in THIS @Transactional, so it is a managed entity —
        // Hibernate dirty-checking flushes the salonId mutation on commit (mirrors
        // deactivateSalon/updateSalon — no redundant explicit save()).
        admin.setSalonId(null);
        // Audit-fix cycle 2 — users.salon_id is surfaced as UserProfileResponse.salonId, so the
        // removed admin's cached GET /users/me would keep naming the salon they no longer belong
        // to for the full 5-minute TTL. Keyed on the ADMIN's id, never the actor's.
        userProfileCacheEvictor.evictAfterCommit(userId);
    }

    /**
     * Rotates (reassigns) a {@code SALON_ADMIN} from {@code salonId} to
     * {@code destinationSalonId} (Phase 21.3). {@code actorId} is a SALON_OWNER acting on any
     * salon they own, or a SALON_ADMIN acting on their own salon — both halves already enforced
     * by {@code @PreAuthorize} on the controller ({@code canManageSalon} for source-salon
     * scoping, {@code adminBelongsToSalon} for confirming {@code userId} is actually an admin of
     * {@code salonId}).
     *
     * <p>The destination salon MUST belong to the SAME owner as the source salon — an admin has
     * no visibility into other owners' salons, and this must never allow a cross-owner transfer.
     * That check is NOT expressible in the controller's {@code @PreAuthorize} SpEL (it depends
     * on the request body, not a path variable), so it is enforced here via
     * {@link AuthorizationService#salonsShareOwner}.
     *
     * <p>Destination validation (Perf MEDIUM-4 / Sec LOW-1): the destination's active-flag is
     * checked via the lightweight {@link SalonRepository#existsByIdAndIsActiveTrue} projection —
     * no full {@code Salon} entity is ever loaded here, since only the raw
     * {@code destinationSalonId} UUID is written onto {@code User.salonId}. All three destination
     * denial reasons — cross-owner, inactive, and not-found — collapse to the SAME
     * {@link ForbiddenException} (403). Distinguishing them via status code would let an actor
     * with legitimate authority over the source salon probe an arbitrary owner's full salon
     * portfolio (including inactive salons a public endpoint would 404 on) by observing
     * 404 vs 403 vs 400 responses. The same-salon no-op check stays a distinct 400 — the actor
     * already knows the source salon's identity, so it carries no oracle risk.
     *
     * @throws NotFoundException  if {@code userId} does not resolve to a user
     * @throws ForbiddenException if the reloaded user is not a SALON_ADMIN assigned to
     *                            {@code salonId} (defense-in-depth re-check of the
     *                            {@code @PreAuthorize} gate), or if the destination salon does not
     *                            exist, is not owned by the same owner as the source salon, or is
     *                            inactive (soft-deleted)
     * @throws BusinessException  (400) if {@code destinationSalonId} equals {@code salonId}
     *                            (no-op rotation)
     */
    @Transactional
    public SalonAdminResponse rotateAdmin(UUID actorId, UUID salonId, UUID userId, UUID destinationSalonId) {
        var admin = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        // Defense-in-depth: redundant with @authz.adminBelongsToSalon on the controller,
        // but matches the existing pattern of service-layer re-validation (mirrors removeAdmin).
        if (admin.getRole() != Role.SALON_ADMIN || !salonId.equals(admin.getSalonId())) {
            throw new ForbiddenException("User is not an admin of this salon");
        }

        if (destinationSalonId.equals(salonId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Already assigned to this salon");
        }

        // Same-owner requirement (product decision 2026-07-06): an admin/owner may only rotate
        // staff within a single owner's portfolio of salons — never across owners. salonsShareOwner
        // also returns false when destinationSalonId does not resolve to a salon at all, so
        // "not found" collapses into this same 403 (Sec LOW-1 status-code oracle fix).
        //
        // Both checks below are evaluated unconditionally (not short-circuited) — Sec LOW-2:
        // without this, the "same-owner but inactive" branch would run one extra DB round-trip
        // (the active-check query) beyond what "cross-owner" and "not-found" execute, since those
        // already deny inside salonsShareOwner. That asymmetry is itself a timing oracle letting an
        // actor distinguish "in my owner's portfolio but soft-deleted" from "not in my portfolio at
        // all" by sampling latency. Assigning to locals before the branch forces identical query
        // shape/count across all three denial reasons.
        boolean sameOwner = authorizationService.salonsShareOwner(salonId, destinationSalonId);
        // Lightweight existence+active check (Perf MEDIUM-4) — no full Salon entity load. Inactive
        // destinations also deny with 403, not 400 (Sec LOW-1): distinguishing "inactive" from
        // "cross-owner" via status code would leak which salons an owner's portfolio contains.
        boolean destinationActive = salonRepository.existsByIdAndIsActiveTrue(destinationSalonId);
        if (!sameOwner || !destinationActive) {
            throw new ForbiddenException("Destination salon is not available for rotation");
        }

        // `admin` was loaded via findById in THIS @Transactional, so it is a managed entity —
        // Hibernate dirty-checking flushes the salonId mutation on commit (mirrors removeAdmin —
        // no redundant explicit save()).
        admin.setSalonId(destinationSalonId);
        // Audit-fix cycle 2 — same field, same reasoning as removeAdmin: the rotated admin's
        // cached GET /users/me would keep reporting the SOURCE salon. Keyed on the admin.
        userProfileCacheEvictor.evictAfterCommit(userId);

        // Audit trail (LOW-fix): no dedicated audit-log subsystem exists in this codebase yet —
        // a structured INFO log line is the established minimal pattern for sensitive mutations
        // (mirrors the debug-level trail in InviteService). UUIDs only, no PII.
        log.info("Salon rotation: admin {} moved from salon {} to {} by actor {}",
                userId, salonId, destinationSalonId, actorId);

        return SalonAdminResponse.from(admin);
    }

    /**
     * Lists the salon's FULL invite history — pending, accepted, expired and cancelled alike —
     * newest-first ({@code GET /salons/{salonId}/invites}). Replaces the earlier pending-only
     * listing: an owner needs to see that an invite was accepted or that they cancelled it, not
     * just what is still outstanding.
     *
     * <p>Salon-scoping — the caller must be the SALON_OWNER of {@code salonId} or a SALON_ADMIN
     * assigned to it — is already enforced by
     * {@code @PreAuthorize("... and @authz.canManageSalon(authentication, #salonId)")} on the
     * controller (mirrors {@link #updateSalon}/{@link #inviteMaster}); a denied caller never
     * reaches this method, and {@code AuthorizationDeniedException} is logged at WARN by
     * {@code GlobalExceptionHandler#handleAuthorizationDenied} (method + path + authorities +
     * non-PII subject — no redundant WARN needed here).
     *
     * <p>Never exposes the token value or its hash — {@link SalonInviteResponse} carries only the
     * recipient email, role, derived status and timestamps (Anti-Bug §I).
     *
     * <p><strong>Truncation is OBSERVABLE, not silent.</strong> The listing is capped at
     * {@link #MAX_INVITE_HISTORY} rows — the cap is what keeps this a bounded collection return
     * (Anti-Bug §E3) rather than an unbounded {@code List} that grows forever, since
     * {@code invite_tokens} has no cleanup job. A bare {@code List} made a truncated history
     * indistinguishable from a complete one, which is unacceptable on an endpoint that IS the
     * audit trail, so the response carries
     * {@link SalonInviteHistoryResponse#truncated()}.
     *
     * <p>The flag is derived by asking for {@code MAX_INVITE_HISTORY + 1} rows and checking
     * whether the extra one came back — a probe, not a second {@code COUNT(*)} round trip. The
     * surplus row is dropped before mapping, so the caller still never sees more than the cap.
     *
     * <p>{@code clock.instant()} is hoisted to a local so every row in one response is classified
     * against ONE instant. Reading the clock inside the map would let a page straddle an
     * {@code expiresAt} boundary and return two rows whose PENDING/EXPIRED split disagrees.
     */
    @Transactional(readOnly = true)
    public SalonInviteHistoryResponse listSalonInvites(UUID salonId) {
        Instant now = clock.instant();
        List<InviteHistoryRow> rows = inviteTokenRepository
                .findSalonInviteHistory(salonId, PageRequest.of(0, MAX_INVITE_HISTORY + 1));

        List<SalonInviteResponse> invites = rows.stream()
                .limit(MAX_INVITE_HISTORY)
                .map(row -> SalonInviteResponse.from(row, now))
                .toList();

        return new SalonInviteHistoryResponse(invites, rows.size() > MAX_INVITE_HISTORY);
    }

    /**
     * Cancels (revokes) a pending invite (Phase 23.1 {@code DELETE
     * /salons/{salonId}/invites/{inviteId}}). Salon-scoping is enforced by {@code @PreAuthorize}
     * on the controller exactly as in {@link #listSalonInvites} — a caller without management
     * access to {@code salonId} never reaches this method.
     *
     * <p>Marks the token revoked via {@link InviteToken#markCancelled(Instant)} rather than
     * deleting the row (avoids FK-cascade surprises and leaves an audit trail of a cancelled,
     * never-accepted invite) — mirrors {@link #removeAdmin}/{@link #rotateAdmin}, which likewise
     * mutate a managed entity loaded in this transaction and rely on Hibernate dirty-checking to
     * flush on commit rather than an explicit {@code save()}.
     *
     * <p>{@code markCancelled} keeps {@code isUsed = true} — exactly what the previous
     * {@code markUsed()} wrote — so the {@code isUsed()} guard below, {@code acceptInvite} and
     * {@code previewInvite} all behave identically. What it ADDS is
     * {@code revokedReason = CANCELLED}, without which {@link #listSalonInvites} could not tell a
     * cancelled invite from an accepted one: both paths set the same single flag. The status
     * ladder in {@link SalonInviteResponse#from} therefore tests CANCELLED before {@code isUsed}.
     *
     * <p><strong>Only a PENDING invite is cancellable</strong> — the guard rejects all four
     * non-pending shapes, which is exactly what the endpoint's OpenAPI description promises. This
     * matters more since the row became history rather than a transient: {@code markCancelled}
     * OVERWRITES {@code revoked_at} and {@code revoked_reason}, so without the
     * {@code getRevokedAt() != null} rung a SUPERSEDED row — which keeps {@code is_used = false}
     * and therefore sailed past the original {@code isUsed()}-only guard — could be rewritten
     * SUPERSEDED &rarr; CANCELLED, retroactively falsifying the audit trail this endpoint exists
     * to serve. The expiry rung closes the same hole for a row that simply lapsed
     * ({@code revoked_at} null, {@code expires_at} past): cancelling a dead invite is a no-op that
     * would nonetheless stamp it CANCELLED and hide the fact it was never acted on. Both use
     * {@link InviteToken#isExpiredAt(Instant)} / the shared predicate rather than an open-coded
     * comparison, so the cancel boundary can never drift from the accept and display boundaries.
     *
     * <p>Defense-in-depth cross-salon check: re-verifies {@code token.getSalonId().equals
     * (salonId)} even though the controller's {@code @PreAuthorize} already scopes the salon —
     * a caller with management access to salon A must never cancel a token that happens to
     * belong to salon B just because it knows the token's id. A token belonging to a different
     * salon, already used, or simply missing all collapse to the same {@link NotFoundException}
     * (404) — distinguishing "belongs to another salon" from "does not exist" via a different
     * status would let an authorized caller probe arbitrary invite ids and learn which ones exist
     * at OTHER salons (IDOR oracle — same rationale as {@code AuthorizationService
     * #salonsShareOwner}'s collapsed denial reasons).
     *
     * <p>QA audit Phase 23.1 (Security MEDIUM): the token is loaded via {@link #lockInviteForCancel}
     * — a {@code PESSIMISTIC_WRITE} row lock, the SAME lock {@code InviteService#acceptInvite}
     * takes via {@code findByTokenForUpdate} — rather than a plain {@code findById}. A cancel racing
     * an accept now serialises against it: whichever side's lock is granted first wins, and the
     * loser's grant always returns the freshly-committed row, so this method's {@code isUsed()}
     * check below can never act on a stale, pre-accept snapshot. See
     * {@code PendingInviteCancelAcceptRaceIT}.
     *
     * @throws NotFoundException if {@code inviteId} does not resolve to a PENDING invite for
     *                            {@code salonId} — accepted, cancelled, superseded, lapsed,
     *                            cross-salon and missing all collapse to the same 404
     */
    @Transactional
    public void cancelInvite(UUID actorId, UUID salonId, UUID inviteId) {
        Instant now = clock.instant();
        InviteToken token = lockInviteForCancel(inviteId)
                .orElseThrow(() -> new NotFoundException("Invite not found: " + inviteId));

        if (token.isUsed()
                || token.getRevokedAt() != null
                || token.isExpiredAt(now)
                || !salonId.equals(token.getSalonId())) {
            throw new NotFoundException("Invite not found: " + inviteId);
        }

        token.markCancelled(now);

        // Audit trail (mirrors rotateAdmin/removeAdmin's INFO line) — UUIDs only, never the
        // recipient email or any token material (Anti-Bug §I).
        log.info("Invite {} for salon {} cancelled by actor {}", inviteId, salonId, actorId);
    }

    /**
     * Seam for {@link #cancelInvite} — isolated into its own method (public, not {@code private})
     * so a concurrency test can {@code @SpyBean} + spy on it and pause the calling thread here,
     * immediately after the row lock is acquired but before the transaction commits (mirrors the
     * lock-seam pattern {@code AppointmentTransitionService} uses, e.g.
     * {@code lockAppointmentHeaderBeforeClientItemCancel}). Delegates to {@link
     * InviteTokenRepository#findByIdForUpdate}, which takes a {@code PESSIMISTIC_WRITE} row lock —
     * see that method's Javadoc for why this replaced a plain {@code findById} (Phase 23.1 QA audit
     * Security MEDIUM).
     */
    public Optional<InviteToken> lockInviteForCancel(UUID inviteId) {
        return inviteTokenRepository.findByIdForUpdate(inviteId);
    }

    /**
     * Owner-row lock seam for {@link #createSalon} — takes a {@code PESSIMISTIC_WRITE}
     * ({@code SELECT ... FOR UPDATE}) lock on the {@code users} row of the prospective owner, so
     * that the {@link #MAX_ACTIVE_SALONS_PER_OWNER} check-then-insert runs atomically per owner
     * (Perf LOW-A).
     *
     * <p><b>The race it closes.</b> {@code createSalon} counts the owner's active salons and then
     * inserts one. With a plain {@code findById} the two statements are unserialised: an owner
     * firing N concurrent {@code POST /salons} has every request read {@code count < 50} and every
     * one insert, so the portfolio lands arbitrarily above the ceiling. The cap is not decoration —
     * {@code SalonRepository#findActiveSiblingsBySalonId}, {@code GET /salons/mine} and the
     * {@code ownerSalons} cache entry are all sized by it.
     *
     * <p><b>Why a row lock and not a DB constraint.</b> "At most N rows per {@code owner_id}" is
     * not expressible as a Postgres {@code CHECK}, {@code UNIQUE} or {@code EXCLUDE} constraint:
     * all three are per-row (or per-key) predicates and none can count sibling rows. Making it a
     * constraint would mean introducing a denormalised counter column plus a backfill migration and
     * a second source of truth that can drift from the {@code salons} table. A per-owner row lock
     * needs no migration at all, and is the shape this codebase already uses for exactly this
     * problem: {@code PasswordResetService} ({@code UserRepository#findByEmailForUpdate} /
     * {@link UserRepository#findByIdForUpdate}) and {@link #lockInviteForCancel}
     * ({@code InviteTokenRepository#findByIdForUpdate}) both close a check-then-write TOCTOU this
     * way. Reusing it keeps one pattern rather than inventing a third.
     *
     * <p><b>Cost.</b> None on the happy path: {@code createSalon} had to load the owner row
     * regardless, so this replaces that read rather than adding a statement. Contention is
     * per-owner and salon creation is a rare, human-paced write.
     *
     * <p>Isolated into its own (public, not {@code private}) method so a concurrency test can
     * {@code @SpyBean} it and pause the calling thread here — after the lock is granted, before the
     * transaction commits — the lock-seam pattern already proven by {@link #lockInviteForCancel}
     * and {@code AppointmentTransitionService}. See
     * {@code SalonCreateCapConcurrencyIT#should_rejectBeyondCap_when_createsIssuedConcurrently}.
     */
    public Optional<User> lockOwnerForCreate(UUID ownerId) {
        return userRepository.findByIdForUpdate(ownerId);
    }

    /**
     * Normalises a raw Instagram value before persistence: trims surrounding
     * whitespace and strips a single leading {@code @} so the stored form is the
     * bare handle (or full URL) — mirroring {@code UserService.normalizeInstagram},
     * which does the same for the independent-master {@code users.instagram} column.
     * A blank or {@code @}-only value normalises to {@code null}. The DTO
     * {@code @Pattern} has already constrained the shape at the boundary; this
     * method only canonicalises an already-valid value.
     *
     * <p>Unlike {@code UserService.normalizeInstagram}, {@code raw} may be
     * {@code null} here: {@code instagramUrl} is an optional field on
     * {@code CreateSalonRequest}, so the create path calls this unconditionally.
     *
     * @param raw the validated request value, possibly {@code null}
     * @return the canonical handle/URL, or {@code null} when the value is
     *         {@code null} or blank
     */
    private String normalizeInstagram(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).strip();
        }
        return trimmed.isBlank() ? null : trimmed;
    }
}
