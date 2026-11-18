package com.beautica.notification.service;

import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    @DisplayName("enqueueStatusChanged saves a STATUS_CHANGED entry with PENDING status, 0 attempts, correct aggregateId and null payload")
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
        // Pin @Builder.Default values so a future refactor that drops them is caught.
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // enqueueReviewRequested (Phase 18.2)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueReviewRequested saves exactly one REVIEW_REQUESTED entry with PENDING status, 0 attempts, correct aggregateId and null payload")
    void should_saveReviewRequestedEntry_when_enqueueReviewRequestedCalled() {
        UUID bookingId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueReviewRequested(bookingId);

        verify(outboxRepository, times(1)).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.REVIEW_REQUESTED);
        assertThat(saved.getAggregateId()).isEqualTo(bookingId);
        assertThat(saved.getPayload()).isNull();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(0);
    }

    @Test
    @DisplayName("enqueueReviewRequested throws NullPointerException with message when bookingId is null — no save")
    void should_throwNullPointerException_when_enqueueReviewRequestedCalledWithNull() {
        assertThatThrownBy(() -> service.enqueueReviewRequested(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("bookingId must not be null");
        verify(outboxRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // enqueueClosureReminder (Phase 29.5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueClosureReminder saves exactly one CLOSURE_REMINDER entry with PENDING status, 0 attempts, correct aggregateId and null payload")
    void should_saveClosureReminderEntry_when_enqueueClosureReminderCalled() {
        UUID bookingId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueClosureReminder(bookingId);

        verify(outboxRepository, times(1)).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.CLOSURE_REMINDER);
        assertThat(saved.getAggregateId()).isEqualTo(bookingId);
        assertThat(saved.getPayload()).isNull();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(0);
        // enqueueClosureReminder must perform NO other write — it never reads or writes a
        // Booking, so the only interaction with any repository is this one outboxRepository.save.
        verifyNoMoreInteractions(outboxRepository);
    }

    @Test
    @DisplayName("enqueueClosureReminder throws NullPointerException with message when bookingId is null — no save")
    void should_throwNullPointerException_when_enqueueClosureReminderCalledWithNull() {
        assertThatThrownBy(() -> service.enqueueClosureReminder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("bookingId must not be null");
        verify(outboxRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // enqueueClientCancelled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueClientCancelled saves a CLIENT_CANCELLED entry with PENDING status, 0 attempts, correct aggregateId and null payload")
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
        // Pin @Builder.Default values so a future refactor that drops them is caught.
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(0);
    }

    @Test
    @DisplayName("Phase 496: enqueueClientCancelReceipt / enqueueClientRescheduleReceipt write their OWN payload-less "
            + "rows keyed to the booking; enqueueClientCancelled keeps the pre-496 null payload")
    void should_writeOwnReceiptRows_when_receiptEnqueued() {
        // Arrange
        UUID cancelId = UUID.randomUUID();
        UUID rescheduleId = UUID.randomUUID();
        UUID masterRowId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        // Act
        service.enqueueClientCancelReceipt(cancelId);
        service.enqueueClientRescheduleReceipt(rescheduleId);
        service.enqueueClientCancelled(masterRowId);

        // Assert
        verify(outboxRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        List<NotificationOutboxEntry> rows = captor.getAllValues();
        assertThat(rows).extracting(NotificationOutboxEntry::getEventType).containsExactly(
                OutboxEventType.CLIENT_CANCEL_RECEIPT, OutboxEventType.CLIENT_RESCHEDULE_RECEIPT,
                OutboxEventType.CLIENT_CANCELLED);
        assertThat(rows).extracting(NotificationOutboxEntry::getAggregateId)
                .containsExactly(cancelId, rescheduleId, masterRowId);
        assertThat(rows).extracting(NotificationOutboxEntry::getPayload).containsOnlyNulls();
    }

    @Test
    @DisplayName("Phase 496: the receipt enqueues reject a null booking id")
    void should_throwNullPointerException_when_receiptEnqueuedWithNull() {
        assertThatThrownBy(() -> service.enqueueClientCancelReceipt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.enqueueClientRescheduleReceipt(null)).isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // B520 enqueueStaffInviteSms — happy path with sealed phone + URL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueStaffInviteSms seals the phone and the inviteUrl — neither plaintext ever reaches the payload")
    void should_saveInviteEntry_withSealedPhoneAndUrl_when_enqueueStaffInviteSmsCalled() throws Exception {
        // Arrange — real ObjectMapper exercised; cipher mock returns a known sentinel
        // so we can prove the sealed value (not the plaintext) is what hits the payload.
        UUID inviteTokenId = UUID.randomUUID();
        String phone     = "+380501234567";
        String salonName = "Beauty Studio";
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueStaffInviteSms(inviteTokenId, phone, VALID_INVITE_URL, salonName);

        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.INVITE);
        assertThat(saved.getAggregateId()).isEqualTo(inviteTokenId);

        // Structural JSON assertion — robust to key-ordering changes and falsifiable
        // on extra/missing keys (vs. brittle substring contains/doesNotContain).
        String payload = saved.getPayload();
        assertThat(payload).isNotNull();
        Map<String, String> parsed = MAPPER.readValue(payload,
                new TypeReference<Map<String, String>>() {});
        assertThat(parsed).hasSize(3);
        assertThat(parsed.keySet()).containsExactlyInAnyOrder("phoneSealed", "salonName", "inviteUrlSealed");
        assertThat(parsed.get("phoneSealed")).isEqualTo(SEALED_STUB);
        assertThat(parsed.get("salonName")).isEqualTo(salonName);
        assertThat(parsed.get("inviteUrlSealed")).isEqualTo(SEALED_STUB);
        assertThat(payload).doesNotContain(phone).doesNotContain(VALID_INVITE_URL);

        // Cipher must be called exactly once with each plaintext.
        verify(cipher, times(1)).seal(eq(phone));
        verify(cipher, times(1)).seal(eq(VALID_INVITE_URL));
    }

    // -------------------------------------------------------------------------
    // writeJson failure path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueStaffInviteSms rethrows JsonProcessingException as IllegalStateException")
    void should_throwIllegalState_when_objectMapperFails() throws Exception {
        // Arrange — construct the service with a mocked ObjectMapper that forces failure.
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        NotificationOutboxService failingService =
                new NotificationOutboxService(outboxRepository, failingMapper, cipher);
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);

        // JsonProcessingException has a protected constructor; use an anonymous subclass.
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("forced serialisation failure") {});

        assertThatThrownBy(() -> failingService.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", VALID_INVITE_URL, "Test Salon"))
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
    @DisplayName("enqueueStatusChanged throws NullPointerException with message when bookingId is null")
    void should_throwNullPointerException_when_enqueueStatusChangedCalledWithNull() {
        assertThatThrownBy(() -> service.enqueueStatusChanged(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("bookingId must not be null");
    }

    @Test
    @DisplayName("enqueueClientCancelled throws NullPointerException with message when bookingId is null")
    void should_throwNullPointerException_when_enqueueClientCancelledCalledWithNull() {
        assertThatThrownBy(() -> service.enqueueClientCancelled(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("bookingId must not be null");
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws NullPointerException when inviteTokenId is null — cipher.seal must never run")
    void should_throwNullPointerException_when_enqueueStaffInviteSmsCalledWithNullInviteTokenId() {
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                null, "+380501234567", VALID_INVITE_URL, "Salon"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("inviteTokenId must not be null");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when the phone is null")
    void should_throwIllegalArgumentException_when_phoneIsNull() {
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), null, VALID_INVITE_URL, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phoneE164");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when the phone is blank")
    void should_throwIllegalArgument_when_phoneIsBlank() {
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "   ", VALID_INVITE_URL, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phoneE164");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when salonName is blank")
    void should_throwIllegalArgumentException_when_salonNameIsBlank() {
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", VALID_INVITE_URL, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salonName");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when the phone is not normalised +380 E.164")
    void should_throwIllegalArgumentException_when_phoneIsNotUaE164() {
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "0501234567", VALID_INVITE_URL, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phoneE164");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when salonName exceeds 255 characters")
    void should_throwIllegalArgument_when_salonNameExceedsMaxLength() {
        // 256-character string — one over the cap of 255.
        String oversizedSalonName = "s".repeat(256);

        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", VALID_INVITE_URL, oversizedSalonName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salonName");
        verify(cipher, never()).seal(any());
    }

    // -------------------------------------------------------------------------
    // inviteUrl-specific guard tests (Phase 5.5 amendment — OutboxPayloadCipher integration)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when inviteUrl is null")
    void should_throwIllegalArgument_when_inviteUrlIsNull() {
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", null, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl must not be blank");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when inviteUrl is blank")
    void should_throwIllegalArgument_when_inviteUrlIsBlank() {
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", "   ", "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl must not be blank");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms throws IllegalArgumentException when inviteUrl exceeds 2048 characters")
    void should_throwIllegalArgument_when_inviteUrlExceedsMaxLength() {
        // 2049-character URL — one over the cap. Use a valid prefix so the length
        // check is what fails (not the scheme guard).
        String oversizedUrl = "https://app.beautica.ua/invite/accept?token="
                + "x".repeat(2049 - "https://app.beautica.ua/invite/accept?token=".length());
        assertThat(oversizedUrl).hasSize(2049);

        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", oversizedUrl, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteUrl exceeds maximum length of 2048");
        verify(cipher, never()).seal(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/path",
            "javascript:void(0)",
            "ftp://x/",
            "file:///etc/passwd"
    })
    @DisplayName("enqueueStaffInviteSms rejects disallowed schemes with stable scheme-error message")
    void should_throwIllegalArgument_when_inviteUrlSchemeIsNotHttpsOrLocalhost(String disallowedUrl) {
        // Empty-string "" omitted from the set: it is caught by the earlier blank-guard
        // ("inviteUrl must not be blank") and would not surface the scheme message.
        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", disallowedUrl, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms rejects inviteUrl with uppercase HTTPS:// scheme (case-sensitive prefix check)")
    void should_throwIllegalArgument_when_inviteUrlSchemeIsUppercase() {
        // String.startsWith is case-sensitive, so "HTTPS://..." must be rejected by the
        // scheme guard. Pins the contract that the prefix check is not silently relaxed.
        String uppercaseSchemeUrl = "HTTPS://app.beautica.ua/invite/accept?token=ABC";

        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", uppercaseSchemeUrl, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms rejects scheme-relative inviteUrl (//app.beautica.ua/...)")
    void should_throwIllegalArgument_when_inviteUrlIsSchemeRelative() {
        // No scheme prefix at all — must be rejected by the scheme guard.
        String schemeRelativeUrl = "//app.beautica.ua/invite/accept?token=ABC";

        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", schemeRelativeUrl, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms accepts inviteUrl that is exactly 'https://' (8-char prefix-only boundary)")
    void should_acceptShortHttpsUrl_when_inviteUrlIsExactlyEightChars() {
        // Boundary pin: the current scheme guard accepts any string starting with
        // "https://" — including the bare prefix itself. If a future tightening adds
        // a "host required" check, this test catches the silent contract change.
        String bareHttpsPrefix = "https://";
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);

        service.enqueueStaffInviteSms(UUID.randomUUID(), "+380501234567", bareHttpsPrefix, "Salon");

        verify(cipher, times(1)).seal(eq(bareHttpsPrefix));
    }

    @Test
    @DisplayName("enqueueStaffInviteSms accepts http://localhost inviteUrl for non-prod scenarios")
    void should_acceptHttpLocalhost_when_inviteUrlIsLocalhost() {
        // Arrange
        String localhostUrl = "http://localhost:3000/invite/accept?token=xyz";
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);
        ArgumentCaptor<NotificationOutboxEntry> captor =
                ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueStaffInviteSms(UUID.randomUUID(), "+380501234567", localhostUrl, "Salon");

        verify(cipher, times(1)).seal(eq(localhostUrl));
        verify(outboxRepository).save(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"inviteUrlSealed\"");
        assertThat(payload).contains(SEALED_STUB);
        assertThat(payload).doesNotContain(localhostUrl);
    }

    // -------------------------------------------------------------------------
    // Localhost prefix-spoof rejection (boundary-correct scheme guard)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enqueueStaffInviteSms rejects http://localhost.attacker.com (prefix-spoof)")
    void should_throwIllegalArgument_when_inviteUrlIsLocalhostSpoof() {
        String spoofUrl = "http://localhost.attacker.com/invite/accept?token=abc";

        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", spoofUrl, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");

        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms rejects http://localhostXYZ (suffix-spoof, no separator)")
    void should_throwIllegalArgument_when_inviteUrlIsLocalhostSuffixSpoof() {
        String spoofUrl = "http://localhostXYZ/invite/accept?token=abc";

        assertThatThrownBy(() -> service.enqueueStaffInviteSms(
                UUID.randomUUID(), "+380501234567", spoofUrl, "Salon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");

        verify(cipher, never()).seal(any());
    }

    @Test
    @DisplayName("enqueueStaffInviteSms accepts http://localhost:3000/invite/accept?token=...")
    void should_accept_when_inviteUrlIsLocalhostWithPort() {
        when(cipher.seal(any(String.class))).thenReturn(SEALED_STUB);
        String url = "http://localhost:3000/invite/accept?token=abc";

        service.enqueueStaffInviteSms(UUID.randomUUID(), "+380501234567", url, "Salon");

        // Positive proof the URL passed validation: cipher.seal must have been invoked
        // with the exact plaintext URL.
        verify(cipher).seal(eq(url));
    }

    // -------------------------------------------------------------------------
    // Phase 431 — provider reschedule start + performing-master rows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 431: a provider reschedule row carries initiatedBy=PROVIDER and the new start in epoch seconds")
    void should_writeInitiatedByAndStartsAt_when_providerRescheduleEnqueued() throws Exception {
        UUID bookingId = UUID.randomUUID();
        java.time.Instant newStart = java.time.Instant.parse("2026-11-02T10:00:00Z");
        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueBookingRescheduled(bookingId, true, newStart);

        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(OutboxEventType.BOOKING_RESCHEDULED);
        Map<String, String> parsed = MAPPER.readValue(captor.getValue().getPayload(),
                new TypeReference<Map<String, String>>() {});
        assertThat(parsed).containsOnly(
                Map.entry("initiatedBy", "PROVIDER"),
                Map.entry("startsAt", Long.toString(newStart.getEpochSecond())));
    }

    @Test
    @DisplayName("Phase 431: a performing-master row carries only the actor id")
    void should_writeActorOnlyMasterRow_when_masterIsNotTheActor() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueMasterBookingDeclined(bookingId, UUID.randomUUID(), actor);

        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(OutboxEventType.MASTER_BOOKING_DECLINED);
        assertThat(captor.getValue().getAggregateId()).isEqualTo(bookingId);
        assertThat(MAPPER.readValue(captor.getValue().getPayload(), new TypeReference<Map<String, String>>() {}))
                .containsOnly(Map.entry("actorUserId", actor.toString()));
    }

    @Test
    @DisplayName("Phase 431: no master row when the master is the actor, or detached (no account)")
    void should_notEnqueueMasterRow_when_masterIsActorOrDetached() {
        UUID actor = UUID.randomUUID();

        service.enqueueMasterBookingRescheduled(UUID.randomUUID(), actor, actor);
        service.enqueueMasterBookingDeclined(UUID.randomUUID(), null, actor);

        verifyNoInteractions(outboxRepository);
    }

    // -------------------------------------------------------------------------
    // Phase 491 — enqueueRequestDecided
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Phase 491: REQUEST_DECIDED payload holds exactly kind, requestId, approved and recipientUserId "
            + "(no reason, name or e-mail); aggregate = the recipient")
    void should_saveIdsOnlyPayload_when_enqueueRequestDecidedCalled() throws Exception {
        UUID recipient = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueRequestDecided(RequestKind.CATEGORY_REQUEST, "42", false, recipient);

        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.REQUEST_DECIDED);
        assertThat(saved.getAggregateId()).isEqualTo(recipient);
        assertThat(MAPPER.readValue(saved.getPayload(), new TypeReference<Map<String, Object>>() {}))
                .containsOnly(
                        Map.entry("kind", "CATEGORY_REQUEST"),
                        Map.entry("requestId", "42"),
                        Map.entry("approved", false),
                        Map.entry("recipientUserId", recipient.toString()));
    }

    @Test
    @DisplayName("Phase 491: the payload round-trips through the drain parser (approved flag read as text)")
    void should_roundTripThroughDrainParser_when_enqueueRequestDecidedCalled() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID suggestionId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        service.enqueueRequestDecided(RequestKind.SERVICE_TYPE_SUGGESTION, suggestionId.toString(), true, recipient);

        verify(outboxRepository).save(captor.capture());
        Map<String, String> asText = MAPPER.readValue(captor.getValue().getPayload(),
                new TypeReference<Map<String, String>>() {});
        assertThat(RequestDecisionEvent.parse(asText)).isEqualTo(new RequestDecisionEvent(
                RequestKind.SERVICE_TYPE_SUGGESTION, suggestionId.toString(), true, recipient));
    }

    @Test
    @DisplayName("Phase 491: a null recipient (no requester) saves nothing")
    void should_notSave_when_requestDecidedRecipientIsNull() {
        service.enqueueRequestDecided(RequestKind.CATEGORY_REQUEST, "42", true, null);

        verifyNoInteractions(outboxRepository);
    }

    // -------------------------------------------------------------------------
    // Phase 494 — enqueueBookingReminder
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReminderKind.class)
    @DisplayName("Phase 494: BOOKING_REMINDER payload holds exactly bookingId, kind and claimedAt; aggregate = the booking")
    void should_saveIdsOnlyPayload_when_enqueueBookingReminderCalled(ReminderKind kind) throws Exception {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);

        // Act
        service.enqueueBookingReminder(bookingId, kind, java.time.Instant.ofEpochSecond(1_780_000_000L));

        // Assert
        verify(outboxRepository).save(captor.capture());
        NotificationOutboxEntry saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEventType.BOOKING_REMINDER);
        assertThat(saved.getAggregateId()).isEqualTo(bookingId);
        assertThat(MAPPER.readValue(saved.getPayload(), new TypeReference<Map<String, Object>>() {}))
                .containsOnly(Map.entry("bookingId", bookingId.toString()), Map.entry("kind", kind.name()),
                        Map.entry("claimedAt", "1780000000"));
    }

    @Test
    @DisplayName("Phase 494: a null booking id, kind or claim instant is refused before any write")
    void should_throwAndWriteNothing_when_enqueueBookingReminderArgumentNull() {
        // Act + Assert
        java.time.Instant tick = java.time.Instant.ofEpochSecond(1_780_000_000L);
        assertThatThrownBy(() -> service.enqueueBookingReminder(null, ReminderKind.H24, tick))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.enqueueBookingReminder(UUID.randomUUID(), null, tick))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.enqueueBookingReminder(UUID.randomUUID(), ReminderKind.H24, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(outboxRepository);
    }
}
