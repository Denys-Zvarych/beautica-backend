package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.service.BookingService;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression net for the track-24.x product rule locked on 2026-07-16:
 *
 * <blockquote>"A booking goes into the past ONLY when an INDEPENDENT_MASTER / SALON_OWNER /
 * SALON_ADMIN marks it as completed."</blockquote>
 *
 * <p>Concretely: an elapsed {@code CONFIRMED} booking (its {@code endsAt} already in the past)
 * is <b>NOT</b> auto-transitioned to any terminal/"past" state by any scheduled or implicit
 * mechanism — there is deliberately NO completion sweep. The one and only path to the reviewable
 * {@code COMPLETED} state is the manual provider action
 * {@link BookingService#completeBooking(UUID, UUID)} (behind {@code PATCH /bookings/{id}/complete}),
 * gated to the three provider roles.
 *
 * <p>Reproduces the user report verbatim: master "Майстер Демо", service "Експрес нарощення",
 * a 45-minute slot whose end is already in the past while "now" is later the same day — status
 * must still be {@code CONFIRMED} because no provider has acted. Slot times are anchored to
 * {@code NOW()} (not a literal 2026-07-16 date) so "elapsed" holds deterministically on every run;
 * the elapsed-ness is asserted explicitly rather than assumed.
 *
 * <p>This class is the guard for the <i>absence</i> of an auto-transition. The full provider
 * authorization matrix over HTTP (all roles × cross-tenant × 401/403 existence oracle) is already
 * pinned by {@link BookingCompletionSecurityIT}; the outbox transition/atomicity matrix by
 * {@link BookingCompletionIT}. This net adds only what those do not cover: the no-auto-sweep rule,
 * exercised against a booking that is specifically <i>elapsed</i>.
 *
 * <p>{@link NotificationService} is mocked so the always-on {@code @Scheduled} outbox drain never
 * fires real email/push against seeded rows; assertions count outbox rows by type (drain-invariant),
 * never their {@code status} — mirroring {@link BookingCompletionIT}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Elapsed CONFIRMED booking — no auto-transition; only a provider mark-complete moves it to the past")
class BookingElapsedNoAutoCompleteIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingReminderJob bookingReminderJob;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Mocked so the background @Scheduled drain never fires real SMTP/push against seeded rows.
    @MockBean
    private NotificationService notificationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── CORE GUARD: an elapsed CONFIRMED booking is NOT auto-moved to a terminal state ───────────

    @Test
    @DisplayName("elapsed CONFIRMED booking stays CONFIRMED after the scheduled sweep runs and no provider acts (no auto-completion)")
    void should_stayConfirmed_when_bookingElapsedAndNoProviderActs() {
        Master master = createIndependentMaster("Майстер", "Демо",
                "elapsed-noauto-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId, "Експрес нарощення");
        UUID clientId = createClient("elapsed-noauto-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId, null);

        // Precondition the whole rule hinges on: the slot has already ended.
        assertThat(isElapsed(bookingId))
                .as("test seam is only meaningful if endsAt is already in the past")
                .isTrue();

        // Run the ONLY booking-related scheduled sweep the app wires. It must not complete the
        // booking (it is the 24h guest-reminder job — it never mutates status).
        bookingReminderJob.sendReminders();

        assertThat(bookingStatus(bookingId))
                .as("an elapsed CONFIRMED booking must remain CONFIRMED until a provider marks it completed")
                .isEqualTo(BookingStatus.CONFIRMED.name());
        assertThat(countOutbox())
                .as("no implicit transition means no STATUS_CHANGED/REVIEW_REQUESTED row is written")
                .isZero();
    }

    @Test
    @DisplayName("the only @Scheduled bean in com.beautica.booking is BookingReminderJob#sendReminders — there is deliberately no completion sweep")
    void should_haveNoScheduledBookingSweep_beyondTheGuestReminder() {
        Set<String> scheduledBookingMethods = new TreeSet<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean); // unwrap the @Scheduled proxy
            if (!targetClass.getName().startsWith("com.beautica.booking")) {
                continue;
            }
            for (Method method : targetClass.getDeclaredMethods()) {
                if (AnnotationUtils.findAnnotation(method, Scheduled.class) != null) {
                    scheduledBookingMethods.add(targetClass.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(scheduledBookingMethods)
                .as("adding any new scheduled booking job (e.g. an auto-complete sweep) must consciously "
                        + "confront this rule — the only permitted scheduled booking job is the guest reminder")
                .containsExactly("BookingReminderJob#sendReminders");
    }

    // ── ONLY MANUAL PATH TO "PAST": each provider role can mark the elapsed booking COMPLETED ────

    @Test
    @DisplayName("INDEPENDENT_MASTER mark-complete moves the elapsed CONFIRMED booking to COMPLETED + enqueues STATUS_CHANGED and REVIEW_REQUESTED")
    void should_moveToCompleted_when_owningIndependentMasterMarksComplete() {
        Master master = createIndependentMaster("Майстер", "Демо",
                "elapsed-im-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId, "Експрес нарощення");
        UUID clientId = createClient("elapsed-im-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId, null);

        bookingService.completeBooking(master.userId, bookingId);

        assertThat(bookingStatus(bookingId))
                .as("the owning independent master's mark-complete is the path into the past")
                .isEqualTo(BookingStatus.COMPLETED.name());
        assertThat(countOutboxByTypeForAggregate("STATUS_CHANGED", bookingId))
                .as("exactly one STATUS_CHANGED row keyed to the completed booking").isEqualTo(1L);
        assertThat(countOutboxByTypeForAggregate("REVIEW_REQUESTED", bookingId))
                .as("exactly one REVIEW_REQUESTED row (client-owned booking)").isEqualTo(1L);
    }

    @Test
    @DisplayName("SALON_OWNER mark-complete moves the elapsed CONFIRMED salon booking to COMPLETED")
    void should_moveToCompleted_when_salonOwnerMarksComplete() {
        Salon salon = createSalon("elapsed-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedSalonBooking(salon);

        // SALON_OWNER authority resolves via the in-memory owner match on the fetched booking
        // graph — no SecurityContext needed at the service layer.
        bookingService.completeBooking(salon.ownerUserId, bookingId);

        assertThat(bookingStatus(bookingId))
                .as("the owning salon owner's mark-complete moves the elapsed booking into the past")
                .isEqualTo(BookingStatus.COMPLETED.name());
        assertThat(countOutboxByTypeForAggregate("STATUS_CHANGED", bookingId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("SALON_ADMIN assigned to the booking's salon can mark-complete the elapsed CONFIRMED booking (Decision D1/D2)")
    void should_moveToCompleted_when_assignedSalonAdminMarksComplete() {
        Salon salon = createSalon("elapsed-admin-owner-" + System.nanoTime() + "@beautica.test");
        UUID adminUserId = createUser("elapsed-admin-" + System.nanoTime() + "@beautica.test",
                "SALON_ADMIN", salon.salonId);
        UUID bookingId = insertElapsedConfirmedSalonBooking(salon);

        // SALON_ADMIN authority falls back to a DB check that reads the role from the security
        // context (roleFromCurrentAuthentication) — prime it exactly as the JWT filter would.
        authenticateAs(adminUserId, "SALON_ADMIN");
        bookingService.completeBooking(adminUserId, bookingId);

        assertThat(bookingStatus(bookingId))
                .as("an assigned salon admin's mark-complete moves the elapsed booking into the past")
                .isEqualTo(BookingStatus.COMPLETED.name());
        assertThat(countOutboxByTypeForAggregate("STATUS_CHANGED", bookingId)).isEqualTo(1L);
    }

    // ── NEGATIVE: no non-provider can push the elapsed booking into the past ─────────────────────

    @Test
    @DisplayName("CLIENT cannot mark-complete — the elapsed booking is rejected with ForbiddenException and stays CONFIRMED")
    void should_rejectAndStayConfirmed_when_clientAttemptsComplete() {
        Master master = createIndependentMaster("Майстер", "Демо",
                "elapsed-cli-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId, "Експрес нарощення");
        UUID clientId = createClient("elapsed-cli-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId, null);

        assertThatThrownBy(() -> bookingService.completeBooking(clientId, bookingId))
                .as("the booking's own client is not a provider and cannot complete it")
                .isInstanceOf(ForbiddenException.class);

        assertThat(bookingStatus(bookingId))
                .as("a rejected completion leaves the elapsed booking CONFIRMED — not in the past")
                .isEqualTo(BookingStatus.CONFIRMED.name());
        assertThat(countOutbox()).as("a rejected completion writes no outbox row").isZero();
    }

    @Test
    @DisplayName("an unrelated INDEPENDENT_MASTER cannot mark-complete another master's elapsed booking — rejected, stays CONFIRMED")
    void should_rejectAndStayConfirmed_when_unrelatedMasterAttemptsComplete() {
        Master owningMaster = createIndependentMaster("Майстер", "Демо",
                "elapsed-xm-a-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(owningMaster.masterId, "Експрес нарощення");
        UUID clientId = createClient("elapsed-xm-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedBooking(clientId, owningMaster.masterId, serviceId, null);

        Master strangerMaster = createIndependentMaster("Інший", "Майстер",
                "elapsed-xm-b-" + System.nanoTime() + "@beautica.test");

        assertThatThrownBy(() -> bookingService.completeBooking(strangerMaster.userId, bookingId))
                .as("a master who does not own the booking cannot complete it")
                .isInstanceOf(ForbiddenException.class);

        assertThat(bookingStatus(bookingId)).isEqualTo(BookingStatus.CONFIRMED.name());
        assertThat(countOutbox()).isZero();
    }

    // ── fixtures (local by house convention — see BookingCompletionIT class Javadoc) ─────────────

    private record Master(UUID masterId, UUID userId) {}

    private record Salon(UUID salonId, UUID ownerUserId, UUID masterId) {}

    private void authenticateAs(UUID userId, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Master createIndependentMaster(String firstName, String lastName, String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD), firstName, lastName);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return new Master(masterId, userId);
    }

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);
        UUID masterUserId = createUser("elapsed-salon-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerId, masterId);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID createClient(String email) {
        return createUser(email, "CLIENT", null);
    }

    private UUID createMasterService(UUID masterId, String serviceName) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        return insertService(masterId, "INDEPENDENT_MASTER", userId, serviceName);
    }

    private UUID createSalonService(UUID salonId, UUID masterId, String serviceName) {
        return insertService(masterId, "SALON", salonId, serviceName);
    }

    private UUID insertService(UUID masterId, String ownerType, UUID ownerId, String serviceName) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, "
                        + "base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 45, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerType, ownerId, serviceName, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /** Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL). */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID insertElapsedConfirmedSalonBooking(Salon salon) {
        UUID clientId = createClient("elapsed-salon-book-client-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createSalonService(salon.salonId, salon.masterId, "Експрес нарощення");
        return insertElapsedConfirmedBooking(clientId, salon.masterId, masterServiceId, salon.salonId);
    }

    /**
     * Inserts an APP booking whose 45-minute slot has already ended (starts 90 min ago, ends 45 min
     * ago) so {@code endsAt < NOW()} always holds — the "elapsed" condition the rule guards.
     */
    private UUID insertElapsedConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() - interval '90 minutes', NOW() - interval '45 minutes', "
                        + "500.00, 45, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    private boolean isElapsed(UUID bookingId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT ends_at < NOW() FROM bookings WHERE id = ?", Boolean.class, bookingId));
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private long countOutbox() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class);
    }

    private long countOutboxByTypeForAggregate(String eventType, UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = ? AND aggregate_id = ?",
                Long.class, eventType, aggregateId);
    }
}
