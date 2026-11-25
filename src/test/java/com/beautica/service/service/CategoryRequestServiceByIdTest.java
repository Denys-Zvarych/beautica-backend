package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.RequestKind;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.service.CategoryRequestService.DecisionOutcome;
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
 * B503 — {@link CategoryRequestService#approveById} / {@link CategoryRequestService#rejectById}: the locked by-id front
 * end of the shared decision. Kept apart from {@code CategoryRequestServiceTest} so that file (the token-path guard)
 * stays byte-for-byte unchanged.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryRequestService — decision by id (unit)")
class CategoryRequestServiceByIdTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final Long ID = 503L;

    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private TokenGenerator tokenGenerator;
    @Mock private EmailService emailService;
    @Mock private PublicBaseUrlProperties publicBaseUrlProperties;
    @Mock private ServiceTypeSuggestionService serviceTypeSuggestionService;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private NotificationOutboxService notificationOutboxService;

    private CategoryRequestService service;

    @BeforeEach
    void setUp() {
        service = new CategoryRequestService(platformCategoryRepository, tokenGenerator, emailService,
                publicBaseUrlProperties, serviceTypeSuggestionService, Clock.fixed(NOW, ZoneOffset.UTC),
                inAppNotificationService, notificationOutboxService);
        ReflectionTestUtils.setField(service, "adminEmail", "admin@beautica.test");
    }

    private PlatformCategory pending(UUID requester, String initialServiceName, OffsetDateTime expiresAt) {
        PlatformCategory category = PlatformCategory.ofPendingRequest("NAIL_ART", "Нейл-арт", requester, "hash",
                NOW_UTC, expiresAt, initialServiceName);
        ReflectionTestUtils.setField(category, "id", ID);
        return category;
    }

    @Test
    @DisplayName("approveById → APPROVED + active, initial service spawned, requester notified, token cleared")
    void should_activateSpawnSuggestionNotifyAndClearToken_when_approvedById() {
        UUID requester = UUID.randomUUID();
        PlatformCategory category = pending(requester, "Френч", NOW_UTC.plusDays(3));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(category));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(category.getStatus()).isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(category.isActive()).isTrue();
        assertThat(category.getTokenHash()).as("email link invalidated").isNull();
        verify(serviceTypeSuggestionService).submitSuggestionInternal("NAIL_ART", "Френч", null, requester);
        verify(inAppNotificationService).notifyCategoryRequestDecided(category, true);
        verify(notificationOutboxService).enqueueRequestDecided(RequestKind.CATEGORY_REQUEST, "503", true, requester);
        verify(platformCategoryRepository, never()).findByTokenHashForUpdate(anyString());
    }

    @Test
    @DisplayName("approveById ignores an expired email token — the portal is the authority")
    void should_approve_when_emailTokenExpired() {
        PlatformCategory category = pending(UUID.randomUUID(), null, NOW_UTC.minusDays(1));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(category));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome).as("token expiry bounds the link, not the portal").isEqualTo(DecisionOutcome.APPROVED);
    }

    @Test
    @DisplayName("rejectById → REJECTED, inactive, reason stored")
    void should_rejectWithReasonInactive_when_rejectedById() {
        UUID requester = UUID.randomUUID();
        PlatformCategory category = pending(requester, "Френч", NOW_UTC.plusDays(3));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(category));

        DecisionOutcome outcome = service.rejectById(ID, "Дублікат");

        assertThat(outcome).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(category.getStatus()).isEqualTo(PlatformCategoryStatus.REJECTED);
        assertThat(category.isActive()).isFalse();
        assertThat(category.getDecisionReason()).isEqualTo("Дублікат");
        verifyNoInteractions(serviceTypeSuggestionService);
        verify(notificationOutboxService).enqueueRequestDecided(RequestKind.CATEGORY_REQUEST, "503", false, requester);
    }

    @Test
    @DisplayName("rejectById on a decided row → 409 REQUEST_NOT_PENDING, nothing decided")
    void should_throwRequestNotPending_when_alreadyDecided() {
        PlatformCategory category = pending(UUID.randomUUID(), null, NOW_UTC.plusDays(3));
        category.approve(NOW_UTC);
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(category));

        assertThatThrownBy(() -> service.rejectById(ID, null))
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING));

        verifyNoInteractions(inAppNotificationService, notificationOutboxService);
    }

    @Test
    @DisplayName("approveById with an unknown id → 404")
    void should_throwNotFound_when_idUnknown() {
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveById(ID)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(notificationOutboxService, serviceTypeSuggestionService);
    }
}
