package com.beautica.notification.service;

import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes notification outbox entries atomically within the caller's transaction.
 *
 * <p><strong>Outbox pattern contract:</strong>
 * <ul>
 *   <li>Every public method uses {@code Propagation.MANDATORY} — the caller MUST hold
 *       an active transaction. If no transaction is present,
 *       {@link org.springframework.transaction.IllegalTransactionStateException} is thrown
 *       immediately, preventing a dangling outbox row from being created outside the
 *       domain event's transaction boundary.
 *   <li>These methods perform one INSERT each. They do NOT call
 *       {@link NotificationService} — notification delivery is the responsibility of
 *       the separate drain scheduler, which runs outside any booking transaction.
 *   <li>Do NOT annotate with {@code @Async} — async dispatch decouples the write from
 *       the transaction boundary, risking notifications for rolled-back domain events.
 * </ul>
 *
 * <p><strong>Security note ({@code enqueueInvite}):</strong> The raw invite URL is
 * never stored in plaintext; the AES-GCM ciphertext is persisted under
 * {@code inviteUrlSealed} (see {@link OutboxPayloadCipher}). The drain worker decrypts
 * the sealed value at send time (Phase 5.6).
 */
@Service
@RequiredArgsConstructor
public class NotificationOutboxService {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_SALON_NAME_LENGTH = 255;
    private static final int MAX_INVITE_URL_LENGTH = 2048;

    private final NotificationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final OutboxPayloadCipher cipher;

    /**
     * Enqueues a {@code NEW_BOOKING} notification entry.
     *
     * @param bookingId the UUID of the newly created booking
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueNewBooking(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.NEW_BOOKING, bookingId, null);
    }

    /**
     * Enqueues a {@code STATUS_CHANGED} notification entry.
     *
     * @param bookingId the UUID of the booking whose status changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueStatusChanged(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.STATUS_CHANGED, bookingId, null);
    }

    /**
     * Enqueues a {@code CLIENT_CANCELLED} notification entry.
     *
     * <p><strong>Phase 5 wire-up note:</strong> Wire this in
     * {@code BookingService.cancelBooking} when role-specific notification templates that
     * distinguish client cancellations from other status changes are implemented.
     * Until then, {@code enqueueStatusChanged} covers all cancellation transitions.
     *
     * @param bookingId the UUID of the booking the client cancelled
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueClientCancelled(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.CLIENT_CANCELLED, bookingId, null);
    }

    /**
     * Enqueues an {@code INVITE} notification entry.
     *
     * <p><strong>Security:</strong> {@code inviteUrl} is encrypted with
     * {@link OutboxPayloadCipher} before persistence; plaintext never reaches the DB.
     * The sealed ciphertext is stored under the {@code inviteUrlSealed} key. The drain
     * worker decrypts the sealed value at send time (Phase 5.6).
     *
     * <p><strong>{@code inviteUrl} validation:</strong>
     * <ul>
     *   <li>Must not be {@code null} or blank — throws {@link IllegalArgumentException}.</li>
     *   <li>Length must be {@code <= 2048} characters — throws {@link IllegalArgumentException}.</li>
     *   <li>Must start with {@code https://} or {@code http://localhost} (mirroring the
     *       guard in {@code InviteService.buildInviteLink}) — throws
     *       {@link IllegalArgumentException}.</li>
     * </ul>
     * Validation runs <strong>before</strong> {@link OutboxPayloadCipher#seal(String)}
     * is invoked, so malformed input never reaches the cipher.
     *
     * @param inviteTokenId the UUID of the {@code InviteToken} row (used as aggregateId
     *                      by the drain worker to look up the row at send time)
     * @param toEmail       the recipient's e-mail address
     * @param inviteUrl     the fully-formed invite URL embedding the raw one-time token;
     *                      will be encrypted before persistence — never logged
     * @param salonName     the salon name displayed in the invite e-mail
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueInvite(UUID inviteTokenId, String toEmail, String inviteUrl, String salonName) {
        Objects.requireNonNull(inviteTokenId, "inviteTokenId must not be null");
        if (toEmail == null || toEmail.isBlank()) {
            throw new IllegalArgumentException("toEmail must not be blank");
        }
        if (toEmail.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("toEmail exceeds maximum length of " + MAX_EMAIL_LENGTH);
        }
        if (salonName == null || salonName.isBlank()) {
            throw new IllegalArgumentException("salonName must not be blank");
        }
        if (salonName.length() > MAX_SALON_NAME_LENGTH) {
            throw new IllegalArgumentException("salonName exceeds maximum length of " + MAX_SALON_NAME_LENGTH);
        }
        if (inviteUrl == null || inviteUrl.isBlank()) {
            throw new IllegalArgumentException("inviteUrl must not be blank");
        }
        if (inviteUrl.length() > MAX_INVITE_URL_LENGTH) {
            throw new IllegalArgumentException("inviteUrl exceeds maximum length of " + MAX_INVITE_URL_LENGTH);
        }
        if (!inviteUrl.startsWith("https://") && !inviteUrl.startsWith("http://localhost")) {
            throw new IllegalArgumentException(
                    "inviteUrl must use https:// scheme or http://localhost for non-prod");
        }
        String sealedInviteUrl = cipher.seal(inviteUrl);
        String payload = writeJson(Map.of(
                "email", toEmail,
                "salonName", salonName,
                "inviteUrlSealed", sealedInviteUrl));
        save(OutboxEventType.INVITE, inviteTokenId, payload);
    }

    // --- private helpers ---

    private void save(OutboxEventType eventType, UUID aggregateId, String payload) {
        outboxRepository.save(NotificationOutboxEntry.builder()
                .eventType(eventType)
                .aggregateId(aggregateId)
                .payload(payload)
                .build()); // status defaults to PENDING, attempts to 0 via @Builder.Default
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox payload", e);
        }
    }
}
