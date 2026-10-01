package com.beautica.notification.inapp.push;

import com.beautica.auth.Role;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.NotificationTarget;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import com.beautica.notification.inapp.service.NotificationViewAssembler;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.beautica.notification.service.PushNotificationService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Renders and sends the Android pushes for the {@code INAPP_PUSH} outbox entries of ONE drain batch
 * (phase 339); each entry's {@code aggregate_id} is a feed row id.
 *
 * <p><b>Two steps, so the drainer's "pre-load is the only DB access" contract holds.</b>
 * {@link #prepare} is the ONLY method that touches the database: it runs inside the drainer's
 * pre-load block, once per batch, with a statement count bounded by the number of distinct
 * rows OR recipients (one feed-row query, one recipient query, one device-token query, then ONE
 * {@link NotificationViewAssembler#assembleForRecipients} call for the whole batch). {@link #dispatch}
 * touches no repository and holds no transaction: it only hands a ready {@link PushPlan} to the push
 * executor.
 *
 * <p><b>Same resolver as the read API (D5/D6).</b> The target and display params come from {@link
 * NotificationViewAssembler} — the code behind {@code GET /api/v1/notifications} — fed each
 * recipient's own id and role, so {@code targetKind}/ids in the push equal what the feed returns for
 * the same row, and a recipient who lost access gets the generic body (params {@code null}). Nothing
 * is re-derived here.
 *
 * <p><b>Rendered from live data at prepare time, ids only in the outbox (D5).</b> A renamed
 * counterpart is current and nothing personal ever sat in the outbox. Never a note, phone or e-mail
 * — {@link PushCopy} reads only the params record, which carries none.
 *
 * <p><b>Skips (D8):</b> a row that is gone (90-day purge, user deletion cascade), already read, or
 * whose recipient is gone, deactivated ({@code isActive = false}) or has no active device token
 * yields NO plan, so the drainer marks the entry
 * {@code SENT} without calling FCM.
 *
 * <p>{@link PushNotificationService#sendToDevices} is {@code @Async("pushExecutor")}, so the FCM I/O
 * runs on another thread and no connection is held across it. A rejected hand-off (saturated
 * executor) throws out of {@link #dispatch}; the drainer re-queues the entry WITHOUT counting an
 * attempt. The tokens captured here are re-verified against the DB on the executor thread right before
 * the send (see {@link PushNotificationService#sendToDevices}), so a token re-registered to another
 * user in between is never pushed to its old owner. Once the hand-off is accepted, delivery is at-most-once (see {@link PushNotificationService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InAppPushDispatcher {

    /** Wire version of the data payload; bump on any breaking change to the key set. */
    static final String PAYLOAD_VERSION = "1";

    private final InAppNotificationRepository repository;
    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationViewAssembler assembler;
    private final PushNotificationService pushNotificationService;

    /** Everything one push needs, fully resolved — sending it requires no further DB access. */
    public record PushPlan(
            UUID recipientId,
            String title,
            String body,
            Map<String, String> data,
            List<? extends DeviceTokenRepository.DeviceTokenSummary> tokens) {
    }

    /**
     * Resolves every sendable push of the batch. The result holds an entry only for a feed row that
     * exists, is unread and whose recipient exists and has at least one active device token; a
     * notification id absent from the map is a skip (D8).
     */
    @Transactional(readOnly = true)
    public Map<UUID, PushPlan> prepare(Collection<UUID> notificationIds) {
        if (notificationIds.isEmpty()) {
            return Map.of();
        }
        List<InAppNotification> unread = repository.findAllForPushDispatch(notificationIds).stream()
                .filter(row -> row.getReadAt() == null)
                .toList();
        if (unread.isEmpty()) {
            return Map.of();
        }
        List<UUID> recipientIds = unread.stream().map(InAppNotification::getRecipientUserId).distinct().toList();
        // A deactivated account must not keep receiving pushes (its device tokens can outlive the
        // deactivation), so only ACTIVE recipients get a role entry and thus a plan.
        Map<UUID, Role> roles = userRepository.findAllById(recipientIds).stream()
                .filter(User::isActive)
                .collect(Collectors.toMap(User::getId, User::getRole));
        if (roles.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<DeviceTokenRepository.UserDeviceToken>> tokens =
                deviceTokenRepository.findActiveTokensByUserIdIn(roles.keySet()).stream()
                        .collect(Collectors.groupingBy(DeviceTokenRepository.UserDeviceToken::getUserId));

        Map<UUID, List<InAppNotification>> byRecipient = unread.stream()
                .filter(row -> roles.containsKey(row.getRecipientUserId())
                        && tokens.containsKey(row.getRecipientUserId()))
                .collect(Collectors.groupingBy(InAppNotification::getRecipientUserId,
                        LinkedHashMap::new, Collectors.toList()));

        if (byRecipient.isEmpty()) {
            return Map.of();
        }
        // ONE batch assemble for every recipient's rows (statement count independent of the number of
        // distinct recipients), each row still resolved with its own recipient's id and role.
        Map<UUID, NotificationResponse> views = assembler.assembleForRecipients(
                byRecipient.values().stream().flatMap(List::stream).toList(), roles);
        Map<UUID, PushPlan> plans = new LinkedHashMap<>();
        byRecipient.forEach((recipientId, rows) -> plan(recipientId, roles.get(recipientId), rows,
                tokens.get(recipientId), views, plans));
        log.debug("in-app push prepared requested={} planned={} recipients={}",
                notificationIds.size(), plans.size(), byRecipient.size());
        return plans;
    }

    /** Hands one prepared push to the push executor. No DB access, no transaction. */
    public void dispatch(PushPlan plan) {
        pushNotificationService.sendToDevices(
                plan.recipientId(), plan.tokens(), plan.title(), plan.body(), plan.data());
        log.debug("in-app push dispatched notificationId={}", plan.data().get("notificationId"));
    }

    private void plan(UUID recipientId, Role role, List<InAppNotification> rows,
                      List<DeviceTokenRepository.UserDeviceToken> tokens,
                      Map<UUID, NotificationResponse> views, Map<UUID, PushPlan> plans) {
        boolean client = role == Role.CLIENT;
        for (InAppNotification row : rows) {
            NotificationResponse view = views.get(row.getId());
            plans.put(row.getId(), new PushPlan(
                    recipientId,
                    PushCopy.title(row.getType(), client),
                    PushCopy.body(row.getType(), client, view.params()),
                    dataPayload(row.getId(), view),
                    tokens));
        }
    }

    /** D6 — all values are strings, absent keys omitted. */
    private static Map<String, String> dataPayload(UUID notificationId, NotificationResponse view) {
        NotificationTarget target = view.target();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("v", PAYLOAD_VERSION);
        data.put("notificationId", notificationId.toString());
        data.put("type", view.type().name());
        data.put("targetKind", target.kind().name());
        putIfPresent(data, "bookingId", target.bookingId());
        putIfPresent(data, "appointmentId", target.appointmentId());
        putIfPresent(data, "salonId", target.salonId());
        return data;
    }

    private static void putIfPresent(Map<String, String> data, String key, UUID value) {
        if (value != null) {
            data.put(key, value.toString());
        }
    }
}
