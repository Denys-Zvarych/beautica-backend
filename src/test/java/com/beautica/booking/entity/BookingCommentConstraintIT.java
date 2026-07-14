package com.beautica.booking.entity;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 25.4 (V114) — proves the two new CHECK constraints reject rows that violate the
 * write-path invariants, and that the deliberately-absent constraint on {@code client_comment}
 * does NOT reject a legitimate CONFIRMED-status creation note. Real PostgreSQL via
 * {@link AbstractDataJpaTest} (Testcontainers) — CHECK constraints are DB-level, not something
 * {@code ddl-auto=validate} or an in-memory database would exercise.
 */
class BookingCommentConstraintIT extends AbstractDataJpaTest {

    @Autowired
    private TestEntityManager em;

    private static final AtomicInteger SORT_ORDER_SEQ = new AtomicInteger(190_000);

    private User client;
    private Master master;
    private MasterServiceAssignment masterService;

    @BeforeEach
    void setUp() {
        User clientUser = new User(
                "comment-constraint-client-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.CLIENT,
                "Anna",
                "Kovalenko",
                "+380501111111"
        );
        em.persist(clientUser);
        client = clientUser;

        User masterUser = new User(
                "comment-constraint-master-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.INDEPENDENT_MASTER,
                "Olena",
                "Shevchenko",
                "+380502222222"
        );
        em.persist(masterUser);

        master = Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master);

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

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Gel Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(serviceType)
                .isActive(true)
                .build();
        em.persist(serviceDefinition);

        masterService = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(masterService);

        em.flush();
    }

    @Test
    @DisplayName("chk_provider_comment_status — provider_comment rejected on a CONFIRMED (non-terminal) row")
    void should_rejectProviderComment_when_statusIsConfirmed() {
        Booking booking = baseBuilder(BookingStatus.CONFIRMED)
                .providerComment("This should never be allowed on a CONFIRMED row")
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(booking))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_provider_comment_status — provider_comment accepted on DECLINED")
    void should_acceptProviderComment_when_statusIsDeclined() {
        Booking booking = baseBuilder(BookingStatus.DECLINED)
                .providerComment("Майстер захворів")
                .build();

        Booking saved = em.persistFlushFind(booking);

        assertThat(saved.getProviderComment()).isEqualTo("Майстер захворів");
    }

    @Test
    @DisplayName("chk_provider_comment_status — provider_comment accepted on NOT_COMPLETED")
    void should_acceptProviderComment_when_statusIsNotCompleted() {
        Booking booking = baseBuilder(BookingStatus.NOT_COMPLETED)
                .providerComment("Клієнт не з'явився")
                .build();

        Booking saved = em.persistFlushFind(booking);

        assertThat(saved.getProviderComment()).isEqualTo("Клієнт не з'явився");
    }

    @Test
    @DisplayName("chk_client_cancellation_note_status — client_cancellation_note rejected on a CONFIRMED row")
    void should_rejectClientCancellationNote_when_statusIsConfirmed() {
        Booking booking = baseBuilder(BookingStatus.CONFIRMED)
                .clientCancellationNote("This should never be allowed on a CONFIRMED row")
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(booking))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_client_cancellation_note_status — client_cancellation_note rejected on DECLINED "
            + "(only CANCELLED may carry it — this note is written exclusively by the client's own /cancel)")
    void should_rejectClientCancellationNote_when_statusIsDeclined() {
        Booking booking = baseBuilder(BookingStatus.DECLINED)
                .clientCancellationNote("Client note must not appear on a provider-declined row")
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(booking))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_client_cancellation_note_status — client_cancellation_note accepted on CANCELLED")
    void should_acceptClientCancellationNote_when_statusIsCancelled() {
        Booking booking = baseBuilder(BookingStatus.CANCELLED)
                .clientCancellationNote("Змінились плани")
                .build();

        Booking saved = em.persistFlushFind(booking);

        assertThat(saved.getClientCancellationNote()).isEqualTo("Змінились плани");
    }

    @Test
    @DisplayName("no CHECK on client_comment — the booking-creation note survives on CONFIRMED "
            + "(deliberately absent constraint, V114 migration comment)")
    void should_acceptClientComment_when_statusIsConfirmed() {
        Booking booking = baseBuilder(BookingStatus.CONFIRMED)
                .clientComment("Please arrive 5 minutes early")
                .build();

        Booking saved = em.persistFlushFind(booking);

        assertThat(saved.getClientComment()).isEqualTo("Please arrive 5 minutes early");
    }

    @Test
    @DisplayName("no CHECK on client_comment — the booking-creation note also survives on COMPLETED")
    void should_acceptClientComment_when_statusIsCompleted() {
        Booking booking = baseBuilder(BookingStatus.COMPLETED)
                .clientComment("Please use the side entrance")
                .build();

        Booking saved = em.persistFlushFind(booking);

        assertThat(saved.getClientComment()).isEqualTo("Please use the side entrance");
    }

    @Test
    @DisplayName("MEDIUM (residual): V115 actually runs VALIDATE CONSTRAINT — all three NOT VALID "
            + "checks added by V113/V114 end up convalidated=true in pg_constraint, proving the "
            + "deferred scan really executed (not just cosmetically split across files)")
    @SuppressWarnings("unchecked")
    void should_haveConvalidatedConstraints_when_v115Applied() {
        List<Object[]> rows = em.getEntityManager()
                .createNativeQuery("""
                        SELECT conname, convalidated
                          FROM pg_constraint
                         WHERE conrelid = 'bookings'::regclass
                           AND conname IN (
                               'chk_booking_status',
                               'chk_provider_comment_status',
                               'chk_client_cancellation_note_status'
                           )
                        """)
                .getResultList();

        assertThat(rows)
                .as("all three constraints must exist on bookings")
                .hasSize(3);
        for (Object[] row : rows) {
            assertThat((Boolean) row[1])
                    .as("constraint %s must be convalidated (V115 VALIDATE CONSTRAINT ran)", row[0])
                    .isTrue();
        }
    }

    private Booking.BookingBuilder baseBuilder(BookingStatus status) {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        return Booking.builder()
                .client(client)
                .master(master)
                .masterService(masterService)
                .salon(null)
                .status(status)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .idempotencyKey(UUID.randomUUID().toString());
    }
}
