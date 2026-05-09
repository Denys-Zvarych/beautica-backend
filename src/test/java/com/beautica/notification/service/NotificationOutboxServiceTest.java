package com.beautica.notification.service;

import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationOutboxService — unit")
class NotificationOutboxServiceTest {

    private static final String SEALED_STUB = "v1:STUB-SEALED";
    private static final String VALID_INVITE_URL =
            "https://app.beautica.ua/invite/accept?token=ABC-DEF-rawTokenSentinel";

    @Mock
    private NotificationOutboxRepository outboxRepository;

    @Mock
    private OutboxPayloadCipher cipher;

    // Real ObjectMapper used by default so JSON serialisation is exercised in the
    // happy-path invite test. Test 5 swaps it for a mock via manual service construction.
    // Static constant avoids 5x ObjectMapper construction overhead per test run (Perf INFO).
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotificationOutboxService service;

    @BeforeEach
    void setUp() {
        service = new NotificationOutboxService(outboxRepository, MAPPER, cipher);
    }

    // -------------------------------------------------------------------------
    // enqueueNewBooking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueNewBooking saves a NEW_BOOKING entry with the correct aggregateId and null payload")
    void should_saveNewBookingEntry_when_enqueueNewBookingCalled() {
        UUID bookingId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueNewBooking(bookingId);

        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.NEW_BOOKING);
        assertThat(saved.getAggregateId()).isEqualTo(bookingId);
        assertThat(saved.getPayload()).isNull();
    }

    // -------------------------------------------------------------------------
    // enqueueStatusChanged
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueStatusChanged saves a STATUS_CHANGED entry with the correct aggregateId and null payload")
    void should_saveStatusChangedEntry_when_enqueueStatusChangedCalled() {
        UUID bookingId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueStatusChanged(bookingId);

        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.STATUS_CHANGED);
        assertThat(saved.getAggregateId()).isEqualTo(bookingId);
        assertThat(saved.getPayload()).isNull();
    }

    // -------------------------------------------------------------------------
    // enqueueClientCancelled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueClientCancelled saves a CLIENT_CANCELLED entry with the correct aggregateId and null payload")
    void should_saveClientCancelledEntry_when_enqueueClientCancelledCalled() {
        UUID bookingId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueClientCancelled(bookingId);

        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.CLIENT_CANCELLED);
        assertThat(saved.getAggregateId()).isEqualTo(bookingId);
        assertThat(saved.getPayload()).isNull();
    }

    // -------------------------------------------------------------------------
    // enqueueInvite — happy path with sealed URL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueInvite seals inviteUrl via cipher and stores ciphertext under inviteUrlSealed — plaintext URL never appears")
    void should_saveInviteEntry_withSealedInviteUrl_when_enqueueInviteCalled() {
        // Arrange — real ObjectMapper exercised; cipher mock returns a known sentinel
        // so we can prove the sealed value (not the plaintext) is what hits the payload.
        UUID inviteTokenId = UUID.randomUUID();
        String toEmail   = "master@example.com";
        String salonName = "Beauty Studio";
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueInvite(inviteTokenId, toEmail, VALID_INVITE_URL, salonName);

        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.INVITE);
        assertThat(saved.getAggregateId()).isEqualTo(inviteTokenId);

        // Payload must contain email, salonName, and the sealed invite URL — but NOT
        // the plaintext URL or the raw token.
        String payload = saved.getPayload();
        assertThat(payload).isNotNull();
        assertThat(payload).contains("\"email\"");
        assertThat(payload).contains("\"salonName\"");
        assertThat(payload).contains("\"inviteUrlSealed\"");
        assertThat(payload).contains(toEmail);
        assertThat(payload).contains(salonName);
        assertThat(payload).contains(SEALED_STUB);
        // Plaintext URL substrings must never appear.
        assertThat(payload).doesNotContain("token=", "rawTokenSentinel", "https://app.beautica.ua");

        // Cipher must be called exactly once with the actual plaintext URL.
        verify(cipher, times(1)).seal(eq(VALID_INVITE_URL));
    }

    // -------------------------------------------------------------------------
    // writeJson failure path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueInvite rethrows JsonProcessingException as IllegalStateException")
    void should_throwIllegalState_when_objectMapperFails() throws Exception {
        // Arrange — construct the service with a mocked ObjectMapper that forces failure.
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        NotificationOutboxService failingService =
                new NotificationOutboxService(outboxRepository, failingMapper, cipher);
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);

        // JsonProcessingException has a protected constructor; use an anonymous subclass.
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("forced serialisation failure") {});

        assertThatThrownBy(() -> failingService.enqueueInvite(
                UUID.randomUUID(), "x@example.com", VALID_INVITE_URL, "Test Salon"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot serialize outbox payload")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    // -------------------------------------------------------------------------
    // Null / blank / length guard tests (Security MEDIUM fixes)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueNewBooking throws NullPointerException with message when bookingId is null")
    void should_throwNullPointerException_when_bookingIdIsNull() {
        assertThatThrownBy(() -> service.enqueueNewBooking(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("bookingId must not be null");
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when toEmail is null")
    void should_throwIllegalArgumentException_when_toEmailIsNull() {
        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), null, VALID_INVITE_URL, "Salon"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when salonName is blank")
    void should_throwIllegalArgumentException_when_salonNameIsBlank() {
        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), "test@test.com", VALID_INVITE_URL, " "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when toEmail exceeds 254 characters")
    void should_throwIllegalArgumentException_when_toEmailExceeds254Characters() {
        // 255-character string — one over the RFC 5321 / DB cap of 254.
        String oversizedEmail = "a".repeat(255);

        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), oversizedEmail, VALID_INVITE_URL, "Salon"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cipher, never()).seal(any());
    }

    // -------------------------------------------------------------------------
    // inviteUrl-specific guard tests (Phase 5.5 amendment — OutboxPayloadCipher integration)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when inviteUrl is null")
    void should_throwIllegalArgument_when_inviteUrlIsNull() {
        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), "test@test.com", null, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl must not be blank");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when inviteUrl is blank")
    void should_throwIllegalArgument_when_inviteUrlIsBlank() {
        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), "test@test.com", "   ", "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl must not be blank");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when inviteUrl exceeds 2048 characters")
    void should_throwIllegalArgument_when_inviteUrlExceedsMaxLength() {
        // 2049-character URL — one over the cap. Use a valid prefix so the length
        // check is what fails (not the scheme guard).
        String oversizedUrl = "https://app.beautica.ua/invite/accept?token="
                + "x".repeat(2049 - "https://app.beautica.ua/invite/accept?token=".length());
        assertThat(oversizedUrl).hasSize(2049);

        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), "test@test.com", oversizedUrl, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl exceeds maximum length of 2048");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when inviteUrl scheme is not https or http://localhost")
    void should_throwIllegalArgument_when_inviteUrlSchemeIsNotHttpsOrLocalhost() {
        // Plain http:// (non-localhost) must be rejected.
        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), "test@test.com", "http://example.com/invite/accept?token=x", "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl must use https:// scheme or http://localhost for non-prod");

        // javascript: scheme must be rejected.
        assertThatThrownBy(() -> service.enqueueInvite(
                UUID.randomUUID(), "test@test.com", "javascript:void(0)", "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl must use https:// scheme or http://localhost for non-prod");

        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueInvite accepts http://localhost inviteUrl for non-prod scenarios")
    void should_acceptHttpLocalhost_when_inviteUrlIsLocalhost() {
        // Arrange
        String localhostUrl = "http://localhost:3000/invite/accept?token=xyz";
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueInvite(UUID.randomUUID(), "test@test.com", localhostUrl, "Salon");

        verify(cipher, times(1)).seal(eq(localhostUrl));
        verify(outboxRepository).save(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"inviteUrlSealed\"");
        assertThat(payload).contains(SEALED_STUB);
        assertThat(payload).doesNotContain(localhostUrl);
    }
}
