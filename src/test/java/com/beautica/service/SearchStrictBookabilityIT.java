package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.service.BookingMasterService;
import com.beautica.common.TimeZones;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strict search verdict (SRCH-30, SRCH-31, SAL-09; user decision 2026-11-18): a master a client
 * cannot actually book is absent from master search, does not put her salon into salon search and is
 * absent from the public salon roster — even when she passes the cheap structural rule
 * ({@code MasterBookabilitySql}: an active service and a weekly template with hours inside the horizon).
 *
 * <p>The prod case: a weekly template valid only today → today + 1 whose remaining working days are
 * both DAY_OFF overrides. Structurally bookable, no free slot anywhere in the horizon.
 */
@DisplayName("Search + public roster use the strict free-slot verdict (full HTTP + real Postgres)")
class SearchStrictBookabilityIT extends AbstractIntegrationTest {

    private static final String MASTERS_URL = "/api/v1/search/masters";
    private static final String SALONS_URL = "/api/v1/search/salons";
    private static final String SUGGESTIONS_URL = "/api/v1/search/suggestions";
    /** Longer than the seeded 09:00–17:00 day, so the service never fits any day. */
    private static final int UNFITTABLE_DURATION_MINUTES = 540;
    /** SRCH-33 fixtures: free-form category values (stored and searched upper-case). */
    private static final String UNBOOKABLE_CATEGORY = "BROVY";
    private static final String BOOKABLE_CATEGORY = "NIGTI";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private BookingMasterService bookingMasterService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookabilityHttp http;
    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        http = new BookabilityHttp(restTemplate, objectMapper);
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── prod case: template today→+1, every remaining working day is a DAY_OFF ──────────────

