package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationOutboxDrainWorker — unit")
class NotificationOutboxDrainWorkerTest {

    @Mock
    private NotificationOutboxRepository outboxRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OutboxPayloadCipher cipher;

    @InjectMocks
    private NotificationOutboxDrainWorker worker;

    // Shared real mapper — avoids repeated construction overhead across tests that need real JSON.
    private static final ObjectMapper REAL_MAPPER = new ObjectMapper();

    // ── Helper ────────────────────────────────────────────────────────────────

    private NotificationOutboxEntry entry(OutboxEventType type, int attempts, String payload, UUID aggregateId) {
        NotificationOutboxEntry e = new NotificationOutboxEntry();
        e.setEventType(type);
        e.setAttempts(attempts);
        e.setStatus(OutboxStatus.PENDING);
        e.setAggregateId(aggregateId);
        e.setPayload(payload);
        return e;
    }

    // ── Test 1: NEW_BOOKING dispatches to notifyNewBooking ────────────────────

    @Test
    @DisplayName("notifyNewBooking is called and status set to SENT when NEW_BOOKING entry processed")
    void should_callNotifyNewBooking_when_newBookingEntryProcessed() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.NEW_BOOKING, 0, null, bookingId);
        Booking booking = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));

        worker.drain();

        verify(notificationService, times(1)).notifyNewBooking(booking);
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    // ── Test 2: STATUS_CHANGED dispatches to notifyBookingStatusChanged ───────

    @Test
    @DisplayName("notifyBookingStatusChanged is called and status set to SENT when STATUS_CHANGED entry processed")
    void should_callNotifyStatusChanged_when_statusChangedEntryProcessed() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.STATUS_CHANGED, 0, null, bookingId);
        Booking booking = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));

        worker.drain();

        verify(notificationService, times(1)).notifyBookingStatusChanged(booking);
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    // ── Test 3a: INVITE happy path — sealed URL is decrypted via cipher ───────

    @Test
    @DisplayName("inviteUrlSealed is decrypted and forwarded to sendInviteEmail when INVITE entry processed")
    void should_decryptInviteUrlSealedAndDispatch_when_inviteEntryProcessed() throws Exception {
        // Arrange — use a real ObjectMapper so JSON deserialisation is actually exercised.
        NotificationOutboxDrainWorker workerWithRealMapper = new NotificationOutboxDrainWorker(
                outboxRepository, notificationService, bookingRepository, REAL_MAPPER, cipher);

        UUID aggregateId = UUID.randomUUID();
        String sealed = "v1:STUB-SEALED";
        String decryptedUrl = "https://app.beautica.ua/invite/accept?token=ABC123";
        String payload = REAL_MAPPER.writeValueAsString(Map.of(
                "email", "a@b.com",
                "salonName", "Beauty",
                "inviteUrlSealed", sealed));
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.INVITE, 0, payload, aggregateId);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        // INVITE entries are excluded from the bookingIds set — stub returns empty to be safe.
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of());
        when(cipher.open(sealed)).thenReturn(decryptedUrl);

        // Act
        workerWithRealMapper.drain();

        // Assert — decrypted URL is forwarded; aggregateId is NOT used as URL.
        verify(cipher, times(1)).open(eq(sealed));
        verify(notificationService, times(1))
                .sendInviteEmail("a@b.com", decryptedUrl, "Beauty");
        // Forward-dependency regex check from Phase 5.11 QA contract.
        assertThat(decryptedUrl).matches("^https?://.+/invite/accept\\?token=.+");
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    // ── Test 3b: INVITE corrupt ciphertext → dead-letter after MAX_ATTEMPTS ──

    @Test
    @DisplayName("entry transitions to DEAD without dispatch when cipher.open throws on corrupt ciphertext")
    void should_dead_letter_when_cipherOpenThrows() throws Exception {
        NotificationOutboxDrainWorker workerWithRealMapper = new NotificationOutboxDrainWorker(
                outboxRepository, notificationService, bookingRepository, REAL_MAPPER, cipher);

        UUID aggregateId = UUID.randomUUID();
        String payload = REAL_MAPPER.writeValueAsString(Map.of(
                "email", "a@b.com",
                "salonName", "Beauty",
                "inviteUrlSealed", "v1:CORRUPT"));
        // attempts=2 so this third attempt promotes the entry to DEAD.
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.INVITE, 2, payload, aggregateId);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of());
        when(cipher.open(anyString())).thenThrow(new IllegalStateException(
                "OutboxPayloadCipher open failed — corrupt or tampered ciphertext"));

        workerWithRealMapper.drain();

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(outboxEntry.getAttempts()).isEqualTo(3);
        assertThat(outboxEntry.getLastError())
                .isNotNull()
                .contains("OutboxPayloadCipher")
                // Sanitiser must not leak plaintext URL fragments or sealed Base64.
                .doesNotContain("v1:CORRUPT")
                .doesNotContain("https://")
                .doesNotContain("token=");
        verify(notificationService, never()).sendInviteEmail(any(), any(), any());
    }

    // ── Test 3c: INVITE missing inviteUrlSealed → dead-letter, cipher untouched ─

    @Test
    @DisplayName("entry transitions to DEAD without invoking cipher when inviteUrlSealed is missing from payload")
    void should_dead_letter_when_inviteUrlSealedMissing() throws Exception {
        NotificationOutboxDrainWorker workerWithRealMapper = new NotificationOutboxDrainWorker(
                outboxRepository, notificationService, bookingRepository, REAL_MAPPER, cipher);

        UUID aggregateId = UUID.randomUUID();
        // Older-schema row: email + salonName only, no inviteUrlSealed key.
        String payload = REAL_MAPPER.writeValueAsString(Map.of(
                "email", "a@b.com",
                "salonName", "Beauty"));
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.INVITE, 2, payload, aggregateId);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of());

        workerWithRealMapper.drain();

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(outboxEntry.getAttempts()).isEqualTo(3);
        assertThat(outboxEntry.getLastError())
                .isNotNull()
                .contains("missing inviteUrlSealed");
        verify(cipher, never()).open(anyString());
        verify(notificationService, never()).sendInviteEmail(any(), any(), any());
    }

    // ── Test 4: failure below MAX_ATTEMPTS → PENDING ──────────────────────────

    @Test
    @DisplayName("status set to PENDING and attempts incremented when delivery fails below max attempts")
    void should_setStatusToPending_when_deliveryFailsAndAttemptsLessThanMax() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.NEW_BOOKING, 0, null, bookingId);
        Booking booking = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));
        doThrow(new RuntimeException("dispatch error")).when(notificationService).notifyNewBooking(booking);

        worker.drain();

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEntry.getAttempts()).isEqualTo(1);
        assertThat(outboxEntry.getLastError()).isNotNull();
    }

    // ── Test 5: failure at MAX_ATTEMPTS → DEAD ────────────────────────────────

    @Test
    @DisplayName("status set to DEAD and attempts set to MAX when delivery fails at max attempts")
    void should_setStatusToDead_when_deliveryFailsAtMaxAttempts() {
        UUID bookingId = UUID.randomUUID();
        // attempts=2 means this is the 3rd attempt (MAX_ATTEMPTS=3), so it goes DEAD.
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.NEW_BOOKING, 2, null, bookingId);
        Booking booking = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));
        doThrow(new RuntimeException("persistent failure")).when(notificationService).notifyNewBooking(booking);

        worker.drain();

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(outboxEntry.getAttempts()).isEqualTo(3);
    }

    // ── Test 6: lastError is truncated to 500 characters ─────────────────────

    @Test
    @DisplayName("lastError is truncated to 500 characters when exception message exceeds limit")
    void should_truncateLastError_when_exceptionMessageExceeds500Chars() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.NEW_BOOKING, 0, null, bookingId);
        Booking booking = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));
        doThrow(new RuntimeException("x".repeat(600))).when(notificationService).notifyNewBooking(booking);

        worker.drain();

        assertThat(outboxEntry.getLastError()).isNotNull();
        assertThat(outboxEntry.getLastError().length()).isLessThanOrEqualTo(500);
    }

    // ── Test 7: mixed batch — success + failure proceed independently ─────────

    @Test
    @DisplayName("entire batch is processed: successful entries get SENT, failed entry gets PENDING")
    void should_processEntireBatch_when_mixedSuccessAndFailure() {
        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();
        UUID bookingId3 = UUID.randomUUID();

        NotificationOutboxEntry entry1 = entry(OutboxEventType.NEW_BOOKING,    0, null, bookingId1);
        NotificationOutboxEntry entry2 = entry(OutboxEventType.NEW_BOOKING,    0, null, bookingId2);
        NotificationOutboxEntry entry3 = entry(OutboxEventType.STATUS_CHANGED, 0, null, bookingId3);

        Booking booking1 = mock(Booking.class);
        Booking booking2 = mock(Booking.class);
        Booking booking3 = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(entry1, entry2, entry3));
        when(booking1.getId()).thenReturn(bookingId1);
        when(booking2.getId()).thenReturn(bookingId2);
        when(booking3.getId()).thenReturn(bookingId3);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking1, booking2, booking3));
        doThrow(new RuntimeException("status change failed"))
                .when(notificationService).notifyBookingStatusChanged(booking3);

        worker.drain();

        assertThat(entry1.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(entry2.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(entry3.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(outboxRepository, times(1)).claimPendingBatch(50);
    }

    // ── Test 8: URL query string token is redacted from lastError ─────────────

    @Test
    @DisplayName("query-string token is redacted from lastError when exception message contains ?token=...")
    void should_redactQueryString_when_exceptionMessageContainsTokenInQueryParam() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.NEW_BOOKING, 0, null, bookingId);
        Booking booking = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));
        doThrow(new RuntimeException("Request failed: https://api.example.com/send?token=abc123secret"))
                .when(notificationService).notifyNewBooking(booking);

        worker.drain();

        assertThat(outboxEntry.getLastError())
                .isNotNull()
                .doesNotContain("abc123secret");
    }

    // ── Test 9: JWT-shaped token is redacted from lastError ───────────────────

    @Test
    @DisplayName("JWT-shaped value is redacted from lastError when exception message contains header.payload.signature")
    void should_redactJwtShape_when_exceptionMessageContainsJwtToken() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.NEW_BOOKING, 0, null, bookingId);
        Booking booking = mock(Booking.class);

        // Each segment must be 20+ chars to trigger the JWT pattern.
        String segment = "a".repeat(20);
        String jwtLike = segment + "." + segment + "." + segment;

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));
        doThrow(new RuntimeException("Auth header invalid: " + jwtLike))
                .when(notificationService).notifyNewBooking(booking);

        worker.drain();

        assertThat(outboxEntry.getLastError())
                .isNotNull()
                .doesNotContain(jwtLike);
    }

    // ── Test 10: CLIENT_CANCELLED arm dispatches to notifyClientCancelled ─────

    @Test
    @DisplayName("notifyClientCancelled is called and status set to SENT when CLIENT_CANCELLED entry processed")
    void should_callNotifyClientCancelled_when_clientCancelledEntryProcessed() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.CLIENT_CANCELLED, 0, null, bookingId);
        Booking booking = mock(Booking.class);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(booking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of(booking));

        worker.drain();

        verify(notificationService, times(1)).notifyClientCancelled(booking);
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    // ── Test 11: booking deleted between enqueue and drain → PENDING ──────────

    @Test
    @DisplayName("status set to PENDING and lastError populated when booking not found in cache")
    void should_setStatusToPending_when_bookingNotFoundInCache() {
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry outboxEntry = entry(OutboxEventType.NEW_BOOKING, 0, null, bookingId);

        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(outboxEntry));
        when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of());

        worker.drain();

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEntry.getAttempts()).isEqualTo(1);
        assertThat(outboxEntry.getLastError()).isNotNull();
    }
}
