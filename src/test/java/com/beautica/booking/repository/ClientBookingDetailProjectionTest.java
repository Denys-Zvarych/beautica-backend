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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} for the CLIENT projection backing {@code GET /bookings/me} (Phase 19.3):
 * {@link BookingRepository#findIdsByClientIdFiltered} (the sargable, sentinel-free ID page) +
 * {@link BookingRepository#hydrateClientBookingDetails} (the PII-sensitive {@code IN :ids}
 * projection hydrate) — composed by the {@link #findClientBookingDetails} helper below exactly
 * as {@code BookingService#listClientBookings} composes them in production (Phase 26.7.1 split
 * what used to be a single {@code findClientBookingDetails} query into these two).
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

    // ── Phase 26.7.1 test-local composition of the two-query pattern ─────────────
    /**
     * {@code findClientBookingDetails} was split by Phase 26.7.1 into a sargable, sentinel-free
     * {@code BookingRepository#findIdsByClientIdFiltered} ID page (built dynamically via
     * {@link com.beautica.booking.repository.BookingSpecifications#clientIdEquals}) and a
     * verbatim {@code WHERE b.id IN :ids} projection hydrate,
     * {@code BookingRepository#hydrateClientBookingDetails} — the PII-sensitive
     * {@code SELECT new ClientBookingDetailProjection(…)} block, including all five salon-
     * precedence {@code CASE WHEN} expressions, moved character-for-character. This helper
     * composes the two exactly like {@code BookingService#listClientBookings} (id page → hydrate
     * → re-impose the id page's order onto the {@code IN}-hydrated rows, since {@code IN} does not
     * guarantee row order), so every existing assertion below — including the PII/precedence ones
     * — keeps exercising the identical end-to-end behaviour production code takes, without
     * duplicating the re-order logic in every test method.
     */
    private Page<ClientBookingDetailProjection> findClientBookingDetails(
            UUID clientId, Collection<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Collection<UUID> serviceIds, Pageable pageable) {
        Page<UUID> idPage = bookingRepository.findIdsByClientIdFiltered(
                clientId, statuses, from, toExclusive, serviceIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
        }

        List<ClientBookingDetailProjection> hydrated =
                bookingRepository.hydrateClientBookingDetails(idPage.getContent());
        Map<UUID, ClientBookingDetailProjection> byId = hydrated.stream()
                .collect(Collectors.toMap(ClientBookingDetailProjection::id, Function.identity()));
        List<ClientBookingDetailProjection> ordered = idPage.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
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

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

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

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

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

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

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

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

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

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

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

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));
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

    // ── salon-employed master, salon address PARTIALLY populated (mixed CASE-null / ─
    // ── CASE-salon within the SAME row — Phase 26.7.2 PII-precedence re-audit) ──────
    //
    // The two tests above cover the "salon has NO address at all" (every field null) and
    // "salon has a FULL address" (every field set) extremes. Phase 26.7.2's re-audit
    // (phase-207 Step 1) explicitly calls out a THIRD, distinct shape those two do not
    // exercise: a salon row where SOME address fields are set and ONE (locationNote) is
    // left null — proving each of the five `CASE WHEN s.id IS NOT NULL THEN s.X ELSE mu.X
    // END` expressions resolves independently per column, not as an all-or-nothing switch
    // keyed on whether the salon row "looks empty". A regression that accidentally shared
    // one salon-emptiness check across all five columns (e.g. gating every CASE on
    // `s.street IS NOT NULL` instead of `s.id IS NOT NULL`) would still pass the two
    // existing all-null/all-populated tests but would fail this one: it would either null
    // out the populated cityId/street/buildingNo too, or fall through locationNote to the
    // master's personal note. The master's own row again carries a FULL, DIFFERENT address
    // plus a personal note (leak canary), so any fall-through is visible.

    @Test
    @DisplayName("projection resolves populated salon fields (cityId/districtId/street/buildingNo) from "
            + "the salon and leaves locationNote NULL — never the master's own note — when only the "
            + "salon's locationNote is unset (mixed populated/null salon row)")
    void should_resolvePopulatedFieldsAndNullLocationNote_when_salonAddressIsPartiallyPopulated() {
        UUID[] salonTax = persistTaxonomy();
        UUID salonCityId = salonTax[0];
        UUID salonDistrictId = salonTax[1];

        User ownerUser = new User(
                "owner-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash", Role.SALON_OWNER, "Owner", "Person", "+380505555555");
        em.persist(ownerUser);

        // Salon sets cityId/districtId/street/buildingNo but deliberately leaves
        // locationNote unset (NULL) — a salon that filled in its address but never wrote a
        // door-code note, the realistic "partial" shape.
        Salon salon = Salon.builder()
                .owner(ownerUser)
                .name("Partial Studio")
                .cityId(salonCityId)
                .districtId(salonDistrictId)
                .street("PartialStreet")
                .buildingNo("21")
                .isActive(true)
                .build();
        em.persist(salon);

        // Master's own user row carries a FULL, DIFFERENT address AND a personal
        // locationNote — the leak canary: if any field fell through to mu.X instead of
        // staying CASE-null/CASE-salon, it would show one of these values instead.
        UUID[] masterOwnTax = persistTaxonomy();
        User masterUser = persistMasterUser(
                Role.SALON_MASTER, masterOwnTax[0], masterOwnTax[1],
                "MasterOwnStreet", "77", "https://cdn.test/salon-master-4.png");
        masterUser.setLocationNote("Master's personal note - must NOT surface for a salon booking");
        Master master = persistMaster(masterUser, salon, MasterType.SALON_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.SALON, salon.getId(), "Styling", "HAIR");
        Booking booking = persistBooking(master, msa, salon, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 20, 12, 0, 0, 0, ZoneOffset.UTC));

        em.flush();
        BookingDetailResponse entityResponse =
                BookingDetailResponse.from(booking, false, "Kyiv", "Podil");
        em.clear();

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        ClientBookingDetailProjection p = page.getContent().get(0);
        assertThat(p)
                .extracting(
                        ClientBookingDetailProjection::discoveryCityId,
                        ClientBookingDetailProjection::discoveryDistrictId,
                        ClientBookingDetailProjection::street,
                        ClientBookingDetailProjection::buildingNo,
                        ClientBookingDetailProjection::locationNote)
                .containsExactly(
                        salonCityId,
                        salonDistrictId,
                        "PartialStreet",
                        "21",
                        null);

        // Entity-path parity: BookingDetailResponse.from must agree that the populated
        // fields come from the salon and the unset field stays null (never the master's).
        assertThat(entityResponse.street()).isEqualTo("PartialStreet");
        assertThat(entityResponse.buildingNo()).isEqualTo("21");
        assertThat(entityResponse.locationNote())
                .as("entity path must also leave locationNote null, not fall through to the "
                        + "master's personal note, when the salon set every field EXCEPT locationNote")
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

        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

        // Two bookings, two rows — the OneToOne LEFT JOIN Review must not duplicate the reviewed row.
        assertThat(page.getTotalElements())
                .as("OneToOne LEFT JOIN Review must not fan out the reviewed booking into extra rows")
                .isEqualTo(2);
        // Order-independent by design (Phase 26.7.1): PageRequest.of(0, 20) carries no Sort, and
        // neither findIdsByClientIdFiltered (unsorted -> DB-plan-dependent id-page order) nor the
        // WHERE b.id IN :ids hydrate (IN never guarantees row order) promise a particular
        // sequence here — production ordering comes from BookingService#normalizeBookingSort
        // injecting a Sort into the Pageable before either query runs, which this repo-direct
        // test deliberately does not do. This test is about row COUNT/dedup + the reviewExists
        // flag per booking, not order, so it asserts the set, not a position.
        assertThat(page.getContent())
                .extracting(ClientBookingDetailProjection::id, ClientBookingDetailProjection::reviewExists)
                .containsExactlyInAnyOrder(
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

        Page<ClientBookingDetailProjection> filtered = findClientBookingDetails(
                clientUser.getId(), java.util.Set.of(BookingStatus.CONFIRMED), null, null, null, PageRequest.of(0, 20));
        Page<ClientBookingDetailProjection> all = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

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

        Page<ClientBookingDetailProjection> union = findClientBookingDetails(
                clientUser.getId(),
                java.util.EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED),
                null, null, null, PageRequest.of(0, 20));

        assertThat(union.getContent())
                .extracting(ClientBookingDetailProjection::id)
                .containsExactlyInAnyOrder(cancelled.getId(), declined.getId());
        assertThat(union.getTotalElements()).isEqualTo(2);
    }

    // ── service filter (Phase 26.4 — masterService.id IN :serviceIds, no CAST) ───
    //
    // Verifies the client-path (:serviceIds IS NULL OR b.masterService.id IN :serviceIds)
    // predicate against a REAL Postgres instance (Testcontainers, not a mock). serviceIds is a
    // Collection<UUID>, bound the same way `statuses` is (Hibernate's IN-clause collection
    // binding assigns the array element type explicitly) — unlike the scalar `from`/`toExclusive`
    // parameters above, which needed an explicit CAST to avoid "could not determine data type of
    // parameter" on a bare `IS NULL` check. If this predicate needed the same CAST treatment, the
    // tests below would fail with that exact Postgres error rather than merely asserting the
    // wrong rows — they do not, confirming the collection-sentinel form is safe here as-is.

    @Test
    @DisplayName("serviceId filter returns only bookings of the matching MasterService; a null filter returns all "
            + "(Phase 26.4 — proves the untyped (:serviceIds IS NULL OR ...) idiom against real Postgres)")
    void should_filterByServiceId_when_serviceIdFilterSupplied() {
        User masterUser = persistMasterUser(null, null, "Bankova", "4", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment manicure =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Manicure", "MANICURE");
        MasterServiceAssignment pedicure =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Pedicure", "PEDICURE");
        Booking manicureBooking = persistBooking(master, manicure, null, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 13, 9, 0, 0, 0, ZoneOffset.UTC));
        persistBooking(master, pedicure, null, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 14, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> filtered = findClientBookingDetails(
                clientUser.getId(), null, null, null, java.util.Set.of(manicure.getId()), PageRequest.of(0, 20));
        Page<ClientBookingDetailProjection> unfiltered = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

        assertThat(filtered.getContent())
                .extracting(ClientBookingDetailProjection::id)
                .containsExactly(manicureBooking.getId());
        assertThat(filtered.getTotalElements())
                .as("countQuery must carry the same serviceIds predicate as the content query")
                .isEqualTo(1);
        assertThat(unfiltered.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("a multi-value serviceId set returns the union of matching bookings (Phase 26.4)")
    void should_returnUnion_when_multipleServiceIdsSupplied() {
        User masterUser = persistMasterUser(null, null, "Instytutska", "6", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment manicure =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Manicure", "MANICURE");
        MasterServiceAssignment pedicure =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Pedicure", "PEDICURE");
        MasterServiceAssignment massage =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Massage", "MASSAGE");
        Booking manicureBooking = persistBooking(master, manicure, null, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 15, 9, 0, 0, 0, ZoneOffset.UTC));
        Booking pedicureBooking = persistBooking(master, pedicure, null, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 16, 9, 0, 0, 0, ZoneOffset.UTC));
        persistBooking(master, massage, null, BookingStatus.CONFIRMED,
                OffsetDateTime.of(2026, 6, 17, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> union = findClientBookingDetails(
                clientUser.getId(), null, null, null,
                new java.util.LinkedHashSet<>(java.util.List.of(manicure.getId(), pedicure.getId())),
                PageRequest.of(0, 20));

        assertThat(union.getContent())
                .extracting(ClientBookingDetailProjection::id)
                .containsExactlyInAnyOrder(manicureBooking.getId(), pedicureBooking.getId());
        assertThat(union.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("a serviceId belonging to a booking whose MasterService was later deleted still leaves the "
            + "booking visible in the UNFILTERED list (Phase 26.4 catalogue-limitation acceptance criterion)")
    void should_stillListBooking_when_itsMasterServiceIsLaterDeleted() {
        User masterUser = persistMasterUser(null, null, "Predslavynska", "8", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Waxing", "WAXING");
        Booking booking = persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 18, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();

        // Simulate the master deleting the service after the booking was made: the booking's
        // masterService FK still resolves (bookings never cascade-delete), the catalogue just no
        // longer lists it as a filter option — see the phase doc's accepted-limitation note.
        msa.setActive(false);
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> unfiltered = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));

        assertThat(unfiltered.getContent())
                .extracting(ClientBookingDetailProjection::id)
                .containsExactly(booking.getId());
    }

    // ── bounded query count (no N+1) ─────────────────────────────────────────────

    @Test
    @DisplayName("query count is bounded (id page + count + hydrate, <= 3) and independent of the "
            + "number of booking rows AND of the number of filters supplied — no N+1, no "
            + "sentinel-driven fan-out (Phase 26.7.1)")
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
        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));
        long fiveRowQueries = statistics.getPrepareStatementCount();

        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getContent())
                .extracting(ClientBookingDetailProjection::reviewExists)
                .containsExactlyInAnyOrder(true, true, true, false, false);

        // Re-run against a freshly cleared persistence context to confirm the statement
        // count is the SAME (id page + count + hydrate), independent of row count — the defining
        // property of an N+1-free query. Phase 26.7.1 raised this bound from <=2 to <=3: the
        // single findClientBookingDetails projection+count pair became three statements
        // (findIdsByClientIdFiltered's id query + its count query + hydrateClientBookingDetails'
        // IN :ids hydrate) — an expected, bounded, filter-count-INDEPENDENT change (the phase doc
        // calls this out explicitly), not an N+1 regression: it does not scale with row count
        // (still 5 rows here), and does not scale with how many of statuses/from/toExclusive/
        // serviceIds were supplied (this call supplies none of them).
        em.clear();
        statistics.clear();
        Page<ClientBookingDetailProjection> rerun = findClientBookingDetails(
                clientUser.getId(), null, null, null, null, PageRequest.of(0, 20));
        long rerunQueries = statistics.getPrepareStatementCount();

        assertThat(rerun.getContent()).hasSize(5);
        assertThat(fiveRowQueries)
                .as("enriched projection runs a bounded number of statements (id page + count + "
                        + "hydrate), not one-per-row; got %s", fiveRowQueries)
                .isEqualTo(rerunQueries)
                .isLessThanOrEqualTo(3);

        // Same bound with EVERY optional predicate supplied at once (status + date range +
        // serviceId) — proves the statement count is independent of the NUMBER OF FILTERS, not
        // just of row count. This is what would catch a sentinel-driven fan-out regression: the
        // (:x IS NULL OR ...) idiom this phase removes doesn't change statement COUNT either, so
        // this assertion is really pinning "still exactly the id+count+hydrate shape", not
        // re-testing sargability (that's Phase 26.7.2's EXPLAIN gate).
        em.clear();
        statistics.clear();
        Page<ClientBookingDetailProjection> filteredRerun = findClientBookingDetails(
                clientUser.getId(),
                java.util.Set.of(BookingStatus.COMPLETED),
                OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 7, 6, 0, 0, 0, 0, ZoneOffset.UTC),
                java.util.Set.of(msa.getId()),
                PageRequest.of(0, 20));
        long filteredQueries = statistics.getPrepareStatementCount();

        assertThat(filteredRerun.getContent()).hasSize(5);
        assertThat(filteredQueries)
                .as("supplying all four optional filters at once must NOT add statements beyond "
                        + "the id page + count + hydrate shape — got %s vs. the no-filter %s",
                        filteredQueries, fiveRowQueries)
                .isEqualTo(fiveRowQueries)
                .isLessThanOrEqualTo(3);
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
        Page<ClientBookingDetailProjection> page = findClientBookingDetails(
                otherClientId, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
    }
}
