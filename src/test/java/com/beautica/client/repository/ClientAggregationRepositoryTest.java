package com.beautica.client.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} (real PostgreSQL via Testcontainers) for the four
 * {@link ClientAggregationRepository} SQL aggregations backing the BEAUTI PASSPORT +
 * BEAUTY TIMELINE (Phase 19.5). Seeds one client with a mix of COMPLETED and
 * non-COMPLETED bookings spanning multiple service-type categories, districts and
 * masters — plus a second client whose bookings must never bleed into the first's
 * aggregates — and pins: COMPLETED-only counting, top-N ordering (including a tie
 * broken by category name ASC), district CASE-WHEN(salon-presence, master-user) resolution
 * (salon wins outright when present, even with a null district — never falls through to the
 * master's own personal district; §Anti-Bug-fix-19.3-class), budget AVG/MIN/MAX/COUNT, and
 * most-recent-first timeline ordering with the category key/name and serviceName populated.
 * {@code findTimeline} is COMPLETED-or-elapsed-CONFIRMED only (2026-08-26 widening) — the other
 * three aggregates ({@code findTopDistricts}, {@code findTopCities}, {@code aggregateBudget})
 * stay COMPLETED-only; see {@link ClientAggregationRepository}'s class javadoc for that
 * deliberate divergence. ASCII-only seed data throughout.
 */
@DisplayName("ClientAggregationRepository — @DataJpaTest")
class ClientAggregationRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private ClientAggregationRepository repository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private EntityManagerFactory emf;

    private static final Pageable TOP_3 = PageRequest.of(0, 3);

    /**
     * Fixed "now" for {@code findTimeline}'s elapsed-{@code CONFIRMED} leg. All {@link #slot()}
     * bookings (base 2026-05-01, advancing 2h per call) and every explicit {@code older}/{@code
     * newer} literal in the {@code findTimeline} tests below sit in May 2026 — strictly before
     * this constant — so any CONFIRMED booking built from them elapses relative to it; a booking
     * that must stay NOT elapsed for a test uses an explicit startsAt after this constant instead.
     */
    private static final OffsetDateTime TIMELINE_NOW =
            OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private User client;
    private User otherClient;
    private final AtomicInteger slotCounter = new AtomicInteger();
    private List<UUID> seededDistrictIds;
    private List<UUID> seededCityIds;

    @BeforeEach
    void setUp() {
        client = persistUser(Role.CLIENT, "client");
        otherClient = persistUser(Role.CLIENT, "other-client");
        seededDistrictIds = realDistrictIds(4);
        seededCityIds = realCityIds(4);
    }

    /**
     * Pulls real {@code city_districts} ids from the Flyway-seeded taxonomy (no Cyrillic in
     * the test — ids only) so the {@code fk_users_district_id} / salon district FK holds.
     */
    @SuppressWarnings("unchecked")
    private List<UUID> realDistrictIds(int n) {
        return em.getEntityManager()
                .createNativeQuery("SELECT id FROM city_districts ORDER BY katotth_code LIMIT :n")
                .setParameter("n", n)
                .getResultList();
    }

    /**
     * City twin of {@link #realDistrictIds(int)} — real {@code cities} ids from the
     * Flyway-seeded (already occupied-territory-filtered) taxonomy, so
     * {@code fk_users_city_id} / {@code fk_salons_city_id} hold. Ids only, no names.
     */
    @SuppressWarnings("unchecked")
    private List<UUID> realCityIds(int n) {
        return em.getEntityManager()
                .createNativeQuery("SELECT id FROM cities ORDER BY katotth_code LIMIT :n")
                .setParameter("n", n)
                .getResultList();
    }

    // ── seed helpers ───────────────────────────────────────────────────────────

    private User persistUser(Role role, String tag) {
        User u = new User(
                tag + "-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                role,
                "First",
                "Last",
                null);
        em.persist(u);
        return u;
    }

    /** Independent master whose own user row carries the discovery district. */
    private Master persistIndependentMaster(UUID masterDistrictId) {
        User masterUser = persistUser(Role.INDEPENDENT_MASTER, "master");
        masterUser.setDistrictId(masterDistrictId);
        em.persist(masterUser);
        Master m = Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(m);
        return m;
    }

    /** Salon master whose salon carries the discovery district (wins via CASE WHEN salon-presence). */
    private Master persistSalonMaster(UUID salonDistrictId, UUID masterUserDistrictId) {
        User owner = persistUser(Role.SALON_OWNER, "owner");
        em.persist(owner);
        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Salon " + UUID.randomUUID())
                .districtId(salonDistrictId)
                .isActive(true)
                .build();
        em.persist(salon);
        User masterUser = persistUser(Role.SALON_MASTER, "salon-master");
        masterUser.setDistrictId(masterUserDistrictId);
        em.persist(masterUser);
        Master m = Master.builder()
                .user(masterUser)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(m);
        return m;
    }

    /** City twin of {@link #persistIndependentMaster}: no salon, so the user row carries the city. */
    private Master persistIndependentMasterWithCity(UUID masterCityId) {
        User masterUser = persistUser(Role.INDEPENDENT_MASTER, "master");
        masterUser.setCityId(masterCityId);
        em.persist(masterUser);
        Master m = Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(m);
        return m;
    }

    /** City twin of {@link #persistSalonMaster}: the salon carries the city (CASE WHEN salon-presence). */
    private Master persistSalonMasterWithCity(UUID salonCityId, UUID masterUserCityId) {
        User owner = persistUser(Role.SALON_OWNER, "owner");
        em.persist(owner);
        Salon salon = Salon.builder()
                .owner(owner)
                .name("Salon " + UUID.randomUUID())
                .cityId(salonCityId)
                .isActive(true)
                .build();
        em.persist(salon);
        User masterUser = persistUser(Role.SALON_MASTER, "salon-master");
        masterUser.setCityId(masterUserCityId);
        em.persist(masterUser);
        Master m = Master.builder()
                .user(masterUser)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(m);
        return m;
    }

    private MasterServiceAssignment persistService(Master master, String name, String category, String price) {
        ServiceDefinition def = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name(name)
                .category(category)
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal(price))
                .serviceType(persistServiceType())
                .isActive(true)
                .build();
        em.persist(def);
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(def)
                .isActive(true)
                .build();
        em.persist(msa);
        return msa;
    }

    private static final AtomicInteger SORT_ORDER_SEQ = new AtomicInteger(90_000);

    /**
     * Persists a CatalogCategory + ServiceType so fixture ServiceDefinitions satisfy the
     * NOT NULL service_type_id FK. sortOrder is unique per call (uq_service_categories_sort_order) —
     * persistService (and therefore this helper) is invoked multiple times per test in this
     * file (one master service per persistService call), so a fixed sortOrder would collide.
     */
    private ServiceType persistServiceType() {
        CatalogCategory category = CatalogCategory.builder()
                .nameUk("Нігті")
                .nameEn("Nails")
                .sortOrder(SORT_ORDER_SEQ.getAndIncrement())
                .build();
        em.persist(category);

        ServiceType serviceType = ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build();
        em.persist(serviceType);
        return serviceType;
    }

    /**
     * Persists a booking on a unique, ever-advancing time slot per master so the DB
     * exclusion constraint (no same-master overlap) never trips, while keeping the
     * relative ordering meaningful for the timeline-DESC assertion.
     */
    private Booking persistBooking(User bookingClient, Master master, MasterServiceAssignment service,
                                   BookingStatus status, String price, OffsetDateTime startsAt) {
        Booking b = Booking.builder()
                .client(bookingClient)
                .master(master)
                .masterService(service)
                .salon(master.getSalon())
                .status(status)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .priceAtBooking(new BigDecimal(price))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(b);
        return b;
    }

    private OffsetDateTime slot() {
        // Each call advances 2 hours from a fixed base — unique non-overlapping slots.
        return OffsetDateTime.of(2026, 5, 1, 8, 0, 0, 0, ZoneOffset.UTC)
                .plusHours(2L * slotCounter.getAndIncrement());
    }

    // ── findTopDistricts ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("findTopDistricts — ranks discovery districts by COMPLETED count desc, salon district "
            + "wins via CASE WHEN salon-presence, master-user district used only when no salon")
    void should_rankDistricts_when_completedBookingsExist() {
        UUID salonDistrict = seededDistrictIds.get(0);
        UUID masterUserDistrict = seededDistrictIds.get(1);
        UUID indepDistrict = seededDistrictIds.get(2);

        // Salon master: salon district wins over the master-user district (COALESCE).
        Master salonMaster = persistSalonMaster(salonDistrict, masterUserDistrict);
        MasterServiceAssignment salonService = persistService(salonMaster, "Salon Manicure", "MANICURE", "500");
        // Independent master: no salon, so its own user district is used.
        Master indepMaster = persistIndependentMaster(indepDistrict);
        MasterServiceAssignment indepService = persistService(indepMaster, "Indep Manicure", "MANICURE", "300");

        // salonDistrict x3, indepDistrict x1.
        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, indepMaster, indepService, BookingStatus.COMPLETED, "300", slot());
        // Non-COMPLETED noise.
        persistBooking(client, indepMaster, indepService, BookingStatus.CANCELLED, "300", slot());
        em.flush();
        em.clear();

        List<DistrictCount> top = repository.findTopDistricts(client.getId(), TOP_3);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).districtId()).as("most-visited district is the salon district").isEqualTo(salonDistrict);
        assertThat(top.get(0).count()).isEqualTo(3L);
        assertThat(top.get(1).districtId()).as("master-user district when no salon").isEqualTo(indepDistrict);
        assertThat(top.get(1).count()).isEqualTo(1L);
        // The master-user district behind the salon must NOT appear — CASE WHEN picked the salon.
        assertThat(top).extracting(DistrictCount::districtId).doesNotContain(masterUserDistrict);
    }

    @Test
    @DisplayName("findTopDistricts — excludes another client's COMPLETED bookings (cross-client isolation; "
            + "regression test authored during the Phase 250 audit — this query never had one)")
    void should_excludeOtherClient_when_rankingDistricts() {
        UUID clientDistrict = seededDistrictIds.get(0);
        UUID otherClientDistrict = seededDistrictIds.get(1);

        Master master = persistIndependentMaster(clientDistrict);
        MasterServiceAssignment service = persistService(master, "Manicure", "MANICURE", "300");
        Master otherMaster = persistIndependentMaster(otherClientDistrict);
        MasterServiceAssignment otherService = persistService(otherMaster, "Pedicure", "PEDICURE", "350");

        persistBooking(client, master, service, BookingStatus.COMPLETED, "300", slot());
        // otherClient has many COMPLETED bookings in a different district — must not bleed in.
        persistBooking(otherClient, otherMaster, otherService, BookingStatus.COMPLETED, "350", slot());
        persistBooking(otherClient, otherMaster, otherService, BookingStatus.COMPLETED, "350", slot());
        em.flush();
        em.clear();

        List<DistrictCount> top = repository.findTopDistricts(client.getId(), TOP_3);

        assertThat(top)
                .as("only the requesting client's own district row surfaces, despite otherClient "
                        + "having a higher COMPLETED count in a different district")
                .hasSize(1);
        assertThat(top.get(0).districtId()).isEqualTo(clientDistrict);
    }

    @Test
    @DisplayName("findTopDistricts — LOW fix: salon presence wins outright even when the salon's own "
            + "district is NULL (legacy/districtless-city salon) — must NOT fall through to the "
            + "salon-employed master's personal user-row district (mirrors the 19.3 booking-detail rule; "
            + "CASE WHEN, not COALESCE)")
    void should_excludeMasterUserDistrict_when_salonExistsButHasNullDistrict() {
        UUID masterUserDistrict = seededDistrictIds.get(1);
        UUID indepDistrict = seededDistrictIds.get(2);

        // Salon has NO district (e.g. a legacy salon predating the Phase 10.3 locality
        // columns, or a districtless-city salon) — its OWN row still exists (LEFT JOIN
        // matches), it just carries districtId = null.
        Master salonMaster = persistSalonMaster(null, masterUserDistrict);
        MasterServiceAssignment salonService = persistService(salonMaster, "Salon Manicure", "MANICURE", "500");
        // Control: an independent master with a real district still counts normally.
        Master indepMaster = persistIndependentMaster(indepDistrict);
        MasterServiceAssignment indepService = persistService(indepMaster, "Indep Manicure", "MANICURE", "300");

        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, indepMaster, indepService, BookingStatus.COMPLETED, "300", slot());
        em.flush();
        em.clear();

        List<DistrictCount> top = repository.findTopDistricts(client.getId(), TOP_3);

        assertThat(top)
                .as("the districtless-salon booking is dropped (null resolved district), never "
                        + "attributed to the master's own personal district — the independent "
                        + "master's booking is the only surviving row")
                .hasSize(1);
        assertThat(top.get(0).districtId()).isEqualTo(indepDistrict);
        assertThat(top).extracting(DistrictCount::districtId).doesNotContain(masterUserDistrict);
    }

    // ── findTopCities ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findTopCities — ranks discovery cities by COMPLETED count desc; non-COMPLETED and "
            + "another client's bookings excluded")
    void should_rankByCompletedCount_when_multipleCities() {
        UUID salonCity = seededCityIds.get(0);
        UUID indepCity = seededCityIds.get(1);

        Master salonMaster = persistSalonMasterWithCity(salonCity, seededCityIds.get(2));
        MasterServiceAssignment salonService = persistService(salonMaster, "Salon Manicure", "MANICURE", "500");
        Master indepMaster = persistIndependentMasterWithCity(indepCity);
        MasterServiceAssignment indepService = persistService(indepMaster, "Indep Manicure", "MANICURE", "300");

        // salonCity x3, indepCity x1.
        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, indepMaster, indepService, BookingStatus.COMPLETED, "300", slot());
        // Noise: non-COMPLETED, and another client's COMPLETED booking.
        persistBooking(client, indepMaster, indepService, BookingStatus.CANCELLED, "300", slot());
        persistBooking(otherClient, indepMaster, indepService, BookingStatus.COMPLETED, "300", slot());
        em.flush();
        em.clear();

        List<CityCount> top = repository.findTopCities(client.getId(), TOP_3);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).cityId()).as("most-visited city is the salon city").isEqualTo(salonCity);
        assertThat(top.get(0).count()).isEqualTo(3L);
        assertThat(top.get(1).cityId()).as("master-user city when no salon").isEqualTo(indepCity);
        assertThat(top.get(1).count()).isEqualTo(1L);
    }

    // REMOVED (V150, "a salon must always have a city"): should_preferSalonCity_when_master
    // UserCityIsStale used to prove CASE WHEN (salon presence), not COALESCE (fall through on a
    // null value), by persisting a salon with city_id = NULL. That premise is now physically
    // unreachable — salons.city_id is a DB-level NOT NULL as of V150, so
    // persistSalonMasterWithCity(null, ...) would throw a DataIntegrityViolationException on
    // em.flush() instead of exercising the query.
    //
    // CORRECTION (QA review, backend-qa): an earlier version of this comment claimed the
    // CASE-WHEN-on-presence semantic was "still covered by the sibling above"
    // (should_rankByCompletedCount_when_multipleCities). That is not accurate as reasoning: the
    // sibling only exercises s.cityId non-null, and whenever s.cityId is non-null, CASE WHEN
    // s.id IS NOT NULL THEN s.cityId ELSE mu.cityId END and COALESCE(s.cityId, mu.cityId)
    // produce the IDENTICAL result (both simply return s.cityId), so that test would pass
    // unchanged even if findTopCities were rewritten to use COALESCE — it never distinguished
    // the two formulas. The real reason no coverage gap remains: because salons.city_id can
    // never be null again, the ONLY state where CASE WHEN and COALESCE could ever diverge for
    // this column (s.id IS NOT NULL but s.cityId IS NULL) is now permanently unreachable, so the
    // two expressions are provably equivalent for findTopCities going forward — there is
    // nothing left for a test to distinguish. district_id stays genuinely nullable (out of
    // scope for V150), so CASE WHEN vs. COALESCE remains a live, testable distinction there, and
    // the equivalent findTopDistricts "districtless salon" test above this one is untouched and
    // still valid.

    @Test
    @DisplayName("findTopCities — a booking whose master has no salon and no user city is excluded")
    void should_excludeNullCity_when_neitherSalonNorUserHasOne() {
        Master cityless = persistIndependentMasterWithCity(null);
        MasterServiceAssignment service = persistService(cityless, "Manicure", "MANICURE", "300");

        persistBooking(client, cityless, service, BookingStatus.COMPLETED, "300", slot());
        em.flush();
        em.clear();

        List<CityCount> top = repository.findTopCities(client.getId(), TOP_3);

        assertThat(top).as("no resolvable city → no row at all").isEmpty();
    }

    @Test
    @DisplayName("findTopCities — bounded by the Pageable: a size-3 page returns the top 3 of 4 cities, "
            + "cut in SQL not in memory")
    void should_limitToThree_when_pageableIsTopThree() {
        // Four distinct cities with strictly descending COMPLETED counts 4/3/2/1.
        int[] counts = {4, 3, 2, 1};
        for (int i = 0; i < counts.length; i++) {
            Master master = persistIndependentMasterWithCity(seededCityIds.get(i));
            MasterServiceAssignment service = persistService(master, "Manicure " + i, "MANICURE", "300");
            for (int n = 0; n < counts[i]; n++) {
                persistBooking(client, master, service, BookingStatus.COMPLETED, "300", slot());
            }
        }
        em.flush();
        em.clear();

        List<CityCount> top = repository.findTopCities(client.getId(), TOP_3);

        assertThat(top).as("SQL LIMIT 3 — the 4th city never comes back").hasSize(3);
        assertThat(top).extracting(CityCount::count).containsExactly(4L, 3L, 2L);
        assertThat(top).extracting(CityCount::cityId)
                .containsExactly(seededCityIds.get(0), seededCityIds.get(1), seededCityIds.get(2));
    }

    // ── aggregateBudget ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("aggregateBudget — AVG/MIN/MAX/COUNT over COMPLETED only; non-COMPLETED excluded")
    void should_aggregateBudget_when_completedBookingsExist() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment service = persistService(master, "Manicure", "MANICURE", "300");

        persistBooking(client, master, service, BookingStatus.COMPLETED, "200.00", slot());
        persistBooking(client, master, service, BookingStatus.COMPLETED, "400.00", slot());
        persistBooking(client, master, service, BookingStatus.COMPLETED, "600.00", slot());
        // Excluded — a cheap CONFIRMED booking must not drag MIN/AVG down.
        persistBooking(client, master, service, BookingStatus.CONFIRMED, "1.00", slot());
        em.flush();
        em.clear();

        BudgetAggregate budget = repository.aggregateBudget(client.getId());

        assertThat(budget.total()).as("COMPLETED count").isEqualTo(3L);
        assertThat(budget.avg()).as("(200+400+600)/3").isEqualByComparingTo("400.00");
        assertThat(budget.min()).as("min").isEqualByComparingTo("200.00");
        assertThat(budget.max()).as("max").isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("aggregateBudget — excludes another client's COMPLETED bookings (cross-client isolation; "
            + "regression test authored during the Phase 250 audit — this query never had one)")
    void should_excludeOtherClient_when_aggregatingBudget() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment service = persistService(master, "Manicure", "MANICURE", "300");

        persistBooking(client, master, service, BookingStatus.COMPLETED, "300.00", slot());
        // otherClient's much larger COMPLETED spend must not drag this client's AVG/MIN/MAX/COUNT.
        persistBooking(otherClient, master, service, BookingStatus.COMPLETED, "5000.00", slot());
        persistBooking(otherClient, master, service, BookingStatus.COMPLETED, "9000.00", slot());
        em.flush();
        em.clear();

        BudgetAggregate budget = repository.aggregateBudget(client.getId());

        assertThat(budget.total()).as("only this client's COMPLETED count").isEqualTo(1L);
        assertThat(budget.avg()).as("unaffected by otherClient's spend").isEqualByComparingTo("300.00");
        assertThat(budget.min()).isEqualByComparingTo("300.00");
        assertThat(budget.max()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("aggregateBudget — empty-set aggregate: total 0, null amounts when no COMPLETED bookings")
    void should_returnEmptyAggregate_when_noCompletedBookings() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment service = persistService(master, "Manicure", "MANICURE", "300");
        // Only a non-COMPLETED booking exists.
        persistBooking(client, master, service, BookingStatus.CONFIRMED, "300", slot());
        em.flush();
        em.clear();

        BudgetAggregate budget = repository.aggregateBudget(client.getId());

        assertThat(budget.total()).isZero();
        assertThat(budget.avg()).isNull();
        assertThat(budget.min()).isNull();
        assertThat(budget.max()).isNull();
    }

    // ── findTimeline ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findTimeline — most-recent-first; COMPLETED only; NOT-yet-elapsed CONFIRMED excluded; "
            + "category, serviceName, masterId populated; cross-client isolation")
    void should_returnTimelineDesc_when_completedBookingsExist() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");
        MasterServiceAssignment pedicure = persistService(master, "Spa Pedicure", "PEDICURE", "450");

        OffsetDateTime older = OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime newer = OffsetDateTime.of(2026, 5, 10, 9, 0, 0, 0, ZoneOffset.UTC);
        // Strictly after TIMELINE_NOW, so this CONFIRMED booking has NOT elapsed — it must stay
        // excluded even under the widened predicate (still "upcoming", not "awaiting closure").
        OffsetDateTime notYetElapsed = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        Booking olderBooking = persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", older);
        Booking newerBooking = persistBooking(client, master, pedicure, BookingStatus.COMPLETED, "450", newer);
        // Excluded: CONFIRMED but not yet elapsed, and another client's COMPLETED booking.
        persistBooking(client, master, manicure, BookingStatus.CONFIRMED, "300", notYetElapsed);
        persistBooking(otherClient, master, manicure, BookingStatus.COMPLETED, "300", slot());
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).as("only this client's COMPLETED bookings").isEqualTo(2L);
        List<TimelineItemProjection> items = page.getContent();
        assertThat(items)
                .as("most-recent-first ordering")
                .extracting(TimelineItemProjection::bookingId)
                .containsExactly(newerBooking.getId(), olderBooking.getId());

        TimelineItemProjection first = items.get(0);
        assertThat(first.category()).isEqualTo("PEDICURE");
        assertThat(first.serviceName()).isEqualTo("Spa Pedicure");
        assertThat(first.masterId()).isEqualTo(master.getId());
        assertThat(first.startsAt().toInstant()).isEqualTo(newer.toInstant());
    }

    @Test
    @DisplayName("findTimeline — elapsed CONFIRMED booking (ends_at < now) is included, unclosed or not "
            + "(2026-08-26 widening) — the master never marking it COMPLETED must not hide it forever")
    void should_includeElapsedConfirmedBooking_when_findingTimeline() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");

        OffsetDateTime elapsedStart = OffsetDateTime.of(2026, 5, 20, 9, 0, 0, 0, ZoneOffset.UTC);
        Booking elapsedConfirmed =
                persistBooking(client, master, manicure, BookingStatus.CONFIRMED, "300", elapsedStart);
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1L);
        assertThat(page.getContent())
                .extracting(TimelineItemProjection::bookingId)
                .containsExactly(elapsedConfirmed.getId());
    }

    @Test
    @DisplayName("findTimeline — NOT_COMPLETED is excluded even when elapsed (locked product decision: "
            + "a no-show is not a beauty-history entry, unlike the PAST partition which DOES include it)")
    void should_excludeNotCompletedBooking_when_findingTimeline() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");

        OffsetDateTime elapsedStart = OffsetDateTime.of(2026, 5, 20, 9, 0, 0, 0, ZoneOffset.UTC);
        persistBooking(client, master, manicure, BookingStatus.NOT_COMPLETED, "300", elapsedStart);
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findTimeline — ends_at == now is NOT included (half-open, strict <, matches "
            + "BookingClosureRule/BookingSpecifications#partition's boundary exactly)")
    void should_excludeConfirmedBooking_when_endsAtEqualsNow() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");

        // startsAt + 1h (persistBooking's fixed duration) == TIMELINE_NOW exactly.
        OffsetDateTime startsAtBoundary = TIMELINE_NOW.minusHours(1);
        persistBooking(client, master, manicure, BookingStatus.CONFIRMED, "300", startsAtBoundary);
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findTimeline — cross-client isolation on the elapsed-CONFIRMED leg (security LOW "
            + "regression test): otherClient's elapsed CONFIRMED booking never leaks into client's "
            + "timeline. This pins operator precedence — `client.id = :clientId AND (status = COMPLETED "
            + "OR (status = CONFIRMED AND endsAt < :now))` — against the mis-parenthesised "
            + "`client.id = :clientId AND status = COMPLETED OR (status = CONFIRMED AND endsAt < :now)`, "
            + "which drops the clientId scope from the elapsed-CONFIRMED disjunct entirely and would leak "
            + "EVERY client's elapsed-CONFIRMED bookings into EVERY other client's timeline. Mutation-proven "
            + "(see QA audit): moving the parens turns this test red.")
    void should_excludeOtherClientsElapsedConfirmed_when_gettingTimeline() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment service = persistService(master, "Classic Manicure", "MANICURE", "300");

        OffsetDateTime elapsedStart = OffsetDateTime.of(2026, 5, 20, 9, 0, 0, 0, ZoneOffset.UTC);
        // otherClient's elapsed CONFIRMED booking — the newly-widened state a mis-parenthesised
        // predicate leaks first, since it is the disjunct without an explicit status = COMPLETED guard.
        persistBooking(otherClient, master, service, BookingStatus.CONFIRMED, "300", elapsedStart);
        // Control: `client` (the requesting principal) has NOTHING — the assertion below must see an
        // unconditionally empty page, not merely "fewer rows than otherClient".
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));

        assertThat(page.getTotalElements())
                .as("otherClient's elapsed CONFIRMED booking must not appear in client's timeline — "
                        + "an unscoped OR disjunct would leak it regardless of the :clientId parameter")
                .isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findTimeline — empty page when the client has no COMPLETED/elapsed-CONFIRMED bookings")
    void should_returnEmptyTimeline_when_noCompletedBookings() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment service = persistService(master, "Manicure", "MANICURE", "300");
        persistBooking(client, master, service, BookingStatus.DECLINED, "300", slot());
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findTimeline — countQuery agrees with the content query across a MIXED "
            + "COMPLETED + elapsed-CONFIRMED set: paging with a page size smaller than the total row "
            + "count exercises totalPages/hasNext/the last partial page — if the content and countQuery "
            + "predicate strings in the @Query annotation ever drifted apart (e.g. one widened and the "
            + "other wasn't), the total here would disagree with what the content query can actually "
            + "return and this test would catch it; a single page-size-20 test (as the other findTimeline "
            + "tests use) cannot, because both queries would still report the same single-page shape")
    void should_agreeCountAndContent_when_pagingMixedCompletedAndElapsedConfirmed() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");
        MasterServiceAssignment pedicure = persistService(master, "Spa Pedicure", "PEDICURE", "450");

        // 3 COMPLETED + 2 elapsed CONFIRMED = 5 rows, all strictly before TIMELINE_NOW.
        OffsetDateTime base = OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", base);
        persistBooking(client, master, pedicure, BookingStatus.CONFIRMED, "450", base.plusDays(1));
        persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", base.plusDays(2));
        persistBooking(client, master, pedicure, BookingStatus.CONFIRMED, "450", base.plusDays(3));
        persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", base.plusDays(4));
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page0 =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 2));

        assertThat(page0.getTotalElements())
                .as("countQuery must count all 5 COMPLETED+elapsed-CONFIRMED rows, matching the "
                        + "content predicate exactly")
                .isEqualTo(5L);
        assertThat(page0.getTotalPages()).as("5 rows at page size 2 -> 3 pages").isEqualTo(3);
        assertThat(page0.getContent()).hasSize(2);
        assertThat(page0.hasNext()).isTrue();

        Page<TimelineItemProjection> lastPage =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(2, 2));

        assertThat(lastPage.getContent())
                .as("the last (partial) page holds exactly the 5th row — a countQuery/content "
                        + "predicate mismatch would make this page empty (over-counted) or overflow "
                        + "(under-counted)")
                .hasSize(1);
        assertThat(lastPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findTimeline — most-recent-first ORDER BY holds across INTERLEAVED COMPLETED and "
            + "elapsed-CONFIRMED rows, not just within one status — the widening merges two statuses "
            + "into one ordered list and this pins that the merge is a single predicate, not a "
            + "status-grouped shape")
    void should_orderDescInterleaved_when_completedAndElapsedConfirmedMixed() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");
        MasterServiceAssignment pedicure = persistService(master, "Spa Pedicure", "PEDICURE", "450");

        OffsetDateTime t1 = OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime t2 = t1.plusDays(1);
        OffsetDateTime t3 = t1.plusDays(2);
        OffsetDateTime t4 = t1.plusDays(3);
        // Oldest to newest, status alternating: CONFIRMED(elapsed), COMPLETED, CONFIRMED(elapsed),
        // COMPLETED — so a correct DESC order must interleave statuses, not group them.
        Booking b1 = persistBooking(client, master, pedicure, BookingStatus.CONFIRMED, "450", t1);
        Booking b2 = persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", t2);
        Booking b3 = persistBooking(client, master, pedicure, BookingStatus.CONFIRMED, "450", t3);
        Booking b4 = persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", t4);
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .as("DESC by startsAt regardless of status — b4 (COMPLETED, newest) then b3 "
                        + "(CONFIRMED-elapsed), b2 (COMPLETED), b1 (CONFIRMED-elapsed, oldest)")
                .extracting(TimelineItemProjection::bookingId)
                .containsExactly(b4.getId(), b3.getId(), b2.getId(), b1.getId());
    }

    // ── findTimeline — no N+1 (bounded statement count) ──────────────────────────

    @Test
    @DisplayName("findTimeline — bounded statement count (content + count), independent of the number of rows; "
            + "no per-row hydration")
    void should_runBoundedQuery_when_findingTimeline() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");
        MasterServiceAssignment pedicure = persistService(master, "Spa Pedicure", "PEDICURE", "450");

        // N >= 3 COMPLETED bookings on unique, non-overlapping slots so the timeline
        // projection has many rows to (potentially) hydrate per-row.
        int n = 6;
        for (int i = 0; i < n; i++) {
            MasterServiceAssignment service = (i % 2 == 0) ? manicure : pedicure;
            persistBooking(client, master, service, BookingStatus.COMPLETED, "300", slot());
        }
        em.flush();
        em.clear();

        SessionFactory sessionFactory = emf.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Page<TimelineItemProjection> page =
                repository.findTimeline(client.getId(), TIMELINE_NOW, PageRequest.of(0, 20));
        long statementCount = statistics.getPrepareStatementCount();

        assertThat(page.getContent()).hasSize(n);
        // A single bounded SELECT for the content plus the COUNT for the page total —
        // never one statement per row. If a per-row master/service/category hydration
        // leaked into the projection, this count would scale with the six rows.
        assertThat(statementCount)
                .as("findTimeline must run a bounded statement count (content + count), "
                        + "not one-per-row; got %s for %s rows", statementCount, n)
                .isLessThanOrEqualTo(2);
    }
}
