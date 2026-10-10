package com.beautica.notification.inapp.push;

import com.beautica.auth.Role;
import com.beautica.notification.inapp.dto.NotificationParams;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.NotificationTarget;
import com.beautica.notification.inapp.dto.TargetKind;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.push.InAppPushDispatcher.PushPlan;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import com.beautica.notification.inapp.service.NotificationViewAssembler;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.beautica.notification.service.PushNotificationService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InAppPushDispatcher — unit")
class InAppPushDispatcherTest {

    private static final Instant STARTS_AT = Instant.parse("2026-10-03T11:30:00Z"); // 14:30 Kyiv

    @Mock
    private InAppNotificationRepository repository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private DeviceTokenRepository deviceTokenRepository;
    @Mock
    private NotificationViewAssembler assembler;
    @Mock
    private PushNotificationService pushNotificationService;

    @InjectMocks
    private InAppPushDispatcher dispatcher;

    private final UUID notificationId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();
    private final UUID salonId = UUID.randomUUID();

    private InAppNotification row(UUID id, UUID recipient, InAppNotificationType type, Instant readAt) {
        InAppNotification row = mock(InAppNotification.class);
        lenient().when(row.getId()).thenReturn(id);
        lenient().when(row.getRecipientUserId()).thenReturn(recipient);
        lenient().when(row.getType()).thenReturn(type);
        lenient().when(row.getReadAt()).thenReturn(readAt);
        return row;
    }

    private InAppNotification row(InAppNotificationType type, Instant readAt) {
        return row(notificationId, recipientId, type, readAt);
    }

