package com.beautica.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

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
     *   master-by-user      — stable userId→Master entity mapping; TTL-only eviction — 10 min TTL, max 500 entries
     *   master-detail         — masterId→MasterDetailResponse DTO for public GET /masters/{masterId} — 5 min TTL, max 1000 entries
     *   master-detail-by-user — userId→MasterDetailResponse DTO for GET /masters/me — 10 min TTL, max 500 entries
     *   service-type-search — trigram search results per (q, categoryId) — 5 min TTL, max 1000 entries
     *   salon-detail        — single salon entity by ID — 5 min TTL, max 1000 entries
     *   search:masters      — discovery results, first 5 pages only — 60 sec TTL, max 500 entries
     *   search:salons       — discovery results, first 5 pages only — 60 sec TTL, max 500 entries
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
     *   localityTaxonomyFacts  — fused city-exists/has-districts/district-child resolution
     *                            per (cityId,districtId) write-validation pair — 24 h TTL, max 600 entries
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
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
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
        manager.registerCustomCache("available-slots",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
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
        manager.registerCustomCache("master-by-user",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        // Public GET /masters/{masterId} — 5 min TTL (shorter than master-detail-by-user
        // since discovery pages cache across many callers; 1000 entries covers active masters
        // at current scale). sync=true is specified on the @Cacheable annotation.
        // Explicit per-key eviction runs afterCommit in MasterService.deactivateMaster,
        // deactivateOwnerMaster, and the reactivation branch of createMasterForOwner.
        // Profile-text writes (bio/phone/locality via UserService) rely solely on this TTL
        // because UserService holds only userId, not masterId — documented trade-off.
        manager.registerCustomCache("master-detail",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("master-detail-by-user",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
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
        manager.registerCustomCache("search:masters",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
        manager.registerCustomCache("search:salons",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
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
        // Phase 10.6 — fused write-path taxonomy resolution per (cityId, districtId)
        // pair, backing LocalityWriteValidator. Same static-reference-data rationale
        // as the locationOblasts/* read caches above: KATOTTH rows are Flyway-seed
        // only and never mutate at runtime, so a long 24-hour TTL with NO @CacheEvict
        // path is correct (the only invalidation is JVM restart / redeploy — also
        // the only time the seed can change). 600 entries comfortably hold every
        // distinct (city, district) pair a real client submits (~600 taxonomy rows).
        manager.registerCustomCache("localityTaxonomyFacts",
                Caffeine.newBuilder()
                        .maximumSize(600)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());
        // Phase 13.1 — public guest-booking lookup behind GET /api/v1/book/{slug}/info,
        // keyed on the slug. permitAll + uncached previously meant a DB master lookup +
        // bounded service-list query per hit (scrape / DB-amplification surface). A short
        // 60-sec TTL caps that fan-out while a freshly-edited profile self-heals within a
        // minute, so NO @CacheEvict wiring is needed. sync=true on the @Cacheable annotation
        // collapses the thundering herd when a popular slug expires (Anti-Bug §F-7).
        manager.registerCustomCache("booking-slug-info",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
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
        return manager;
    }
}
