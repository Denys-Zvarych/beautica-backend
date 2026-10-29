package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ModerationOutcome;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        service = new InAppNotificationService(repository, salonRepository, resolver, false);

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

    // ── Phase 339 — the FIREBASE_ENABLED gate picks the push twin ────────────────────────────────

    @Test
    @DisplayName("pushEnabled=true routes a per-event write to the push twin and never the plain insert")
    void should_useWithPushTwin_when_pushEnabledEventWrite() {
        service = new InAppNotificationService(
                repository, salonRepository, new InAppRecipientResolver(userRepository), true);
        Booking booking = org.mockito.Mockito.mock(Booking.class);
        org.mockito.Mockito.lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(booking.getMaster()).thenReturn(master);
        org.mockito.Mockito.lenient().when(booking.getSalon()).thenReturn(salon);
        org.mockito.Mockito.lenient().when(booking.getBookingSource()).thenReturn(BookingSource.APP);

        service.notifyBookingEvent(InAppNotificationType.BOOKING_CREATED, booking, clientId);

        org.mockito.Mockito.verify(repository).insertForRecipientsWithPush(
                eq("BOOKING_CREATED"), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).insertForRecipients(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pushEnabled=true routes both bulk writes to their push twins; false uses the plain ones")
    void should_routeBulkWrites_when_pushFlagToggled() {
        UUID bookingId = UUID.randomUUID();
        InAppRecipientResolver resolver = new InAppRecipientResolver(userRepository);

        new InAppNotificationService(repository, salonRepository, resolver, true)
                .notifyClientOnlyBulk(InAppNotificationType.BOOKING_CANCELLED_SALON_CLOSED, List.of(bookingId), null);
        new InAppNotificationService(repository, salonRepository, resolver, true)
                .notifyProviderSetBulk(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, List.of(bookingId), null);
        new InAppNotificationService(repository, salonRepository, resolver, false)
                .notifyClientOnlyBulk(InAppNotificationType.BOOKING_CANCELLED_SALON_CLOSED, List.of(bookingId), null);

        org.mockito.Mockito.verify(repository).insertClientOnlyBulkWithPush(
                eq("BOOKING_CANCELLED_SALON_CLOSED"), any(), any());
        org.mockito.Mockito.verify(repository).insertProviderSetBulkWithPush(
                eq("BOOKING_CANCELLED_BY_CLIENT"), any(), any());
        org.mockito.Mockito.verify(repository).insertClientOnlyBulk(
                eq("BOOKING_CANCELLED_SALON_CLOSED"), any(), any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).insertProviderSetBulk(any(), any(), any());
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

    // ── Phase 372 — request-decision items (requester only) ──────────────────────────────────

    @Test
    @DisplayName("category decision writes one row for the requester only, keyed TYPE:<bigint id>, "
            + "no booking/appointment/salon/subject ids")
    void should_writeOneCategoryItemForRequester_when_categoryDecided() {
        UUID requesterId = UUID.randomUUID();
        com.beautica.service.entity.PlatformCategory category =
                com.beautica.service.entity.PlatformCategory.ofPendingRequest(
                        "NAIL_ART", "Нейл-арт", requesterId, "h", null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(category, "id", 77L);

        service.notifyCategoryRequestDecided(category, true);
        service.notifyCategoryRequestDecided(category, false);

        verify(repository).insertRequestDecision(
                "CATEGORY_REQUEST_APPROVED", requesterId, 77L, null, "CATEGORY_REQUEST_APPROVED:77");
        verify(repository).insertRequestDecision(
                "CATEGORY_REQUEST_REJECTED", requesterId, 77L, null, "CATEGORY_REQUEST_REJECTED:77");
        verify(repository, never()).insertForRecipients(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("suggestion decision writes one row for the requester only, keyed TYPE:<uuid>")
    void should_writeOneSuggestionItemForRequester_when_suggestionDecided() {
        UUID requesterId = UUID.randomUUID();
        UUID suggestionId = UUID.randomUUID();
        com.beautica.service.entity.ServiceTypeSuggestion suggestion =
                com.beautica.service.entity.ServiceTypeSuggestion.ofPending(
                        "NAIL_ART", "Гель", null, requesterId, "h", null);
        org.springframework.test.util.ReflectionTestUtils.setField(suggestion, "id", suggestionId);

        service.notifySuggestionDecided(suggestion, true);
        service.notifySuggestionDecided(suggestion, false);

        verify(repository).insertRequestDecision("SERVICE_TYPE_SUGGESTION_APPROVED", requesterId, null,
                suggestionId, "SERVICE_TYPE_SUGGESTION_APPROVED:" + suggestionId);
        verify(repository).insertRequestDecision("SERVICE_TYPE_SUGGESTION_REJECTED", requesterId, null,
                suggestionId, "SERVICE_TYPE_SUGGESTION_REJECTED:" + suggestionId);
    }

    @Test
    @DisplayName("a null requester (legacy row / deleted user) makes no repository call at all")
    void should_makeNoRepositoryCall_when_requesterNull() {
        com.beautica.service.entity.PlatformCategory category =
                com.beautica.service.entity.PlatformCategory.ofPendingRequest(
                        "NAIL_ART", "Нейл-арт", null, "h", null, null, null);
        com.beautica.service.entity.ServiceTypeSuggestion suggestion =
                com.beautica.service.entity.ServiceTypeSuggestion.ofPending(
                        "NAIL_ART", "Гель", null, null, "h", null);

        service.notifyCategoryRequestDecided(category, true);
        service.notifySuggestionDecided(suggestion, false);

        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("pushEnabled=true routes a request decision to the push twin and never the plain insert")
    void should_useWithPushTwin_when_pushEnabledRequestDecision() {
        service = new InAppNotificationService(
                repository, salonRepository, new InAppRecipientResolver(userRepository), true);
        UUID requesterId = UUID.randomUUID();
        com.beautica.service.entity.PlatformCategory category =
                com.beautica.service.entity.PlatformCategory.ofPendingRequest(
                        "NAIL_ART", "Нейл-арт", requesterId, "h", null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(category, "id", 5L);

        service.notifyCategoryRequestDecided(category, false);

        verify(repository).insertRequestDecisionWithPush(
                "CATEGORY_REQUEST_REJECTED", requesterId, 5L, null, "CATEGORY_REQUEST_REJECTED:5");
        verify(repository, never()).insertRequestDecision(any(), any(), any(), any(), any());
    }

    // ── Phase 415 — moderation outcomes (one merged statement per resolution) ─────────────────────

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1} -> {2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "REVIEW, REMOVED, REPORT_CONTENT_REMOVED",
            "CLIENT_REVIEW, REMOVED, REPORT_CONTENT_REMOVED",
            "PORTFOLIO_PHOTO, REMOVED, REPORT_CONTENT_REMOVED",
            "REVIEW, RESTORED, REPORT_NO_VIOLATION",
            "REVIEW, DISMISSED, REPORT_NO_VIOLATION",
            "PORTFOLIO_PHOTO, DISMISSED, REPORT_NO_VIOLATION",
            "REVIEW, TARGET_GONE, REPORT_REVIEWED",
            "PORTFOLIO_PHOTO, TARGET_GONE, REPORT_REVIEWED",
            "MASTER_PROFILE, ACTIONED, REPORT_REVIEWED",
            "SALON_PROFILE, ACTIONED, REPORT_REVIEWED",
            "CLIENT_PROFILE, ACTIONED, REPORT_REVIEWED",
            "CLIENT_PROFILE, DISMISSED, REPORT_NO_VIOLATION"
    })
    @DisplayName("D1/D4/D5 — reporter type per target and resolution, target type + batch id bound")
    void should_mapReporterType_when_resolved(ReportTargetType target, ReportResolution resolution,
                                              InAppNotificationType expected) {
        UUID reporter = UUID.randomUUID();
        UUID batch = UUID.randomUUID();

        service.notifyModerationOutcome(new ModerationOutcome(target, resolution, batch, Set.of(reporter), null, null));

        assertThat(writtenRows(target.name(), batch)).containsExactly(Map.entry(reporter, expected.name()));
    }

    @Test
    @DisplayName("REMOVE of a review — reporter, author and subject rows go out in ONE statement (audit-fix PERF 5)")
    void should_writeAllGroupsInOneStatement_when_reviewRemoved() {
        UUID reporter = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID subjectA = UUID.randomUUID();
        UUID subjectB = UUID.randomUUID();
        UUID batch = UUID.randomUUID();

        service.notifyModerationOutcome(new ModerationOutcome(ReportTargetType.REVIEW, ReportResolution.REMOVED, batch,
                Set.of(reporter), Set.of(author), Set.of(subjectA, subjectB)));

        assertThat(writtenRows("REVIEW", batch)).containsExactlyInAnyOrder(
                Map.entry(reporter, "REPORT_CONTENT_REMOVED"),
                Map.entry(author, "CONTENT_REMOVED"),
                Map.entry(subjectA, "REVIEW_ABOUT_YOU_REMOVED"),
                Map.entry(subjectB, "REVIEW_ABOUT_YOU_REMOVED"));
    }

    @Test
    @DisplayName("audit-fix SEC 3 — a subject who also reported gets BOTH rows (no reporter inference by feed diff); "
            + "the author still wins over the subject")
    void should_writeSubjectRowToReporter_and_letAuthorWin() {
        UUID reporterSubject = UUID.randomUUID();
        UUID authorSubject = UUID.randomUUID();
        UUID plainSubject = UUID.randomUUID();
        UUID batch = UUID.randomUUID();

        service.notifyModerationOutcome(new ModerationOutcome(ReportTargetType.CLIENT_REVIEW,
                ReportResolution.REMOVED, batch, Set.of(reporterSubject), Set.of(authorSubject),
                Set.of(reporterSubject, authorSubject, plainSubject)));

        assertThat(writtenRows("CLIENT_REVIEW", batch)).containsExactlyInAnyOrder(
                Map.entry(reporterSubject, "REPORT_CONTENT_REMOVED"),
                Map.entry(reporterSubject, "REVIEW_ABOUT_YOU_REMOVED"),
                Map.entry(authorSubject, "CONTENT_REMOVED"),
                Map.entry(plainSubject, "REVIEW_ABOUT_YOU_REMOVED"));
    }

    @Test
    @DisplayName("audit-fix cycle 2 — an author who also reported gets BOTH the reporter row and CONTENT_REMOVED "
            + "(no reporter inference inside a salon team)")
    void should_writeBothRows_when_authorIsReporter() {
        UUID author = UUID.randomUUID();
        UUID batch = UUID.randomUUID();

        service.notifyModerationOutcome(new ModerationOutcome(ReportTargetType.REVIEW, ReportResolution.REMOVED,
                batch, Set.of(author), Set.of(author), null));

        assertThat(writtenRows("REVIEW", batch)).containsExactlyInAnyOrder(
                Map.entry(author, "REPORT_CONTENT_REMOVED"), Map.entry(author, "CONTENT_REMOVED"));
    }

    @Test
    @DisplayName("audit-fix PERF 2 — pre-resolved salon author ids are written as-is; no Salon / User entity is loaded")
    void should_writePreResolvedAuthorIds_without_loadingEntities() {
        UUID adminId = UUID.randomUUID();
        UUID batch = UUID.randomUUID();

        service.notifyModerationOutcome(new ModerationOutcome(ReportTargetType.PORTFOLIO_PHOTO,
                ReportResolution.REMOVED, batch, Set.of(), Set.of(ownerId, adminId), null));

        assertThat(writtenRows("PORTFOLIO_PHOTO", batch)).containsExactlyInAnyOrder(
                Map.entry(ownerId, "CONTENT_REMOVED"), Map.entry(adminId, "CONTENT_REMOVED"));
        org.mockito.Mockito.verifyNoInteractions(salonRepository, userRepository);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1}")
    @org.junit.jupiter.params.provider.CsvSource({"PORTFOLIO_PHOTO, REMOVED", "REVIEW, DISMISSED", "REVIEW, RESTORED",
            "REVIEW, TARGET_GONE", "CLIENT_REVIEW, DISMISSED"})
    @DisplayName("D4 — no author or subject row for a photo subject or any resolution other than REMOVED of a review")
    void should_notNotifySubjectOrAuthor_when_notApplicable(ReportTargetType target, ReportResolution resolution) {
        UUID subject = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        boolean authorApplies = resolution == ReportResolution.REMOVED;

        service.notifyModerationOutcome(new ModerationOutcome(target, resolution, UUID.randomUUID(), Set.of(),
                authorApplies ? Set.of() : Set.of(author), Set.of(subject)));

        verify(repository, never()).insertModerationOutcomes(any(), any(), any(), any());
    }

    @Test
    @DisplayName("nobody to notify → no statement at all")
    void should_writeNothing_when_noRecipients() {
        service.notifyModerationOutcome(new ModerationOutcome(ReportTargetType.SALON_PROFILE,
                ReportResolution.ACTIONED, UUID.randomUUID(), Set.of(), null, null));

        verify(repository, never()).insertModerationOutcomes(any(), any(), any(), any());
    }

    @Test
    @DisplayName("pushEnabled=true routes the merged moderation write to its push twin")
    void should_usePushTwin_when_pushEnabledModerationOutcome() {
        service = new InAppNotificationService(
                repository, salonRepository, new InAppRecipientResolver(userRepository), true);

        service.notifyModerationOutcome(new ModerationOutcome(ReportTargetType.SALON_PROFILE,
                ReportResolution.ACTIONED, UUID.randomUUID(), Set.of(UUID.randomUUID()), null, null));

        verify(repository).insertModerationOutcomesWithPush(any(), any(), eq("SALON_PROFILE"), any());
        verify(repository, never()).insertModerationOutcomes(any(), any(), any(), any());
    }

    /**
     * The (recipient, type) rows of the single merged insert, asserting it ran exactly once with the expected target
     * type and batch id and with parallel arrays of equal length.
     */
    private List<Map.Entry<UUID, String>> writtenRows(String targetType, UUID batch) {
        ArgumentCaptor<UUID[]> ids = ArgumentCaptor.forClass(UUID[].class);
        ArgumentCaptor<String[]> types = ArgumentCaptor.forClass(String[].class);
        verify(repository, times(1)).insertModerationOutcomes(ids.capture(), types.capture(), eq(targetType), eq(batch));
        assertThat(ids.getValue()).hasSameSizeAs(types.getValue());
        List<Map.Entry<UUID, String>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < ids.getValue().length; i++) {
            rows.add(Map.entry(ids.getValue()[i], types.getValue()[i]));
        }
        return rows;
    }
}