    private User user(UUID id, Role role) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.getRole()).thenReturn(role);
        lenient().when(user.isActive()).thenReturn(true);
        return user;
    }

    private User inactiveUser(UUID id, Role role) {
        User user = user(id, role);
        lenient().when(user.isActive()).thenReturn(false);
        return user;
    }

    private DeviceTokenRepository.UserDeviceToken token(UUID owner, String value) {
        DeviceTokenRepository.UserDeviceToken token = mock(DeviceTokenRepository.UserDeviceToken.class);
        lenient().when(token.getUserId()).thenReturn(owner);
        lenient().when(token.getToken()).thenReturn(value);
        return token;
    }

    /** One unread row, its recipient with the given role, and one active device token. */
    private void world(InAppNotification row, Role role) {
        stubRows(row);
        stubUsers(user(recipientId, role));
        stubTokens(token(recipientId, "fcm-1"));
    }

    // Stubbing helpers take ALREADY-BUILT mocks: building one inside a when(...).thenReturn(...) argument
    // list would open a nested stubbing (UnfinishedStubbingException).
    private void stubRows(InAppNotification... rows) {
        when(repository.findAllForPushDispatch(any())).thenReturn(List.of(rows));
    }

    private void stubUsers(User... users) {
        when(userRepository.findAllById(any())).thenReturn(List.of(users));
    }

    private void stubTokens(DeviceTokenRepository.UserDeviceToken... tokens) {
        when(deviceTokenRepository.findActiveTokensByUserIdIn(any())).thenReturn(List.of(tokens));
    }

    private void viewIs(InAppNotificationType type, NotificationTarget target, NotificationParams params) {
        NotificationResponse view = new NotificationResponse(
                notificationId, type, Instant.now(), false, target, params);
        when(assembler.assembleForRecipients(any(), any())).thenReturn(Map.of(notificationId, view));
    }

    private PushPlan planFor() {
        Map<UUID, PushPlan> plans = dispatcher.prepare(List.of(notificationId));
        assertThat(plans).containsOnlyKeys(notificationId);
        return plans.get(notificationId);
    }

    @Test
    @DisplayName("sends exactly the D6 data keys, with the target the read API resolved")
    void should_sendD6Keys_when_unreadRowPrepared() {
        world(row(InAppNotificationType.BOOKING_CREATED, null), Role.SALON_OWNER);
        viewIs(InAppNotificationType.BOOKING_CREATED,
                new NotificationTarget(TargetKind.BOOKING, bookingId, appointmentId, salonId),
                new NotificationParams("Олена Коваленко", "Манікюр", 1, STARTS_AT, "Salon", null, null, null));

        PushPlan plan = planFor();

        assertThat(plan.recipientId()).isEqualTo(recipientId);
        assertThat(plan.title()).isEqualTo("Новий запис");
        assertThat(plan.body()).isEqualTo("Олена Коваленко — Манікюр, сб, 3 жовтня, 14:30");
        assertThat(plan.data()).containsOnly(
                Map.entry("v", "1"),
                Map.entry("notificationId", notificationId.toString()),
                Map.entry("type", "BOOKING_CREATED"),
                Map.entry("targetKind", "BOOKING"),
                Map.entry("bookingId", bookingId.toString()),
                Map.entry("appointmentId", appointmentId.toString()),
                Map.entry("salonId", salonId.toString()));
    }

    @Test
    @DisplayName("absent target ids are omitted from the data payload, not sent as null/empty")
    void should_omitAbsentKeys_when_targetHasNoBookingOrAppointment() {
        world(row(InAppNotificationType.INVITE_ACCEPTED, null), Role.SALON_OWNER);
        viewIs(InAppNotificationType.INVITE_ACCEPTED,
                new NotificationTarget(TargetKind.SALON_TEAM, null, null, salonId),
                new NotificationParams(null, null, 0, null, null, null, "Ірина Мельник", Role.SALON_MASTER));

        PushPlan plan = planFor();

        assertThat(plan.data()).containsOnlyKeys("v", "notificationId", "type", "targetKind", "salonId");
        assertThat(plan.data()).containsEntry("targetKind", "SALON_TEAM");
    }

    @Test
    @DisplayName("a recipient who lost access (params null, target NONE) gets the generic body, no name")
    void should_useGenericBody_when_recipientLostAccess() {
        world(row(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, null), Role.SALON_ADMIN);
        viewIs(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, NotificationTarget.none(salonId), null);

        PushPlan plan = planFor();

        assertThat(plan.body()).isEqualTo(PushCopy.GENERIC_BODY);
        assertThat(plan.data()).containsEntry("targetKind", "NONE").doesNotContainKey("bookingId");
    }

    @Test
    @DisplayName("a CLIENT recipient gets the client-audience title")
    void should_useClientTitle_when_recipientIsClient() {
        world(row(InAppNotificationType.BOOKING_DECLINED, null), Role.CLIENT);
        viewIs(InAppNotificationType.BOOKING_DECLINED,
                new NotificationTarget(TargetKind.BOOKING, bookingId, null, salonId),
                new NotificationParams("Салон Оазис", "Манікюр", 1, STARTS_AT, "Салон Оазис", null, null, null));

        assertThat(planFor().title()).isEqualTo("Ваш запис скасовано");
    }

    @Test
    @DisplayName("the rendered text carries the names and time but never a note, phone or e-mail sentinel")
    void should_neverLeakNoteOrContact_when_bodyRendered() {
        world(row(InAppNotificationType.BOOKING_CREATED, null), Role.INDEPENDENT_MASTER);
        viewIs(InAppNotificationType.BOOKING_CREATED,
                new NotificationTarget(TargetKind.BOOKING, bookingId, null, null),
                new NotificationParams("SENTINEL-NAME", "SENTINEL-SERVICE", 1, STARTS_AT, null, null, null, null));

        PushPlan plan = planFor();

        String everything = plan.title() + "|" + plan.body() + "|" + plan.data();
        assertThat(plan.body()).contains("SENTINEL-NAME").contains("SENTINEL-SERVICE").contains("14:30");
        assertThat(everything).doesNotContain("SENTINEL-NOTE").doesNotContain("+380").doesNotContain("@");
    }

    @Test
    @DisplayName("D8: an already-read row yields no plan — and nothing else is even loaded")
    void should_skip_when_rowAlreadyRead() {
        stubRows(row(InAppNotificationType.BOOKING_CREATED, Instant.now()));

        assertThat(dispatcher.prepare(List.of(notificationId))).isEmpty();

        verifyNoInteractions(assembler, userRepository, deviceTokenRepository, pushNotificationService);
    }

    @Test
    @DisplayName("D8: a purged/cascaded row yields no plan")
    void should_skip_when_rowGone() {
        when(repository.findAllForPushDispatch(any())).thenReturn(List.of());

        assertThat(dispatcher.prepare(List.of(notificationId))).isEmpty();

        verifyNoInteractions(assembler, userRepository, deviceTokenRepository, pushNotificationService);
    }

    @Test
    @DisplayName("a recipient user that no longer exists yields no plan and is never assembled")
    void should_skip_when_recipientUserGone() {
        stubRows(row(InAppNotificationType.BOOKING_CREATED, null));
        stubUsers();

        assertThat(dispatcher.prepare(List.of(notificationId))).isEmpty();

        verifyNoInteractions(assembler, deviceTokenRepository);
    }

    @Test
    @DisplayName("S2: a deactivated recipient (isActive=false) yields no plan even with a live token, and is never assembled")
    void should_skip_when_recipientDeactivated() {
        stubRows(row(InAppNotificationType.BOOKING_CREATED, null));
        stubUsers(inactiveUser(recipientId, Role.CLIENT));

        assertThat(dispatcher.prepare(List.of(notificationId))).isEmpty();

        verifyNoInteractions(assembler, deviceTokenRepository);
    }

    @Test
    @DisplayName("S2: only the deactivated recipient of a mixed batch is dropped")
    void should_dropOnlyDeactivatedRecipient_when_batchMixed() {
        UUID activeRecipient = UUID.randomUUID();
        UUID deactivatedRecipient = UUID.randomUUID();
        UUID activeRow = UUID.randomUUID();
        UUID deactivatedRow = UUID.randomUUID();
        stubRows(row(activeRow, activeRecipient, InAppNotificationType.BOOKING_CREATED, null),
                row(deactivatedRow, deactivatedRecipient, InAppNotificationType.BOOKING_CREATED, null));
        stubUsers(user(activeRecipient, Role.CLIENT), inactiveUser(deactivatedRecipient, Role.CLIENT));
        stubTokens(token(activeRecipient, "a"), token(deactivatedRecipient, "b"));
        NotificationResponse view = new NotificationResponse(activeRow, InAppNotificationType.BOOKING_CREATED,
                Instant.now(), false, NotificationTarget.none(null), null);
        when(assembler.assembleForRecipients(any(), any())).thenReturn(Map.of(activeRow, view));

        Map<UUID, PushPlan> plans = dispatcher.prepare(List.of(activeRow, deactivatedRow));

        assertThat(plans).containsOnlyKeys(activeRow);
    }

    @Test
    @DisplayName("a recipient with no active device token yields no plan and is never assembled")
    void should_skip_when_recipientHasNoToken() {
        stubRows(row(InAppNotificationType.BOOKING_CREATED, null));
        stubUsers(user(recipientId, Role.CLIENT));
        stubTokens();

        assertThat(dispatcher.prepare(List.of(notificationId))).isEmpty();

        verifyNoInteractions(assembler);
    }

    @Test
    @DisplayName("an empty id list does no work at all")
    void should_doNothing_when_noIds() {
        assertThat(dispatcher.prepare(List.of())).isEmpty();

        verifyNoInteractions(repository, userRepository, deviceTokenRepository, assembler);
    }

    @Test
    @DisplayName("P2: every recipient's rows go through ONE batch assemble call, with each recipient's own role")
    void should_assembleOnceForWholeBatch_when_manyRecipients() {
        UUID recipientB = UUID.randomUUID();
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        InAppNotification a1 = row(n1, recipientId, InAppNotificationType.BOOKING_CREATED, null);
        InAppNotification a2 = row(n2, recipientId, InAppNotificationType.BOOKING_CREATED, null);
        InAppNotification b1 = row(n3, recipientB, InAppNotificationType.BOOKING_DECLINED, null);
        stubRows(a1, b1, a2);
        stubUsers(user(recipientId, Role.SALON_OWNER), user(recipientB, Role.CLIENT));
        stubTokens(token(recipientId, "a"), token(recipientB, "b"));
        NotificationTarget target = new NotificationTarget(TargetKind.BOOKING, bookingId, null, salonId);
        NotificationParams params = new NotificationParams("Олена", "Манікюр", 1, STARTS_AT, "S", null, null, null);
        Map<UUID, NotificationResponse> views = Map.of(
                n1, new NotificationResponse(n1, InAppNotificationType.BOOKING_CREATED, Instant.now(), false, target, params),
                n2, new NotificationResponse(n2, InAppNotificationType.BOOKING_CREATED, Instant.now(), false, target, params),
                n3, new NotificationResponse(n3, InAppNotificationType.BOOKING_DECLINED, Instant.now(), false, target, params));
        when(assembler.assembleForRecipients(any(), eq(Map.of(
                recipientId, Role.SALON_OWNER, recipientB, Role.CLIENT)))).thenReturn(views);

        Map<UUID, PushPlan> plans = dispatcher.prepare(List.of(n1, n2, n3));

        assertThat(plans).containsOnlyKeys(n1, n2, n3);
        assertThat(plans.get(n3).title()).isEqualTo("Ваш запис скасовано");
        assertThat(plans.get(n1).data()).containsEntry("notificationId", n1.toString());
        ArgumentCaptor<List<InAppNotification>> assembled = ArgumentCaptor.forClass(List.class);
        verify(assembler, times(1)).assembleForRecipients(assembled.capture(), any());
        assertThat(assembled.getValue()).containsExactlyInAnyOrder(a1, a2, b1);
        verify(assembler, never()).assemble(any(), any(), any());
        verify(repository, times(1)).findAllForPushDispatch(any());
        verify(userRepository, times(1)).findAllById(any());
        verify(deviceTokenRepository, times(1)).findActiveTokensByUserIdIn(any());
    }

    @Test
    @DisplayName("dispatch hands the pre-loaded tokens to the push executor and touches no repository")
    void should_handOffPlanWithPreloadedTokens_when_dispatch() {
        DeviceTokenRepository.UserDeviceToken token = token(recipientId, "fcm-1");
        Map<String, String> data = Map.of("notificationId", notificationId.toString());
        PushPlan plan = new PushPlan(recipientId, "T", "B", data, List.of(token));
        ArgumentCaptor<List<? extends DeviceTokenRepository.DeviceTokenSummary>> tokens =
                ArgumentCaptor.forClass(List.class);

        dispatcher.dispatch(plan);

        verify(pushNotificationService).sendToDevices(eq(recipientId), tokens.capture(), eq("T"), eq("B"), eq(data));
        assertThat(tokens.getValue()).hasSize(1);
        assertThat(tokens.getValue().get(0)).isSameAs(token);
        verifyNoInteractions(repository, userRepository, deviceTokenRepository, assembler);
    }
}
