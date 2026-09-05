package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.RatingBucket;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.dto.MasterPublicProfileResponse;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.RatingCountProjection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final LocalityWriteValidator localityWriteValidator;
    private final CityRepository cityRepository;
    private final CityDistrictRepository cityDistrictRepository;
    private final CacheManager cacheManager;
    private final ClientReviewRepository clientReviewRepository;
    private final MasterRepository masterRepository;

    public UserService(UserRepository userRepository,
                       LocalityWriteValidator localityWriteValidator,
                       CityRepository cityRepository,
                       CityDistrictRepository cityDistrictRepository,
                       CacheManager cacheManager,
                       ClientReviewRepository clientReviewRepository,
                       MasterRepository masterRepository) {
        this.userRepository = userRepository;
        this.localityWriteValidator = localityWriteValidator;
        this.cityRepository = cityRepository;
        this.cityDistrictRepository = cityDistrictRepository;
        this.cacheManager = cacheManager;
        this.clientReviewRepository = clientReviewRepository;
        this.masterRepository = masterRepository;
    }

    /**
     * The caller's own aggregate client rating (Phase 27.6), extended in Phase 27.x with a
     * per-star {@code ratingDistribution}. Reads the narrow {@link UserRatingProjection} for the
     * two scalar columns — never the full {@link User} entity — and a separate {@code GROUP BY}
     * over {@code client_reviews} for the bucket counts, zero-filling ratings 5 down to 1 so a
     * never-reviewed caller still gets all-zero buckets rather than an empty list. {@code
     * client_reviews} rows themselves (comments, author identity, timestamps) are never surfaced
     * here or anywhere on the CLIENT side — see {@link UserRatingResponse}'s javadoc.
     */
    @Transactional(readOnly = true)
    public UserRatingResponse getMyRating(UUID userId) {
        UserRatingProjection projection = userRepository.findRatingById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        List<RatingCountProjection> rows = clientReviewRepository.countBySubjectClientIdGroupByRating(userId);
        Map<Integer, Long> countsByRating = rows.stream()
                .collect(Collectors.toMap(RatingCountProjection::getRating, RatingCountProjection::getCount));

        // Zero-fill every bucket from 5 down to 1 — a rating with zero reviews must still
        // appear in the response, never be silently omitted (mirrors ReviewService's
        // getMasterReviewSummary/getSalonReviewSummary zero-fill exactly).
        List<RatingBucket> distribution = IntStream.rangeClosed(1, 5)
                .boxed()
                .sorted(Comparator.reverseOrder())
                .map(rating -> new RatingBucket(rating, countsByRating.getOrDefault(rating, 0L)))
                .toList();

        return UserRatingResponse.from(projection, distribution);
    }

    /**
     * The authenticated caller's own account record — {@code GET /api/v1/users/me}.
     *
     * <p><b>Cached since audit-fix cycle 2 (LOW).</b> This is the hottest authenticated read in the
     * app (every role, every launch) and was the only one with no cache: three queries per hit —
     * the {@code users} row, the district label when a district is set, the city→oblast scalar —
     * plus a fourth {@code EXISTS} for a {@code SALON_OWNER}'s {@code hasMasterProfile}.
     * {@code sync = true} because this is a per-user hot key and a herd on TTL expiry would
     * otherwise admit N threads into all four (Anti-Bug §F-7).
     *
     * <p><b>The hazard, named.</b> This cache is CROSS-AGGREGATE. Everything on
     * {@link UserProfileResponse} is read off the {@code users} row except
     * {@code hasMasterProfile}, which {@link #resolveHasMasterProfile} derives from an active
     * {@code masters} row of type {@code SALON_OWNER} — a table this service does not own and does
     * not write. So {@code MasterService} and {@code SalonService} are writers of this cache
     * without importing anything from this package, and nothing in the type system says so. That
     * is why eviction does not live inline here but in
     * {@link com.beautica.common.cache.UserProfileCacheEvictor}, whose javadoc carries the complete
     * writer set, the paths that are provably NOT writers, and the field-by-field evidence for
     * each. Adding a field to {@code UserProfileResponse} means re-deriving that set.
     *
     * <p>The 5-minute TTL is the backstop for a writer someone forgets to wire up — deliberately
     * shorter than {@code master-detail-by-user}'s 10 minutes, because this DTO has strictly more
     * writers than that one does.
     */
    @Cacheable(value = UserProfileCacheEvictor.USER_PROFILE_CACHE, key = "#userId", sync = true)
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        // cityName/oblastName are read off the denormalised users.city/users.region columns
        // by UserProfileResponse.from (zero query). districtName is resolved on demand only
        // when a district is set — most users (CLIENTs, districtless cities) skip the query.
        String districtName = user.getDistrictId() == null
                ? null
                : cityDistrictRepository.findNameUkById(user.getDistrictId()).orElse(null);
        // oblastId lets the mobile Location-edit screen pre-select the oblast tier without
        // scanning every oblast's cities. Resolved on demand only when a city is set — one
        // scalar FK lookup (cities.oblast_id, no JOIN to oblasts); null otherwise. No City is
        // loaded on this read path (cityName/oblastName come from denormalised columns), so
        // there is nothing to reuse — this is the minimal extra query.
        UUID oblastId = user.getCityId() == null
                ? null
                : cityRepository.findOblastIdById(user.getCityId()).orElse(null);
        return UserProfileResponse.from(user, districtName, oblastId, resolveHasMasterProfile(user));
    }

    /**
     * The owner-as-master toggle state (Phase 265): {@code true} iff an <em>active</em> master row
     * of type {@code SALON_OWNER} exists for this user. Derived on every read — the toggle IS that
     * row, so there is no column to read and no migration in this phase.
     *
     * <p>The {@code isActive} predicate is the whole point. {@code DELETE
     * /api/v1/salons/&#123;salonId&#125;/master} deactivates rather than hard-deletes, so a bare
     * existence check would report every owner who has ever opted in as permanently opted in.
     *
     * <p>The role short-circuit ahead of the query is an optimisation that provably cannot change
     * the answer, not a second source of truth: {@code MasterService.createMasterForOwner} is the
     * only site in {@code src/main} that mints a {@code MasterType.SALON_OWNER} row and it throws
     * {@link ForbiddenException} unless {@code owner.getRole() == SALON_OWNER}, and no production
     * code path mutates {@code User.role} after registration (there is no {@code setRole} call in
     * {@code src/main} at all). It keeps {@code GET /users/me} — the hottest authenticated read in
     * the app, hit on every launch by every {@code CLIENT} — at its current query count.
     *
     * <p>Cross-package repository injection mirrors the existing {@code ClientReviewRepository}
     * dependency on this same service: a one-boolean {@code EXISTS} needs no behaviour from
     * {@code MasterService}, and routing it through that service would create a
     * {@code UserService} ⇄ {@code MasterService} bean cycle.
     */
    private boolean resolveHasMasterProfile(User user) {
        if (user.getRole() != Role.SALON_OWNER) {
            return false;
        }
        return masterRepository.existsByUserIdAndMasterTypeAndIsActiveTrue(
                user.getId(), MasterType.SALON_OWNER);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        Optional.ofNullable(request.firstName()).ifPresent(user::setFirstName);
        Optional.ofNullable(request.lastName()).ifPresent(user::setLastName);
        Optional.ofNullable(request.phoneNumber())
                .filter(s -> !s.isBlank())
                .ifPresent(user::setPhoneNumber);

        applyLocality(user, request);

        // Phase 19.6: optional Instagram handle. A null value leaves the stored handle
        // unchanged; a blank value clears it (symmetric to the master contacts-edit path
        // in updateMasterProfile). normalizeInstagram strips a leading @ and trims so the
        // stored form is canonical and satisfies the V61 chk_users_instagram CHECK.
        if (request.instagram() != null) {
            user.setInstagram(normalizeInstagram(request.instagram()));
        }

        // Custom professional title: a provider-only headline (e.g. "Майстер манікюру").
        // Every role EXCEPT CLIENT may set it. A CLIENT that supplies the field is
        // rejected outright (400) rather than silently ignored, so the mobile client
        // gets a clear signal instead of a no-op save. A null value leaves the stored
        // title unchanged (PATCH semantics); a blank value clears it (trim → null).
        if (request.professionalTitle() != null) {
            if (user.getRole() == Role.CLIENT) {
                throw new BusinessException("Clients cannot set a professional title");
            }
            String trimmed = request.professionalTitle().strip();
            user.setProfessionalTitle(trimmed.isBlank() ? null : trimmed);
        }

        // Hibernate dirty-checking flushes the mutation on commit — no explicit save() needed.
        // Mirror updateMasterProfile's narrow searchAffected gate: the search:masters projection
        // is fed by the master's DISPLAY NAME (firstName/lastName) and LOCALITY filter keys
        // (cityId/districtId). instagram/phoneNumber/street/buildingNo/locationNote never appear
        // in search results, so editing only those must not blanket-clear every cached search page.
        // null = not changed under the PATCH semantics used throughout this method.
        boolean searchAffected = request.firstName() != null
                || request.lastName() != null
                || request.cityId() != null
                || request.districtId() != null;
        evictUserCachesAfterCommit(userId, user.getRole(), searchAffected);

        // Phase 265 / audit fix — resolve hasMasterProfile on the WRITE path too, with the same
        // helper the GET path uses. This response is a full UserProfileResponse on the same wire
        // type as GET /users/me, so a hard-coded `false` here would hand an opted-in owner a body
        // that contradicts the read they made one second earlier: one non-nullable field with two
        // meanings depending on which verb produced it. The field is not made nullable and is not
        // omitted — a boolean that is sometimes absent is a worse contract than one that is always
        // right. Cost is the same single indexed EXISTS as the read path, and only for
        // SALON_OWNER (resolveHasMasterProfile short-circuits every other role without a query).
        //
        // This also fixes IndependentMasterController#updateLocality, which serialises the very
        // same DTO by delegating to this method — there is one write path, not two.
        return UserProfileResponse.from(user, null, null, resolveHasMasterProfile(user));
    }

    /**
     * Updates the independent master's public profile fields: first name, last name,
     * phone number, bio, and Instagram handle.
     *
     * <p>This method is intentionally separate from {@link #updateProfile} so that
     * the locality and profile-text write paths remain independently testable and
     * maintainable. The two paths cover disjoint columns on {@code users}:
     * locality columns (cityId, districtId, street, …) vs. profile columns
     * (firstName, lastName, phoneNumber, bio, instagram).
     *
     * <p>All fields are optional. A null value leaves the stored value unchanged.
     * For {@code firstName} and {@code lastName} a blank string is also treated as
     * "no change" — an empty name must not overwrite a previously stored value.
     *
     * <p>Evicts {@code master-detail-by-user} and {@code master-by-user} after
     * commit so that a parallel reader cannot repopulate either cache with stale
     * data mid-transaction (§F — cache eviction must run afterCommit).
     *
     * @param userId  the authenticated user's UUID
     * @param request validated request body; all fields nullable
     * @return updated profile echoing the fields this endpoint can modify
     */
    @Transactional
    public MasterPublicProfileResponse updateMasterProfile(UUID userId, MasterProfileUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        Role role = user.getRole();
        // Load-bearing UNION backstop — do NOT delete as "redundant with @PreAuthorize".
        // This single service method serves TWO single-role controllers:
        // IndependentMasterController gates INDEPENDENT_MASTER, MasterController gates
        // SALON_MASTER. Neither controller alone admits the other's role, so only the
        // union {INDEPENDENT_MASTER, SALON_MASTER} may write here — and only the service,
        // seeing both call sites, can enforce that union. It is the intentional
        // defence-in-depth guard (§D / B14) that keeps CLIENT (and any future role wired
        // to a third caller) out of this write path.
        if (role != Role.INDEPENDENT_MASTER && role != Role.SALON_MASTER) {
            throw new ForbiddenException("Profile update not permitted for role: " + role);
        }

        Optional.ofNullable(request.firstName())
                .filter(s -> !s.isBlank())
                .ifPresent(user::setFirstName);
        Optional.ofNullable(request.lastName())
                .filter(s -> !s.isBlank())
                .ifPresent(user::setLastName);
        Optional.ofNullable(request.phoneNumber())
                .filter(s -> !s.isBlank())
                .ifPresent(user::setPhoneNumber);
        if (request.bio() != null) {
            user.setBio(request.bio());
        }
        if (request.instagram() != null) {
            // Clear-field semantics: the mobile edit screen sends instagram="" to remove a
            // stored handle. Bean Validation accepts "" (the @Pattern ^$ arm), but the DB
            // CHECK constraint chk_users_instagram (V61) only permits NULL or a valid
            // handle/URL — persisting "" raises a 23514 constraint violation (surfaced as a
            // 409), which rolls back the whole transaction and silently drops firstName/
            // lastName. Normalising a blank handle to NULL satisfies the constraint and makes
            // "clear" mean "remove". MasterProfileUpdateContractIT pins this end-to-end.
            user.setInstagram(request.instagram().isBlank() ? null : request.instagram());
        }
        if (request.professionalTitle() != null) {
            // Clear-field semantics: the mobile edit screen sends "" to remove a stored
            // title. This path is already gated to INDEPENDENT_MASTER / SALON_MASTER, so
            // no CLIENT can reach it — the role guard needed on /users/me is unnecessary here.
            String trimmed = request.professionalTitle().strip();
            user.setProfessionalTitle(trimmed.isBlank() ? null : trimmed);
        }

        // Hibernate dirty-checking flushes the mutation on commit — no explicit save() needed.
        // firstName/lastName appear in search result display values — changing them makes cached
        // search:masters pages stale. bio/phone/instagram are not search filter keys, so they
        // do not require a full cache clear.
        boolean searchAffected = request.firstName() != null || request.lastName() != null;
        evictUserCachesAfterCommit(userId, user.getRole(), searchAffected);

        return new MasterPublicProfileResponse(
                user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getBio(),
                user.getInstagram(),
                user.getProfessionalTitle()
        );
    }

    /**
     * Registers a post-commit callback that evicts user-keyed caches and, for
     * {@code INDEPENDENT_MASTER} writes, also clears the discovery cache.
     *
     * <p>Eviction runs {@code afterCommit} so a parallel reader cannot repopulate
     * stale data inside the write transaction's commit window (§F cache eviction
     * correctness rule). Caches evicted:
     * <ul>
     *   <li>{@code master-detail-by-user} — DTO cache for {@code GET /masters/me}</li>
     *   <li>{@code master-by-user} — entity cache used by calendar and slot endpoints</li>
     *   <li>{@code user-profile} — DTO cache for {@code GET /users/me} (audit-fix cycle 2)</li>
     *   <li>{@code search:masters} — discovery cache; cleared only when the writing user
     *       is an {@code INDEPENDENT_MASTER}, since locality or profile changes affect
     *       search results. Salon-bound roles route discovery through the salon record.</li>
     * </ul>
     * This method is a no-op when called outside a transaction (guard on
     * {@link TransactionSynchronizationManager#isSynchronizationActive()}).
     *
     * @param userId          the authenticated user's UUID (used as the per-user eviction key)
     * @param role            the role of the writing user, used to gate {@code search:masters} eviction
     * @param searchAffected  when {@code true} the search:masters cache is cleared; callers pass
     *                        {@code false} when only bio/phone/instagram changed so that a single
     *                        profile write does not evict every cached search page
     */
    private void evictUserCachesAfterCommit(UUID userId, Role role, boolean searchAffected) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache detail = cacheManager.getCache("master-detail-by-user");
                if (detail != null) {
                    detail.evict(userId);
                }
                Cache byUser = cacheManager.getCache("master-by-user");
                if (byUser != null) {
                    byUser.evict(userId);
                }
                // Audit-fix cycle 2 — GET /users/me is cached as of this cycle. Both public write
                // paths on this service funnel through here, and so do the private helpers they
                // call (applyLocality, writeLocalityFields, writeCityDisplayStrings) — those run
                // inside these same two transactions and are not separate entry points, so this
                // single call site covers every users-side write of a UserProfileResponse field.
                // The masters-side writers evict through UserProfileCacheEvictor instead; see its
                // javadoc for the full set.
                Cache profile = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
                if (profile != null) {
                    profile.evict(userId);
                }
                // Search results reflect INDEPENDENT_MASTER locality and profile fields
                // directly. Clear the entire search:masters cache so the next discovery
                // request re-queries the DB rather than serving stale data.
                // BOTH halves of the split discovery cache (browse + free-text): a renamed
                // master must disappear from the location-only listing AND from any cached
                // free-text page that matched the old name. Iterating
                // SearchCacheNames.MASTERS_ALL keeps a future third partition from being
                // silently missed here.
                if (searchAffected && role == Role.INDEPENDENT_MASTER) {
                    for (String cacheName : SearchCacheNames.MASTERS_ALL) {
                        Cache search = cacheManager.getCache(cacheName);
                        if (search != null) {
                            search.clear();
                        }
                    }
                }
            }
        });
    }

    /**
     * Routes the locality fields per the Phase 10.6 per-role matrix.
     *
     * <ul>
     *   <li><b>INDEPENDENT_MASTER</b> — full personal address; the
     *       most-specific-node rule is enforced (city mandatory, district
     *       mandatory iff the city has urban districts).</li>
     *   <li><b>CLIENT</b> — all 5 locality fields ({@code cityId},
     *       {@code districtId}, {@code street}, {@code buildingNo},
     *       {@code locationNote}) are persisted. City and district serve as
     *       the discovery-filter default; the structured address fields allow
     *       clients to record a service-delivery address (e.g. for
     *       at-home appointments).</li>
     *   <li><b>SALON_OWNER / SALON_MASTER / SALON_ADMIN</b> — no personal
     *       locality write path. Owner locality lives on the salon
     *       ({@code SalonService}); SALON_MASTER discovery resolves via the
     *       salon link (Phase 10.5 M2 seam); SALON_ADMIN is search-excluded.
     *       Any submitted locality fields are ignored for these roles.</li>
     * </ul>
     */
    private void applyLocality(User user, UpdateProfileRequest request) {
        Role role = user.getRole();
        if (role == Role.INDEPENDENT_MASTER) {
            localityWriteValidator.validateProviderLocality(request.toLocalityInput());
            writeLocalityFields(user, request);
        } else if (role == Role.CLIENT) {
            localityWriteValidator.validateClientLocality(request.toLocalityInput());
            writeLocalityFields(user, request);
        }
        // SALON_OWNER / SALON_MASTER / SALON_ADMIN: no personal locality write.
    }

    /**
     * Writes the 5 locality columns and denormalizes the city display strings.
     *
     * <p>Extracted from the two branches of {@link #applyLocality} (INDEPENDENT_MASTER
     * and CLIENT) to eliminate duplication. A future 6th locality column must be added
     * here only — callers are unchanged.
     */
    private void writeLocalityFields(User user, UpdateProfileRequest request) {
        // PATCH semantics: a null cityId means "locality not included in this update",
        // NOT "clear my city". Assigning unconditionally let a street/note-only edit
        // (cityId omitted) wipe a previously-saved city FK while leaving the denormalized
        // city/region text behind — the mobile read keys off cityId/oblastId, so location
        // then rendered empty. Only (re)write the FK + display strings when a city is supplied.
        // A city-with-no-districts case is still handled correctly: when a real cityId is
        // sent, setDistrictId(request.districtId()) runs with the supplied (possibly null)
        // districtId, so districtless cities persist district_id = NULL as before.
        if (request.cityId() != null) {
            user.setCityId(request.cityId());
            user.setDistrictId(request.districtId());
            writeCityDisplayStrings(user, request.cityId());
        }
        Optional.ofNullable(request.street()).ifPresent(user::setStreet);
        Optional.ofNullable(request.buildingNo()).ifPresent(user::setBuildingNo);
        Optional.ofNullable(request.locationNote()).ifPresent(user::setLocationNote);
    }

    /**
     * Denormalizes the human-readable city and region (oblast) display strings
     * into {@code users.city} and {@code users.region} so that read paths
     * (e.g. {@link com.beautica.master.dto.MasterDetailResponse}) can surface
     * them without a JOIN to the taxonomy tables.
     *
     * <p>Called only when {@code cityId} is non-null. If the city row is not
     * found (e.g. stale/invalid UUID slipped past validation), a WARN is logged
     * and both columns are left unchanged — the caller's transaction continues
     * normally.</p>
     *
     * <p>The {@link com.beautica.location.entity.City#getOblast()} association is
     * {@code FetchType.LAZY}; it is safe to traverse here because this method is
     * always called within an active {@code @Transactional} context.</p>
     */
    private void writeCityDisplayStrings(User user, UUID cityId) {
        if (cityId == null) {
            return;
        }
        Optional<City> cityOpt = cityRepository.findByIdWithOblast(cityId);
        if (cityOpt.isEmpty()) {
            log.warn("applyLocality: city not found for id={}, skipping city/region denorm", cityId);
            return;
        }
        City city = cityOpt.get();
        Oblast oblast = city.getOblast();
        if (oblast == null) {
            log.warn("applyLocality: city {} has no oblast association, skipping region denorm", cityId);
            user.setCity(city.getNameUk());
            return;
        }
        user.setCity(city.getNameUk());
        user.setRegion(oblast.getNameUk());
    }

    /**
     * Normalises a raw Instagram value before persistence: trims surrounding
     * whitespace and strips a single leading {@code @} so the stored form is the
     * bare handle (or full URL). A blank or {@code @}-only value normalises to
     * {@code null}, which both clears the field and satisfies the V61
     * {@code chk_users_instagram} CHECK (NULL or a valid handle/URL). The DTO
     * {@code @Pattern} has already constrained the shape at the boundary; this
     * method only canonicalises an already-valid value.
     *
     * @param raw the validated request value (never {@code null} — callers guard)
     * @return the canonical handle/URL, or {@code null} when the value is blank
     */
    private String normalizeInstagram(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).strip();
        }
        return trimmed.isBlank() ? null : trimmed;
    }
}
