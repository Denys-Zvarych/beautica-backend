package com.beautica.notification.service;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationOutboxService — unit")
class NotificationOutboxServiceTest {

    @Mock
    private NotificationOutboxRepository outboxRepository;

    // Real ObjectMapper used by default so JSON serialisation is exercised in test 4.
    // Test 5 swaps it for a mock via manual service construction.
    // Static constant avoids 5x ObjectMapper construction overhead per test run (Perf INFO).
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotificationOutboxService service;

    @BeforeEach
    void setUp() {
        service = new NotificationOutboxService(outboxRepository, MAPPER);
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
    // enqueueInvite
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueInvite saves an INVITE entry with email and salonName in the JSON payload — no raw token URL")
    void should_saveInviteEntry_withEmailAndSalonName_when_enqueueInviteCalled() throws Exception {
        // Arrange — service constructed in @BeforeEach with a real ObjectMapper so JSON
        // serialisation is actually exercised rather than stubbed.
        UUID inviteTokenId = UUID.randomUUID();
        String toEmail    = "master@example.com";
        String salonName  = "Beauty Studio";
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueInvite(inviteTokenId, toEmail, salonName);

        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.INVITE);
        assertThat(saved.getAggregateId()).isEqualTo(inviteTokenId);

        // Payload must be valid JSON containing both keys — raw token URL must NOT appear.
        String payload = saved.getPayload();
        assertThat(payload).isNotNull();
        assertThat(payload).contains("\"email\"");
        assertThat(payload).contains("\"salonName\"");
        assertThat(payload).contains(toEmail);
        assertThat(payload).contains(salonName);
        assertThat(payload).doesNotContain("inviteUrl");
        assertThat(payload).doesNotContain("token");
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
                new NotificationOutboxService(outboxRepository, failingMapper);

        // JsonProcessingException has a protected constructor; use an anonymous subclass.
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("forced serialisation failure") {});

        assertThatThrownBy(() -> failingService.enqueueInvite(
                UUID.randomUUID(), "x@example.com", "Test Salon"))
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
        assertThatThrownBy(() -> service.enqueueInvite(UUID.randomUUID(), null, "Salon"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when salonName is blank")
    void should_throwIllegalArgumentException_when_salonNameIsBlank() {
        assertThatThrownBy(() -> service.enqueueInvite(UUID.randomUUID(), "test@test.com", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("enqueueInvite throws IllegalArgumentException when toEmail exceeds 254 characters")
    void should_throwIllegalArgumentException_when_toEmailExceedsMaxLength() {
        // 255-character string — one over the RFC 5321 / DB cap of 254.
        String oversizedEmail = "a".repeat(255);

        assertThatThrownBy(() -> service.enqueueInvite(UUID.randomUUID(), oversizedEmail, "Salon"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
