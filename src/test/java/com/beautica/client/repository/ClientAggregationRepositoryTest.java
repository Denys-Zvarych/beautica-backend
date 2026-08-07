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
 * ASCII-only seed data throughout.
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

    // ── findTopServiceTypes ──────────────────────────────────────────────────────

    @Test
    @DisplayName("findTopServiceTypes — ranks categories by COMPLETED count desc, tie broken by name asc, "
            + "non-COMPLETED excluded, null category excluded")
    void should_rankServiceTypes_when_completedBookingsExist() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Manicure", "MANICURE", "300");
        MasterServiceAssignment pedicure = persistService(master, "Pedicure", "PEDICURE", "350");
        MasterServiceAssignment haircut = persistService(master, "Haircut", "HAIRCUT", "400");
        MasterServiceAssignment uncategorized = persistService(master, "Mystery", null, "100");

        // MANICURE x3, then a PEDICURE/HAIRCUT tie at 2 each → name asc: HAIRCUT before PEDICURE.
        persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", slot());
        persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", slot());
        persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", slot());
        persistBooking(client, master, pedicure, BookingStatus.COMPLETED, "350", slot());
        persistBooking(client, master, pedicure, BookingStatus.COMPLETED, "350", slot());
        persistBooking(client, master, haircut, BookingStatus.COMPLETED, "400", slot());
        persistBooking(client, master, haircut, BookingStatus.COMPLETED, "400", slot());
        // Noise: non-COMPLETED and null-category never count.
        persistBooking(client, master, pedicure, BookingStatus.CONFIRMED, "350", slot());
        persistBooking(client, master, uncategorized, BookingStatus.COMPLETED, "100", slot());
        em.flush();
        em.clear();

        List<String> top = repository.findTopServiceTypes(client.getId(), TOP_3);

        assertThat(top)
                .as("MANICURE(3) > HAIRCUT(2) = PEDICURE(2) with HAIRCUT first by name asc")
                .containsExactly("MANICURE", "HAIRCUT", "PEDICURE");
    }

    @Test
    @DisplayName("findTopServiceTypes — excludes another client's COMPLETED bookings")
    void should_excludeOtherClient_when_rankingServiceTypes() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Manicure", "MANICURE", "300");
        MasterServiceAssignment pedicure = persistService(master, "Pedicure", "PEDICURE", "350");

        persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", slot());
        // otherClient has many PEDICUREs — must not influence client's ranking.
        persistBooking(otherClient, master, pedicure, BookingStatus.COMPLETED, "350", slot());
        persistBooking(otherClient, master, pedicure, BookingStatus.COMPLETED, "350", slot());
        em.flush();
        em.clear();

        List<String> top = repository.findTopServiceTypes(client.getId(), TOP_3);

        assertThat(top).containsExactly("MANICURE");
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

    @Test
    @DisplayName("findTopCities — salon presence wins outright even when the salon's own city is NULL: "
            + "must NOT fall through to the salon master's (possibly stale) personal user-row city "
            + "— CASE WHEN, not COALESCE")
    void should_preferSalonCity_when_masterUserCityIsStale() {
        UUID staleMasterUserCity = seededCityIds.get(1);
        UUID indepCity = seededCityIds.get(2);

        // The salon row exists (LEFT JOIN matches) but carries city_id = NULL, while the
        // salon-employed master's own user row still mirrors a city from salon-creation time.
        Master salonMaster = persistSalonMasterWithCity(null, staleMasterUserCity);
        MasterServiceAssignment salonService = persistService(salonMaster, "Salon Manicure", "MANICURE", "500");
        // Control: an independent master with a real city still counts normally.
        Master indepMaster = persistIndependentMasterWithCity(indepCity);
        MasterServiceAssignment indepService = persistService(indepMaster, "Indep Manicure", "MANICURE", "300");

        persistBooking(client, salonMaster, salonService, BookingStatus.COMPLETED, "500", slot());
        persistBooking(client, indepMaster, indepService, BookingStatus.COMPLETED, "300", slot());
        em.flush();
        em.clear();

        List<CityCount> top = repository.findTopCities(client.getId(), TOP_3);

        assertThat(top)
                .as("the cityless-salon booking is dropped (null resolved city), never attributed to "
                        + "the master's own stale personal city")
                .hasSize(1);
        assertThat(top.get(0).cityId()).isEqualTo(indepCity);
        assertThat(top).extracting(CityCount::cityId).doesNotContain(staleMasterUserCity);
    }

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
    @DisplayName("findTimeline — most-recent-first; COMPLETED only; category, serviceName, masterId populated; "
            + "cross-client isolation")
    void should_returnTimelineDesc_when_completedBookingsExist() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment manicure = persistService(master, "Classic Manicure", "MANICURE", "300");
        MasterServiceAssignment pedicure = persistService(master, "Spa Pedicure", "PEDICURE", "450");

        OffsetDateTime older = OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime newer = OffsetDateTime.of(2026, 5, 10, 9, 0, 0, 0, ZoneOffset.UTC);
        Booking olderBooking = persistBooking(client, master, manicure, BookingStatus.COMPLETED, "300", older);
        Booking newerBooking = persistBooking(client, master, pedicure, BookingStatus.COMPLETED, "450", newer);
        // Excluded: not COMPLETED, and another client's COMPLETED booking.
        persistBooking(client, master, manicure, BookingStatus.CONFIRMED, "300", slot());
        persistBooking(otherClient, master, manicure, BookingStatus.COMPLETED, "300", slot());
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page = repository.findTimeline(client.getId(), PageRequest.of(0, 20));

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
    @DisplayName("findTimeline — empty page when the client has no COMPLETED bookings")
    void should_returnEmptyTimeline_when_noCompletedBookings() {
        Master master = persistIndependentMaster(seededDistrictIds.get(0));
        MasterServiceAssignment service = persistService(master, "Manicure", "MANICURE", "300");
        persistBooking(client, master, service, BookingStatus.DECLINED, "300", slot());
        em.flush();
        em.clear();

        Page<TimelineItemProjection> page = repository.findTimeline(client.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
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

        Page<TimelineItemProjection> page = repository.findTimeline(client.getId(), PageRequest.of(0, 20));
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
