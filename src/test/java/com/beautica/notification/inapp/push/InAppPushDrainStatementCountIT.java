package com.beautica.notification.inapp.push;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.PushNotificationService;
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
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 339 audit-fix cycle 1 (perf finding 5) — SQL-capture pin for the drain's INAPP_PUSH
 * pre-load. A batch of 50 {@code INAPP_PUSH} entries for 5 recipients must cost a number of
 * statements that depends on the RECIPIENT count, never on the row count: the old per-entry path
 * cost ~6-7 statements and a transaction per row.
 *
 * <p>Real Hibernate {@link Statistics} via {@link HibernateStatistics} (enabling is inside the
 * helper — an un-enabled probe reads zero and would pass vacuously; the test also asserts the
 * counter actually moved).
 */
class InAppPushDrainStatementCountIT extends AbstractIntegrationTest {

    private static final int RECIPIENTS = 5;
    private static final int ROWS_PER_RECIPIENT = 10;
    private static final int MANY_RECIPIENTS = 50;
    /** feed rows + recipients + tokens + bookings (+visits/subjects/reviews/owners/admins, skipped here) + slack. */
    private static final long FLAT_BOUND = 8;
    private static final AtomicInteger SORT_ORDER_SEQ = new AtomicInteger(790_000);

    /**
     * S4 moved a token-ownership query onto the {@code pushExecutor} thread, per push. Hibernate
     * {@link Statistics} are factory-wide, not per-thread, so with the real executor those queries
     * would land inside this test's measurement window nondeterministically. The IT measures the
     * DRAIN-THREAD pre-load only, so the hand-off target is mocked (its own behaviour is covered by
     * PushNotificationServiceTest).
     */
    @MockBean
    private PushNotificationService pushNotificationService;
    @Autowired
    private NotificationOutboxDrainWorker drainWorker;
    @Autowired
    private InAppPushDispatcher dispatcher;
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
    private EntityManagerFactory emf;

    /** {@code rows.get(r)} holds the feed row ids of recipient {@code r}. */
    private List<List<UUID>> seed() {
        return seed(RECIPIENTS, ROWS_PER_RECIPIENT);
    }

    private List<List<UUID>> seed(int recipients, int rowsPerRecipient) {
        User masterUser = persistUser(Role.INDEPENDENT_MASTER);
        Master master = masterRepository.save(Master.builder()
                .user(masterUser).masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO).reviewCount(0).isActive(true).build());
        CatalogCategory category = catalogCategoryRepository.save(CatalogCategory.builder()
                .nameUk("Нігті").nameEn("Nails").sortOrder(SORT_ORDER_SEQ.getAndIncrement()).build());
        ServiceType serviceType = serviceTypeRepository.save(ServiceType.builder()
                .category(category).nameUk("Манікюр").nameEn("Manicure")
                .slug("push-drain-type-" + UUID.randomUUID()).platformCategoryName("NAIL_SERVICE").build());
        ServiceDefinition definition = serviceRepository.save(ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER).ownerId(master.getId()).name("Gel Manicure")
                .category("MANICURE").baseDurationMinutes(60).priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00")).serviceType(serviceType).isActive(true).build());
        MasterServiceAssignment masterService = masterServiceRepository.save(MasterServiceAssignment.builder()
                .master(master).serviceDefinition(definition).isActive(true).build());