    @Test
    @DisplayName("an independent master whose only remaining working days are days off is absent from "
            + "master search (browse and ?q by name); a bookable one stays")
    void should_hideIndependentMaster_when_remainingWorkingDaysAreDaysOff() throws Exception {
        UUID bookable = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Yavir");
        BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, bookable);
        UUID daysOff = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Verbena");
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, daysOff);
        BookableMasterSeeder.seedEveryRemainingDayOff(jdbcTemplate, daysOff);

        JsonNode browse = http.get(MASTERS_URL + "?page=0&size=20");
        JsonNode byName = http.get(MASTERS_URL + "?q=Verbena");

        assertThat(ids(browse, "masterId")).containsExactly(bookable.toString());
        assertThat(browse.path("totalElements").asLong()).isEqualTo(1);
        assertThat(ids(byName, "masterId")).isEmpty();
    }

    @Test
    @DisplayName("a salon whose only master has days off on every remaining working day is absent from "
            + "salon search (static browse, static ?q, dynamic slug filter) and its roster is empty")
    void should_hideSalonAndRoster_when_onlyMasterHasEveryRemainingDayOff() throws Exception {
        UUID open = BookableMasterSeeder.insertSalon(jdbcTemplate, "Lileya Studio");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, open);
        UUID closed = BookableMasterSeeder.insertSalon(jdbcTemplate, "Rosava Studio");
        UUID closedMaster = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, closed);
        UUID closedDef = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, closed, closedMaster);
        BookableMasterSeeder.seedEveryRemainingDayOff(jdbcTemplate, closedMaster);

        JsonNode browse = http.get(SALONS_URL + "?page=0&size=20");
        JsonNode byName = http.get(SALONS_URL + "?q=Rosava");
        JsonNode bySlug = http.get(SALONS_URL + "?serviceTypeSlugs=" + slugOf(closedDef));

        assertThat(ids(browse, "salonId")).containsExactly(open.toString());
        assertThat(browse.path("totalElements").asLong()).isEqualTo(1);
        assertThat(ids(byName, "salonId")).isEmpty();
        assertThat(ids(bySlug, "salonId")).doesNotContain(closed.toString());
        assertThat(http.rosterIds(closed)).isEmpty();
    }

    @Test
    @DisplayName("the public roster keeps the bookable master and drops the one with every remaining day off")
    void should_listOnlyStrictlyBookableMasters_when_rosterMixesBoth() throws Exception {
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Kvitka Studio");
        UUID bookable = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon);
        UUID daysOff = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, daysOff);
        BookableMasterSeeder.seedEveryRemainingDayOff(jdbcTemplate, daysOff);

        assertThat(http.rosterIds(salon)).containsExactly(bookable.toString());
    }

    // ── service filter: the matching service must itself be bookable ───────────────────────

    @Test
    @DisplayName("with a slug filter a master is listed only when the MATCHING service fits a free slot "
            + "(a service longer than every working day hides her for that slug only)")
    void should_gateOnMatchingService_when_serviceTypeFilterActive() throws Exception {
        UUID master = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Chervona");
        UUID shortDef = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        UUID longDef = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        makeUnfittable(longDef);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);

        JsonNode shortSlug = http.get(MASTERS_URL + "?serviceTypeSlugs=" + slugOf(shortDef));
        JsonNode longSlug = http.get(MASTERS_URL + "?serviceTypeSlugs=" + slugOf(longDef));

        assertThat(ids(shortSlug, "masterId")).containsExactly(master.toString());
        assertThat(ids(longSlug, "masterId")).isEmpty();
        assertThat(longSlug.path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("salon slug filter: a salon is listed only when a master of it can book the matching service")
    void should_gateSalonOnMatchingService_when_serviceTypeFilterActive() throws Exception {
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Dibrova Studio");
        UUID master = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        UUID shortDef = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, master);
        UUID longDef = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, master);
        makeUnfittable(longDef);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);

        assertThat(ids(http.get(SALONS_URL + "?serviceTypeSlugs=" + slugOf(shortDef)), "salonId"))
                .containsExactly(salon.toString());
        assertThat(ids(http.get(SALONS_URL + "?serviceTypeSlugs=" + slugOf(longDef)), "salonId"))
                .isEmpty();
    }

    // ── category filter (SRCH-33): the in-category service must itself be bookable ───────────

    @Test
    @DisplayName("master ?category lists a master only when a service IN that category is strictly bookable: "
            + "her bookable service in another category does not carry her into the first")
    void should_hideMaster_when_onlyServiceInCategoryIsUnbookable() throws Exception {
        UUID master = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Shypshyna");
        UUID inCategory = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        UUID otherCategory = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        makeUnfittable(inCategory);
        categorise(inCategory, UNBOOKABLE_CATEGORY);
        categorise(otherCategory, BOOKABLE_CATEGORY);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);

        JsonNode unbookableCategory = http.get(MASTERS_URL + "?category=" + UNBOOKABLE_CATEGORY);
        JsonNode bookableCategory = http.get(MASTERS_URL + "?category=" + BOOKABLE_CATEGORY);

        assertThat(ids(unbookableCategory, "masterId")).isEmpty();
        assertThat(unbookableCategory.path("totalElements").asLong()).isZero();
        assertThat(ids(bookableCategory, "masterId"))
                .as("control: the same master IS listed under the category she can be booked in")
                .containsExactly(master.toString());
    }

    @Test
    @DisplayName("salon ?category (static path) and ?category + slugs (dynamic path) list a salon only when a "
            + "service IN that category is strictly bookable — no 500, no carry-over from another category")
    void should_hideSalon_when_onlyServiceInCategoryIsUnbookable() throws Exception {
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Shypshyna Studio");
        UUID master = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        UUID inCategory = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, master);
        UUID otherCategory = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, master);
        makeUnfittable(inCategory);
        categorise(inCategory, UNBOOKABLE_CATEGORY);
        categorise(otherCategory, BOOKABLE_CATEGORY);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);
        String bothSlugs = "&serviceTypeSlugs=" + slugOf(inCategory) + "&serviceTypeSlugs=" + slugOf(otherCategory);

        JsonNode staticUnbookable = http.get(SALONS_URL + "?category=" + UNBOOKABLE_CATEGORY);
        JsonNode dynamicUnbookable = http.get(SALONS_URL + "?category=" + UNBOOKABLE_CATEGORY + bothSlugs);
        JsonNode staticBookable = http.get(SALONS_URL + "?category=" + BOOKABLE_CATEGORY);
        JsonNode dynamicBookable = http.get(SALONS_URL + "?category=" + BOOKABLE_CATEGORY + bothSlugs);

        assertThat(ids(staticUnbookable, "salonId")).isEmpty();
        assertThat(ids(dynamicUnbookable, "salonId"))
                .as("dynamic path: the slug-matched bookable service is in ANOTHER category, so it may "
                        + "not carry the salon into this one")
                .isEmpty();
        assertThat(dynamicUnbookable.path("totalElements").asLong()).isZero();
        assertThat(ids(staticBookable, "salonId")).containsExactly(salon.toString());
        assertThat(ids(dynamicBookable, "salonId")).containsExactly(salon.toString());
    }

    @Test
    @DisplayName("master ?q matching a platform category label lists a master only when a service IN that "
            + "category is strictly bookable: her bookable service in another category does not carry her in")
    void should_hideMaster_when_qMatchesCategoryLabelAndCategoryServiceIsUnbookable() throws Exception {
        Map<String, Object> category = jdbcTemplate.queryForMap(
                "SELECT name, display_name FROM platform_categories WHERE status = 'APPROVED' AND active = TRUE "
                        + "AND name NOT IN (?, ?) ORDER BY display_name LIMIT 1",
                UNBOOKABLE_CATEGORY, BOOKABLE_CATEGORY);
        String categoryName = (String) category.get("name");
        UUID unbookable = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Kalyna");
        UUID inCategory = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, unbookable);
        UUID otherCategory = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, unbookable);
        makeUnfittable(inCategory);
        categorise(inCategory, categoryName);
        categorise(otherCategory, BOOKABLE_CATEGORY);
        rename(inCategory, "Kalyna Dovha");
        rename(otherCategory, "Kalyna Korotka");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, unbookable);
        UUID bookable = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Hortenzia");
        UUID bookableInCategory = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, bookable);
        categorise(bookableInCategory, categoryName);
        rename(bookableInCategory, "Hortenzia Korotka");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, bookable);

        JsonNode byLabel = http.get(MASTERS_URL + "?q=" + category.get("display_name"));

        assertThat(ids(byLabel, "masterId"))
                .as("only the master whose in-category service is bookable; the control proves the label matched")
                .containsExactly(bookable.toString());
        assertThat(byLabel.path("totalElements").asLong()).isEqualTo(1);
    }

    // ── one verdict, one cache ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("search fills the master-bookable-assignments entry the services tab reads, with the "
            + "same verdict BookingMasterService#getBookableAssignmentIds computes")
    void should_shareVerdictAndCacheEntry_when_searchResolvesMaster() throws Exception {
        UUID master = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Smereka");
        UUID shortDef = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        UUID longDef = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        makeUnfittable(longDef);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);

        http.get(MASTERS_URL + "?page=0&size=20");
        Cache cache = cacheManager.getCache(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE);
        Cache.ValueWrapper filledBySearch = cache.get(BookingMasterService.bookableAssignmentsCacheKey(master));
        Set<UUID> uncached = bookingMasterService.computeBookableAssignmentIds(Set.of(master)).get(master);

        assertThat(filledBySearch).as("search wrote the per-master entry").isNotNull();
        assertThat(filledBySearch.get()).isEqualTo(uncached);
        assertThat(uncached).containsExactly(assignmentOf(shortDef));
        assertThat(bookingMasterService.getBookableAssignmentIds(master)).isEqualTo(uncached);
    }

    // ── services longer than every working interval ─────────────────────────────────────────

    @Test
    @DisplayName("a master whose ONLY service is longer than every working day is absent from master search, "
            + "her salon is absent from salon search (total 0) and the roster is empty; bookable controls stay")
    void should_hideProviderEverywhere_when_onlyServiceIsLongerThanEveryWorkingInterval() throws Exception {
        UUID control = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Kalyna");
        BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, control);
        UUID longIndep = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Dovha");
        makeUnfittable(BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, longIndep));
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, longIndep);
        UUID controlSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Topolia Studio");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, controlSalon);
        UUID longSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Dovhyi Studio");
        UUID longSalonMaster = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, longSalon);
        makeUnfittable(BookableMasterSeeder.assignNewSalonService(jdbcTemplate, longSalon, longSalonMaster));
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, longSalonMaster);

        JsonNode masters = http.get(MASTERS_URL + "?page=0&size=20");
        JsonNode salons = http.get(SALONS_URL + "?page=0&size=20");

        assertThat(ids(masters, "masterId")).containsExactly(control.toString());
        assertThat(masters.path("totalElements").asLong()).isEqualTo(1);
        assertThat(ids(salons, "salonId")).containsExactly(controlSalon.toString());
        assertThat(salons.path("totalElements").asLong()).isEqualTo(1);
        assertThat(http.rosterIds(longSalon)).isEmpty();
    }

    // ── salon price band ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("salon price band is priced from strictly bookable masters only: a days-off master's "
            + "2000 service does not widen the 500 band of the bookable master")
    void should_priceSalonBandFromBookableMastersOnly_when_otherMasterHasNoFreeSlot() throws Exception {
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Yasen Studio");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon);
        UUID daysOff = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        UUID expensive = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, daysOff);
        jdbcTemplate.update("UPDATE service_definitions SET base_price = 2000.00 WHERE id = ?", expensive);
        BookableMasterSeeder.seedEveryRemainingDayOff(jdbcTemplate, daysOff);

        JsonNode row = http.get(SALONS_URL + "?page=0&size=20").path("data").get(0);

        assertThat(row.path("salonId").asText()).isEqualTo(salon.toString());
        assertThat(row.path("priceMin").decimalValue()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(row.path("priceMax").decimalValue())
                .as("the unbookable master's price must not reach the band")
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ── salon free-text q ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("salon ?q matches a salon only through a service a client can book: the name of a "
            + "service longer than every working day finds nothing; the bookable one finds the salon")
    void should_matchSalonByBookableServiceOnly_when_salonSearchedByServiceName() throws Exception {
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Klen Studio");
        UUID master = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        UUID fitting = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, master);
        UUID unfittable = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, master);
        makeUnfittable(unfittable);
        rename(fitting, "Zaplitka");
        rename(unfittable, "Kokoshnyk");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);

        JsonNode byUnbookable = http.get(SALONS_URL + "?q=Kokoshnyk");
        JsonNode byBookable = http.get(SALONS_URL + "?q=Zaplitka");

        assertThat(ids(byUnbookable, "salonId")).isEmpty();
        assertThat(byUnbookable.path("totalElements").asLong()).isZero();
        assertThat(ids(byBookable, "salonId")).containsExactly(salon.toString());
        assertThat(names(byBookable.path("data").get(0), "matchedServiceNames"))
                .as("only the bookable service explains the match")
                .containsExactly("Zaplitka");
    }

    // ── B523 fix pass: master q, card previews, filtered salon candidates ──────────────────

    @Test
    @DisplayName("master ?q matches a master only through a service a client can book: the name of a "
            + "service longer than every working day finds nothing; the bookable one and her name find her")
    void should_matchMasterByBookableServiceOnly_when_masterSearchedByServiceName() throws Exception {
        UUID master = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Horobyna");
        UUID fitting = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        UUID unfittable = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        makeUnfittable(unfittable);
        rename(fitting, "Zaplitka");
        rename(unfittable, "Kokoshnyk");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);

        JsonNode byUnbookable = http.get(MASTERS_URL + "?q=Kokoshnyk");
        JsonNode byBookable = http.get(MASTERS_URL + "?q=Zaplitka");
        JsonNode byName = http.get(MASTERS_URL + "?q=Horobyna");

        assertThat(ids(byUnbookable, "masterId")).isEmpty();
        assertThat(byUnbookable.path("totalElements").asLong()).isZero();
        assertThat(ids(byBookable, "masterId")).containsExactly(master.toString());
        assertThat(names(byBookable.path("data").get(0), "matchedServiceNames"))
                .as("only the bookable service explains the match")
                .containsExactly("Zaplitka");
        assertThat(ids(byName, "masterId")).as("a name match needs no service match").containsExactly(master.toString());
    }

    @Test
    @DisplayName("card previews list only strictly bookable services: a service longer than every working "
            + "day is absent from the master card and the salon card (static and dynamic salon path)")
    void should_excludeUnbookableServicesFromCardPreview_when_providerAlsoHasBookableOnes() throws Exception {
        UUID master = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Lypa");
        rename(BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master), "Alfa Manikiur");
        UUID masterLong = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        makeUnfittable(masterLong);
        rename(masterLong, "Beta Dovha");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Vyshnia Studio");
        UUID salonMaster = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        UUID salonShort = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, salonMaster);
        rename(salonShort, "Alfa Stryzhka");
        UUID salonLong = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, salonMaster);
        makeUnfittable(salonLong);
        rename(salonLong, "Beta Dovha");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, salonMaster);

        JsonNode masterRow = http.get(MASTERS_URL + "?page=0&size=20").path("data").get(0);
        JsonNode staticSalonRow = http.get(SALONS_URL + "?page=0&size=20").path("data").get(0);
        JsonNode dynamicSalonRow = http.get(SALONS_URL + "?serviceTypeSlugs=" + slugOf(salonShort)
                + "&serviceTypeSlugs=" + slugOf(salonLong)).path("data").get(0);

        assertThat(names(masterRow, "serviceNames")).as("master card").containsExactly("Alfa Manikiur");
        assertThat(names(staticSalonRow, "serviceNames")).as("salon card, static path")
                .containsExactly("Alfa Stryzhka");
        assertThat(names(dynamicSalonRow, "serviceNames")).as("salon card, dynamic path")
                .containsExactly("Alfa Stryzhka");
        assertThat(names(dynamicSalonRow, "matchedServiceNames")).as("salon matched names, dynamic path")
                .containsExactly("Alfa Stryzhka");
    }

    @Test
    @DisplayName("a filtered salon search resolves only the masters of salons that match the filter: the "
            + "non-matching salon's master is never handed to the strict verdict (no cache entry)")
    void should_resolveOnlyMatchingSalonsMasters_when_salonSearchIsFiltered() throws Exception {
        UUID matching = BookableMasterSeeder.insertSalon(jdbcTemplate, "Shypshyna Studio");
        UUID matchingMaster = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, matching);
        UUID other = BookableMasterSeeder.insertSalon(jdbcTemplate, "Riabyna Studio");
        UUID otherMaster = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, other);
        Cache cache = cacheManager.getCache(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE);
        cache.clear();

        JsonNode byName = http.get(SALONS_URL + "?q=Shypshyna");

        assertThat(ids(byName, "salonId")).containsExactly(matching.toString());
        assertThat(cache.get(BookingMasterService.bookableAssignmentsCacheKey(matchingMaster)))
                .as("the matching salon's master was resolved").isNotNull();
        assertThat(cache.get(BookingMasterService.bookableAssignmentsCacheKey(otherMaster)))
                .as("the non-matching salon's master was filtered out before the strict verdict").isNull();
    }

    // ── SRCH-D07: suggestions + favourites keep the structural rule ─────────────────────────

    @Test
    @DisplayName("SRCH-D07: a master and a salon with no free slot leave search, yet favourites still list "
            + "them and suggestions still offer their service type (structural rule unchanged)")
    void should_keepStructuralVerdict_when_suggestionsAndFavouritesRead_whileSearchIsStrict() throws Exception {
        UUID master = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Bereza");
        UUID def = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master);
        BookableMasterSeeder.seedEveryRemainingDayOff(jdbcTemplate, master);
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Holub Studio");
        UUID salonMaster = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, salonMaster);
        BookableMasterSeeder.seedEveryRemainingDayOff(jdbcTemplate, salonMaster);
        String clientToken = fixtures.createClientAndGetToken("d07-" + System.nanoTime() + "@beautica.test");
        http.addFavorite(clientToken, FavoriteTargetType.MASTER, master);
        http.addFavorite(clientToken, FavoriteTargetType.SALON, salon);
        String typeName = jdbcTemplate.queryForObject(
                "SELECT st.name_uk FROM service_definitions sd JOIN service_types st ON st.id = sd.service_type_id "
                        + "WHERE sd.id = ?", String.class, def);

        assertThat(ids(http.get(MASTERS_URL + "?page=0&size=20"), "masterId")).as("strict master search").isEmpty();
        assertThat(ids(http.get(SALONS_URL + "?page=0&size=20"), "salonId")).as("strict salon search").isEmpty();
        assertThat(ids(http.get("/api/v1/favorites/masters", clientToken), "masterId"))
                .as("favourites (and the home rail) keep the structural rule").containsExactly(master.toString());
        assertThat(ids(http.get("/api/v1/favorites/salons", clientToken), "salonId"))
                .as("favourite salons keep the structural rule").containsExactly(salon.toString());
        assertThat(http.get(SUGGESTIONS_URL + "?limit=8&q=" + typeName).findValuesAsText("serviceTypeSlug"))
                .as("suggestions keep the structural rule").contains(slugOf(def));
    }

    // ── cache window: booking the last free slot ────────────────────────────────────────────

    @Test
    @DisplayName("booking the last free slot evicts the cached verdict at once (roster empties immediately); "
            + "once the search result cache window passes, master and salon search drop the provider")
    void should_dropProviderAfterCacheWindow_when_lastFreeSlotIsBooked() throws Exception {
        LocalDate day = LocalDate.now(TimeZones.KYIV).plusDays(2);
        UUID indep = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Ostannia");
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, indep);
        BookableMasterSeeder.seedCustomHoursOverride(jdbcTemplate, indep, day, LocalTime.of(10, 0), LocalTime.of(11, 0));
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Vyshnia Studio");
        UUID salonMaster = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salon);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon, salonMaster);
        BookableMasterSeeder.seedCustomHoursOverride(jdbcTemplate, salonMaster, day, LocalTime.of(10, 0), LocalTime.of(11, 0));
        Cache verdicts = cacheManager.getCache(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE);
        assertThat(ids(http.get(MASTERS_URL + "?page=0&size=20"), "masterId"))
                .as("precondition: exactly one free slot — listed").containsExactly(indep.toString());
        assertThat(ids(http.get(SALONS_URL + "?page=0&size=20"), "salonId"))
                .as("precondition: salon listed").containsExactly(salon.toString());
        assertThat(http.rosterIds(salon)).as("precondition: on the roster").containsExactly(salonMaster.toString());

        fixtures.bookTenOClockSlot(indep, day);
        fixtures.bookTenOClockSlot(salonMaster, day);

        assertThat(verdicts.get(BookingMasterService.bookableAssignmentsCacheKey(indep)))
                .as("booking evicted the independent master's verdict").isNull();
        assertThat(verdicts.get(BookingMasterService.bookableAssignmentsCacheKey(salonMaster)))
                .as("booking evicted the salon master's verdict").isNull();
        assertThat(http.rosterIds(salon)).as("roster has no result cache — empty at once").isEmpty();
        expireSearchResultCaches();
        JsonNode masters = http.get(MASTERS_URL + "?page=0&size=20");
        JsonNode salons = http.get(SALONS_URL + "?page=0&size=20");
        assertThat(ids(masters, "masterId")).isEmpty();
        assertThat(masters.path("totalElements").asLong()).isZero();
        assertThat(ids(salons, "salonId")).isEmpty();
        assertThat(salons.path("totalElements").asLong()).isZero();
    }

    // ── fixture helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Stands in for the 60 s / 30 s TTL of the search result caches (D4 staleness bound): the
     * per-master verdict is already evicted by the booking, only the page/total caches remain.
     */
    private void expireSearchResultCaches() {
        List.of(SearchCacheNames.MASTERS_BROWSE, SearchCacheNames.MASTERS_QUERY, SearchCacheNames.MASTERS_TOTAL,
                        SearchCacheNames.SALONS_BROWSE, SearchCacheNames.SALONS_QUERY, SearchCacheNames.SALONS_TOTAL)
                .forEach(name -> cacheManager.getCache(name).clear());
    }

    private void makeUnfittable(UUID definitionId) {
        jdbcTemplate.update("UPDATE service_definitions SET base_duration_minutes = ? WHERE id = ?",
                UNFITTABLE_DURATION_MINUTES, definitionId);
    }

    private void categorise(UUID definitionId, String category) {
        jdbcTemplate.update("UPDATE service_definitions SET category = ? WHERE id = ?", category, definitionId);
    }

    private void rename(UUID definitionId, String name) {
        jdbcTemplate.update("UPDATE service_definitions SET name = ? WHERE id = ?", name, definitionId);
    }

    private static List<String> names(JsonNode node, String field) {
        List<String> names = new ArrayList<>();
        node.path(field).forEach(n -> names.add(n.asText()));
        return names;
    }

    private String slugOf(UUID definitionId) {
        return jdbcTemplate.queryForObject(
                "SELECT st.slug FROM service_definitions sd JOIN service_types st ON st.id = sd.service_type_id "
                        + "WHERE sd.id = ?",
                String.class, definitionId);
    }

    private UUID assignmentOf(UUID definitionId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, definitionId);
    }
}
