package com.beautica.notification.inapp.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.support.IndexCapabilityProbe;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 332 — schema + persistence contract for the in-app notification feed
 * ({@code V181__create_in_app_notification.sql}). Real PostgreSQL via {@link AbstractDataJpaTest}
 * (Testcontainers) — the CHECK constraint, the FK cascades and the partial index are all DB-level
 * and would never fire under {@code ddl-auto=validate} or an in-memory database.
 *
 * <p>Every write in this class goes through {@link InAppNotificationRepository#insertIgnoringDuplicate}
 * or raw JDBC, never {@code repository.save(entity)} — matching the production write path (phase
 * 333 always uses the {@code ON CONFLICT DO NOTHING} insert), and letting the dedup/CHECK tests
 * control every column precisely.
 */
class InAppNotificationRepositoryIT extends AbstractDataJpaTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InAppNotificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SORT_ORDER_SEQ = new AtomicInteger(340_000);

    private User client;
    private User teammate;
    private Booking booking;
    private Appointment appointment;
    private Salon salon;

    @BeforeEach
    void setUp() {
        client = persistUser("inapp-client-" + UUID.randomUUID() + "@example.com", Role.CLIENT);
        teammate = persistUser("inapp-teammate-" + UUID.randomUUID() + "@example.com", Role.SALON_MASTER);
        User masterUser = persistUser("inapp-master-" + UUID.randomUUID() + "@example.com", Role.INDEPENDENT_MASTER);
        User salonOwner = persistUser("inapp-owner-" + UUID.randomUUID() + "@example.com", Role.SALON_OWNER);

        Master master = Master.builder()
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

        MasterServiceAssignment masterService = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(masterService);

        salon = Salon.builder()
                .cityId(testCityId())
                .owner(salonOwner)
                .name("Test Salon " + UUID.randomUUID())
                .isActive(true)
                .build();
        em.persist(salon);

        appointment = Appointment.builder()
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .build();
        em.persist(appointment);

        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC);
        booking = Booking.builder()
                .client(client)
                .master(master)
                .masterService(masterService)
                .salon(null)
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .idempotencyKey("inapp-idem-" + UUID.randomUUID())
                .build();
        em.persist(booking);

        em.flush();
    }

    private User persistUser(String email, Role role) {
        User user = new User(email, "$2a$10$hashedpassword", role, "Anna", "Kovalenko", "+380501111111");
        em.persist(user);
        return user;
    }

    // ── Dedup (idempotency) ─────────────────────────────────────────────────

    @Test
    @DisplayName("in_app_notification_dedup_uq — a second insertIgnoringDuplicate with the same "
            + "(recipient, dedup_key) is a no-op via ON CONFLICT DO NOTHING")
    void should_ignoreDuplicate_when_sameDedupKey() {
        String dedupKey = "BOOKING_CREATED:" + booking.getId();

        int first = insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null, dedupKey);
        int second = insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null, dedupKey);

        assertThat(first).as("first insert creates the row").isEqualTo(1);
        assertThat(second).as("second insert is suppressed by the conflict guard").isEqualTo(0);
        assertThat(countRows()).as("exactly one row exists, not two").isEqualTo(1);
    }

    @Test
    @DisplayName("in_app_notification_dedup_uq is scoped PER RECIPIENT — the same dedup_key for a "
            + "different recipient inserts a second, independent row")
    void should_insertBothRows_when_sameDedupKeyDifferentRecipient() {
        String dedupKey = "BOOKING_CREATED:" + booking.getId();

        int forClient = insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null, dedupKey);
        int forTeammate = insert(teammate.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null, dedupKey);

        assertThat(forClient).isEqualTo(1);
        assertThat(forTeammate).isEqualTo(1);
        assertThat(countRows()).isEqualTo(2);
    }

    // ── CHECK constraint ────────────────────────────────────────────────────

    @Test
    @DisplayName("in_app_notification_type_chk — a raw INSERT with a type outside the 10-value set is rejected by the DB")
    void should_rejectUnknownType_when_rawInsert() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, dedup_key)
                VALUES (?, ?, 'BOGUS_TYPE', ?, ?)
                """, UUID.randomUUID(), client.getId(), booking.getId(), "BOGUS_TYPE:" + UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    // ── dedup_key shape CHECK (audit-fix cycle 1, finding 5) ───────────────────

    @Test
    @DisplayName("in_app_notification_dedup_key_chk — TYPE:id is accepted (the common-case format)")
    void should_insertDedupKey_when_typeColonIdFormat() {
        int inserted = insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + booking.getId());

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("in_app_notification_dedup_key_chk — TYPE:id:epochSeconds is accepted "
            + "(BOOKING_RESCHEDULED's new-start-time suffix)")
    void should_insertDedupKey_when_rescheduleEpochSecondsSuffix() {
        String dedupKey = "BOOKING_RESCHEDULED:" + booking.getId() + ":" + Instant.now().getEpochSecond();

        int inserted = insert(client.getId(), InAppNotificationType.BOOKING_RESCHEDULED, booking.getId(), null, null, null, dedupKey);

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("in_app_notification_dedup_key_chk — TYPE:salonId:userId is accepted (INVITE_ACCEPTED's format)")
    void should_insertDedupKey_when_inviteAcceptedSalonAndUserIdFormat() {
        int inserted = repository.insertIgnoringDuplicate(UUID.randomUUID(), teammate.getId(),
                InAppNotificationType.INVITE_ACCEPTED.name(), null, null, salon.getId(), client.getId(),
                "INVITE_ACCEPTED:" + salon.getId() + ":" + client.getId());

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("in_app_notification_dedup_key_chk — a raw INSERT with non-id characters (an email) in dedup_key is rejected")
    void should_rejectDedupKeyWithNonIdCharacters_when_rawInsert() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, dedup_key)
                VALUES (?, ?, 'BOOKING_CREATED', ?, ?)
                """, UUID.randomUUID(), client.getId(), booking.getId(), "client@example.com"))
                .isInstanceOf(DataAccessException.class);
    }

    // ── FK cascades ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("recipient_user_id FK ON DELETE CASCADE — deleting the recipient user deletes their feed rows")
    void should_cascade_when_userDeleted() {
        insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + booking.getId());
        assertThat(countRows()).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM bookings WHERE id = ?", booking.getId());
        jdbcTemplate.update("DELETE FROM appointments WHERE id = ?", appointment.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", client.getId());

        assertThat(countRows())
                .as("the row must be gone once its recipient user is deleted")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("booking_id FK ON DELETE CASCADE — deleting the booking deletes rows pointing at it")
    void should_cascade_when_bookingDeleted() {
        insert(teammate.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + booking.getId());
        assertThat(countRows()).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM bookings WHERE id = ?", booking.getId());

        assertThat(countRows())
                .as("the row must be gone once the booking it points at is deleted")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("appointment_id FK ON DELETE CASCADE — deleting the appointment deletes rows pointing at it")
    void should_cascade_when_appointmentDeleted() {
        insert(teammate.getId(), InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, null, appointment.getId(), null, null,
                "BOOKING_CANCELLED_BY_CLIENT:" + appointment.getId());
        assertThat(countRows()).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM bookings WHERE id = ?", booking.getId());
        jdbcTemplate.update("DELETE FROM appointments WHERE id = ?", appointment.getId());

        assertThat(countRows())
                .as("the row must be gone once the appointment it points at is deleted")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("salon_id FK ON DELETE SET NULL — deleting the salon nulls salon_id but keeps the row")
    void should_setSalonIdNull_when_salonDeleted() {
        UUID id = UUID.randomUUID();
        repository.insertIgnoringDuplicate(id, teammate.getId(), InAppNotificationType.INVITE_ACCEPTED.name(),
                null, null, salon.getId(), client.getId(), "INVITE_ACCEPTED:" + salon.getId() + ":" + client.getId());

        jdbcTemplate.update("DELETE FROM salons WHERE id = ?", salon.getId());

        InAppNotification reloaded = em.find(InAppNotification.class, id);
        assertThat(reloaded.getSalonId()).as("ON DELETE SET NULL, not CASCADE — the row survives").isNull();
    }

    @Test
    @DisplayName("subject_user_id FK ON DELETE SET NULL — deleting the subject (teammate) user nulls "
            + "subject_user_id but keeps the recipient's row (audit-fix cycle 1, finding 7: was CASCADE, "
            + "which would have deleted a DIFFERENT user's — the recipient's — feed history)")
    void should_surviveWithNullSubject_when_subjectUserDeleted() {
        UUID id = UUID.randomUUID();
        repository.insertIgnoringDuplicate(id, salon.getOwner().getId(), InAppNotificationType.INVITE_ACCEPTED.name(),
                null, null, salon.getId(), teammate.getId(), "INVITE_ACCEPTED:" + salon.getId() + ":" + teammate.getId());
        assertThat(countRows()).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", teammate.getId());

        assertThat(countRows())
                .as("the recipient's row must survive the subject (teammate) user's deletion")
                .isEqualTo(1);
        InAppNotification reloaded = em.find(InAppNotification.class, id);
        assertThat(reloaded.getSubjectUserId())
                .as("ON DELETE SET NULL, not CASCADE")
                .isNull();
    }

    // ── shape CHECK (audit-fix cycle 1, finding 8) ─────────────────────────────

    @ParameterizedTest
    @EnumSource(value = InAppNotificationType.class, names = "INVITE_ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("in_app_notification_shape_chk — every booking/review type requires booking_id or "
            + "appointment_id; a raw INSERT with neither set is rejected")
    void should_rejectWithNoBookingOrAppointment_when_rawInsert(InAppNotificationType type) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, dedup_key)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), client.getId(), type.name(), type.name() + ":" + UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @EnumSource(value = InAppNotificationType.class, names = "INVITE_ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("in_app_notification_shape_chk — booking_id alone satisfies every booking/review type")
    void should_acceptWithBookingIdOnly_when_rawInsert(InAppNotificationType type) {
        int inserted = repository.insertIgnoringDuplicate(UUID.randomUUID(), client.getId(), type.name(),
                booking.getId(), null, null, null, type.name() + ":" + booking.getId());

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("in_app_notification_shape_chk — INVITE_ACCEPTED with only salon_id set is accepted "
            + "(exempted: no shape requirement, see the migration's constraint comment)")
    void should_acceptInviteAcceptedWithOnlySalonId_when_rawInsert() {
        int inserted = repository.insertIgnoringDuplicate(UUID.randomUUID(), teammate.getId(),
                InAppNotificationType.INVITE_ACCEPTED.name(), null, null, salon.getId(), null,
                "INVITE_ACCEPTED:" + salon.getId());

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("in_app_notification_shape_chk — INVITE_ACCEPTED with only subject_user_id set is accepted "
            + "(exempted: no shape requirement, see the migration's constraint comment)")
    void should_acceptInviteAcceptedWithOnlySubjectUserId_when_rawInsert() {
        int inserted = repository.insertIgnoringDuplicate(UUID.randomUUID(), salon.getOwner().getId(),
                InAppNotificationType.INVITE_ACCEPTED.name(), null, null, null, teammate.getId(),
                "INVITE_ACCEPTED:" + teammate.getId());

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("in_app_notification_shape_chk — INVITE_ACCEPTED survives BOTH salon_id and subject_user_id "
            + "going null via two independent SET NULL cascades (the exact scenario that ruled out a "
            + "mandatory 'salon_id OR subject_user_id' shape requirement for this type)")
    void should_surviveWithBothNull_when_subjectUserAndSalonBothDeleted() {
        UUID id = UUID.randomUUID();
        repository.insertIgnoringDuplicate(id, salon.getOwner().getId(), InAppNotificationType.INVITE_ACCEPTED.name(),
                null, null, salon.getId(), teammate.getId(),
                "INVITE_ACCEPTED:" + salon.getId() + ":" + teammate.getId());

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", teammate.getId());
        jdbcTemplate.update("DELETE FROM salons WHERE id = ?", salon.getId());

        InAppNotification reloaded = em.find(InAppNotification.class, id);
        assertThat(reloaded)
                .as("neither SET NULL cascade may violate in_app_notification_shape_chk")
                .isNotNull();
        assertThat(reloaded.getSubjectUserId()).isNull();
        assertThat(reloaded.getSalonId()).isNull();
    }

    // ── BEFORE INSERT trigger guard (audit-fix cycle 2, finding 1) ────────────

    @Test
    @DisplayName("in_app_notification_reject_empty_invite_accepted_trg — an INSERT of an INVITE_ACCEPTED "
            + "row with both salon_id and subject_user_id null is rejected by the BEFORE INSERT trigger")
    void should_rejectInviteAcceptedWithNoSalonAndNoSubject_when_insert() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, dedup_key)
                VALUES (?, ?, 'INVITE_ACCEPTED', ?)
                """, UUID.randomUUID(), teammate.getId(), "INVITE_ACCEPTED:" + UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    // should_surviveWithBothNull_when_subjectUserAndSalonBothDeleted (above, in the shape-CHECK
    // section) already proves the complementary half of this guard: the trigger is BEFORE INSERT
    // only, so the two independent SET NULL cascades that leave an existing row with both columns
    // null are never re-validated and never abort.

    // ── Unread count ────────────────────────────────────────────────────────

    @Test
    @DisplayName("countByRecipientUserIdAndReadAtIsNull — counts only the recipient's unread rows")
    void should_countOnlyUnread_when_mixedReadState() {
        insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + UUID.randomUUID());
        UUID readId = UUID.randomUUID();
        repository.insertIgnoringDuplicate(readId, client.getId(), InAppNotificationType.BOOKING_CREATED.name(),
                booking.getId(), null, null, null, "BOOKING_CREATED:" + UUID.randomUUID());
        repository.markRead(readId, client.getId(), Instant.now());
        // a different recipient's unread row must not be counted
        insert(teammate.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + UUID.randomUUID());

        long unread = repository.countByRecipientUserIdAndReadAtIsNull(client.getId());

        assertThat(unread).as("one unread + one read for this recipient — only the unread one counts").isEqualTo(1);
    }

    @Test
    @DisplayName("acceptance criterion — EXPLAIN of the unread count uses in_app_notification_unread_idx")
    void should_useUnreadIndex_when_explainingUnreadCountQuery() {
        insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + UUID.randomUUID());

        IndexCapabilityProbe probe = new IndexCapabilityProbe(jdbcTemplate, "in_app_notification");
        String plan = probe.explainWithOnly("in_app_notification_unread_idx",
                "SELECT count(*) FROM in_app_notification WHERE recipient_user_id = '"
                        + client.getId() + "' AND read_at IS NULL");

        assertThat(plan)
                .as("the unread-count query must be able to use the partial unread index:\n" + plan)
                .contains("in_app_notification_unread_idx");
    }

    @Test
    @DisplayName("acceptance criterion (finding 9) — EXPLAIN of the feed page query uses in_app_notification_feed_idx")
    void should_useFeedIndex_when_explainingFeedQuery() {
        insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + UUID.randomUUID());

        IndexCapabilityProbe probe = new IndexCapabilityProbe(jdbcTemplate, "in_app_notification");
        String plan = probe.explainWithOnly("in_app_notification_feed_idx",
                "SELECT * FROM in_app_notification WHERE recipient_user_id = '"
                        + client.getId() + "' ORDER BY created_at DESC, id DESC LIMIT 20");

        assertThat(plan)
                .as("the feed page query (findByRecipientUserIdOrderByCreatedAtDescIdDesc) must be able "
                        + "to use the feed index:\n" + plan)
                .contains("in_app_notification_feed_idx");
    }

    // ── partial FK-support index EXPLAIN probes (audit-fix cycle 2, finding 3) ─────────────────

    @Test
    @DisplayName("acceptance criterion — EXPLAIN of a subject_user_id lookup uses in_app_notification_subject_idx")
    void should_useSubjectIndex_when_explainingSubjectLookupQuery() {
        UUID subjectId = teammate.getId();
        repository.insertIgnoringDuplicate(UUID.randomUUID(), salon.getOwner().getId(),
                InAppNotificationType.INVITE_ACCEPTED.name(), null, null, salon.getId(), subjectId,
                "INVITE_ACCEPTED:" + salon.getId() + ":" + subjectId);

        IndexCapabilityProbe probe = new IndexCapabilityProbe(jdbcTemplate, "in_app_notification");
        String plan = probe.explainWithOnly("in_app_notification_subject_idx",
                "SELECT * FROM in_app_notification WHERE subject_user_id = '" + subjectId + "'");

        assertThat(plan)
                .as("a subject_user_id lookup must be able to use the partial subject index:\n" + plan)
                .contains("in_app_notification_subject_idx");
    }

    @Test
    @DisplayName("acceptance criterion — EXPLAIN of a booking_id lookup uses in_app_notification_booking_idx")
    void should_useBookingIndex_when_explainingBookingLookupQuery() {
        insert(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), null, null, null,
                "BOOKING_CREATED:" + UUID.randomUUID());

        IndexCapabilityProbe probe = new IndexCapabilityProbe(jdbcTemplate, "in_app_notification");
        String plan = probe.explainWithOnly("in_app_notification_booking_idx",
                "SELECT * FROM in_app_notification WHERE booking_id = '" + booking.getId() + "'");

        assertThat(plan)
                .as("a booking_id lookup must be able to use the partial booking index:\n" + plan)
                .contains("in_app_notification_booking_idx");
    }

    @Test
    @DisplayName("acceptance criterion — EXPLAIN of an appointment_id lookup uses in_app_notification_appointment_idx")
    void should_useAppointmentIndex_when_explainingAppointmentLookupQuery() {
        insert(teammate.getId(), InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, null, appointment.getId(), null, null,
                "BOOKING_CANCELLED_BY_CLIENT:" + UUID.randomUUID());

        IndexCapabilityProbe probe = new IndexCapabilityProbe(jdbcTemplate, "in_app_notification");
        String plan = probe.explainWithOnly("in_app_notification_appointment_idx",
                "SELECT * FROM in_app_notification WHERE appointment_id = '" + appointment.getId() + "'");

        assertThat(plan)
                .as("an appointment_id lookup must be able to use the partial appointment index:\n" + plan)
                .contains("in_app_notification_appointment_idx");
    }

    @Test
    @DisplayName("acceptance criterion — EXPLAIN of a salon_id lookup uses in_app_notification_salon_idx")
    void should_useSalonIndex_when_explainingSalonLookupQuery() {
        repository.insertIgnoringDuplicate(UUID.randomUUID(), teammate.getId(),
                InAppNotificationType.INVITE_ACCEPTED.name(), null, null, salon.getId(), null,
                "INVITE_ACCEPTED:" + salon.getId());

        IndexCapabilityProbe probe = new IndexCapabilityProbe(jdbcTemplate, "in_app_notification");
        String plan = probe.explainWithOnly("in_app_notification_salon_idx",
                "SELECT * FROM in_app_notification WHERE salon_id = '" + salon.getId() + "'");

        assertThat(plan)
                .as("a salon_id lookup must be able to use the partial salon index:\n" + plan)
                .contains("in_app_notification_salon_idx");
    }

    // ── type/dedup-regex/enum drift guard (audit-fix cycle 2, finding 2) ───────────────────────

    @Test
    @DisplayName("should_keepTypeCheckDedupRegexAndEnumInSync — the 10 type literals in "
            + "in_app_notification_type_chk, the TYPE alternation embedded in "
            + "in_app_notification_dedup_key_chk's regex, and InAppNotificationType.values() are hand-kept "
            + "in sync (migration comment); this makes any future drift between the three a red build "
            + "instead of a silent runtime gap")
    void should_keepTypeCheckDedupRegexAndEnumInSync() {
        String typeChkDef = constraintDef("in_app_notification_type_chk");
        String dedupChkDef = constraintDef("in_app_notification_dedup_key_chk");

        Set<String> typeChkTypes = extractTypeChkLiterals(typeChkDef);
        Set<String> dedupChkTypes = extractDedupChkAlternation(dedupChkDef);
        Set<String> enumTypes = Arrays.stream(InAppNotificationType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(enumTypes).as("sanity: the enum itself must not be empty").isNotEmpty();
        assertThat(typeChkTypes)
                .as("in_app_notification_type_chk's literal set must exactly match the enum:\n" + typeChkDef)
                .hasSize(enumTypes.size())
                .isEqualTo(enumTypes);
        assertThat(dedupChkTypes)
                .as("in_app_notification_dedup_key_chk's regex TYPE alternation must exactly match the enum:\n" + dedupChkDef)
                .hasSize(enumTypes.size())
                .isEqualTo(enumTypes);
    }

    /** Every {@code 'LITERAL'::character varying} element of an {@code IN (...)}-style CHECK's {@code ANY(ARRAY[...])}. */
    private static final Pattern TYPE_CHK_LITERAL = Pattern.compile("'([A-Z][A-Z0-9_]*)'::character varying");

    /** Any single-quoted literal chunk, in appearance order — used to reconstruct a {@code ||}-concatenated regex. */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']*)'");

    /** The {@code ^(A|B|C):} alternation at the head of the reconstructed dedup_key regex. */
    private static final Pattern DEDUP_ALTERNATION = Pattern.compile("^\\^\\(([A-Z_|]+)\\):");

    private String constraintDef(String conname) {
        return jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, conname);
    }

    private static Set<String> extractTypeChkLiterals(String constraintDef) {
        Set<String> result = new HashSet<>();
        Matcher matcher = TYPE_CHK_LITERAL.matcher(constraintDef);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * pg_get_constraintdef deparses a {@code ||} chain of string literals as separate quoted chunks
     * (Postgres does not constant-fold them into one literal), so this reassembles the ORIGINAL regex
     * text by concatenating every quoted chunk in the order it appears, then pulls the leading
     * {@code ^(TYPE1|TYPE2|...):} alternation off the front of that reassembled string.
     */
    private static Set<String> extractDedupChkAlternation(String constraintDef) {
        StringBuilder reconstructed = new StringBuilder();
        Matcher chunks = QUOTED_LITERAL.matcher(constraintDef);
        while (chunks.find()) {
            reconstructed.append(chunks.group(1));
        }

        Matcher alternation = DEDUP_ALTERNATION.matcher(reconstructed.toString());
        if (!alternation.find()) {
            throw new IllegalStateException(
                    "could not locate the '^(TYPE|...):' alternation in the reconstructed dedup_key_chk "
                            + "regex — has its shape changed?\nreconstructed: " + reconstructed);
        }
        return new HashSet<>(Arrays.asList(alternation.group(1).split("\\|")));
    }

    // ── markRead ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markRead — only the owning recipient can mark a row read; a foreign recipient id updates 0 rows")
    void should_markReadOnlyOwnRow_when_otherRecipientId() {
        UUID id = UUID.randomUUID();
        repository.insertIgnoringDuplicate(id, client.getId(), InAppNotificationType.BOOKING_CREATED.name(),
                booking.getId(), null, null, null, "BOOKING_CREATED:" + UUID.randomUUID());

        int foreignAttempt = repository.markRead(id, teammate.getId(), Instant.now());
        assertThat(foreignAttempt).as("a different recipient id must never mark someone else's row read").isEqualTo(0);
        assertThat(em.find(InAppNotification.class, id).getReadAt()).isNull();

        int ownAttempt = repository.markRead(id, client.getId(), Instant.now());
        assertThat(ownAttempt).isEqualTo(1);
        assertThat(em.find(InAppNotification.class, id).getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("markRead — a replay against an already-read row updates 0 rows (idempotent by consequence)")
    void should_updateZeroRows_when_alreadyRead() {
        UUID id = UUID.randomUUID();
        repository.insertIgnoringDuplicate(id, client.getId(), InAppNotificationType.BOOKING_CREATED.name(),
                booking.getId(), null, null, null, "BOOKING_CREATED:" + UUID.randomUUID());
        assertThat(repository.markRead(id, client.getId(), Instant.now())).isEqualTo(1);

        int replay = repository.markRead(id, client.getId(), Instant.now());

        assertThat(replay).isEqualTo(0);
    }

    // ── markAllRead ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("markAllRead — marks only rows created at or before the cutoff; a later row stays unread")
    void should_markAllRead_upToCutoffOnly() {
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID beforeCutoff = insertWithCreatedAt(client.getId(), base.minusSeconds(60));
        UUID atCutoff = insertWithCreatedAt(client.getId(), base);
        UUID afterCutoff = insertWithCreatedAt(client.getId(), base.plusSeconds(60));

        int updated = repository.markAllRead(client.getId(), base, Instant.now());

        assertThat(updated).as("only the two rows at or before the cutoff are marked").isEqualTo(2);
        assertThat(em.find(InAppNotification.class, beforeCutoff).getReadAt()).isNotNull();
        assertThat(em.find(InAppNotification.class, atCutoff).getReadAt()).isNotNull();
        assertThat(em.find(InAppNotification.class, afterCutoff).getReadAt())
                .as("a row created AFTER the cutoff must remain unread")
                .isNull();
    }

    @Test
    @DisplayName("markAllRead — never touches another recipient's rows")
    void should_notMarkOtherRecipientRows_when_markAllRead() {
        UUID clientRow = insertWithCreatedAt(client.getId(), Instant.now().minusSeconds(60));
        UUID teammateRow = insertWithCreatedAt(teammate.getId(), Instant.now().minusSeconds(60));

        repository.markAllRead(client.getId(), Instant.now(), Instant.now());

        assertThat(em.find(InAppNotification.class, clientRow).getReadAt()).isNotNull();
        assertThat(em.find(InAppNotification.class, teammateRow).getReadAt())
                .as("markAllRead for the client must not touch the teammate's row")
                .isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private int insert(UUID recipientUserId, InAppNotificationType type, UUID bookingId, UUID appointmentId,
                        UUID salonId, UUID subjectUserId, String dedupKey) {
        return repository.insertIgnoringDuplicate(UUID.randomUUID(), recipientUserId, type.name(),
                bookingId, appointmentId, salonId, subjectUserId, dedupKey);
    }

    /**
     * The total row count, via raw SQL — {@link InAppNotificationRepository} no longer extends
     * {@code JpaRepository} (audit-fix cycle 1, finding 6), so an unscoped {@code count()} is not
     * available on it any more.
     */
    private long countRows() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification", Long.class);
        return count == null ? 0 : count;
    }

    /** Inserts a row with an explicit {@code created_at}, bypassing the column's {@code DEFAULT now()}. */
    private UUID insertWithCreatedAt(UUID recipientUserId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, dedup_key, created_at)
                VALUES (?, ?, 'BOOKING_CREATED', ?, ?, ?)
                """, id, recipientUserId, booking.getId(), "BOOKING_CREATED:" + UUID.randomUUID(),
                java.sql.Timestamp.from(createdAt));
        return id;
    }
}
