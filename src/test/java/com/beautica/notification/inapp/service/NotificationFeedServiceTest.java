package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.notification.inapp.dto.MarkAllReadResponse;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.NotificationTarget;
import com.beautica.notification.inapp.dto.TargetKind;
import com.beautica.notification.inapp.dto.UnreadCountResponse;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationFeedService — mapping, unread-count cap, mark-read idempotency, read-all cutoff")
class NotificationFeedServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Mock
    private InAppNotificationRepository repository;
    @Mock
    private NotificationViewAssembler assembler;

    private NotificationFeedService service;

    private final UUID recipientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new NotificationFeedService(repository, assembler, clock);
    }

    @Test
    @DisplayName("should_returnAssembledPage_when_listingFeed")
    void should_returnAssembledPage_when_listingFeed() {
        InAppNotification row = mockRow();
        Page<InAppNotification> page = new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1);
        when(repository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(eq(recipientId), any(Pageable.class)))
                .thenReturn(page);
        NotificationResponse mapped = new NotificationResponse(
                row.getId(), InAppNotificationType.BOOKING_CREATED, FIXED_NOW, false,
                new NotificationTarget(TargetKind.BOOKING, UUID.randomUUID(), null, null), null);
        when(assembler.assemble(eq(List.of(row)), eq(recipientId), eq(Role.CLIENT))).thenReturn(List.of(mapped));

        PageResponse<NotificationResponse> result = service.listFeed(recipientId, Role.CLIENT, 0, 20);

        assertThat(result.data()).containsExactly(mapped);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("should_capAt99_when_rawUnreadCountIsAtOrAboveTheCap")
    void should_capAt99_when_rawUnreadCountIsAtOrAboveTheCap() {
        when(repository.countUnreadCapped(recipientId)).thenReturn(100L);

        UnreadCountResponse result = service.unreadCount(recipientId);

        assertThat(result.count()).isEqualTo(99);
    }

    @Test
    @DisplayName("should_returnRawCount_when_belowTheCap")
    void should_returnRawCount_when_belowTheCap() {
        when(repository.countUnreadCapped(recipientId)).thenReturn(7L);

        UnreadCountResponse result = service.unreadCount(recipientId);

        assertThat(result.count()).isEqualTo(7);
    }

    @Test
    @DisplayName("should_returnNormally_when_markReadUpdatesOneRow")
    void should_returnNormally_when_markReadUpdatesOneRow() {
        UUID notificationId = UUID.randomUUID();
        when(repository.markRead(eq(notificationId), eq(recipientId), eq(FIXED_NOW))).thenReturn(1);

        service.markRead(recipientId, notificationId);

        verify(repository, never()).existsByIdAndRecipientUserId(any(), any());
    }

    @Test
    @DisplayName("should_returnNormally_when_markReadReplaysAnAlreadyReadOwnRow_idempotent")
    void should_returnNormally_when_markReadReplaysAnAlreadyReadOwnRow_idempotent() {
        UUID notificationId = UUID.randomUUID();
        when(repository.markRead(eq(notificationId), eq(recipientId), eq(FIXED_NOW))).thenReturn(0);
        when(repository.existsByIdAndRecipientUserId(notificationId, recipientId)).thenReturn(true);

        service.markRead(recipientId, notificationId);
    }

    @Test
    @DisplayName("should_throwNotFound_when_markReadTargetsMissingOrForeignId")
    void should_throwNotFound_when_markReadTargetsMissingOrForeignId() {
        UUID notificationId = UUID.randomUUID();
        when(repository.markRead(eq(notificationId), eq(recipientId), eq(FIXED_NOW))).thenReturn(0);
        when(repository.existsByIdAndRecipientUserId(notificationId, recipientId)).thenReturn(false);

        assertThatThrownBy(() -> service.markRead(recipientId, notificationId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_defaultCutoffToNow_when_upToIsNull")
    void should_defaultCutoffToNow_when_upToIsNull() {
        when(repository.markAllRead(eq(recipientId), any(Instant.class), eq(FIXED_NOW))).thenReturn(3);

        MarkAllReadResponse result = service.markAllRead(recipientId, null);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).markAllRead(eq(recipientId), cutoffCaptor.capture(), eq(FIXED_NOW));
        assertThat(cutoffCaptor.getValue()).isEqualTo(FIXED_NOW);
        assertThat(result.updated()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_useCallerSuppliedCutoff_when_upToIsProvided")
    void should_useCallerSuppliedCutoff_when_upToIsProvided() {
        Instant clientCutoff = FIXED_NOW.minusSeconds(3600);
        when(repository.markAllRead(recipientId, clientCutoff, FIXED_NOW)).thenReturn(5);

        MarkAllReadResponse result = service.markAllRead(recipientId, clientCutoff);

        verify(repository, times(1)).markAllRead(recipientId, clientCutoff, FIXED_NOW);
        assertThat(result.updated()).isEqualTo(5);
    }

    @Test
    @DisplayName("should_throwBusinessException_when_upToIsFarInTheFuture")
    void should_throwBusinessException_when_upToIsFarInTheFuture() {
        Instant farFuture = FIXED_NOW.plusSeconds(600); // 10 minutes ahead, past the 5-minute skew

        assertThatThrownBy(() -> service.markAllRead(recipientId, farFuture))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).markAllRead(any(), any(), any());
    }

    @Test
    @DisplayName("should_markAllRead_when_upToIsJustInsideTheAllowedFutureSkew")
    void should_markAllRead_when_upToIsJustInsideTheAllowedFutureSkew() {
        Instant nearFuture = FIXED_NOW.plusSeconds(60); // 1 minute ahead, within the 5-minute skew
        when(repository.markAllRead(recipientId, nearFuture, FIXED_NOW)).thenReturn(2);

        MarkAllReadResponse result = service.markAllRead(recipientId, nearFuture);

        verify(repository, times(1)).markAllRead(recipientId, nearFuture, FIXED_NOW);
        assertThat(result.updated()).isEqualTo(2);
    }

    private InAppNotification mockRow() {
        return org.mockito.Mockito.mock(InAppNotification.class);
    }
}
