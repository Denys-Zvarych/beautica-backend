package com.beautica.booking.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.review.entity.Review;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
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
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        em.getEntityManager().createNativeQuery(
                "INSERT INTO oblasts (id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?)")
                .setParameter(1, oblastId).setParameter(2, "OB-" + suffix)
                .setParameter(3, "Oblast").setParameter(4, "Oblast").executeUpdate();
        em.getEntityManager().createNativeQuery(
                "INSERT INTO cities (id, oblast_id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?, ?)")
                .setParameter(1, cityId).setParameter(2, oblastId).setParameter(3, "CT-" + suffix)
                .setParameter(4, "City").setParameter(5, "City").executeUpdate();
        em.getEntityManager().createNativeQuery(
                "INSERT INTO city_districts (id, city_id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?, ?)")
                .setParameter(1, districtId).setParameter(2, cityId).setParameter(3, "DT-" + suffix)
                .setParameter(4, "District").setParameter(5, "District").executeUpdate();
        return new UUID[]{cityId, districtId};
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
                        "MANICURE",
                        false);
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
                .isActive(true)
                .build();
        em.persist(salon);

        // Master's own user row carries DIFFERENT locality to prove the salon link wins.
        // masterType is sourced from the master user's role, so it must be SALON_MASTER here.
        User masterUser = persistMasterUser(
                Role.SALON_MASTER, masterOwnTax[0], masterOwnTax[1],
                "OwnStreet", "99", "https://cdn.test/salon-master.png");
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
                        ClientBookingDetailProjection::categoryName)
                .containsExactly(
                        "Glamour Studio",
                        Role.SALON_MASTER,
                        salonCityId,
                        salonDistrictId,
                        "Volodymyrska",
                        "55",
                        "PEDICURE");
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

    // ── status filter ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status filter returns only matching bookings; a null filter returns all")
    void should_filterByStatus_when_statusFilterSupplied() {
        User masterUser = persistMasterUser(null, null, "Lypska", "7", null);
        Master master = persistMaster(masterUser, null, MasterType.INDEPENDENT_MASTER);
        MasterServiceAssignment msa =
                persistService(master, OwnerType.INDEPENDENT_MASTER, master.getId(), "Massage", "MASSAGE");
        Booking pending = persistBooking(master, msa, null, BookingStatus.PENDING,
                OffsetDateTime.of(2026, 6, 5, 9, 0, 0, 0, ZoneOffset.UTC));
        persistBooking(master, msa, null, BookingStatus.COMPLETED,
                OffsetDateTime.of(2026, 6, 6, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        Page<ClientBookingDetailProjection> filtered = bookingRepository.findClientBookingDetails(
                clientUser.getId(), BookingStatus.PENDING, PageRequest.of(0, 20));
        Page<ClientBookingDetailProjection> all = bookingRepository.findClientBookingDetails(
                clientUser.getId(), null, PageRequest.of(0, 20));

        assertThat(filtered.getContent())
                .extracting(ClientBookingDetailProjection::id)
                .containsExactly(pending.getId());
        assertThat(all.getTotalElements()).isEqualTo(2);
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
        persistBooking(master, msa, null, BookingStatus.PENDING,
                OffsetDateTime.of(2026, 6, 7, 9, 0, 0, 0, ZoneOffset.UTC));
        em.flush();
        em.clear();

        UUID otherClientId = UUID.randomUUID();
        Page<ClientBookingDetailProjection> page = bookingRepository.findClientBookingDetails(
                otherClientId, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
    }
}
