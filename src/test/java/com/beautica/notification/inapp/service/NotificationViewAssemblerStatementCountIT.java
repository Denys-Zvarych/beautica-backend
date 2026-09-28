package com.beautica.notification.inapp.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.PageResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import com.beautica.support.HibernateStatistics;
import com.beautica.support.LocalityTestLookup;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 334 — SQL-capture pin for {@code NotificationFeedService#listFeed}'s statement cost,
 * driven through {@link NotificationViewAssembler} (memory: Hibernate batch-fetch staircase —
 * enumerate by SQL capture, never trust a per-item count as a ceiling).
 *
 * <p>Real Hibernate {@link Statistics} (not a mock), via the shared {@link HibernateStatistics}
 * helper — the same technique every other "this page must not scale with row count" gate in this
 * suite uses (e.g. {@code BookingSalonBookingsIT}).
 */
class NotificationViewAssemblerStatementCountIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationFeedService notificationFeedService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MasterRepository masterRepository;
    @Autowired
    private CatalogCategoryRepository catalogCategoryRepository;
    @Autowired
    private ServiceTypeRepository serviceTypeRepository;
    @Autowired
    private ServiceRepository serviceRepository;
    @Autowired
    private MasterServiceRepository masterServiceRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private SalonRepository salonRepository;
    @Autowired
    private EntityManagerFactory emf;

    private static final AtomicInteger SORT_ORDER_SEQ = new AtomicInteger(700_000);

    private User client;
    private Master master;
    private MasterServiceAssignment masterService;

    @BeforeEach
    void setUp() {
        client = persistUser(Role.CLIENT);
        User masterUser = persistUser(Role.INDEPENDENT_MASTER);

        master = masterRepository.save(Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build());

        CatalogCategory category = catalogCategoryRepository.save(CatalogCategory.builder()
                .nameUk("Нігті")
                .nameEn("Nails")
                .sortOrder(SORT_ORDER_SEQ.getAndIncrement())
                .build());
        ServiceType serviceType = serviceTypeRepository.save(ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("stmt-count-type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build());
        ServiceDefinition serviceDefinition = serviceRepository.save(ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Gel Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(serviceType)
                .isActive(true)
                .build());
        masterService = masterServiceRepository.save(MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build());
    }

    private User persistUser(Role role) {
        return userRepository.save(new User(
                "stmt-count-" + UUID.randomUUID() + "@example.com", "$2a$10$hashedpassword",
                role, "Anna", "Kovalenko", "+380501111111"));
    }

    private Booking newBooking(OffsetDateTime startsAt) {
        return Booking.builder()
                .client(client)
                .master(master)
                .masterService(masterService)
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .idempotencyKey("stmt-count-idem-" + UUID.randomUUID())
                .build();
    }

    private void seedBookingNotification(int index) {
        Booking booking = bookingRepository.save(
                newBooking(OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC).plusDays(index)));
        insertNotificationRow(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null,
                "BOOKING_CREATED:" + booking.getId() + ":" + index);
    }

    /** A 2-service visit whose header event is written as ONE appointment-keyed notification row. */
    private void seedVisitNotification(int index) {
        Appointment appointment = appointmentRepository.save(
                Appointment.builder().client(client).status(BookingStatus.CONFIRMED).build());
        OffsetDateTime start = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC).plusDays(index);
        Booking first = newBooking(start);
        first.setAppointment(appointment);
        Booking second = newBooking(start.plusHours(1));
        second.setAppointment(appointment);
        bookingRepository.save(first);
        bookingRepository.save(second);
        insertNotificationRow(client.getId(), InAppNotificationType.BOOKING_RESCHEDULED, null, appointment.getId(),
                "BOOKING_RESCHEDULED:" + appointment.getId() + ":" + index);
    }

    /**
     * Raw SQL, not {@code InAppNotificationRepository#insertIgnoringDuplicate} — that
     * {@code @Modifying} query requires an already-open transaction, which this class (a plain
     * {@code @SpringBootTest} via {@link AbstractIntegrationTest}, deliberately not
     * {@code @Transactional}) does not provide outside a service method.
     */
    private void insertNotificationRow(UUID recipientId, InAppNotificationType type, UUID bookingId,
                                        UUID appointmentId, String dedupKey) {
        jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, appointment_id, dedup_key)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), recipientId, type.name(), bookingId, appointmentId, dedupKey);
    }

    private Statistics statistics() {
        return HibernateStatistics.enabledOn(emf);
    }

    // ── audit-fix cycle 1, finding 4 (LOW, perf) — SALON_OWNER / SALON_ADMIN role coverage ──────
    // The original suite above only ever drives a CLIENT recipient. The assembler's SALON_OWNER
    // arm adds one fixed findIdsByOwnerIdAndIsActiveTrue call and the SALON_ADMIN arm adds at
    // most one adminBelongsToSalon call PER DISTINCT SALON (memoized) — both documented as
    // page-flat, never per-row, in NotificationViewAssembler's class javadoc. Neither role was
    // ever actually measured.

    private record SalonMasterFixture(Salon salon, Master master, MasterServiceAssignment masterService) {}

    /** A SALON_OWNER-type master (the owner operating their own salon) bound to a fresh salon. */
    private SalonMasterFixture createSalonBoundMaster(User ownerUser) {
        Salon salon = salonRepository.save(Salon.builder()
                .cityId(testCityId())
                .owner(ownerUser)
                .name("Stmt Count Salon " + UUID.randomUUID())
                .isActive(true)
                .build());
        Master salonMaster = masterRepository.save(Master.builder()
                .user(ownerUser)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build());
        CatalogCategory category = catalogCategoryRepository.save(CatalogCategory.builder()
                .nameUk("Брови")
                .nameEn("Brows")
                .sortOrder(SORT_ORDER_SEQ.getAndIncrement())
                .build());
        ServiceType serviceType = serviceTypeRepository.save(ServiceType.builder()
                .category(category)
                .nameUk("Корекція брів")
                .nameEn("Brow shaping")
                .slug("stmt-count-salon-type-" + UUID.randomUUID())
                .platformCategoryName("BROWS")
                .build());
        ServiceDefinition serviceDefinition = serviceRepository.save(ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salon.getId())
                .name("Brow Shaping")
                .category("BROWS")
                .baseDurationMinutes(30)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .serviceType(serviceType)
                .isActive(true)
                .build());
        MasterServiceAssignment assignment = masterServiceRepository.save(MasterServiceAssignment.builder()
                .master(salonMaster)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build());
        return new SalonMasterFixture(salon, salonMaster, assignment);
    }

    private void seedSalonBookingNotification(SalonMasterFixture fixture, UUID recipientId, int index) {
        OffsetDateTime startsAt =
                OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC).plusDays(index);
        Booking booking = bookingRepository.save(Booking.builder()
                .client(client)
                .master(fixture.master())
                .masterService(fixture.masterService())
                .salon(fixture.salon())
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .priceAtBooking(new BigDecimal("300.00"))
                .durationMinutesAtBooking(30)
                .bufferMinutesAtBooking(0)
                .idempotencyKey("stmt-count-salon-idem-" + UUID.randomUUID())
                .build());
        insertNotificationRow(recipientId, InAppNotificationType.BOOKING_CREATED, booking.getId(), null,
                "BOOKING_CREATED:" + booking.getId() + ":" + index);
    }

    @Test
    @DisplayName("acceptance criterion — a SALON_OWNER recipient's page costs a FLAT statement "
            + "count regardless of page size: 1 item and 20 items cost the SAME (the owner's "
            + "findIdsByOwnerIdAndIsActiveTrue lookup runs once per page, not once per row)")
    void should_costFlatStatementCount_forSalonOwnerRole() {
        User owner = persistUser(Role.SALON_OWNER);
        SalonMasterFixture fixture = createSalonBoundMaster(owner);
        Statistics statistics = statistics();

        seedSalonBookingNotification(fixture, owner.getId(), 0);
        statistics.clear();
        PageResponse<NotificationResponse> onePage =
                notificationFeedService.listFeed(owner.getId(), Role.SALON_OWNER, 0, 1);
        long statementsForOneItem = statistics.getPrepareStatementCount();

        for (int i = 1; i < 20; i++) {
            seedSalonBookingNotification(fixture, owner.getId(), i);
        }
        statistics.clear();
        PageResponse<NotificationResponse> twentyPage =
                notificationFeedService.listFeed(owner.getId(), Role.SALON_OWNER, 0, 20);
        long statementsForTwentyItems = statistics.getPrepareStatementCount();

        assertThat(onePage.data()).hasSize(1);
        assertThat(twentyPage.data()).hasSize(20);
        assertThat(twentyPage.data())
                .as("every row must actually resolve params — a null-everywhere page would make "
                        + "this statement count vacuously cheap")
                .allSatisfy(row -> assertThat(row.params()).isNotNull());
        assertThat(statementsForTwentyItems)
                .as("statement count for a 20-item SALON_OWNER page (%d) must equal a 1-item page "
                        + "(%d) — a rise means the owner-salon lookup crept into a per-row query",
                        statementsForTwentyItems, statementsForOneItem)
                .isEqualTo(statementsForOneItem);
    }

    @Test
    @DisplayName("acceptance criterion — a SALON_ADMIN recipient's page costs a FLAT statement "
            + "count regardless of page size: 1 item and 20 items cost the SAME (the "
            + "adminBelongsToSalon check is memoized per distinct salon, not re-run per row)")
    void should_costFlatStatementCount_forSalonAdminRole() {
        User owner = persistUser(Role.SALON_OWNER);
        SalonMasterFixture fixture = createSalonBoundMaster(owner);
        User admin = new User(
                "stmt-count-admin-" + UUID.randomUUID() + "@example.com", "$2a$10$hashedpassword",
                Role.SALON_ADMIN, "Anna", "Kovalenko", "+380501111111", fixture.salon().getId());
        admin = userRepository.save(admin);
        Statistics statistics = statistics();

        seedSalonBookingNotification(fixture, admin.getId(), 0);
        statistics.clear();
        PageResponse<NotificationResponse> onePage =
                notificationFeedService.listFeed(admin.getId(), Role.SALON_ADMIN, 0, 1);
        long statementsForOneItem = statistics.getPrepareStatementCount();

        for (int i = 1; i < 20; i++) {
            seedSalonBookingNotification(fixture, admin.getId(), i);
        }
        statistics.clear();
        PageResponse<NotificationResponse> twentyPage =
                notificationFeedService.listFeed(admin.getId(), Role.SALON_ADMIN, 0, 20);
        long statementsForTwentyItems = statistics.getPrepareStatementCount();

        assertThat(onePage.data()).hasSize(1);
        assertThat(twentyPage.data()).hasSize(20);
        assertThat(twentyPage.data())
                .as("every row must actually resolve params — a null-everywhere page would make "
                        + "this statement count vacuously cheap")
                .allSatisfy(row -> assertThat(row.params()).isNotNull());
        assertThat(statementsForTwentyItems)
                .as("statement count for a 20-item SALON_ADMIN page (%d) must equal a 1-item page "
                        + "(%d) — a rise means adminBelongsToSalon crept into a per-row query",
                        statementsForTwentyItems, statementsForOneItem)
                .isEqualTo(statementsForOneItem);
    }

    @Test
    @DisplayName("acceptance criterion — a page of single-service booking events costs a FLAT "
            + "statement count regardless of page size: 1 item and 20 items cost the SAME")
    void should_costFlatStatementCount_whenPageSizeGrowsFromOneToTwenty() {
        Statistics statistics = statistics();

        seedBookingNotification(0);
        statistics.clear();
        PageResponse<NotificationResponse> onePage =
                notificationFeedService.listFeed(client.getId(), Role.CLIENT, 0, 1);
        long statementsForOneItem = statistics.getPrepareStatementCount();

        for (int i = 1; i < 20; i++) {
            seedBookingNotification(i);
        }
        statistics.clear();
        PageResponse<NotificationResponse> twentyPage =
                notificationFeedService.listFeed(client.getId(), Role.CLIENT, 0, 20);
        long statementsForTwentyItems = statistics.getPrepareStatementCount();

        assertThat(onePage.data()).hasSize(1);
        assertThat(twentyPage.data()).hasSize(20);
        assertThat(twentyPage.data())
                .as("every row must actually resolve params — a null-everywhere page would make "
                        + "this statement count vacuously cheap")
                .allSatisfy(row -> assertThat(row.params()).isNotNull());
        assertThat(statementsForTwentyItems)
                .as("statement count for a 20-item page (%d) must equal a 1-item page (%d) — a rise "
                        + "means a per-row query crept in (N+1)", statementsForTwentyItems, statementsForOneItem)
                .isEqualTo(statementsForOneItem);
    }

    @Test
    @DisplayName("acceptance criterion — a MIXED page (single-service booking events + a "
            + "multi-service visit event) costs a small, bounded statement count — the extra "
            + "sibling-booking batch adds exactly ONE more statement, not one per visit row")
    void should_costBoundedStatementCount_whenPageMixesBookingAndVisitEvents() {
        Statistics statistics = statistics();

        // page size 21, one wider than the 10 (then 20) rows seeded below — Spring Data's Page<>
        // skips its COUNT query whenever content.size() < pageSize (the "known last page"
        // optimisation), so keeping BOTH calls on that side of the boundary isolates the assembler's
        // OWN statement cost from that unrelated, size-triggered COUNT-query variation.
        int pageSize = 21;
        for (int i = 0; i < 10; i++) {
            seedBookingNotification(i);
        }
        statistics.clear();
        notificationFeedService.listFeed(client.getId(), Role.CLIENT, 0, pageSize);
        long statementsBookingOnly = statistics.getPrepareStatementCount();

        // Ten MORE notification rows, all pointing at visit events — some sharing the SAME
        // appointment as each other is not needed here; each seedVisitNotification call creates
        // its own appointment, which is the more demanding (more distinct ids) shape.
        for (int i = 0; i < 10; i++) {
            seedVisitNotification(i);
        }
        statistics.clear();
        PageResponse<NotificationResponse> mixedPage =
                notificationFeedService.listFeed(client.getId(), Role.CLIENT, 0, pageSize);
        long statementsMixed = statistics.getPrepareStatementCount();

        assertThat(mixedPage.data()).hasSize(20);
        assertThat(mixedPage.data())
                .as("every row (booking-keyed AND appointment-keyed) must resolve params")
                .allSatisfy(row -> assertThat(row.params()).isNotNull());
        assertThat(statementsMixed)
                .as("adding visit-typed rows to the SAME page must add exactly ONE extra batched "
                        + "statement (the sibling-booking fetch) over the booking-only baseline (%d), "
                        + "never one per visit row — measured %d", statementsBookingOnly, statementsMixed)
                .isEqualTo(statementsBookingOnly + 1);
    }
}