        List<List<UUID>> rows = new ArrayList<>();
        int day = 0;
        for (int r = 0; r < recipients; r++) {
            User client = persistUser(Role.CLIENT);
            jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')",
                    client.getId(), "push-drain-token-" + client.getId());
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < rowsPerRecipient; i++) {
                OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC).plusDays(day++);
                Booking booking = bookingRepository.save(Booking.builder()
                        .client(client).master(master).masterService(masterService)
                        .status(BookingStatus.CONFIRMED).startsAt(startsAt).endsAt(startsAt.plusHours(1))
                        .priceAtBooking(new BigDecimal("450.00")).durationMinutesAtBooking(60)
                        .bufferMinutesAtBooking(0).idempotencyKey("push-drain-" + UUID.randomUUID()).build());
                UUID id = UUID.randomUUID();
                jdbcTemplate.update("""
                        INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, dedup_key)
                        VALUES (?, ?, 'BOOKING_DECLINED', ?, ?)
                        """, id, client.getId(), booking.getId(), "BOOKING_DECLINED:" + booking.getId());
                ids.add(id);
            }
            rows.add(ids);
        }
        return rows;
    }

    private User persistUser(Role role) {
        return userRepository.save(new User(
                "push-drain-" + UUID.randomUUID() + "@example.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("test-password"),
                role, "Anna", "Kovalenko", "+380501111111"));
    }

    private static List<NotificationOutboxEntry> entriesFor(List<UUID> feedRowIds) {
        List<NotificationOutboxEntry> batch = new ArrayList<>();
        for (UUID id : feedRowIds) {
            NotificationOutboxEntry entry = new NotificationOutboxEntry();
            entry.setEventType(OutboxEventType.INAPP_PUSH);
            entry.setStatus(OutboxStatus.PROCESSING);
            entry.setAttempts(0);
            entry.setAggregateId(id);
            batch.add(entry);
        }
        return batch;
    }

    private long statementsFor(List<UUID> feedRowIds) {
        Statistics statistics = HibernateStatistics.enabledOn(emf);
        long before = statistics.getPrepareStatementCount();

        List<?> results = drainWorker.dispatchAll(entriesFor(feedRowIds));

        long cost = statistics.getPrepareStatementCount() - before;
        assertThat(results).hasSameSizeAs(feedRowIds);
        // EntryResult is a private record of the worker; its toString carries the decided status.
        assertThat(results).allSatisfy(r -> assertThat(r.toString()).contains("status=SENT"));
        return cost;
    }

    @Test
    @DisplayName("draining 50 INAPP_PUSH entries for 5 recipients costs a statement count that does not "
            + "scale with the number of rows")
    void should_notScaleStatementsWithRows_when_drainingInAppPushBatch() {
        List<List<UUID>> rows = seed();
        List<UUID> twoPerRecipient = new ArrayList<>();
        List<UUID> all = new ArrayList<>();
        rows.forEach(ids -> {
            twoPerRecipient.addAll(ids.subList(0, 2));
            all.addAll(ids);
        });

        long small = statementsFor(twoPerRecipient);
        long large = statementsFor(all);
        org.slf4j.LoggerFactory.getLogger(InAppPushDrainStatementCountIT.class)
                .info("INAPP_PUSH pre-load statements: 10 rows={}, 50 rows={}", small, large);

        assertThat(small).as("the probe must actually be counting").isPositive();
        assertThat(large).as("5x the rows for the same 5 recipients must cost the same statements "
                + "(small=%d, large=%d)", small, large).isLessThanOrEqualTo(small + 1);
        assertThat(large).as("flat bound: one batch assemble, so independent of rows AND recipients "
                + "(was ~5 statements per recipient)").isLessThanOrEqualTo(FLAT_BOUND);
    }

    @Test
    @DisplayName("P2: draining 50 rows for 50 DISTINCT recipients costs the same statements as 5 rows for 5 "
            + "recipients — one batch assemble, not one per recipient")
    void should_notScaleStatementsWithRecipients_when_drainingInAppPushBatch() {
        List<List<UUID>> rows = seed(MANY_RECIPIENTS, 1);
        List<UUID> five = new ArrayList<>();
        List<UUID> fifty = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            fifty.addAll(rows.get(r));
            if (r < RECIPIENTS) {
                five.addAll(rows.get(r));
            }
        }

        long small = statementsFor(five);
        long large = statementsFor(fifty);
        org.slf4j.LoggerFactory.getLogger(InAppPushDrainStatementCountIT.class)
                .info("INAPP_PUSH pre-load statements: 5 recipients={}, 50 recipients={}", small, large);

        assertThat(small).as("the probe must actually be counting").isPositive();
        assertThat(large).as("10x the DISTINCT recipients must cost the same statements (small=%d, large=%d)",
                small, large).isLessThanOrEqualTo(small + 1);
        assertThat(large).as("flat bound, nowhere near ~5 statements per recipient (~250)")
                .isLessThanOrEqualTo(FLAT_BOUND);
    }

    @Test
    @DisplayName("every one of the 50 pre-loaded rows yields a sendable plan for its own recipient")
    void should_planEveryRow_when_fiftyRowsForFiveRecipients() {
        List<List<UUID>> rows = seed();
        List<UUID> all = new ArrayList<>();
        rows.forEach(all::addAll);

        var plans = dispatcher.prepare(all);

        assertThat(plans).hasSize(RECIPIENTS * ROWS_PER_RECIPIENT).containsOnlyKeys(all);
        assertThat(plans.values()).allSatisfy(plan -> {
            assertThat(plan.tokens()).hasSize(1);
            assertThat(plan.title()).isEqualTo("Ваш запис скасовано");
            assertThat(plan.data()).containsEntry("targetKind", "BOOKING");
        });
    }
}
