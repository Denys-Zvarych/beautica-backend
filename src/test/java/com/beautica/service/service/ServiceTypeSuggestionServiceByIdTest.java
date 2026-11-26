package com.beautica.service.service;

import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.notification.EmailService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.RequestKind;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.entity.ServiceTypeSuggestionStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.service.service.ServiceTypeSuggestionService.DecisionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B503 — {@link ServiceTypeSuggestionService#approveById} / {@link ServiceTypeSuggestionService#rejectById}: the locked
 * by-id front end of the shared decision. Kept apart from {@code ServiceTypeSuggestionServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTypeSuggestionService — decision by id (unit)")
class ServiceTypeSuggestionServiceByIdTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000503");

    @Mock private ServiceTypeSuggestionRepository suggestionRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private EmailService emailService;
    @Mock private ServiceTypePromotionService promotionService;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private NotificationOutboxService notificationOutboxService;

    private ServiceTypeSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new ServiceTypeSuggestionService(suggestionRepository, platformCategoryRepository, emailService,
                new AdminRequestsPortalLink("https://beautica.app"), promotionService, Clock.fixed(NOW, ZoneOffset.UTC), inAppNotificationService,
                notificationOutboxService);
        ReflectionTestUtils.setField(service, "adminEmail", "admin@beautica.test");
    }

    private ServiceTypeSuggestion pending(UUID requester, OffsetDateTime expiresAt) {
        ServiceTypeSuggestion suggestion =
                ServiceTypeSuggestion.ofPending("HAIR", "Балаяж", null, requester);
        ReflectionTestUtils.setField(suggestion, "id", ID);
        return suggestion;
    }

    @Test
    @DisplayName("approveById → promoted, requester notified")
    void should_promoteAndNotify_when_approvedById() {
        UUID requester = UUID.randomUUID();
        ServiceTypeSuggestion suggestion = pending(requester, NOW_UTC.plusDays(3));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(suggestion));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(suggestion.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.APPROVED);
        verify(promotionService).promote(suggestion);
        verify(inAppNotificationService).notifySuggestionDecided(suggestion, true);
        verify(notificationOutboxService).enqueueRequestDecided(RequestKind.SERVICE_TYPE_SUGGESTION, ID.toString(),
                true, requester);
    }

    @Test
    @DisplayName("rejectById → REJECTED with reason, nothing promoted")
    void should_rejectWithReasonWithoutPromotion_when_rejectedById() {
        UUID requester = UUID.randomUUID();
        ServiceTypeSuggestion suggestion = pending(requester, NOW_UTC.plusDays(3));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(suggestion));

        DecisionOutcome outcome = service.rejectById(ID, "Вже існує");

        assertThat(outcome).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(suggestion.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.REJECTED);
        assertThat(suggestion.getDecisionReason()).isEqualTo("Вже існує");
        verifyNoInteractions(promotionService);
        verify(notificationOutboxService).enqueueRequestDecided(RequestKind.SERVICE_TYPE_SUGGESTION, ID.toString(),
                false, requester);
    }

    @Test
    @DisplayName("approveById on a decided row → 409 REQUEST_NOT_PENDING, nothing decided")
    void should_throwRequestNotPending_when_alreadyDecided() {
        ServiceTypeSuggestion suggestion = pending(UUID.randomUUID(), NOW_UTC.plusDays(3));
        suggestion.reject(NOW_UTC);
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> service.approveById(ID))
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING));

        verifyNoInteractions(promotionService, inAppNotificationService, notificationOutboxService);
    }

    @Test
    @DisplayName("rejectById with an unknown id → 404")
    void should_throwNotFound_when_idUnknown() {
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rejectById(ID, null)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(notificationOutboxService);
    }
}
