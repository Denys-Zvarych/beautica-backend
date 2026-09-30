package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link InAppNotificationService} — dedup-key shapes, actor exclusion, and the
 * owner=performing-master collapse (via the REAL {@link InAppRecipientResolver}, backed by a mocked
 * {@link UserRepository} — the resolver's own de-duplication is exactly what "collapse" tests here,
 * so mocking it away would delete the property under test).
 *
 * <p>No Spring context, no DB — {@link InAppNotificationRepository} is mocked and every {@code
 * insertForRecipients} call is captured to inspect the exact SQL parameters (recipient set, dedup
 * key, ids) without a real INSERT ever running. Real-DB proof of the write path lives in {@code
 * InAppNotificationWritePathIT} / {@code StaffBookingEndpointIT$InAppFeed}.
 *
 * <p><b>Audit-fix cycle 1, findings 1 &amp; 3.</b> {@code BookingRepository}/{@code
 * AppointmentRepository} are gone from both the constructor and this test — the service no longer
 * reloads a booking/appointment by id at all (finding 1), so every test below hands it an
 * already-built {@link Booking} directly, the same shape every real caller already holds. Every
 * assertion also verifies exactly ONE {@code insertForRecipients} call (never a per-recipient loop —
 * finding 3), with the FULL recipient set captured in that one call's argument.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InAppNotificationService — unit")
class InAppNotificationServiceTest {

    @Mock
    private InAppNotificationRepository repository;
    @Mock
    private SalonRepository salonRepository;
    @Mock
    private UserRepository userRepository;

    private InAppNotificationService service;

    private UUID ownerId;
    private UUID masterUserId;
    private UUID clientId;
    private UUID salonId;
    private UUID masterId;
    private Salon salon;
    private Master master;
    private User owner;

    @BeforeEach
    void setUp() {
        InAppRecipientResolver resolver = new InAppRecipientResolver(userRepository);
        service = new InAppNotificationService(repository, salonRepository, resolver);

        ownerId = UUID.randomUUID();
        masterUserId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        salonId = UUID.randomUUID();
        masterId = UUID.randomUUID();

        owner = userWithId(ownerId);
        salon = salonWith(salonId, owner);
        master = masterWith(masterId, userWithId(masterUserId), salon, MasterType.SALON_MASTER);

        // No admins by default — tests that need one stub it explicitly. Lenient: several tests
        // below never reach the admin lookup at all (walk-in create, actor-emptied-set no-op),
        // and MockitoExtension's default STRICT_STUBS would flag this as unnecessary for every one
        // of them.
        org.mockito.Mockito.lenient()
                .when(userRepository.findBySalonIdAndRoleAndIsActiveTrue(eq(salonId), eq(Role.SALON_ADMIN)))
                .thenReturn(List.of());
    }

    private static User userWithId(UUID id) {
        // No public no-arg constructor (protected, same-package only — see User's own javadoc on
        // why); the cheapest public constructor is the plain-registration one, with the generated
        // id overwritten via reflection right after.
        User u = new User("u-" + id + "@test.invalid", "hash", Role.CLIENT, "Test", "User", null);
        setId(u, id);
        return u;
    }

    private static Salon salonWith(UUID id, User owner) {
        return Salon.builder().id(id).owner(owner).build();
    }

    private static Master masterWith(UUID id, User user, Salon salon, MasterType type) {
        return Master.builder().id(id).user(user).salon(salon).masterType(type).build();
    }

