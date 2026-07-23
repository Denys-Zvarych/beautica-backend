package com.beautica.booking.entity;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-1 (V124/V125) — persistence contract for the multi-service {@code appointments} aggregate
 * header and the additive nullable {@code bookings.appointment_id} FK.
 *
 * <p>There is no service / endpoint / status-transition logic in BE-1, so the entire contract this
 * phase introduces lives at the persistence + mapping layer. This IT proves it against real
 * PostgreSQL via {@link AbstractDataJpaTest} (Testcontainers) — the DB CHECK constraints copied
 * from {@code bookings} are DB-level and would never fire under {@code ddl-auto=validate} or an
 * in-memory database.
 *
 * <p>{@code ddl-auto=validate} itself (the {@link Appointment} entity/column mapping matching the
 * V124 schema, and {@link Booking#appointment} matching V125) is already exercised on every boot of
 * this and every other {@code @DataJpaTest} / {@code @SpringBootTest} in the module — a mapping
 * mismatch fails the context load before any test body runs, so no dedicated assertion is added for
 * it here.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Appointment round-trips every column (status, notes, source default 'APP', guest identity,
 *       tokens, audit timestamps).</li>
 *   <li>A Booking links to an Appointment (appointment_id set) AND persists with appointment = null
 *       (legacy backward-compat path) — both succeed.</li>
 *   <li>The CHECKs mirrored from {@code bookings} fire on {@code appointments}: invalid status,
 *       invalid source, provider_comment / client_cancellation_note / cancellation_reason
 *       status-coupling, guest_phone E.164 format, the partial-unique cancel_token index, and the
 *       partial-unique {@code (client_id, idempotency_key)} dedup index (BE-1 idempotency guard,
 *       mirroring bookings' {@code uq_client_idempotency_key_active}).</li>
 *   <li>The deferred polymorphic guest-fields CHECK {@code chk_appointment_guest_fields} added in
 *       BE-3 (V126): APP ⇒ client_id NOT NULL + no guest identity + no cancel token; LINK ⇒ the
 *       inverse, with V91's cancel-token-by-status relaxation mirrored.</li>
 * </ol>
 */
class AppointmentEntityIT extends AbstractDataJpaTest {

    @Autowired
    private TestEntityManager em;

    private static final AtomicInteger SORT_ORDER_SEQ = new AtomicInteger(240_000);

    private User client;
    private Master master;
    private MasterServiceAssignment masterService;

    @BeforeEach
    void setUp() {
        User clientUser = new User(
                "appointment-client-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.CLIENT,
                "Anna",
                "Kovalenko",
                "+380501111111"
        );
        em.persist(clientUser);
        client = clientUser;

        User masterUser = new User(
                "appointment-master-" + UUID.randomUUID() + "@example.com",
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

    // ── 1. Appointment round-trip ─────────────────────────────────────────────

    @Test
    @DisplayName("V124/V126 mapping — a LINK (guest) Appointment persists every guest column and round-trips")
    void should_persistAndRetrieveGuestAppointment_withAllFields() {
        UUID cancelToken = UUID.randomUUID();
        // A LINK visit has NO registered account: chk_appointment_guest_fields (V126) requires
        // client_id NULL + guest identity + a cancel token on an active (CONFIRMED) row. This is the
        // valid all-guest-columns shape — the earlier fixture set BOTH client_id and the guest
        // identity, which V126 now (correctly) rejects. The account-bound columns round-trip in the
        // APP twin below.
        Appointment appointment = Appointment.builder()
                .client(null)
                .salon(null)
                .status(BookingStatus.CONFIRMED)
                .clientComment("Please arrive 5 minutes early")
                .idempotencyKey("appt-idem-" + UUID.randomUUID())
                .cancelToken(cancelToken)
                .bookingSource(BookingSource.LINK)
                .guestName("Iryna")
                .guestSurname("Melnyk")
                .guestPhone("+380631234567")
                .build();

        em.persist(appointment);
        em.flush();
        em.clear();

        Appointment loaded = em.find(Appointment.class, appointment.getId());

        assertThat(loaded.getId()).as("generated id").isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(loaded.getClientComment()).isEqualTo("Please arrive 5 minutes early");
        assertThat(loaded.getIdempotencyKey()).isEqualTo(appointment.getIdempotencyKey());
        assertThat(loaded.getCancelToken()).isEqualTo(cancelToken);
        assertThat(loaded.getBookingSource()).isEqualTo(BookingSource.LINK);
        assertThat(loaded.getGuestName()).isEqualTo("Iryna");
        assertThat(loaded.getGuestSurname()).isEqualTo("Melnyk");
        assertThat(loaded.getGuestPhone()).isEqualTo("+380631234567");
        assertThat(loaded.getClient()).as("a LINK visit carries no registered client (V126)").isNull();
        assertThat(loaded.getSalon()).isNull();
        assertThat(loaded.getCreatedAt()).as("@CreationTimestamp populated").isNotNull();
        assertThat(loaded.getUpdatedAt()).as("@UpdateTimestamp populated").isNotNull();
    }

    @Test
    @DisplayName("V124/V126 mapping — an APP Appointment persists its account-bound columns and round-trips")
    void should_persistAndRetrieveAppAppointment_withAllFields() {
        // The APP twin of the LINK round-trip above: chk_appointment_guest_fields (V126) requires an
        // APP visit to carry a client_id and NO guest identity / cancel token.
        Appointment appointment = Appointment.builder()
                .client(client)
                .salon(null)
                .status(BookingStatus.CONFIRMED)
                .clientComment("Please use the side entrance")
                .idempotencyKey("appt-idem-" + UUID.randomUUID())
                .bookingSource(BookingSource.APP)
                .build();

        Appointment saved = em.persistFlushFind(appointment);

        assertThat(saved.getClient().getId()).isEqualTo(client.getId());
        assertThat(saved.getBookingSource()).isEqualTo(BookingSource.APP);
        assertThat(saved.getClientComment()).isEqualTo("Please use the side entrance");
        assertThat(saved.getIdempotencyKey()).isEqualTo(appointment.getIdempotencyKey());
        assertThat(saved.getGuestName()).as("an APP visit carries no guest identity (V126)").isNull();
        assertThat(saved.getGuestPhone()).isNull();
        assertThat(saved.getCancelToken()).as("an APP visit carries no cancel token (V126)").isNull();
    }

    @Test
    @DisplayName("booking_source DEFAULT 'APP' — an Appointment built without an explicit source reads back APP")
    void should_defaultSourceToApp_when_notExplicitlySet() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .build();

        Appointment saved = em.persistFlushFind(appointment);

        assertThat(saved.getBookingSource())
                .as("@Builder.Default supplies APP so the NOT NULL column is always populated")
                .isEqualTo(BookingSource.APP);
    }

    // ── 2. Booking ⇄ Appointment link (both directions of the nullable FK) ─────

    @Test
    @DisplayName("V125 FK — a Booking links to an Appointment (appointment_id set) and round-trips")
    void should_linkBookingToAppointment_when_appointmentIdSet() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .build();
        em.persist(appointment);

        Booking booking = baseBooking(BookingStatus.CONFIRMED);
        booking.setAppointment(appointment);
        em.persist(booking);
        em.flush();
        em.clear();

        Booking loaded = em.find(Booking.class, booking.getId());

        assertThat(loaded.getAppointment()).as("owning-side FK hydrated").isNotNull();
        assertThat(loaded.getAppointment().getId()).isEqualTo(appointment.getId());
    }

    @Test
    @DisplayName("V125 backward-compat — a legacy single-service Booking persists with appointment = null")
    void should_persistBooking_when_appointmentIsNull() {
        Booking booking = baseBooking(BookingStatus.CONFIRMED);
        // appointment intentionally left unset — the legacy single-service path.

        Booking saved = em.persistFlushFind(booking);

        assertThat(saved.getAppointment())
                .as("nullable FK — legacy bookings carry appointment_id = NULL")
                .isNull();
    }

    // ── 3. CHECK constraints mirrored from bookings fire on appointments ───────

    @Test
    @DisplayName("chk_appointment_status — a status value outside the 5-value set is rejected by the DB")
    void should_rejectAppointment_when_statusValueInvalid() {
        // The enum-typed entity cannot express an out-of-set status, so this exercises the raw
        // DB CHECK via a native INSERT — the only way a bad value could ever reach the column.
        assertThatThrownBy(() -> em.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO appointments (id, status, booking_source, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'BOGUS_STATUS', 'APP', now(), now())
                        """)
                .executeUpdate())
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_source — a booking_source outside {APP,LINK} is rejected by the DB")
    void should_rejectAppointment_when_sourceValueInvalid() {
        assertThatThrownBy(() -> em.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO appointments (id, status, booking_source, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'CONFIRMED', 'SMS', now(), now())
                        """)
                .executeUpdate())
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_provider_comment_status — provider_comment rejected on a CONFIRMED (non-terminal) visit")
    void should_rejectAppointment_when_providerCommentOnConfirmed() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .providerComment("Not allowed on a CONFIRMED visit header")
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(appointment))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_provider_comment_status — provider_comment accepted on DECLINED")
    void should_acceptAppointment_when_providerCommentOnDeclined() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.DECLINED)
                .providerComment("Майстер захворів")
                .build();

        Appointment saved = em.persistFlushFind(appointment);

        assertThat(saved.getProviderComment()).isEqualTo("Майстер захворів");
    }

    @Test
    @DisplayName("chk_appointment_client_cancellation_note_status — client_cancellation_note rejected on a CONFIRMED visit")
    void should_rejectAppointment_when_clientCancellationNoteOnConfirmed() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .clientCancellationNote("Not allowed unless the visit is CANCELLED")
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(appointment))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_client_cancellation_note_status — client_cancellation_note rejected on DECLINED "
            + "(only CANCELLED may carry it — this note is the client's own /cancel note, not a provider one)")
    void should_rejectAppointment_when_clientCancellationNoteOnDeclined() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.DECLINED)
                .clientCancellationNote("A client note must not appear on a provider-declined visit")
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(appointment))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_client_cancellation_note_status — client_cancellation_note accepted on CANCELLED")
    void should_acceptAppointment_when_clientCancellationNoteOnCancelled() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CANCELLED)
                .clientCancellationNote("Змінились плани")
                .build();

        Appointment saved = em.persistFlushFind(appointment);

        assertThat(saved.getClientCancellationNote()).isEqualTo("Змінились плани");
    }

    @Test
    @DisplayName("no CHECK on client_comment — the booking-creation note survives on a CONFIRMED visit "
            + "(deliberately absent constraint, V124 migration comment)")
    void should_acceptAppointment_when_clientCommentOnConfirmed() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .clientComment("Please use the side entrance")
                .build();

        Appointment saved = em.persistFlushFind(appointment);

        assertThat(saved.getClientComment()).isEqualTo("Please use the side entrance");
    }

    @Test
    @DisplayName("chk_appointment_cancellation_reason_status — a reason on a non-terminal CONFIRMED visit is rejected")
    void should_rejectAppointment_when_cancellationReasonOnConfirmed() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .cancellationReason(CancellationReason.OTHER)
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(appointment))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_guest_phone_format — a non-E.164 guest_phone is rejected by the DB")
    void should_rejectAppointment_when_guestPhoneNotE164() {
        // A LINK visit so the row satisfies chk_appointment_guest_fields (V126) in every respect
        // EXCEPT the phone format — isolating chk_appointment_guest_phone_format as the sole violation.
        // (An APP row cannot carry a guest_phone at all under V126, which would mask this constraint.)
        Appointment appointment = guestVisit(BookingStatus.CONFIRMED)
                .cancelToken(UUID.randomUUID())
                .guestPhone("0631234567") // missing leading + → violates ^\+[0-9]{6,18}$
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(appointment))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    // ── V126 — the deferred polymorphic guest-fields CHECK (BE-3) ──────────────

    @Test
    @DisplayName("chk_appointment_guest_fields (V126) — an APP visit carrying a guest identity is rejected")
    void should_rejectAppointment_when_appVisitHasGuestIdentity() {
        Appointment appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .bookingSource(BookingSource.APP)
                .guestName("Should not be on an APP visit")
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(appointment))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_guest_fields (V126) — a LINK visit carrying a client_id is rejected")
    void should_rejectAppointment_when_linkVisitHasClientId() {
        Appointment appointment = Appointment.builder()
                .client(client) // LINK requires client_id NULL
                .status(BookingStatus.CONFIRMED)
                .bookingSource(BookingSource.LINK)
                .guestName("Iryna")
                .guestPhone("+380631234567")
                .cancelToken(UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(appointment))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_guest_fields (V126) — an active (CONFIRMED) LINK visit with a NULL "
            + "cancel_token is rejected")
    void should_rejectLinkVisit_when_confirmedWithoutCancelToken() {
        Appointment stillActive = guestVisit(BookingStatus.CONFIRMED).build(); // no cancel token

        assertThatThrownBy(() -> em.persistAndFlush(stillActive))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_appointment_guest_fields (V126) — a terminal (CANCELLED) LINK visit may drop its "
            + "cancel_token (V91 relaxation, mirrored — the guest-cancel UPDATE nulls it)")
    void should_acceptLinkVisit_when_cancelledWithoutCancelToken() {
        Appointment terminal = guestVisit(BookingStatus.CANCELLED).build(); // no cancel token

        Appointment saved = em.persistFlushFind(terminal);

        assertThat(saved.getCancelToken()).isNull();
    }

    @Test
    @DisplayName("ux_appointments_cancel_token — a duplicate non-null cancel_token is rejected (partial-unique index)")
    void should_rejectAppointment_when_duplicateCancelToken() {
        UUID sharedToken = UUID.randomUUID();

        // Only a LINK visit may carry a cancel_token: chk_appointment_guest_fields (V126) forbids it
        // on an APP row. Both rows are otherwise valid LINK visits (client_id NULL + guest identity),
        // so the ONLY constraint that can fire on the second is ux_appointments_cancel_token.
        Appointment first = guestVisit(BookingStatus.CONFIRMED)
                .cancelToken(sharedToken)
                .build();
        em.persist(first);
        em.flush();

        Appointment second = guestVisit(BookingStatus.CONFIRMED)
                .cancelToken(sharedToken)
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(second))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ux_appointments_cancel_token — two visits with a NULL cancel_token both persist "
            + "(partiality: uniqueness is only over non-NULL rows)")
    void should_allowMultipleAppointments_when_cancelTokenIsNull() {
        Appointment first = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .build();
        Appointment second = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .build();

        em.persist(first);
        em.persist(second);
        em.flush();

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getCancelToken()).isNull();
        assertThat(second.getCancelToken()).isNull();
    }

    @Test
    @DisplayName("ux_appointments_client_idempotency_key_active — a duplicate (client_id, idempotency_key) "
            + "on a CONFIRMED visit is rejected (partial-unique dedup index, mirroring bookings)")
    void should_rejectAppointment_when_duplicateIdempotencyKeyOnConfirmed() {
        String sharedKey = "appt-idem-" + UUID.randomUUID();

        Appointment first = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .idempotencyKey(sharedKey)
                .build();
        em.persist(first);
        em.flush();

        Appointment second = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .idempotencyKey(sharedKey)
                .build();

        assertThatThrownBy(() -> em.persistAndFlush(second))
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ux_appointments_client_idempotency_key_active — the same idempotency_key persists again "
            + "on a non-CONFIRMED visit (partiality: the index only constrains CONFIRMED rows)")
    void should_allowDuplicateIdempotencyKey_when_visitNotConfirmed() {
        String sharedKey = "appt-idem-" + UUID.randomUUID();

        Appointment first = Appointment.builder()
                .client(client)
                .status(BookingStatus.CANCELLED)
                .idempotencyKey(sharedKey)
                .build();
        Appointment second = Appointment.builder()
                .client(client)
                .status(BookingStatus.CANCELLED)
                .idempotencyKey(sharedKey)
                .build();

        em.persist(first);
        em.persist(second);
        em.flush();

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
    }

    /**
     * A valid LINK (guest) visit builder: {@code client_id} NULL + guest identity, satisfying
     * chk_appointment_guest_fields (V126) apart from the token/format the caller then tweaks. The
     * caller adds a {@code cancelToken} for an active (CONFIRMED) row; a terminal-status row may omit
     * it (V91 relaxation, mirrored).
     */
    private Appointment.AppointmentBuilder guestVisit(BookingStatus status) {
        return Appointment.builder()
                .client(null)
                .status(status)
                .bookingSource(BookingSource.LINK)
                .guestName("Iryna")
                .guestPhone("+380631234567");
    }

    private Booking baseBooking(BookingStatus status) {
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
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
    }
}
