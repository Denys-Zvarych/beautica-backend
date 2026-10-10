package com.beautica.config;

import com.beautica.booking.service.BookingMasterService;
import com.beautica.client.service.ClientPassportService;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.location.SettlementDisplayNameResolver;
import com.beautica.location.service.SettlementSearchService;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.search.service.SearchSuggestionActivePlaces;
import com.beautica.search.service.SearchSuggestionAvailability;
import com.beautica.search.service.SearchSuggestionCatalogue;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <b>Advisor ordering (Perf MEDIUM — {@code @Cacheable(sync = true)} + {@code @Transactional}).</b>
 * {@code order = Ordered.HIGHEST_PRECEDENCE} pins the caching advisor OUTSIDE the transaction advisor.
 * It is load-bearing, not decorative — do not drop it back to a bare {@code @EnableCaching}.
 *
 * <p>Roughly 38 methods in this codebase pair {@code @Cacheable(..., sync = true)} with
 * {@code @Transactional} on the same method ({@code SlotCalculationService}, {@code MasterService},
 * {@code SearchService}, {@code DashboardService}, {@code ReviewService}, {@code LocationQueryService},
 * …). Caffeine's {@code sync = true} loader funnels through {@code ConcurrentHashMap.compute}, so on a
 * popular key exactly one thread loads while every other caller BLOCKS on that bin lock for the full
 * DB round-trip to Neon.
 *
 * <p>The ordering decides what those blocked callers are holding while they wait. With the transaction
 * advisor outer, each waiter has already entered a transaction before reaching the cache advisor and
 * sits on a Hikari connection out of a pool of ten ({@code application.yml}: {@code maximum-pool-size:
 * 10}, a Neon-tier ceiling) — one slow query on one hot key can then exhaust the pool. With the cache
 * advisor outer, a waiter blocks BEFORE any transaction is opened and holds no connection; a cache HIT
 * likewise returns without ever starting a transaction. The MISS path is unchanged — the cache advisor
 * proceeds into the target, which is still wrapped by the transaction advisor as before.
 *
 * <p>Neither advisor was previously ordered: {@code @EnableCaching} carried no {@code order}, there is
 * no {@code @EnableTransactionManagement} in this codebase at all (transactions come from Boot's
 * auto-config), and no {@code BeanFactoryCacheOperationSourceAdvisor} /
 * {@code BeanFactoryTransactionAttributeSourceAdvisor} bean sets an order. Both therefore defaulted to
 * {@code Ordered.LOWEST_PRECEDENCE} and the nesting fell out of bean-registration tie-breaking —
 * undefined by configuration rather than verified-correct. This pins it.
 *
 * <p><b>Residual, NOT fixed here:</b> {@code spring.threads.virtual.enabled: true} is set, and on Java
 * 21 a virtual thread blocking inside {@code ConcurrentHashMap.compute}'s {@code synchronized} bin lock
 * PINS its carrier thread. Advisor ordering stops a pinned waiter from also holding a connection, but
 * not the pinning itself; that is a JDK-level fix (JEP 491, JDK 24) and no configuration here can
 * substitute for it. It is why {@code sync = true} should stay reserved for genuinely hot public keys
 * rather than applied by default.
 */
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig {

    /** Bounded, cross-user-shared location-only discovery pages — unchanged sizing. */
    private static final int SEARCH_BROWSE_MAX_ENTRIES = 500;
    private static final long SEARCH_BROWSE_TTL_SECONDS = 60;

    /**
     * Unbounded free-text discovery keys: fewer entries (lower reuse per key) and a shorter
     * TTL (free-text results tolerate staleness better than a browse listing).
     */
    private static final int SEARCH_QUERY_MAX_ENTRIES = 300;
    private static final long SEARCH_QUERY_TTL_SECONDS = 30;

    /**
     * Filter-scoped {@code totalElements} memo (perf follow-up) — see
     * {@link com.beautica.search.service.SearchCacheNames#MASTERS_TOTAL} for the full
     * rationale. An entry is a single {@code Long}, so 1000 entries is negligible; the TTL
     * matches {@link #SEARCH_BROWSE_TTL_SECONDS} since a stale total is tolerated for the
     * same window as a stale browse page.
     */
    private static final int SEARCH_TOTAL_MAX_ENTRIES = 1000;
    private static final long SEARCH_TOTAL_TTL_SECONDS = 60;

    /**
     * BEAUTY PASSPORT sizing. Named constants (not inline literals) so
     * {@code ClientPassportCacheIT} can assert the LIVE cache's {@code maximumSize} /
     * {@code expireAfterWrite} against the same symbols this config applies — a test asserting
     * hard-coded 2000/10 would pass against a cache built by Caffeine's default (unbounded,
     * never-expiring) builder only by coincidence, so the shared constant is what makes that
     * assertion meaningful.
     */
    public static final int CLIENT_PASSPORT_MAX_ENTRIES = 2000;
    public static final long CLIENT_PASSPORT_TTL_MINUTES = 10;

    /**
     * Per-cache TTL configuration using individual Caffeine caches.
     *
     * Cache inventory:
     *   service-categories  — catalog categories, rarely change — 60 min TTL, max 200 entries
     *   approved-categories — APPROVED+active platform categories for the mobile picker
     *                         (GET /service-categories/approved); single global 'all' key —
     *                         60 min TTL, max 4 entries; evicted on approve/reject
     *   service-types       — service types per category — 60 min TTL, max 500 entries
     *   service-type-by-id  — single service type by ID — 60 min TTL, max 500 entries
     *   ownerSalons         — salon list per owner, high-frequency read — 5 min TTL, max 1000 entries
     *   masterServices      — service list per master, public endpoint — 10 min TTL, max 500 entries
     *   available-slots     — slot availability per master/date/service — 60 sec TTL, max 500 entries
     *   master-calendar     — paginated booking calendar per master/date range — 30 sec TTL, max 500 entries
     *   master-working-days — CLIENT-safe boolean working-day gating per master/from/to (Phase 15.11
     *                         GET /masters/{masterId}/working-days) — 60 sec TTL, max 500 entries; evicted
     *                         by master prefix alongside available-slots on every schedule write
     *   master-usable-schedule — short-circuiting boolean "has ≥1 working day in [from,to]" per
     *                         master/from/to (Phase 23.x, MasterScheduleService#hasUsableSchedule,
     *                         backs GET /salons/{salonId}/services/{serviceDefId}/masters) — same
     *                         60 sec TTL/500-entry sizing and master-prefix eviction as
     *                         master-working-days, which it mirrors
     *   master-service-bookable — shared per-(master, service) "≥1 free future slot in [from,to]"
     *                         bookability verdict (Phase 23.x, SlotCalculationService#hasBookableFutureSlot)
     *                         gating both the booking master-list and the salon catalogue — 60 sec TTL,
     *                         max 500; evicted by master prefix on every schedule AND booking write
     *   master-bookable-days — availability-aware per-date bookability projection per
     *                         master/from/to/masterService (SlotCalculationService#getBookableWorkingDays,
     *                         backs GET /masters/{masterId}/working-days?serviceId=…) — 60 sec TTL,
     *                         max 2000; evicted by master prefix on every schedule AND booking write
     *   master-by-user      — stable userId→Master entity mapping; TTL-only eviction — 10 min TTL, max 500 entries
     *   master-detail         — masterId→MasterDetailResponse DTO for public GET /masters/{masterId} — 5 min TTL, max 1000 entries
     *   master-detail-by-user — userId→Optional&lt;MasterDetailResponse&gt; for GET /masters/me — 10 min
     *                         TTL, max 1000 entries (raised from 500 by the Phase 265 audit:
     *                         widening the endpoint to SALON_OWNER admits every owner into this
     *                         population, and SalonService#createSalon auto-creates the owner-master
     *                         row, so owners are eligible by default; 1000 matches sibling
     *                         master-detail). NEGATIVE-CACHING: the value type is Optional, so
     *                         Spring stores NullValue for "this user has no active master row" and
     *                         the miss is memoised too — which is why eviction is BIDIRECTIONAL,
     *                         firing on create/reactivate as well as deactivate. See the invariant
     *                         block above MasterService#evictUserKeyedMasterCachesAfterCommit.
     *   user-profile        — userId→UserProfileResponse DTO for GET /users/me — 5 min TTL, max
     *                         2000 entries. CROSS-AGGREGATE: hasMasterProfile is derived from a
     *                         `masters` row this cache's owning service does not write, so
     *                         MasterService and SalonService are also writers. Eviction is
     *                         centralised in common/cache/UserProfileCacheEvictor, whose javadoc
     *                         carries the complete writer set.
     *   service-type-search — trigram search results per (q, categoryId) — 5 min TTL, max 1000 entries
     *   salon-detail        — single salon entity by ID — 5 min TTL, max 1000 entries
     *   search:masters:browse / search:salons:browse — location-only discovery pages, first 5
     *                         pages only; bounded key space, shared across all callers —
     *                         60 sec TTL, max 500 entries
     *   search:masters:q / search:salons:q — free-text discovery pages, first 5 pages only;
     *                         UNBOUNDED key space (one key per settled keystroke) —
     *                         30 sec TTL, max 300 entries. Routed by SearchCacheResolver;
     *                         split so free-text churn cannot evict shared browse pages
     *   search:masters:total / search:salons:total — filter-scoped totalElements memo (perf
     *                         follow-up); one entry per filter tuple (page/size excluded from
     *                         the key), read/written directly via CacheManager inside
     *                         SearchService (not @Cacheable) so it also covers page >= 5 —
     *                         60 sec TTL, max 1000 entries. See SearchCacheNames#MASTERS_TOTAL
     *   portfolio           — per-entity portfolio listing, public unauthenticated GET — 5 min TTL, max 2000 entries
     *   reviews-by-master   — paginated review list per master, public endpoint — 5 min TTL, max 1000 entries
     *   reviews-by-salon    — paginated review list per salon, public endpoint — 5 min TTL, max 1000 entries
     *   review-detail       — single review by ID, public endpoint — 10 min TTL, max 2000 entries (immutable; no evict path)
     *   platform-category-order — approved+active PlatformCategory ordering used to sort a salon's
     *                             public catalog groups (ServiceCatalogService#buildCategoryOrderAndNames);
     *                             admin-approval-gated reference data, identical across every
     *                             salon/request — 60 min TTL, max 200 entries; evicted by
     *                             CategoryRequestService.approve/reject
     *   revenue-dashboard   — revenue summary per actor (master or salon owner) — 5 min TTL, max 500 entries
     *   locationOblasts        — full serviced-oblast list (single entry) — 24 h TTL, max 4 entries
     *   locationCitiesByOblast — cities (+hasDistricts) per oblast — 24 h TTL, max 50 entries
     *   locationDistrictsByCity— urban districts per city — 24 h TTL, max 200 entries
     *   settlementMajors       — the 50 curated is_major settlements shown before the user
     *                            types in the Phase 326 autocomplete — 24 h TTL, max 2 entries
     *                            (the method takes no arguments: ONE entry in practice)
     *   settlementSearch       — Phase 329: per-query settlement autocomplete results, keyed by
     *                            the normalised lower-cased term — 24 h TTL, max 1024 entries
     *                            (bounded; supersedes phase 326's "per-query NOT cached")
     *   cityOblastId            — shared cityId -> oblastId resolver
     *                             (SalonService/MasterService/UserService) — 24 h TTL,
     *                             max 2000 entries; negatives not cached; no eviction path
     *                             (static reference data)
     *   localityTaxonomyFacts  — fused city-exists/has-districts/district-child resolution
     *                            per (cityId,districtId) write-validation pair — 24 h TTL,
     *                            max 600 entries (an admission FENCE, not a capacity estimate)
     *
     * <p>Note on {@code search:*}: short TTL is preferred over explicit
     * {@code @CacheEvict} on master/salon write paths because discovery results
     * aggregate across many entities — the eviction key set would balloon
     * (a single rating update touches one master but invalidates every cached
     * query that returned it). 60-second TTL trades freshness for hit-rate on
     * the hot-path first 5 pages and avoids tying every write to a fan-out
     * eviction.
     */
    @Bean
    public CacheManager cacheManager(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // DYNAMIC CACHE CREATION OFF (2026-08 security audit LOW — client-passport).
        //
        // A bare `new CaffeineCacheManager()` leaves `dynamic = true`: getCache(name) for an
        // UNREGISTERED name silently mints a cache from Caffeine's DEFAULT builder — no
        // maximumSize, no expireAfterWrite. Every registration below deliberately picks both, so
        // that default is never a correct answer here; it is an unbounded retained-heap leak that
        // fails silently. The specific trigger the audit raised: `client-passport` holds per-client
        // PII-derived aggregates, and if its registration were renamed or dropped, @Cacheable
        // would keep working against an unbounded, never-expiring cache while
        // ClientPassportCacheEvictor's getCache() returned that SAME cache — so nothing would fail
        // loudly and per-user data would be retained for the life of the JVM.
        //
        // Passing an EMPTY collection sets `dynamic = false` without pre-creating anything (Spring
        // iterates the collection, then flips the flag), so it composes with registerCustomCache:
        // every name registered below stays available, and only unregistered names change
        // behaviour — from "silent unbounded cache" to a hard `IllegalArgumentException: Cannot
        // find cache named 'X'` from Spring's cache interceptor.
        //
        // VERIFIED SAFE FOR THE EXISTING CACHES: every cache name declared by any
        // @Cacheable/@CacheEvict/@CachePut in src/main (resolving the constants —
        // SearchCacheNames.*, ClientPassportService.CLIENT_PASSPORT_CACHE, PORTFOLIO_CACHE,
        // APPROVED_CATEGORIES_CACHE, BOOKABLE_CACHE, BOOKABLE_DAYS_CACHE, the location CACHE_*,
        // PlatformCategoryOrderLookup.CACHE_NAME) and every name reaching a runtime
        // cacheManager.getCache(...) call site (SearchService, SearchCacheResolver,
        // SalonService/UserService via SearchCacheNames.MASTERS_ALL/SALONS_ALL, ReviewEventListener,
        // MediaService, MasterService, ServiceCatalogService, MasterCachePrefixEvictor) is
        // registered below. @WebMvcTest slices are unaffected — they do not load this class.
        manager.setCacheNames(List.of());
        // Optional so pure @WebMvcTest slices (no actuator/metrics context) still wire the cache
        // manager; when a MeterRegistry is present the hot slot caches export hit-rate / eviction
        // gauges (Perf #5). getIfAvailable() returns the actuator-autoconfigured primary registry.
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        manager.registerCustomCache("service-categories",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(60, TimeUnit.MINUTES)
                        .build());
        // Global APPROVED+active category list for the authenticated mobile picker.
        // A single 'all' key (the list is platform-wide, not per-user), so a tiny
        // maximumSize. Evicted by CategoryRequestService.approve/reject, which change
        // APPROVED/active membership; submitRequest only inserts PENDING rows (not in
        // this list) so it needs no eviction.
        manager.registerCustomCache("approved-categories",
                Caffeine.newBuilder()
                        .maximumSize(4)
                        .expireAfterWrite(60, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("service-types",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("service-type-by-id",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("ownerSalons",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("masterServices",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        // Metered (Perf #5): high-cardinality per-(master, date, service) slot lists on hot public
        // read + write-evict paths. The CaffeineCacheMetrics gauge surfaces hit-rate / eviction-count
        // so a churn regression (500-cap too small for live master×date×service fan-out) is observable
        // before it degrades latency, rather than raising the cap blind.
        registerMetered(manager, meterRegistry, "available-slots",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS));
        manager.registerCustomCache("master-calendar",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .build());
        // Phase 15.11 (perf follow-up) — CLIENT-safe boolean working-day gating, a pure function of
        // (masterId, from, to). Mirrors available-slots: same 60-sec TTL/500-entry sizing, same
        // by-master-prefix eviction technique, fired from the same 4 schedule write paths
        // (MasterScheduleService#evictSlotsAfterCommit).
        manager.registerCustomCache("master-working-days",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
        // Phase 23.x (perf follow-up) — short-circuiting boolean usability gate backing the
        // bookable-masters endpoint. Mirrors master-working-days: same 60-sec TTL/500-entry sizing,
        // same by-master-prefix eviction technique, fired from the same schedule write paths
        // (MasterScheduleService#evictSlotsAfterCommit).
        manager.registerCustomCache("master-usable-schedule",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
        // Phase 23.x (CRITICAL free-slot fix) — the shared per-(master, service) "≥1 free future slot
        // in [from,to]" bookability verdict (SlotCalculationService#hasBookableFutureSlot) that gates
        // both the booking master-list AND the salon catalogue. Mirrors available-slots: 60-sec TTL,
        // 500 entries, sync=true. Key is {masterId, masterServiceId, from, to}; the window portion
        // cannot be evicted per-date, so it is evicted by MASTER PREFIX on every schedule write
        // (MasterScheduleService#evictSlotsAfterCommit) AND every booking write
        // (SlotCalculationService#evictMasterAvailabilityCaches, called from the booking-write
        // afterCommit hooks) — a new/cancelled booking anywhere in the horizon can flip the verdict.
        // Metered + raised cap (Perf #5). Key is {masterId, masterServiceId, from, to}: the booking
        // horizon window is fixed per call, so live cardinality is ~ (active masters × services they
        // perform). Across many salons that exceeds the previous 500-cap, so a popular master-list /
        // catalogue mix churned entries within the 60-sec TTL, defeating the cache. Raised to 2000 to
        // hold the working set for current scale; the CaffeineCacheMetrics gauge makes further tuning
        // data-driven rather than guessed.
        registerMetered(manager, meterRegistry, "master-service-bookable",
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(60, TimeUnit.SECONDS));
        // Availability-aware calendar day-gating (SlotCalculationService#getBookableWorkingDays) — the
        // serviceId-PRESENT mode of GET /masters/{masterId}/working-days, which the client booking
        // calendar reads. Distinct cache from master-working-days (the serviceId-ABSENT schedule-shape
        // mode the master's own schedule UI reads): the two answer different questions, and the
        // masterServiceId is part of THIS key because two services of different duration legitimately
        // yield different bookable-day sets. Mirrors master-service-bookable: 60-sec TTL, sync=true
        // (hot client-calendar key), metered. Evicted by MASTER PREFIX (the key's first element) on every
        // schedule write (MasterScheduleService#evictSlotsAfterCommit) AND every booking write
        // (SlotCalculationService#evictMasterAvailabilityCaches) — a new/cancelled booking anywhere in
        // the window can flip a day from bookable to full. Cardinality is master × service × calendar
        // window (the mobile calendar pages by month), so it is sized like master-service-bookable.
        //
        // SIZING (Perf MEDIUM-2). maximumSize counts ENTRIES, not weight, and unlike its neighbours (whose
        // entry is a Boolean) an entry here is a List<MasterWorkingDayResponse>. That only makes 2 000
        // entries a safe cap because the ENTRY SIZE is itself now bounded: SlotCalculationService
        // #assertBookableSpan rejects a serviceId-mode span wider than 63 inclusive dates, so no entry can
        // exceed 63 records (record of LocalDate + boolean ≈ 40 B, + list/array overhead ≈ 3 KB/entry).
        // Worst-case retained heap ≈ 2 000 × 3 KB ≈ 6 MB — comfortable on the Railway container, and an
        // order of magnitude below the 40-60 MB the uncapped 366-day window allowed. A maximumWeight +
        // weigher is therefore NOT needed: the window cap subsumes it, and it keeps the sizing directly
        // comparable to the sibling caches. If the span cap is ever raised, revisit BOTH numbers together.
        registerMetered(manager, meterRegistry, "master-bookable-days",
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(60, TimeUnit.SECONDS));
        manager.registerCustomCache("master-by-user",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        // Public GET /masters/{masterId} — 5 min TTL (shorter than master-detail-by-user
        // since discovery pages cache across many callers; 1000 entries covers active masters
        // at current scale). sync=true is specified on the @Cacheable annotation.
        // Explicit per-key eviction runs afterCommit in MasterService.deactivateMaster
        // and the reactivation branch of createMasterForOwner.
        // Profile-text writes (bio/phone/locality via UserService) rely solely on this TTL
        // because UserService holds only userId, not masterId — documented trade-off.
        manager.registerCustomCache("master-detail",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        // NEGATIVE CACHING (audit-fix cycle 2, MEDIUM). MasterService#findMyMasterDetail returns
        // Optional<MasterDetailResponse>; Spring's cache abstraction unwraps that to null and — with
        // CaffeineCacheManager's default allowNullValues=true, which this class deliberately does NOT
        // override — stores NullValue.INSTANCE. So an opted-out SALON_OWNER's 404 costs ONE query per
        // TTL window instead of one per request, and a caller that ignores the GET /users/me
        // hasMasterProfile render gate can no longer turn /masters/me into an uncached DB read.
        //
        // The price is that "no row" is now a CACHED FACT, so the eviction contract became
        // BIDIRECTIONAL: before, only deactivation could stale this cache (a miss was never stored);
        // now creation and reactivation stale it too, and every such path must evict. The complete,
        // named path list — and why it is exhaustive — lives in the invariant block above
        // MasterService#evictUserKeyedMasterCachesAfterCommit. Do not add a path without reading it.
        //
        // Phase 265 audit (finding 6): 500 → 1000. GET /masters/me was widened to SALON_OWNER,
        // and SalonService#createSalon auto-creates the owner-master row on first-salon
        // registration, so every owner is eligible for a key here by default — the previous 500
        // no longer left headroom over the provider population it serves. 1000 aligns it with
        // the sibling master-detail cache below/above, which serves a strictly LARGER (public,
        // all-masters) population at the same size; the old asymmetry was backwards. Cost is
        // ~2-3 MB of the 512 MB Railway container.
        manager.registerCustomCache("master-detail-by-user",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        // GET /api/v1/users/me (audit-fix cycle 2, LOW). The hottest authenticated read in the app —
        // every client, every launch — and until now the only one with no cache at all: three queries
        // per hit (the users row, the district label when a district is set, the city→oblast scalar)
        // plus, for a SALON_OWNER, the hasMasterProfile EXISTS.
        //
        // CROSS-AGGREGATE COUPLING — the actual hazard, stated explicitly per the audit. Every other
        // per-user cache in this file is invalidated by the service that owns the row it caches.
        // This one is not. UserProfileResponse.hasMasterProfile is derived (UserService
        // #resolveHasMasterProfile) from an ACTIVE `masters` row of type SALON_OWNER — a different
        // table, owned by a different service, in a different feature package. A write in
        // MasterService therefore stales a cache in the `user` package with nothing in the type
        // system, the call graph, or this file's neighbours to say so. That is why the eviction is
        // NOT an inline block here or in UserService but a named component,
        // common/cache/UserProfileCacheEvictor: its javadoc enumerates the complete writer set
        // (UserService, MasterService × 5 lifecycle paths, SalonService × 3,
        // EmailVerificationProcessor) together with the paths that are provably NOT writers and the
        // field-by-field evidence for each. A new writer of `masters.is_active`, `users.salon_id` or
        // `users.email_verified` must be added there.
        //
        // 5 min TTL — deliberately SHORTER than master-detail-by-user's 10, because this DTO has
        // strictly more writers than that one does and the TTL is the backstop for a writer someone
        // forgets to wire up. 2000 entries: this population is every authenticated user, not just
        // providers, so it is the largest per-user cache here.
        manager.registerCustomCache(UserProfileCacheEvictor.USER_PROFILE_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("service-type-search",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("salon-detail",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        // Discovery caches, SPLIT BY KEY POPULATION (perf + security audit 2026-07-29). Both
        // auditors raised the same defect independently: one shared 500-entry cache per surface
        // held two structurally different populations, and the unbounded one evicted the bounded
        // one.
        //
        //  * BROWSE — location-only keys (city/district × category × sort × price × page).
        //    Bounded by that product and SHARED ACROSS EVERY VISITOR, so each entry is
        //    high-value: one load serves everybody who opens that district's discovery screen.
        //    Keeps the previous 500 / 60 s sizing; nothing about this population changed.
        //
        //  * :q — free-text keys, UNBOUNDED in cardinality. An incremental search box settles
        //    once per keystroke, so typing «ламінування вій» mints ~13 distinct keys of which
        //    only the last is ever read again, and the discovery screen queries masters AND
        //    salons. On the shared cache ~40 concurrent typers churned the whole 500-entry cap
        //    inside the TTL, and what they evicted was not their own throwaway prefixes (never
        //    read again anyway) but the reusable browse pages — so the observable symptom of an
        //    undersized search cache was a latency regression on BROWSE, and an unauthenticated
        //    caller varying q converted other users' cached pages back into DB work.
        //    Sized 300 / 30 s: lower reuse justifies fewer entries, and free-text results are
        //    more staleness-tolerant than a browse listing, so the shorter TTL costs little.
        //
        // Routing is per-request (SearchCacheResolver), keyed on whether the normalised q is
        // present — the @Cacheable declares only the browse half. This split is also what makes
        // the two hit-rate gauges interpretable: a single meter averaged two populations with
        // completely different expected rates, which is exactly why the 500 cap could not be
        // tuned from it. Each half now reports its own rate.
        //
        // BLANKET-EVICTION CONTRACT: SalonService.deactivateSalon and UserService's
        // INDEPENDENT_MASTER name/locality writes clear discovery wholesale and MUST clear both
        // halves — they iterate SearchCacheNames.SALONS_ALL / MASTERS_ALL for that reason.
        registerMetered(manager, meterRegistry, SearchCacheNames.MASTERS_BROWSE,
                Caffeine.newBuilder()
                        .maximumSize(SEARCH_BROWSE_MAX_ENTRIES)
                        .expireAfterWrite(SEARCH_BROWSE_TTL_SECONDS, TimeUnit.SECONDS));
        registerMetered(manager, meterRegistry, SearchCacheNames.MASTERS_QUERY,
                Caffeine.newBuilder()
                        .maximumSize(SEARCH_QUERY_MAX_ENTRIES)
                        .expireAfterWrite(SEARCH_QUERY_TTL_SECONDS, TimeUnit.SECONDS));
        registerMetered(manager, meterRegistry, SearchCacheNames.SALONS_BROWSE,
                Caffeine.newBuilder()
                        .maximumSize(SEARCH_BROWSE_MAX_ENTRIES)
                        .expireAfterWrite(SEARCH_BROWSE_TTL_SECONDS, TimeUnit.SECONDS));
        registerMetered(manager, meterRegistry, SearchCacheNames.SALONS_QUERY,
                Caffeine.newBuilder()
                        .maximumSize(SEARCH_QUERY_MAX_ENTRIES)
                        .expireAfterWrite(SEARCH_QUERY_TTL_SECONDS, TimeUnit.SECONDS));
        // Filter-scoped totalElements memo (perf follow-up, architect decision — keyset/cursor
        // pagination REJECTED as the fix; see SearchCacheNames.MASTERS_TOTAL Javadoc). Read and
        // written directly through CacheManager inside SearchService (NOT @Cacheable — it is a
        // read-then-maybe-skip, not a memoized return value), so it keeps working on page >= 5,
        // the exact population the browse/query caches' `pageNumber < 5` condition leaves
        // uncached. One entry per filter tuple (page/size excluded from the key), so a
        // page=5..499 sweep against the SAME filter costs one probe instead of ~990.
        //
        // TRADE-OFF: a memoized total can go stale for up to SEARCH_TOTAL_TTL_SECONDS. Contained
        // by using the memo ONLY to short-circuit an out-of-range page to an EMPTY page
        // (offset >= total) — never to serve or shape data. Worst case is a transiently-empty
        // tail page that self-heals within the TTL.
        registerMetered(manager, meterRegistry, SearchCacheNames.MASTERS_TOTAL,
                Caffeine.newBuilder()
                        .maximumSize(SEARCH_TOTAL_MAX_ENTRIES)
                        .expireAfterWrite(SEARCH_TOTAL_TTL_SECONDS, TimeUnit.SECONDS));
        registerMetered(manager, meterRegistry, SearchCacheNames.SALONS_TOTAL,
                Caffeine.newBuilder()
                        .maximumSize(SEARCH_TOTAL_MAX_ENTRIES)
                        .expireAfterWrite(SEARCH_TOTAL_TTL_SECONDS, TimeUnit.SECONDS));
        // Phase 7.7 — public portfolio listing is read-mostly; the 5-min TTL bounds stale
        // exposure if an eviction is missed, and 2000 entries cover the most active
        // salons + masters for current scale.
        manager.registerCustomCache("portfolio",
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        // Phase 8 — public review listing per master; evicted by ReviewEventListener on new review creation.
        manager.registerCustomCache("reviews-by-master",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        // Phase 13.6 — public review listing per salon; mirrors reviews-by-master. Evicted by
        // ReviewEventListener on new review creation (salon branch, prefix "salon:<salonId>:").
        manager.registerCustomCache("reviews-by-salon",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        // Phase 8 — single review by ID; reviews are immutable after creation so TTL is the only expiry path.
        manager.registerCustomCache("review-detail",
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        // Phase 9 — revenue dashboard per actor; evicted by DashboardService after any booking status change.
        manager.registerCustomCache("revenue-dashboard",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        // Phase 31.x / 2026-08 perf audit F3 — BEAUTY PASSPORT per client, keyed on the caller's
        // OWN principal id (ClientPassportService#getPassport, @Cacheable(key = "#clientUserId",
        // sync = true)). A miss costs 7 statements, 3 of them aggregations over the client's
        // entire COMPLETED booking history, so this is the most expensive per-user read in the app.
        // Evicted PER KEY, AFTER_COMMIT, by ClientPassportCacheEvictor on booking completion and
        // review creation; the 10-minute TTL is only the backstop for a dropped eviction, not the
        // primary invalidation path. 2000 entries covers the active client base at current scale.
        //
        // Registered under the CONSTANT, never a duplicated "client-passport" literal (2026-08
        // security audit LOW): the @Cacheable, the evictor's getCache() and this registration are
        // then the same symbol, so a rename cannot leave the registration behind pointing at a
        // dead name. Sizing is asserted end-to-end by ClientPassportCacheIT.
        manager.registerCustomCache(ClientPassportService.CLIENT_PASSPORT_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(CLIENT_PASSPORT_MAX_ENTRIES)
                        .expireAfterWrite(CLIENT_PASSPORT_TTL_MINUTES, TimeUnit.MINUTES)
                        .build());
        // Phase 10.4 — KATOTTH locality taxonomy (~370 static reference rows, written
        // only by Flyway seed migrations). Long 24-hour TTL with NO @CacheEvict path:
        // the data cannot change at runtime, so the only invalidation is JVM restart
        // (a redeploy — also the only time the seed can change). This is the documented
        // exception to the "every @Cacheable needs a @CacheEvict" rule: there is no
        // write path to evict against and the locality repositories expose no mutations.
        manager.registerCustomCache("locationOblasts",
                Caffeine.newBuilder()
                        .maximumSize(4)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());
        manager.registerCustomCache("locationCitiesByOblast",
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());
        manager.registerCustomCache("locationDistrictsByCity",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());
        // Phase 326 — the settlement autocomplete's PRE-TYPING list only: the 50 curated
        // is_major settlements (SettlementSearchService#listMajorSettlements). Same static
        // reference data and the same 24h-TTL / no-@CacheEvict contract as the three
        // location* caches above — the rows are written by Flyway alone (V53/V170/V171), so
        // the only invalidation is a redeploy, which is also the only time they can change.
        //
        // maximumSize(2), not 50: this cache holds ONE entry — the whole 50-row list under
        // @Cacheable's SimpleKey.EMPTY, because the method takes no arguments. 2 leaves room
        // for the key to gain a dimension later without silently thrashing at 1.
        manager.registerCustomCache("settlementMajors",
                Caffeine.newBuilder()
                        .maximumSize(2)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());
        // Phase 329 — per-query settlement autocomplete results
        // (SettlementSearchService#runIndexedSearch), keyed by the normalised, LOWER-CASED term so
        // «Льв» and «льв» share one entry.
        //
        // THIS DELIBERATELY OVERTURNS phase 326's "per-query results are NOT cached". Its sole
        // rationale was the unbounded key space on a permitAll endpoint (§A, Caffeine slot
        // exhaustion). maximumSize(1024) removes that premise: an attacker cycling unique terms
        // can only EVICT entries, and each miss costs exactly what every request cost before
        // this cache existed. None of the three
        // phase-326/327 DoS controls moves: the 3-alnum-run admission and the 50-char cap run
        // BEFORE the cache (a refused term never reaches it), the 20-row LIMIT bounds each value,
        // and the per-IP bucket in AuthRateLimitFilter runs before the controller, so cache HITS
        // are charged a token too.
        //
        // 24h TTL with no @CacheEvict path: the same static Flyway-seed contract as
        // settlementMajors above — CityRepository has no write method, so a redeploy (JVM
        // restart) is the only invalidation, and the only time the rows can change. Metered so
        // the 1024 sizing can be checked against the real hit ratio rather than argued.
        //
        // Worst case ~4.4 MB (1024 keys x ~140 B + 1024 x <=20 rows x ~200 B). The @Cacheable is
        // deliberately NOT sync = true: this cache misses on every new prefix, and a sync load
        // holds Caffeine's synchronized bin lock across the whole miss, pinning a virtual-thread
        // carrier (see the advisor-order note above) — a duplicate concurrent miss costs ~1 ms.
        registerMetered(manager, meterRegistry, SettlementSearchService.CACHE_SETTLEMENT_SEARCH,
                Caffeine.newBuilder()
                        .maximumSize(1024)
                        .expireAfterWrite(24, TimeUnit.HOURS));
        // Phase 331 — the search-suggestions catalogue (SearchSuggestionCatalogue#snapshot),
        // every selectable category + active service type with NO availability applied. ONE
        // entry (the method takes no arguments — SimpleKey.EMPTY), 10-minute TTL (shorter than
        // the 60-min service-types/platform-category-order caches it is built from, since this
        // is a convenience autocomplete list, not authoritative catalogue data). sync = true on
        // the bean itself (§F-7): the single hottest key behind an unauthenticated per-keystroke
        // endpoint must collapse a TTL-expiry herd to one reload. maximumSize(4), not 1: mirrors
        // approved-categories' own headroom for a future argument the method might gain.
        manager.registerCustomCache(SearchSuggestionCatalogue.CACHE_NAME,
                Caffeine.newBuilder()
                        .maximumSize(4)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        // Phase 331 — per-place availability for search suggestions
        // (SearchSuggestionAvailability#forPlace), keyed by the district-primary-normalised
        // SuggestionPlaceKey(cityId, districtId). D5 sizing: cities holds 25 698 settlements
        // since V170, but availability only exists where providers are — realistically a few
        // hundred distinct keys per 10-minute TTL (the pre-widening ~356 cities + 76 districts).
        // maximumSize(1000) bounds worst case at ~15 MB (≤~200 UUIDs + ~21 short strings per
        // entry), ~1 MB typical. Deliberately NOT sync = true — unlike the catalogue's one hot
        // key, this cache misses on every new place, and a sync load would hold Caffeine's bin
        // lock (pinning a virtual-thread carrier) across the whole miss, the same reasoning as
        // settlementSearch above. Metered so the 1000 sizing can be checked against the real hit
        // ratio.
        registerMetered(manager, meterRegistry, SearchSuggestionAvailability.CACHE_NAME,
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(10, TimeUnit.MINUTES));
        // Audit-fix cycle 1, finding 2 (LOW security + perf) — the active-places short-circuit
        // gate consulted by SearchSuggestionService BEFORE calling
        // SearchSuggestionAvailability#forPlace. Without this, searchSuggestionAvailability's
        // 1 000-slot cache was keyed on an attacker-choosable (cityId, districtId) pair: any
        // well-formed but unknown UUID minted one repository query AND one new per-place cache
        // entry, and cycling ids could evict real places' hot entries. This cache holds ONE
        // entry (the method takes no arguments — SimpleKey.EMPTY): the full set of city/district
        // ids that have >=1 bookable offer, computed by SearchSuggestionAvailabilityRepository
        // #findActivePlaces() with the SAME D3 predicates the per-place query uses. A request
        // whose place is absent from this set short-circuits to PlaceAvailability.EMPTY without
        // ever touching the per-place cache or the DB. Same 10-minute TTL as the two caches
        // above (the national key is exempt from this gate and still resolves through
        // searchSuggestionAvailability directly). sync = true: unlike the per-place cache, this
        // one is now consulted on EVERY non-national request, so a TTL-expiry herd must collapse
        // to one reload, mirroring searchSuggestionCatalogue's reasoning. maximumSize(4) mirrors
        // that cache's own headroom for a future argument the method might gain.
        manager.registerCustomCache(SearchSuggestionActivePlaces.CACHE_NAME,
                Caffeine.newBuilder()
                        .maximumSize(4)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        // Phase 240 perf MEDIUM — shared cityId -> oblastId resolver
        // (LocationQueryService#resolveCityOblastId) backing SalonService/MasterService's
        // per-request oblastId resolution. Same static-reference-data rationale as the
        // locationOblasts/* caches above: 24h TTL, no @CacheEvict contract (data is
        // Flyway-seed-only, never mutated at runtime).
        //
        // SIZING — 2 000. This was briefly 26 000 on a WRONG premise: that Phase 325's widening of
        // `cities` from 356 rows to 25 698 had widened this cache's key space to match, making 400
        // "1.5% of the key space". It had not. Every caller keys on a STORED FK — SalonService on
        // `salons.city_id`, MasterService on the master's city, UserService#getProfile on
        // `users.city_id` — so the reachable key space is CITIES-THAT-HOST-A-PROVIDER, which
        // tracks provider growth, not the settlement table. Importing 25 342 villages nobody has
        // registered in created no new keys.
        //
        // 26 000 was also 52% of every maximumSize in this file combined, for a cache whose live
        // working set is in the hundreds — and the "~100 bytes/entry -> ~2.5 MB" arithmetic that
        // justified it was low: a Caffeine bounded entry holding UUID -> UUID measures ~158 B, so
        // 26 000 reserves ~4.1 MB of heap ceiling.
        //
        // 2 000 is ~10x the realistic distinct-provider-city count with room for years of growth,
        // costs ~315 kB fully populated, and keeps the §F-5 bound meaningful. Negatives are not
        // cached (`unless = "#result == null"` on the resolver), so an enumeration of random UUIDs
        // cannot occupy entries at all — the cap no longer has to double as that defence.
        registerMetered(manager, meterRegistry, "cityOblastId",
                Caffeine.newBuilder()
                        .maximumSize(2_000)
                        .expireAfterWrite(24, TimeUnit.HOURS));
        // Phase 346 — shared cityId -> (city name, oblast name) labels
        // (SettlementDisplayNameResolver#resolve) denormalised into users/salons city/region on
        // every cityId write. Sibling of cityOblastId above: static Flyway-seed data, 24h TTL, no
        // @CacheEvict contract. Negatives are not cached (`unless = "#result == null"`), so only
        // ids that name a real settlement occupy entries — the key space is bounded by the
        // settlement table itself, and in practice by the cities users/providers actually pick.
        // 2 000 matches cityOblastId's sizing argument; an entry (UUID -> two short strings) is a
        // few hundred bytes, and a miss is one PK lookup.
        // Metered (hit ratio / size / evictions) so the 2 000 sizing above can be checked
        // against a real working set rather than argued; same for cityOblastId.
        registerMetered(manager, meterRegistry, SettlementDisplayNameResolver.CACHE_SETTLEMENT_DISPLAY_NAMES,
                Caffeine.newBuilder()
                        .maximumSize(2_000)
                        .expireAfterWrite(24, TimeUnit.HOURS));
        // Phase 10.6 — fused write-path taxonomy resolution per (cityId, districtId)
        // pair, backing LocalityWriteValidator. Same static-reference-data rationale
        // as the locationOblasts/* read caches above: KATOTTH rows are Flyway-seed
        // only and never mutate at runtime, so a long 24-hour TTL with NO @CacheEvict
        // path is correct (the only invalidation is JVM restart / redeploy — also
        // the only time the seed can change).
        //
        // 600 IS A FENCE, NOT A CAPACITY ESTIMATE. The comment here used to justify it as
        // "~600 taxonomy rows"; Phase 325 killed that premise (25 698 cities x districts is a far
        // larger pair space). It is deliberately NOT resized to match, because unlike every other
        // cache in this file the key is not derived from a stored FK: `(cityId, districtId)` comes
        // straight off a CLIENT REQUEST BODY on the profile/salon write paths, and the
        // CITY_ABSENT verdict is itself cached — so a caller posting random UUIDs mints a new,
        // cacheable entry every time. A cap sized to the data would be a cap sized to the
        // attacker. 600 bounds that at a few tens of kB while still holding every pair a real
        // client population submits, and the cost of exceeding it is one 0.06 ms Index Only Scan
        // (6 buffers) — a miss here is cheap, which is exactly why the fence can be tight.
        manager.registerCustomCache("localityTaxonomyFacts",
                Caffeine.newBuilder()
                        .maximumSize(600)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());
        // Phase 13.1 — public guest-booking lookup behind GET /api/v1/book/{slug}/info,
        // keyed on the slug. permitAll + uncached previously meant a DB master lookup +
        // bounded service-list query per hit (scrape / DB-amplification surface). A short
        // 60-sec TTL caps that fan-out while a freshly-edited profile self-heals within a
        // minute. Exception (Phase 344 c1): avatar writes evict the slug key after commit via
        // MasterProfileCacheEvictor, because a replaced/deleted avatar's blob is purged — a stale
        // entry would render a broken image, not just an old one. sync=true on the @Cacheable annotation
        // collapses the thundering herd when a popular slug expires (Anti-Bug §F-7).
        manager.registerCustomCache("booking-slug-info",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
        // Phase 23.x (perf/security #2) — public salon service catalogue behind
        // GET /api/v1/salons/{salonId}/services, keyed on salonId. permitAll + uncached previously ran
        // an O(distinct masters) free-slot compute per hit (schedule resolve + booking load per master),
        // a DB-amplification / stampede surface. Mirrors booking-slug-info: 60-sec TTL, sync=true on the
        // @Cacheable (collapses the thundering herd when a popular salon's entry expires — §F-7), metered
        // (Perf #5). Unlike booking-slug-info this cache HAS explicit @CacheEvict wiring
        // (SalonCatalogCacheEvictor, afterCommit): a booking / schedule change on any master in the salon,
        // or a service-definition mutation, flips the bookable-service set, so the 60-sec TTL is a backstop
        // not the primary invalidation. 500 entries cover the most-browsed salons at current scale.
        registerMetered(manager, meterRegistry, "salon-service-catalog",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS));
        // Strict per-master verdict (BookingMasterService#getBookableAssignmentIds) behind the public
        // GET /masters/{id}/services client filter — keyed
        // [masterId] (one-element list), so the existing by-master afterCommit sweeps
        // (SlotCalculationService BOOKING_WRITE_CACHES, MasterScheduleService SCHEDULE_WRITE_CACHES)
        // evict it with no parallel wiring; assignment writes evict the key directly. 60-sec TTL
        // backstop, sync=true on the @Cacheable (the read is permitAll — §F-7). Sized like
        // master-service-bookable: one small id set per browsed master.
        registerMetered(manager, meterRegistry, BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(60, TimeUnit.SECONDS));
        // Phase 13.6 (perf follow-up) — approved+active PlatformCategory ordering backing
        // ServiceCatalogService#buildCategoryOrderAndNames. Mirrors service-categories's config: this
        // is admin-approval-gated reference data, identical across every request, previously
        // re-queried on every public salon-catalog hit. Evicted by
        // CategoryRequestService.approve/reject, the only writes that change APPROVED/active
        // membership.
        manager.registerCustomCache("platform-category-order",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(60, TimeUnit.MINUTES)
                        .build());
        assertCustomRegistration(manager, ClientPassportService.CLIENT_PASSPORT_CACHE);
        // Same treatment for user-profile: it is written by four services across three feature
        // packages via UserProfileCacheEvictor, which resolves the cache through
        // cacheManager.getCache(...) and NULL-CHECKS the result. That null-check is correct
        // defensive style but it means a dropped registration would make every eviction a silent
        // no-op rather than a loud failure — while @Cacheable on the read side failed loudly. Fail
        // at context refresh instead.
        assertCustomRegistration(manager, UserProfileCacheEvictor.USER_PROFILE_CACHE);
        return manager;
    }

    /**
     * Fails application startup if {@code cacheName} was not registered above as a CUSTOM cache
     * (2026-08 security audit LOW).
     *
     * <p>Read together with the {@code setCacheNames(List.of())} call at the top of
     * {@link #cacheManager}: that turns off dynamic creation, so an unregistered name resolves to
     * {@code null} instead of an unbounded default cache — and this check converts that
     * {@code null} into an {@code IllegalStateException} at context refresh rather than a lazy
     * {@code IllegalArgumentException} on whichever request first touches the cache. Only names
     * put into the manager's map by {@code registerCustomCache} can be present at this point,
     * because nothing else has been able to create one.
     *
     * <p>Applied to {@code client-passport} specifically because it is the only cache here holding
     * per-user PII-derived data — silently losing its sizing/TTL is a data-retention defect, not
     * just a performance one. It is deliberately a targeted assertion rather than a reflective
     * sweep of every {@code @Cacheable}: the {@code setCacheNames} flag already makes every OTHER
     * missing registration fail loudly at first use.
     */
    private static void assertCustomRegistration(CaffeineCacheManager manager, String cacheName) {
        if (!manager.getCacheNames().contains(cacheName)) {
            throw new IllegalStateException(
                    "Cache '" + cacheName + "' is not registered in CacheConfig. Dynamic cache "
                            + "creation is disabled, so @Cacheable on this name would fail at "
                            + "runtime; re-register it with an explicit maximumSize and "
                            + "expireAfterWrite.");
        }
    }

    /**
     * Builds a stats-recording Caffeine cache, registers it under {@code name}, and — when a
     * {@link MeterRegistry} is available — binds a {@link CaffeineCacheMetrics} gauge
     * (hit-rate, eviction-count, load stats) tagged by cache name (Perf #5). {@code recordStats()}
     * is mandatory for the metrics to report; the builder is finalised here so callers never forget
     * it. A null registry (metrics-less slice test) registers the cache without metrics.
     */
    private static void registerMetered(CaffeineCacheManager manager, MeterRegistry meterRegistry,
                                        String name, Caffeine<Object, Object> builder) {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = builder.recordStats().build();
        manager.registerCustomCache(name, cache);
        if (meterRegistry != null) {
            CaffeineCacheMetrics.monitor(meterRegistry, cache, name);
        }
    }
}