    /** {@code User.id} has no public setter (JPA entities carry none) — reflection sets it once. */
    private static void setId(User user, UUID id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Booking bookingWith(UUID id, User client, Master master, Salon salon, BookingSource source) {
        return Booking.builder()
                .id(id)
                .client(client)
                .master(master)
                .salon(salon)
                .bookingSource(source)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Collection<UUID>> recipientsCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    // ── dedup key shapes ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("notifyBookingEvent: dedup key is TYPE:bookingId, ONE insertForRecipients call")
    void should_useTypeAndBookingIdDedupKey_when_notifyBookingEvent() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, userWithId(clientId), master, salon, BookingSource.APP);

        service.notifyBookingEvent(InAppNotificationType.BOOKING_CREATED, booking, clientId);

        // Provider set = owner + performing master (no admins seeded) = 2 recipients, in ONE call;
        // the CLIENT is the actor and was never in that set to begin with.
        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_CREATED"), recipients.capture(), eq(bookingId), isNull(), any(), isNull(),
                keyCaptor.capture());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(ownerId, masterUserId);
        assertThat(keyCaptor.getValue()).isEqualTo("BOOKING_CREATED:" + bookingId);
    }

    @Test
    @DisplayName("notifyVisitEvent: dedup key is TYPE:appointmentId, never a booking id")
    void should_useTypeAndAppointmentIdDedupKey_when_notifyVisitEvent() {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking item = bookingWith(bookingId, userWithId(clientId), master, salon, BookingSource.APP);

        service.notifyVisitEvent(InAppNotificationType.BOOKING_CREATED, appointmentId, item, clientId);

        // Provider set = owner + performing master (no admins seeded) = 2 recipients.
        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_CREATED"), recipients.capture(), isNull(), eq(appointmentId), any(), isNull(),
                keyCaptor.capture());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(ownerId, masterUserId);
        assertThat(keyCaptor.getValue()).isEqualTo("BOOKING_CREATED:" + appointmentId);
    }

    @Test
    @DisplayName("notifyRescheduled (booking): dedup key appends the new-start epoch-second suffix")
    void should_appendEpochSecondSuffix_when_notifyRescheduled() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, userWithId(clientId), master, salon, BookingSource.APP);
        Instant newStart = Instant.parse("2026-10-01T12:00:00Z");

        service.notifyRescheduled(booking, true, newStart, ownerId);

        // initiatedByProvider=true: performing master (always) + the client (provider-initiated
        // leg) = 2 recipients, both written by the SAME dedup key (it carries no recipient).
        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_RESCHEDULED"), recipients.capture(), eq(bookingId), isNull(), any(), isNull(),
                keyCaptor.capture());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(masterUserId, clientId);
        assertThat(keyCaptor.getValue())
                .isEqualTo("BOOKING_RESCHEDULED:" + bookingId + ":" + newStart.getEpochSecond());
    }

    @Test
    @DisplayName("notifyRescheduledVisit: dedup key is keyed to the appointment id, never a booking id")
    void should_useAppointmentIdDedupKey_when_notifyRescheduledVisit() {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking item = bookingWith(bookingId, userWithId(clientId), master, salon, BookingSource.APP);
        Instant newStart = Instant.parse("2026-10-01T12:00:00Z");

        service.notifyRescheduledVisit(appointmentId, item, false, newStart, clientId);

        // initiatedByProvider=false: performing master (always) + owner/admins (client-initiated
        // leg) = {masterUserId, ownerId}; the CLIENT is the actor and was never in that set.
        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_RESCHEDULED"), recipients.capture(), isNull(), eq(appointmentId), any(), isNull(),
                keyCaptor.capture());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(masterUserId, ownerId);
        assertThat(keyCaptor.getValue())
                .isEqualTo("BOOKING_RESCHEDULED:" + appointmentId + ":" + newStart.getEpochSecond());
    }

    @Test
    @DisplayName("notifyInviteAccepted: dedup key is INVITE_ACCEPTED:salonId:newMemberUserId")
    void should_useSalonAndMemberIdDedupKey_when_notifyInviteAccepted() {
        UUID newMemberId = UUID.randomUUID();
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        service.notifyInviteAccepted(salonId, newMemberId);

        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(1)).insertForRecipients(
                eq("INVITE_ACCEPTED"), recipients.capture(), isNull(), isNull(), eq(salonId), eq(newMemberId),
                keyCaptor.capture());
        assertThat(recipients.getValue()).containsExactly(ownerId);
        assertThat(keyCaptor.getValue()).isEqualTo("INVITE_ACCEPTED:" + salonId + ":" + newMemberId);
    }

    // ── actor exclusion ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("actor exclusion: the performing master declining their OWN booking writes only the "
            + "client's row, never the master's")
    void should_excludeActor_when_performingMasterDeclinesOwnBooking() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, userWithId(clientId), master, salon, BookingSource.APP);

        service.notifyBookingEvent(InAppNotificationType.BOOKING_DECLINED, booking, masterUserId);

        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_DECLINED"), recipients.capture(), any(), any(), any(), any(), any());
        assertThat(recipients.getValue()).containsExactly(clientId);
    }

    @Test
    @DisplayName("actor exclusion: a null actor (guest/system event) is a safe no-op, never an NPE")
    void should_notThrow_when_actorIsNull() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, null, master, salon, BookingSource.LINK);

        service.notifyBookingEvent(InAppNotificationType.BOOKING_CREATED, booking, null);

        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_CREATED"), recipients.capture(), any(), any(), any(), any(), any());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(ownerId, masterUserId);
    }

    // ── owner = performing master collapse ──────────────────────────────────────────────────

    @Test
    @DisplayName("owner=master collapse: a SALON_OWNER-type master's own user id is BOTH the owner and "
            + "the performing master — exactly ONE recipient, never two")
    void should_writeOneRecipient_when_ownerIsAlsoPerformingMaster() {
        Master ownerAsMaster = masterWith(UUID.randomUUID(), owner, salon, MasterType.SALON_OWNER);
        UUID bookingId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, userWithId(clientId), ownerAsMaster, salon, BookingSource.APP);

        service.notifyBookingEvent(InAppNotificationType.BOOKING_CREATED, booking, clientId);

        // Provider set is {owner==master} ∪ {admins=∅} = ONE recipient, not two.
        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_CREATED"), recipients.capture(), any(), any(), any(), any(), any());
        assertThat(recipients.getValue()).containsExactly(ownerId);
    }

    // ── walk-in restriction ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("STAFF-source create notifies ONLY the performing master, never owner/admins")
    void should_notifyMasterOnly_when_bookingSourceIsStaff() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, null, master, salon, BookingSource.STAFF);

        service.notifyBookingEvent(InAppNotificationType.BOOKING_CREATED, booking, ownerId);

        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        verify(repository, times(1)).insertForRecipients(
                eq("BOOKING_CREATED"), recipients.capture(), any(), any(), any(), any(), any());
        assertThat(recipients.getValue()).containsExactly(masterUserId);
    }

    // ── defensive no-op: every recipient excludes down to empty ────────────────────────────

    @Test
    @DisplayName("a walk-in (STAFF, no client) declined by the performing master themselves empties "
            + "the recipient set entirely — no insertForRecipients call at all, not a call with an "
            + "empty collection")
    void should_writeNothing_when_allRecipientsExcluded() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, null, master, salon, BookingSource.STAFF);

        service.notifyBookingEvent(InAppNotificationType.BOOKING_DECLINED, booking, masterUserId);

        verify(repository, never()).insertForRecipients(
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── row 8 — review received excludes admins ─────────────────────────────────────────────

    @Test
    @DisplayName("notifyReviewReceived: owner + performing master, never admins")
    void should_excludeAdmins_when_notifyReviewReceived() {
        // Deliberately NOT stubbing an admin here (would be an unnecessary stub, flagged by
        // STRICT_STUBS): InAppRecipientResolver#ownerAndMasterOnly never calls
        // findBySalonIdAndRoleAndIsActiveTrue at all — the admin-exclusion proof below is that the
        // query is never even issued, not merely that its result is filtered out afterward.
        UUID bookingId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Booking booking = bookingWith(bookingId, userWithId(clientId), master, salon, BookingSource.APP);

        service.notifyReviewReceived(reviewId, booking, clientId);

        ArgumentCaptor<Collection<UUID>> recipients = recipientsCaptor();
        verify(repository, times(1)).insertForRecipients(
                eq("REVIEW_RECEIVED"), recipients.capture(), any(), any(), any(), any(), any());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(ownerId, masterUserId);
        verify(userRepository, never()).findBySalonIdAndRoleAndIsActiveTrue(any(), any());
    }

    // ── bulk-insert id chunking (Phase 336 audit-fix, INFO security) ─────────────────────────────

    @Test
    @DisplayName("notifyClientOnlyBulk: 2500 ids split into 1000/1000/500, one statement per chunk")
    void should_chunkIdsAt1000_when_notifyClientOnlyBulkGetsMoreThanOneChunk() {
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(2500).toList();

        service.notifyClientOnlyBulk(InAppNotificationType.BOOKING_CANCELLED_SALON_CLOSED, ids, null);

        ArgumentCaptor<Collection<UUID>> chunks = recipientsCaptor();
        verify(repository, times(3)).insertClientOnlyBulk(eq("BOOKING_CANCELLED_SALON_CLOSED"), chunks.capture(), any());
        assertThat(chunks.getAllValues()).extracting(Collection::size).containsExactly(1000, 1000, 500);
        assertThat(chunks.getAllValues().stream().flatMap(Collection::stream)).containsExactlyElementsOf(ids);
    }

    @Test
    @DisplayName("notifyProviderSetBulk: 2500 ids split into 1000/1000/500, one statement per chunk")
    void should_chunkIdsAt1000_when_notifyProviderSetBulkGetsMoreThanOneChunk() {
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(2500).toList();

        service.notifyProviderSetBulk(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, ids, null);

        ArgumentCaptor<Collection<UUID>> chunks = recipientsCaptor();
        verify(repository, times(3)).insertProviderSetBulk(eq("BOOKING_CANCELLED_BY_CLIENT"), chunks.capture(), any());
        assertThat(chunks.getAllValues()).extracting(Collection::size).containsExactly(1000, 1000, 500);
    }

    @Test
    @DisplayName("bulk notify: 1001 ids split into exactly 1000 + 1 (one past the chunk boundary), no id lost")
    void should_splitIntoThousandPlusOne_when_bulkIdsExceedChunkByOne() {
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(1001).toList();

        service.notifyClientOnlyBulk(InAppNotificationType.BOOKING_CANCELLED_SALON_CLOSED, ids, null);

        ArgumentCaptor<Collection<UUID>> chunks = recipientsCaptor();
        verify(repository, times(2)).insertClientOnlyBulk(eq("BOOKING_CANCELLED_SALON_CLOSED"), chunks.capture(), any());
        assertThat(chunks.getAllValues()).extracting(Collection::size).containsExactly(1000, 1);
        assertThat(chunks.getAllValues().stream().flatMap(Collection::stream)).containsExactlyElementsOf(ids);
    }

    @Test
    @DisplayName("bulk notify: up to 1000 ids stay ONE statement (statement count flat), an exact 1000 too")
    void should_issueOneStatement_when_bulkIdsFitOneChunk() {
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(1000).toList();

        service.notifyClientOnlyBulk(InAppNotificationType.BOOKING_CANCELLED_MASTER_REMOVED, ids, null);
        service.notifyProviderSetBulk(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, ids, null);

        verify(repository, times(1)).insertClientOnlyBulk(any(), any(), any());
        verify(repository, times(1)).insertProviderSetBulk(any(), any(), any());
    }
}
