package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.dto.FavoriteServiceResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.favorite.service.FavoriteService;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.service.ServiceCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres coverage for the MERGED BEAUTY WISH LIST — {@link
 * FavoriteRepository#findFavoriteServiceRows} (salon-service-favourites track, Phase B) — the
 * {@code UNION ALL} of the MASTER arm ({@code SERVICE} favourites) and the SALON arm
 * ({@code SALON_SERVICE} favourites) into one {@code created_at DESC} ordered page.
 *
 * <p>Unit-level row-mapping coverage (mocked repository) lives in
 * {@code FavoriteServiceTest.ListServiceFavorites}; this class proves the real SQL — ordering
 * across both arms, the master-performed self-healing property, the salon-active exclusion, the
 * price-band degenerate cases, and the "wish list and catalogue never disagree" anti-divergence
 * rule (Phase 31.4 D2, extended to the SALON arm).
 */
@DisplayName("FavoriteService.listServiceFavorites — merged MASTER+SALON wish list (Testcontainers)")
class FavoriteServiceListIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private ServiceCatalogService serviceCatalogService;

    // ── merged ordering across both arms ────────────────────────────────────────

    @Test
    @DisplayName("interleaves MASTER and SALON rows in one list, ordered created_at DESC")
    void should_mergeAndOrderByCreatedAtDesc_when_bothArmsFavorited() {
        UUID clientId = createClient("mixed-order-client@beautica.test");

        // Arm A: an independent master's service (MASTER arm / SERVICE favourite).
        UUID indepMaster = createIndependentMaster("mixed-order-indep@beautica.test");
        UUID masterServiceId = createIndependentMasterService(indepMaster);

        // Arm B: a salon-catalogue service (SALON arm / SALON_SERVICE favourite).
        UUID salonId = createSalon("mixed-order-salon-owner@beautica.test");
        UUID salonMaster = createSalonMaster(salonId, "mixed-order-salon-master@beautica.test");
        UUID salonServiceDefId = createSalonServiceDefinition(salonId);
        assignMasterToService(salonMaster, salonServiceDefId, true);

        // Oldest first: SALON, then MASTER, then a second SALON — created_at is forced explicitly
        // (no Thread.sleep) so the merged ORDER BY is exercised deterministically across arms.
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceDefId);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        UUID salonId2 = createSalon("mixed-order-salon-owner-2@beautica.test");
        UUID salonMaster2 = createSalonMaster(salonId2, "mixed-order-salon-master-2@beautica.test");
        UUID salonServiceDefId2 = createSalonServiceDefinition(salonId2);
        assignMasterToService(salonMaster2, salonServiceDefId2, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceDefId2);

        setCreatedAt(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceDefId,
                Instant.parse("2026-08-01T10:00:00Z"));
        setCreatedAt(clientId, FavoriteTargetType.SERVICE, masterServiceId,
                Instant.parse("2026-08-01T10:05:00Z"));
        setCreatedAt(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceDefId2,
                Instant.parse("2026-08-01T10:10:00Z"));

        List<FavoriteServiceResponse> page =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent();

        assertThat(page).hasSize(3);
        assertThat(page).extracting(FavoriteServiceResponse::serviceDefId)
                .as("newest favourite first, across BOTH arms — one total order, not two "
                        + "sub-lists stitched together")
                .containsExactly(
                        salonServiceDefId2, resolveServiceDefIdForAssignment(masterServiceId), salonServiceDefId);
        assertThat(page.get(0).sourceType()).isEqualTo(FavoriteServiceResponse.SourceType.SALON);
        assertThat(page.get(1).sourceType()).isEqualTo(FavoriteServiceResponse.SourceType.MASTER);
        assertThat(page.get(2).sourceType()).isEqualTo(FavoriteServiceResponse.SourceType.SALON);
    }

    /** The MASTER row's {@code serviceDefId} is the underlying service definition, not the
     *  {@code master_services.id} — resolve it once here so the ordering assertion above reads
     *  the same identity space for every row. */
    private UUID resolveServiceDefIdForAssignment(UUID masterServiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT service_def_id FROM master_services WHERE id = ?", UUID.class, masterServiceId);
    }

    // ── salon-row field population ───────────────────────────────────────────────

    @Test
    @DisplayName("a SALON_SERVICE row carries the salon's identity and no master identity")
    void should_populateSalonRowFields_when_favoritingSalonService() {
        UUID clientId = createClient("salon-row-fields-client@beautica.test");
        UUID salonId = createSalon("salon-row-fields-owner@beautica.test");
        jdbcTemplate.update("UPDATE salons SET name = 'Salon Bella', avatar_url = "
                + "'https://cdn/salon.png' WHERE id = ?", salonId);
        UUID masterId = createSalonMaster(salonId, "salon-row-fields-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId);
        assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        FavoriteServiceResponse row =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent().get(0);

        assertThat(row.sourceType()).isEqualTo(FavoriteServiceResponse.SourceType.SALON);
        assertThat(row.masterServiceId()).isNull();
        assertThat(row.masterId()).isNull();
        assertThat(row.masterFirstName()).isNull();
        assertThat(row.masterLastName()).isNull();
        assertThat(row.masterAvatarUrl()).isNull();
        assertThat(row.serviceDefId()).isEqualTo(serviceDefId);
        assertThat(row.salonId()).isEqualTo(salonId);
        assertThat(row.salonName()).isEqualTo("Salon Bella");
        assertThat(row.salonAvatarUrl()).isEqualTo("https://cdn/salon.png");
    }

    // ── self-healing: vanish then reappear ──────────────────────────────────────

    @Test
    @DisplayName("a SALON_SERVICE row vanishes when the last active master drops the service, and "
            + "reappears when a master is re-assigned — the favourite row itself is retained")
    void should_hideThenReappear_when_lastMasterDropsServiceThenReassigned() {
        UUID clientId = createClient("self-heal-client@beautica.test");
        UUID salonId = createSalon("self-heal-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "self-heal-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId);
        UUID assignmentId = assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("precondition: an active, master-performed salon service IS wish-listed")
                .hasSize(1);

        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", assignmentId);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("no active master performs it any more — the row must vanish")
                .isEmpty();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId))
                .as("the favourite ROW itself survives — no cleanup job, consistent with every other arm")
                .isTrue();

        jdbcTemplate.update("UPDATE master_services SET is_active = true WHERE id = ?", assignmentId);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("re-assigning (reactivating) a master must make the SAME favourite row reappear "
                        + "— this is the deliberate self-healing property, not a bug")
                .hasSize(1);
    }

    // ── deactivated salon excluded ───────────────────────────────────────────────

    @Test
    @DisplayName("a SALON_SERVICE row is excluded once its owning salon is deactivated")
    void should_excludeRow_when_owningSalonDeactivated() {
        UUID clientId = createClient("deact-salon-list-client@beautica.test");
        UUID salonId = createSalon("deact-salon-list-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "deact-salon-list-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId);
        assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        Page<FavoriteServiceResponse> page =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements())
                .as("the count query carries the same predicate, or paging metadata lies")
                .isZero();
    }

    // ── price band degenerate cases ──────────────────────────────────────────────

    @Test
    @DisplayName("FIXED price renders a single figure, never a fabricated N-N band")
    void should_renderSingleFigure_when_priceTypeFixed() {
        UUID clientId = createClient("price-fixed-client@beautica.test");
        UUID salonId = createSalon("price-fixed-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "price-fixed-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinitionWithPrice(salonId, "FIXED", "400.00", null);
        assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        FavoriteServiceResponse row =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent().get(0);

        assertThat(row.priceMax()).isNull();
        assertThat(row.priceDisplay()).isEqualTo("400 ₴");
    }

    @Test
    @DisplayName("RANGE with priceMax > basePrice renders a band")
    void should_renderBand_when_priceTypeRangeWithDistinctBounds() {
        UUID clientId = createClient("price-range-client@beautica.test");
        UUID salonId = createSalon("price-range-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "price-range-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinitionWithPrice(salonId, "RANGE", "500.00", "800.00");
        assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        FavoriteServiceResponse row =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent().get(0);

        assertThat(row.priceDisplay()).isEqualTo("від 500 до 800 ₴");
    }

    /**
     * <b>Plan-vs-reality note (do not silently "fix" this test to match the phase-brief prose).</b>
     * The task brief describes a RANGE row with {@code priceMax == basePrice} as "collapsing to a
     * single figure". No such collapsing exists anywhere in the current codebase:
     * {@code PriceDisplayFormatter.format} always renders {@code RANGE} as
     * "від X до Y ₴" regardless of whether X equals Y, and {@code ServicePricing.ofDefinition}
     * does not special-case it either. This test therefore pins the REAL, current behaviour (a
     * degenerate "від 500 до 500 ₴" band) rather than asserting a collapse the code does not
     * perform — see the task hand-off notes for the flag raised against the brief. Reaching this
     * state at all requires bypassing {@code ServicePriceValidator} (which enforces
     * {@code priceMax > priceMin} strictly on every write path), so it is seeded directly via SQL,
     * exercising only the DB CHECK's {@code price_max >= base_price} (non-strict).
     */
    @Test
    @DisplayName("RANGE with priceMax == basePrice (DB-legal, request-illegal edge case) renders the "
            + "real current band — NOT a collapsed single figure; see javadoc")
    void should_renderDegenerateBand_when_priceMaxEqualsBasePrice() {
        UUID clientId = createClient("price-degenerate-client@beautica.test");
        UUID salonId = createSalon("price-degenerate-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "price-degenerate-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinitionWithPrice(salonId, "RANGE", "500.00", "500.00");
        assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        FavoriteServiceResponse row =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent().get(0);

        assertThat(row.priceDisplay()).isEqualTo("від 500 до 500 ₴");
    }

    // ── anti-divergence: wish list vs catalogue must never disagree ────────────

    @Test
    @DisplayName("the favourite row's priceDisplay equals GET /salons/{id}/services' string for the "
            + "SAME definition — the wish list and the catalogue tile it was saved from must never "
            + "print a different price")
    void should_matchSalonCataloguePriceDisplay_when_sameDefinition() {
        UUID clientId = createClient("anti-divergence-client@beautica.test");
        UUID salonId = createSalon("anti-divergence-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "anti-divergence-master@beautica.test");
        seedUsableSchedule(masterId);
        UUID serviceDefId = createSalonServiceDefinitionWithPrice(salonId, "RANGE", "600.00", "900.00");
        assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        String wishListPriceDisplay =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20))
                        .getContent().get(0).priceDisplay();

        String cataloguePriceDisplay = serviceCatalogService.getSalonServiceCatalog(salonId)
                .categories().stream()
                .flatMap(g -> g.services().stream())
                .filter(s -> s.id().equals(serviceDefId))
                .map(ServiceDefinitionResponse::priceDisplay)
                .findFirst().orElseThrow();

        assertThat(wishListPriceDisplay).isEqualTo(cataloguePriceDisplay);
    }

    // ── pagination across the UNION ALL boundary ────────────────────────────────

    /**
     * Gap closed by backend-qa (2026-08-10 audit): every other test in this class fetches
     * ONE page big enough to hold every row ({@code Pageable.ofSize(20)}), so a page boundary
     * that falls in the MIDDLE of the interleaved MASTER/SALON sequence was never exercised.
     * {@code findFavoriteServiceRows} is a single {@code UNION ALL} wrapped in an outer
     * {@code ORDER BY ... LIMIT/OFFSET}, but that structure is exactly what a Postgres planner
     * bug or a future "optimize" (e.g. pushing the LIMIT into one arm) could silently break —
     * this test would catch a dropped or duplicated row at the boundary that a single-page test
     * structurally cannot.
     */
    @Test
    @DisplayName("a page boundary falling between a SALON row and a MASTER row drops nothing and "
            + "duplicates nothing — the UNION ALL is paginated as ONE ordered sequence")
    void should_preserveOrderAcrossPages_when_pageBoundarySplitsTheTwoArms() {
        UUID clientId = createClient("paging-boundary-client@beautica.test");

        // Five favourites, oldest to newest: SALON, MASTER, SALON, MASTER, SALON — deliberately
        // alternating arms so every page-size-2 boundary below splits a SALON row from a MASTER
        // row (or vice versa), never two rows of the same arm.
        UUID salonA = createSalon("paging-salon-a@beautica.test");
        UUID masterA = createSalonMaster(salonA, "paging-salon-a-master@beautica.test");
        UUID salonServiceA = createSalonServiceDefinition(salonA);
        assignMasterToService(masterA, salonServiceA, true);

        UUID indepMasterA = createIndependentMaster("paging-indep-a@beautica.test");
        UUID masterServiceA = createIndependentMasterService(indepMasterA);

        UUID salonB = createSalon("paging-salon-b@beautica.test");
        UUID masterB = createSalonMaster(salonB, "paging-salon-b-master@beautica.test");
        UUID salonServiceB = createSalonServiceDefinition(salonB);
        assignMasterToService(masterB, salonServiceB, true);

        UUID indepMasterB = createIndependentMaster("paging-indep-b@beautica.test");
        UUID masterServiceB = createIndependentMasterService(indepMasterB);

        UUID salonC = createSalon("paging-salon-c@beautica.test");
        UUID masterC = createSalonMaster(salonC, "paging-salon-c-master@beautica.test");
        UUID salonServiceC = createSalonServiceDefinition(salonC);
        assignMasterToService(masterC, salonServiceC, true);

        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceA);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceA);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceB);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceB);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceC);

        setCreatedAt(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceA, Instant.parse("2026-08-01T10:00:00Z"));
        setCreatedAt(clientId, FavoriteTargetType.SERVICE, masterServiceA, Instant.parse("2026-08-01T10:01:00Z"));
        setCreatedAt(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceB, Instant.parse("2026-08-01T10:02:00Z"));
        setCreatedAt(clientId, FavoriteTargetType.SERVICE, masterServiceB, Instant.parse("2026-08-01T10:03:00Z"));
        setCreatedAt(clientId, FavoriteTargetType.SALON_SERVICE, salonServiceC, Instant.parse("2026-08-01T10:04:00Z"));

        // Newest first: salonC, masterServiceB, salonB, masterServiceA, salonA.
        List<UUID> expectedOrder = List.of(
                salonServiceC, resolveServiceDefIdForAssignment(masterServiceB), salonServiceB,
                resolveServiceDefIdForAssignment(masterServiceA), salonServiceA);

        List<UUID> collected = new java.util.ArrayList<>();
        long totalElementsSeenOnEveryPage = -1;
        int pageIndex = 0;
        Page<FavoriteServiceResponse> page;
        do {
            page = favoriteService.listServiceFavorites(
                    clientId, org.springframework.data.domain.PageRequest.of(pageIndex, 2));
            if (totalElementsSeenOnEveryPage == -1) {
                totalElementsSeenOnEveryPage = page.getTotalElements();
            }
            assertThat(page.getTotalElements())
                    .as("page %d: total-elements paging metadata must agree across every page — "
                            + "the count query must sum both arms exactly like the content query", pageIndex)
                    .isEqualTo(totalElementsSeenOnEveryPage);
            page.getContent().forEach(row -> collected.add(row.serviceDefId()));
            pageIndex++;
        } while (page.hasNext());

        assertThat(totalElementsSeenOnEveryPage)
                .as("the count query must agree with the content across both arms")
                .isEqualTo(5);
        assertThat(collected)
                .as("no row dropped or duplicated at a page boundary that splits the two arms — "
                        + "one total order, paginated as ONE sequence")
                .containsExactlyElementsOf(expectedOrder);
    }

    // ── seed helpers (ASCII data) ────────────────────────────────────────────────

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                id, email);
        return id;
    }

    private UUID createSalon(String ownerEmail) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)",
                ownerId, ownerEmail);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, 'Test Salon', true, NOW(), NOW(), ?)",
                salonId, ownerId, testCityId());
        return salonId;
    }

    private UUID createSalonMaster(UUID salonId, String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createSalonServiceDefinition(UUID salonId) {
        return createSalonServiceDefinitionWithPrice(salonId, "FIXED", "500.00", null);
    }

    private UUID createSalonServiceDefinitionWithPrice(UUID salonId, String priceType,
                                                       String basePrice, String priceMax) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, "
                        + "price_type, base_price, price_max, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, ?, ?, ?, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId), priceType,
                new java.math.BigDecimal(basePrice),
                priceMax == null ? null : new java.math.BigDecimal(priceMax));
        return serviceDefId;
    }

    private UUID assignMasterToService(UUID masterId, UUID serviceDefId, boolean assignmentActive) {
        UUID assignmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                assignmentId, masterId, serviceDefId, assignmentActive);
        return assignmentId;
    }

    private void setCreatedAt(UUID clientId, FavoriteTargetType targetType, UUID targetId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE favorites SET created_at = ? WHERE client_id = ? AND target_type = ? AND target_id = ?",
                java.sql.Timestamp.from(createdAt), clientId, targetType.name(), targetId);
    }

    // ── Phase 23.x free-slot gate fixture (schedule) — needed only by the
    //    anti-divergence test, which calls the real getSalonServiceCatalog ───────

    private LocalDate kyivToday() {
        return LocalDate.now(KYIV);
    }

    private void seedUsableSchedule(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, NOW(), NOW())",
                scheduleId, masterId, kyivToday());
        jdbcTemplate.update(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, kyivToday().getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
    }
}
