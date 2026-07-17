package com.beautica.booking.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.review.entity.Review;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} for {@link BookingRepository#findClientBookingDetails} — the
 * single-query projection backing {@code GET /bookings/me} for CLIENT (Phase 19.3).
 *
 * <p>Pins three acceptance criteria from phase-156:
 * <ol>
 *   <li>The projection populates every enriched field — master avatar/type, the (nullable)
 *       salon name, the discovery locality FK ids (salon-primary via COALESCE), the master
 *       address, and the service category.</li>
 *   <li>{@code reviewExists} is true only when a {@code Review} row references the booking;
 *       the {@code OneToOne} {@code LEFT JOIN Review} does NOT fan out rows.</li>
 *   <li>The query count is BOUNDED and independent of the number of booking rows (no N+1) —
 *       asserted via Hibernate {@link Statistics} statement count.</li>
 * </ol>
 *
 * <p>All fixture data is ASCII-only English.
 */
class ClientBookingDetailProjectionTest extends AbstractDataJpaTest {

    /**
     * The {@code users.professional_title} VARCHAR(100) cap (V110), mirrored by
     * {@code User.professionalTitle}'s {@code @Column(length = 100)} and by the
     * {@code @Size(max = 100)} on both write-path DTOs.
     */
    private static final int MAX_PROFESSIONAL_TITLE_LENGTH = 100;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private EntityManagerFactory emf;

    private User clientUser;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        clientUser = new User(
                "client-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.CLIENT,
                "Client",
                "User",
                "+380501111111"
        );
        em.persist(clientUser);
        em.flush();

        SessionFactory sessionFactory = emf.unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /**
     * Inserts a real oblast → city → district chain via native SQL so the
     * {@code fk_users_city_id} / {@code fk_users_district_id} / salon FKs (V54) are satisfied.
     * The projection only carries the FK ids (labels are resolved by the service), so the
     * names are irrelevant to the assertion — but the rows must exist. ASCII-only.
     *
     * @return {@code [cityId, districtId]}
     */
    private UUID[] persistTaxonomy() {
        UUID oblastId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        em.getEntityManager().createNativeQuery(
                "INSERT INTO oblasts (id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?)")
                .setParameter(1, oblastId).setParameter(2, randomKatotthCode())
                .setParameter(3, "Oblast").setParameter(4, "Oblast").executeUpdate();
        em.getEntityManager().createNativeQuery(
                "INSERT INTO cities (id, oblast_id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?, ?)")
                .setParameter(1, cityId).setParameter(2, oblastId).setParameter(3, randomKatotthCode())
                .setParameter(4, "City").setParameter(5, "City").executeUpdate();
        em.getEntityManager().createNativeQuery(
                "INSERT INTO city_districts (id, city_id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?, ?)")
                .setParameter(1, districtId).setParameter(2, cityId).setParameter(3, randomKatotthCode())
                .setParameter(4, "District").setParameter(5, "District").executeUpdate();
        return new UUID[]{cityId, districtId};
    }

    /**
     * V102 added {@code chk_*_katotth_code_format} (^UA[0-9]{17}$) on oblasts/cities/
     * city_districts — this generates a conforming-but-fake code per row (random 17-digit
     * suffix), distinct from any real V53-seeded code and unique enough across rows/tests.
     */
    private static String randomKatotthCode() {
        long n = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 100_000_000_000_000_000L;
        return "UA" + String.format("%017d", n);
    }

    private User persistMasterUser(UUID cityId, UUID districtId, String street, String buildingNo, String avatarUrl) {
        return persistMasterUser(Role.INDEPENDENT_MASTER, cityId, districtId, street, buildingNo, avatarUrl);
    }

    private User persistMasterUser(Role role, UUID cityId, UUID districtId,
                                   String street, String buildingNo, String avatarUrl) {
        User u = new User(
                "master-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                role,
                "Master",
                "Person",
                "+380502222222"
        );
        u.setCityId(cityId);
        u.setDistrictId(districtId);
        u.setStreet(street);
        u.setBuildingNo(buildingNo);
        u.setAvatarUrl(avatarUrl);
        em.persist(u);
        return u;
    }

    private Master persistMaster(User masterUser, Salon salon, MasterType type) {
        Master m = Master.builder()
                .user(masterUser)
                .salon(salon)
                .masterType(type)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(m);
        return m;
    }

    private MasterServiceAssignment persistService(Master master, OwnerType ownerType, UUID ownerId,
                                                   String serviceName, String category) {
        ServiceDefinition sd = ServiceDefinition.builder()
                .ownerType(ownerType)
                .ownerId(ownerId)
                .name(serviceName)
                .category(category)
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(persistServiceType())
                .isActive(true)
                .build();
        em.persist(sd);

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(sd)
                .isActive(true)
                .build();
        em.persist(msa);
        return msa;
    }

    private static final java.util.concurrent.atomic.AtomicInteger SORT_ORDER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(90_000);

    /**
     * Persists a CatalogCategory + ServiceType so fixture ServiceDefinitions satisfy the
     * NOT NULL service_type_id FK. sortOrder is unique per call (uq_service_categories_sort_order).
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

    private Booking persistBooking(Master master, MasterServiceAssignment msa, Salon salon,
                                   BookingStatus status, OffsetDateTime startsAt) {
        Booking b = Booking.builder()
                .client(clientUser)
                .master(master)
                .masterService(msa)
                .salon(salon)
                .status(status)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(b);
        return b;
    }

    private Review persistReview(Booking booking, Master master, Salon salon) {
        Review r = Review.builder()
                .booking(booking)
                .client(clientUser)
                .master(master)
                .salon(salon)
                .rating((short) 5)
                .comment("Great service")
                .build();
        em.persist(r);
        return r;
    }

    // ── enriched-field population (independent master, no salon) ─────────────────

    @Test
    @DisplayName("projection populates avatar, masterType, master-own locality/address, category and null salonName for an independent-master booking")
    void should_populateEnrichedFields_when_independentMasterBooking() {
        UUID[] tax = persistTaxonomy();
        UUID masterCityId = tax[0];
        UUID masterDistrictId = tax[1];
        User masterUser = persistMasterUser(
                masterCityId, masterDistrictId, "Khreschatyk", "10", "https://cdn.test/avatar.png");
        masterUser.setLocationNote("Ring the bell twice");
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Manicure", "MANICURE");
        Booking booking = persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        ClientBookingDetailProjection p = page.getContent().get(0);
        assertThat(p)
                .extracting(
                        ClientBookingDetailProjection::id,
                        ClientBookingDetailProjection::serviceName,
                        ClientBookingDetailProjection::masterAvatarUrl,
                        ClientBookingDetailProjection::masterType,
                        ClientBookingDetailProjection::salonName,
                        ClientBookingDetailProjection::discoveryCityId,
                        ClientBookingDetailProjection::discoveryDistrictId,
                        ClientBookingDetailProjection::street,
                        ClientBookingDetailProjection::buildingNo,
                        ClientBookingDetailProjection::locationNote,
                        ClientBookingDetailProjection::categoryName,
                        ClientBookingDetailProjection::reviewExists)
                .containsExactly(
                        booking.getId(),
                        "Manicure",
                        "https://cdn.test/avatar.png",
                        Role.INDEPENDENT_MASTER,
                        null,
                        masterCityId,
                        masterDistrictId,
                        "Khreschatyk",
                        "10",
                        "Ring the bell twice",
                        "MANICURE",
                        false);
    }

    // ── masterProfessionalTitle round-trip (Anti-Bug audit LOW-2) ────────────────
    //
    // masterProfessionalTitle is a straight passthrough with no conditional logic
    // (mu.professionalTitle -> ClientBookingDetailProjection.masterProfessionalTitle ->
    // BookingDetailResponse.masterProfessionalTitle) — nothing here resolves salon-vs-master
    // or applies any transform. The write-side gate (VARCHAR(100) cap, control/bidi-char
    // rejection) is pinned at the DTO layer by MasterProfileUpdateRequestValidationTest's
    // professionalTitle nested classes; what's worth pinning at THIS layer is that the
    // projection does not silently truncate or mangle a title at the DB column's exact
    // boundary length on its way through the JPQL projection into the response DTO.

    @Test
    @DisplayName("projection round-trips a max-length (100 char) masterProfessionalTitle intact — "
            + "no truncation through the JPQL projection")
    void should_notTruncateMasterProfessionalTitle_when_atMaxLength() {
        // Padded to the cap programmatically rather than hand-counted — the assertion is
        // about length preservation, not content, so the exact characters are irrelevant as
        // long as they are valid per MasterProfileUpdateRequest's professionalTitle @Pattern.
        String prefix = "Майстер-перукар вищої категорії ";
        String maxLengthTitle = prefix + "x".repeat(MAX_PROFESSIONAL_TITLE_LENGTH - prefix.length());
        assertThat(maxLengthTitle).hasSize(MAX_PROFESSIONAL_TITLE_LENGTH);

        User masterUser = persistMasterUser(
                null, null, "Khreschatyk", "10", "https://cdn.test/avatar.png");
        masterUser.setProfessionalTitle(maxLengthTitle);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Manicure", "MANICURE");
        persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).masterProfessionalTitle())
                .as("a %s-char title (the VARCHAR(100)/@Size(max=100) boundary) must survive the "
                        + "projection byte-for-byte — no truncation, no whitespace trim",
                        MAX_PROFESSIONAL_TITLE_LENGTH)
                .isEqualTo(maxLengthTitle)
                .hasSize(MAX_PROFESSIONAL_TITLE_LENGTH);
    }

    @Test
    @DisplayName("projection returns null masterProfessionalTitle when the master never set one")
    void should_returnNullMasterProfessionalTitle_when_neverSet() {
        User masterUser = persistMasterUser(
                null, null, "Khreschatyk", "10", "https://cdn.test/avatar.png");
        // professionalTitle deliberately left unset (null) — the common case.
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Manicure", "MANICURE");
        persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        assertThat(page.getContent().get(0).masterProfessionalTitle())
                .as("a master who never set a title must render as null, not an empty-string placeholder")
                .isNull();
    }

    // ── salon-primary locality (salon link wins via COALESCE) ────────────────────

    @Test
    @DisplayName("projection resolves salonName and the salon-primary locality/address for a salon-employed master")
    void should_resolveSalonLocality_when_salonEmployedMasterBooking() {
        UUID[] salonTax = persistTaxonomy();
        UUID salonCityId = salonTax[0];
        UUID salonDistrictId = salonTax[1];
        UUID[] masterOwnTax = persistTaxonomy();

        User ownerUser = new User(
                "owner-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash", Role.SALON_OWNER, "Owner", "Person", "+380503333333");
        em.persist(ownerUser);

        Salon salon = Salon.builder()
                .owner(ownerUser)
                .name("Glamour Studio")
                .cityId(salonCityId)
                .districtId(salonDistrictId)
                .street("Volodymyrska")
                .buildingNo("55")
                .locationNote("3rd floor, door code 1234")
                .isActive(true)
                .build();
        em.persist(salon);

        // Master's own user row carries DIFFERENT locality AND a DIFFERENT locationNote to
        // prove the salon link wins for both — a client must never see one provider's street
        // paired with another provider's door code.
        User masterUser = persistMasterUser(
                Role.SALON_MASTER, masterOwnTax[0], masterOwnTax[1],
                "OwnStreet", "99", "https://cdn.test/salon-master.png");
        masterUser.setLocationNote("Master's own note - must NOT surface for a salon booking");
        Master master = persistMaster(masterUser, salon, MasterType.SALON_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.SALON, salon.getId(), "Pedicure", "PEDICURE");
        persistBooking(master, msa, salon, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 2, 12, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        ClientBookingDetailProjection p = page.getContent().get(0);
        assertThat(p)
                .extracting(
                        ClientBookingDetailProjection::salonName,
                        ClientBookingDetailProjection::masterType,
                        ClientBookingDetailProjection::discoveryCityId,
                        ClientBookingDetailProjection::discoveryDistrictId,
                        ClientBookingDetailProjection::street,
                        ClientBookingDetailProjection::buildingNo,
                        ClientBookingDetailProjection::locationNote,
                        ClientBookingDetailProjection::categoryName)
                .containsExactly(
                        "Glamour Studio",
                        Role.SALON_MASTER,
                        salonCityId,
                        salonDistrictId,
                        "Volodymyrska",
                        "55",
                        "3rd floor, door code 1234",
                        "PEDICURE");
    }

    // ── salon-employed master, salon has NO note (COALESCE-fallthrough regression) ─
    //
    // HIGH security finding: `COALESCE(s.X, mu.X)` falls through to the master's own
    // column whenever the salon's column is NULL — the common case, since most salons
    // never fill in an address/note. For a salon-employed master this silently leaked
    // the master's PERSONAL street/buildingNo/locationNote (e.g. their home door code)
    // onto a SALON booking. `BookingDetailResponse.from` (the entity path) never had
    // this bug — its ternary (`salon != null ? salon.getX() : masterUser.getX()`) picks
    // the salon's value outright, even when null. The two tests below pin the fixed
    // `CASE WHEN s.id IS NOT NULL THEN s.X ELSE mu.X END` projection rule and prove it
    // now agrees with the entity path.

    @Test
    @DisplayName("projection returns NULL locality/address fields — never the master's own — "
            + "when the salon-employed master's salon has no note/address set")
    void should_returnNullFields_when_salonEmployedAndSalonFieldsAreNull() {
        User ownerUser = new User(
                "owner-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash", Role.SALON_OWNER, "Owner", "Person", "+380503333333");
        em.persist(ownerUser);

        // Salon deliberately leaves cityId/districtId/street/buildingNo/locationNote unset
        // (NULL) — the common case for a salon that never filled in its address.
        Salon salon = Salon.builder()
                .owner(ownerUser)
                .name("Bare Studio")
                .isActive(true)
                .build();
        em.persist(salon);

        // Master's own user row carries a FULL address AND a personal locationNote — this
        // must NOT surface for a salon booking, no matter how "empty" the salon's row is.
        UUID[] masterOwnTax = persistTaxonomy();
        User masterUser = persistMasterUser(
                Role.SALON_MASTER, masterOwnTax[0], masterOwnTax[1],
                "MasterHomeStreet", "42", "https://cdn.test/salon-master-2.png");
        masterUser.setLocationNote("Master's home door code - must NOT surface for a salon booking");
        Master master = persistMaster(masterUser, salon, MasterType.SALON_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.SALON, salon.getId(), "Haircut", "HAIR");
        persistBooking(master, msa, salon, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 8, 12, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        ClientBookingDetailProjection p = page.getContent().get(0);
        assertThat(p)
                .extracting(
                        ClientBookingDetailProjection::salonName,
                        ClientBookingDetailProjection::discoveryCityId,
                        ClientBookingDetailProjection::discoveryDistrictId,
                        ClientBookingDetailProjection::street,
                        ClientBookingDetailProjection::buildingNo,
                        ClientBookingDetailProjection::locationNote)
                .containsExactly(
                        "Bare Studio",
                        null,
                        null,
                        null,
                        null,
                        null);
    }

    @Test
    @DisplayName("entity path (BookingDetailResponse.from) and projection path "
            + "(findClientBookingDetails) agree on street/buildingNo/locationNote "
            + "for the same booking when the salon has no note/address")
    void should_matchEntityPathValues_when_salonEmployedAndSalonNoteIsNull() {
        User ownerUser = new User(
                "owner-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash", Role.SALON_OWNER, "Owner", "Person", "+380504444444");
        em.persist(ownerUser);

        Salon salon = Salon.builder()
                .owner(ownerUser)
                .name("Parity Studio")
                .isActive(true)
                .build();
        em.persist(salon);

        UUID[] masterOwnTax = persistTaxonomy();
        User masterUser = persistMasterUser(
                Role.SALON_MASTER, masterOwnTax[0], masterOwnTax[1],
                "AnotherHomeStreet", "7", "https://cdn.test/salon-master-3.png");
        masterUser.setLocationNote("Another personal note - must NOT surface");
        Master master = persistMaster(masterUser, salon, MasterType.SALON_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.SALON, salon.getId(), "Coloring", "HAIR");
        Booking booking = persistBooking(master, msa, salon, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 9, 12, 0, 0, 0, ZoneOffset.UTC));

        // Flush so JPA auditing populates booking.createdAt (a @PrePersist callback fired at
        // flush time) before the entity path reads it — but don't clear yet, so the graph
        // below (master/user/salon) is still the exact in-memory objects, not lazy proxies.
        em.flush();

        BookingDetailResponse entityResponse =
                BookingDetailResponse.from(booking, false, "Kyiv", "Podil");

        em.clear();

        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));
        ClientBookingDetailProjection projection = page.getContent().get(0);

        assertThat(projection.street())
                .as("street must agree between entity and projection paths")
                .isEqualTo(entityResponse.street())
                .isNull();
        assertThat(projection.buildingNo())
                .as("buildingNo must agree between entity and projection paths")
                .isEqualTo(entityResponse.buildingNo())
                .isNull();
        assertThat(projection.locationNote())
                .as("locationNote must agree between entity and projection paths "
                        + "(and must NOT leak the master's personal note)")
                .isEqualTo(entityResponse.locationNote())
                .isNull();
    }

    // ── reviewExists via LEFT JOIN Review (OneToOne — no row fan-out) ─────────────

    @Test
    @DisplayName("reviewExists is true for a reviewed booking and false for an un-reviewed one, with the OneToOne LEFT JOIN producing exactly one row per booking")
    void should_setReviewExistsAndNotFanOutRows_when_someBookingsReviewed() {
        // Locality is not asserted here — pass null FK ids to keep the fixture minimal.
        User masterUser = persistMasterUser(null, null, "Sichovykh", "3", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Haircut", "HAIR");

        Booking reviewed = persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 3, 9, 0, 0, 0, ZoneOffset.UTC));
        Booking notReviewed = persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 4, 9, 0, 0, 0, ZoneOffset.UTC));
        persistReview(reviewed, master, null);
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        // Two bookings, two rows — the OneToOne LEFT JOIN Review must not duplicate the reviewed row.
        assertThat(page.getTotalElements())
                .as("OneToOne LEFT JOIN Review must not fan out the reviewed booking into extra rows")
                .isEqualTo(2);
        // Ordered by startsAt DESC: notReviewed (Jun 4) first, reviewed (Jun 3) second.
        assertThat(page.getContent())
                .extracting(ClientBookingDetailProjection::id, ClientBookingDetailProjection::reviewExists)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(notReviewed.getId(), false),
                        org.assertj.core.groups.Tuple.tuple(reviewed.getId(), true));
    }

    // ── status filter (Phase 26.1 — widened from a scalar equality to a Collection IN) ───

    @Test
    @DisplayName("status filter returns only matching bookings; a null filter returns all")
    void should_filterByStatus_when_statusFilterSupplied() {
        User masterUser = persistMasterUser(null, null, "Lypska", "7", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Massage", "MASSAGE");
        Booking confirmed = persistBooking(master, msa, null, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 5, 9, 0, 0, 0, ZoneOffset.UTC));
        persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 6, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> filtered = bookingRepository.findClientBookingDetails(
                clientUser.getId(), java.util.Set.of(BookingStatus.CONFIRMED), PageRequest.of(0, 20));
        Page<ClientBookingDetailProjection> all = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        assertThat(filtered.getContent())
                .extracting(ClientBookingDetailProjection::id)
                .containsExactly(confirmed.getId());
        assertThat(all.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("a multi-value status set returns the union of matching bookings — proves the widened "
            + "(:statuses IS NULL OR b.status IN :statuses) idiom against a real Postgres instance (Phase 26.1)")
    void should_returnUnion_when_multipleStatusesSupplied() {
        User masterUser = persistMasterUser(null, null, "Sofiivska", "9", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Massage", "MASSAGE");
        Booking cancelled = persistBooking(master, msa, null, BookingStatus.CANCELLED,
                OffsetDateTime.of(2026, 6, 10, 9, 0, 0, 0, ZoneOffset.UTC));
        Booking declined = persistBooking(master, msa, null, BookingStatus.DECLINED,
                OffsetDateTime.of(2026, 6, 11, 9, 0, 0, 0, ZoneOffset.UTC));
        persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 12, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> union = bookingRepository.findClientBookingDetails(
                clientUser.getId(),
                java.util.EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED),
                PageRequest.of(0, 20));

        assertThat(union.getContent())
                .extracting(ClientBookingDetailProjection::id)
                .containsExactlyInAnyOrder(cancelled.getId(), declined.getId());
        assertThat(union.getTotalElements()).isEqualTo(2);
    }

    // ── bounded query count (no N+1) ─────────────────────────────────────────────

    @Test
    @DisplayName("query count is bounded and independent of the number of booking rows — no N+1 for the enriched projection")
    void should_executeBoundedQueryCount_when_pageHasManyRows() {
        User masterUser = persistMasterUser(null, null, "Antonovycha", "12", "https://cdn.test/a.png");
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Spa", "SPA");

        // Five COMPLETED bookings on distinct (non-overlapping) days; three reviewed.
        for (int day = 1; day <= 5; day++) {
            Booking b = persistBooking(master, msa, null, BookingStatus.COMPLETED,
                    OffsetDateTime.of(2026, 7, day, 9, 0, 0, 0, ZoneOffset.UTC));
            if (day <= 3) {
                persistReview(b, master, null);
            }
        }
        em.flush();
        em.clear();

        statistics.clear();
        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));
        long fiveRowQueries = statistics.getPrepareStatementCount();

        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getContent())
                .extracting(ClientBookingDetailProjection::reviewExists)
                .containsExactlyInAnyOrder(true, true, true, false, false);

        // Re-run against a freshly cleared persistence context to confirm the statement
        // count is the SAME (page + count query), independent of row count — the defining
        // property of an N+1-free query. The two unfiltered list queries (content + count)
        // dominate; if a per-row review/label/master lookup leaked in, this count would
        // scale with the five rows.
        em.clear();
        statistics.clear();
        Page<ClientBookingDetailProjection> rerun = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));
        long rerunQueries = statistics.getPrepareStatementCount();

        assertThat(rerun.getContent()).hasSize(5);
        assertThat(fiveRowQueries)
                .as("enriched projection runs a bounded number of statements (content + count), "
                        + "not one-per-row; got %s", fiveRowQueries)
                .isEqualTo(rerunQueries)
                .isLessThanOrEqualTo(2);
    }

    // ── ownership boundary ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a client never sees another client's bookings — the projection is scoped by clientId")
    void should_returnEmpty_when_queriedForADifferentClient() {
        User masterUser = persistMasterUser(null, null, "Saksahanskoho", "1", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Waxing", "WAXING");
        persistBooking(master, msa, null, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 7, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        UUID otherClientId = UUID.randomUUID();
        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                otherClientId, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
    }
}
