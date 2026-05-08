package com.beautica.booking.entity;

import com.beautica.auth.Role;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BookingEntityJpaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestEntityManager em;

    private User client;
    private Master master;
    private MasterServiceAssignment masterService;

    @BeforeEach
    void setUp() {
        User clientUser = new User(
                "client-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.CLIENT,
                "Anna",
                "Kovalenko",
                "+380501111111"
        );
        em.persist(clientUser);
        client = clientUser;

        User masterUser = new User(
                "master-" + UUID.randomUUID() + "@example.com",
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

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Gel Manicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("450.00"))
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
    @DisplayName("should_persistAndRetrieveBooking_withAllFields")
    void should_persistAndRetrieveBooking_withAllFields() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = buildBooking(client, master, masterService, startsAt, endsAt);
        booking = em.persist(booking);

        em.flush();
        em.clear();

        Booking loaded = em.find(Booking.class, booking.getId());

        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(loaded.getPriceAtBooking().compareTo(new BigDecimal("450.00"))).isZero();
        assertThat(loaded.getDurationMinutesAtBooking()).isEqualTo(60);
        assertThat(loaded.getStartsAt()).isNotNull();
        assertThat(loaded.getIdempotencyKey()).isEqualTo("test-key-123");
        assertThat(loaded.getClientComment()).isEqualTo("Please be on time");
        assertThat(loaded.getCancellationReason()).isNull();
        assertThat(loaded.getProviderComment()).isNull();
        assertThat(loaded.getSalon()).isNull();
    }

    @Test
    @DisplayName("should_throwException_when_overlappingActiveBookingsForSameMaster")
    void should_throwException_when_overlappingActiveBookingsForSameMaster() {
        OffsetDateTime firstStart = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime firstEnd = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        // Use distinct idempotency keys so this test exercises the GiST exclusion
        // constraint, not the unique idempotency-key constraint.
        Booking first = buildBooking(client, master, masterService, firstStart, firstEnd,
                UUID.randomUUID().toString());
        em.persist(first);
        em.flush();

        OffsetDateTime secondStart = OffsetDateTime.of(2026, 6, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime secondEnd = OffsetDateTime.of(2026, 6, 1, 11, 30, 0, 0, ZoneOffset.UTC);

        Booking second = buildBooking(client, master, masterService, secondStart, secondEnd,
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> em.persistAndFlush(second))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @ParameterizedTest(name = "BookingStatus.{0} persists and reads back correctly")
    @EnumSource(BookingStatus.class)
    @DisplayName("all BookingStatus values survive a DB round-trip")
    void should_persistAndRetrieveAllStatuses(BookingStatus status) {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);

        Booking booking = Booking.builder()
                .client(client).master(master).masterService(masterService).salon(null)
                .status(status)
                .startsAt(startsAt).endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60).bufferMinutesAtBooking(0)
                .idempotencyKey("status-key-" + status.name() + "-" + UUID.randomUUID())
                .build();
        em.persist(booking);
        em.flush();
        em.clear();

        Booking loaded = em.find(Booking.class, booking.getId());
        assertThat(loaded.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("chk_booking_interval — ends_at must be strictly after starts_at")
    void should_rejectBooking_when_endsAtEqualsStartsAt() {
        OffsetDateTime ts = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        Booking booking = Booking.builder()
                .client(client).master(master).masterService(masterService).salon(null)
                .status(BookingStatus.PENDING)
                .startsAt(ts).endsAt(ts)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60).bufferMinutesAtBooking(0)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(booking))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_booking_duration_positive — duration_minutes_at_booking must be > 0")
    void should_rejectBooking_when_durationIsZero() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        Booking booking = Booking.builder()
                .client(client).master(master).masterService(masterService).salon(null)
                .status(BookingStatus.PENDING)
                .startsAt(startsAt).endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(0).bufferMinutesAtBooking(0)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(booking))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_booking_price_non_negative — price_at_booking must be >= 0")
    void should_rejectBooking_when_priceIsNegative() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        Booking booking = Booking.builder()
                .client(client).master(master).masterService(masterService).salon(null)
                .status(BookingStatus.PENDING)
                .startsAt(startsAt).endsAt(endsAt)
                .priceAtBooking(new BigDecimal("-1.00"))
                .durationMinutesAtBooking(60).bufferMinutesAtBooking(0)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(booking))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_cancellation_reason_status — non-null reason rejected for non-terminal PENDING status")
    void should_rejectBooking_when_cancellationReasonSetOnPendingStatus() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        Booking booking = Booking.builder()
                .client(client).master(master).masterService(masterService).salon(null)
                .status(BookingStatus.PENDING)
                .startsAt(startsAt).endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60).bufferMinutesAtBooking(0)
                .idempotencyKey(UUID.randomUUID().toString())
                .cancellationReason(CancellationReason.OTHER)
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(booking))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("uq_client_idempotency_key_active — duplicate key for same client in PENDING status is rejected")
    void should_rejectBooking_when_duplicateIdempotencyKeyForActiveBooking() {
        OffsetDateTime startsAt1 = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt1 = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        String sharedKey = "shared-idem-key-" + UUID.randomUUID();

        Booking first = buildBooking(client, master, masterService, startsAt1, endsAt1, sharedKey);
        em.persist(first);
        em.flush();

        OffsetDateTime startsAt2 = OffsetDateTime.of(2026, 6, 2, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt2 = OffsetDateTime.of(2026, 6, 2, 11, 0, 0, 0, ZoneOffset.UTC);
        Booking second = buildBooking(client, master, masterService, startsAt2, endsAt2, sharedKey);

        assertThatThrownBy(() -> em.persistAndFlush(second))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    private Booking buildBooking(
            User client,
            Master master,
            MasterServiceAssignment masterService,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt
    ) {
        return buildBooking(client, master, masterService, startsAt, endsAt, "test-key-123");
    }

    private Booking buildBooking(
            User client,
            Master master,
            MasterServiceAssignment masterService,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String idempotencyKey
    ) {
        return Booking.builder()
                .client(client)
                .master(master)
                .masterService(masterService)
                .salon(null)
                .status(BookingStatus.PENDING)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .idempotencyKey(idempotencyKey)
                .cancellationReason(null)
                .clientComment("Please be on time")
                .providerComment(null)
                .build();
    }
}
