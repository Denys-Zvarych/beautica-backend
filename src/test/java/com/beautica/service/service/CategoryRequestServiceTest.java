package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.service.CategoryRequestService.DecisionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryRequestService — unit")
class CategoryRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final String RAW_TOKEN = "raw-token-value";
    private static final String TOKEN_HASH = "deadbeefcafebabe";
    private static final String ADMIN_EMAIL = "admin@beautica.test";

    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private TokenGenerator tokenGenerator;
    @Mock private EmailService emailService;
    @Mock private PublicBaseUrlProperties publicBaseUrlProperties;
    @Mock private ServiceTypeSuggestionService serviceTypeSuggestionService;

    private CategoryRequestService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new CategoryRequestService(
                platformCategoryRepository, tokenGenerator, emailService,
                publicBaseUrlProperties, serviceTypeSuggestionService, fixedClock);
        ReflectionTestUtils.setField(service, "adminEmail", ADMIN_EMAIL);
    }

    private PlatformCategory pendingCategory(OffsetDateTime expiresAt) {
        return PlatformCategory.ofPendingRequest(
                "NAIL_ART", "Нейл-арт", UUID.randomUUID(), TOKEN_HASH,
                OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)), expiresAt, null);
    }

    private PlatformCategory pendingCategory(OffsetDateTime expiresAt, UUID requester, String initialServiceName) {
        return PlatformCategory.ofPendingRequest(
                "NAIL_ART", "Нейл-арт", requester, TOKEN_HASH,
                OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)), expiresAt, initialServiceName);
    }

    private void stubSubmitHappyPath() {
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(publicBaseUrlProperties.getPublicBaseUrl()).thenReturn("https://x");
        when(platformCategoryRepository.save(any(PlatformCategory.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── submitRequest ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_persistPendingAndEmailAdmin_when_nameIsNew")
    void should_persistPendingAndEmailAdmin_when_nameIsNew() {
        UUID requester = UUID.randomUUID();
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(publicBaseUrlProperties.getPublicBaseUrl()).thenReturn("https://api.beautica.app");
        when(platformCategoryRepository.save(any(PlatformCategory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CategoryRequestResponse response = service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), requester);

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        PlatformCategory saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(PlatformCategoryStatus.PENDING);
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(response.status()).isEqualTo("PENDING");
        verify(emailService).sendCategoryRequestNotification(
                eq(ADMIN_EMAIL), eq(requester.toString()), eq("NAIL_ART"), eq("Нейл-арт"),
                eq("https://api.beautica.app/api/v1/service-categories/requests/review?token=" + RAW_TOKEN));
    }

    @Test
    @DisplayName("should_normalizeNameToUpperCase_when_submitted")
    void should_normalizeNameToUpperCase_when_submitted() {
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(publicBaseUrlProperties.getPublicBaseUrl()).thenReturn("https://x");
        when(platformCategoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitRequest(new CreateCategoryRequestRequest("nail_art", "Нейл-арт", null), UUID.randomUUID());

        verify(platformCategoryRepository).existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any());
    }

    @Test
    @DisplayName("should_throwConflict_when_nameAlreadyExistsOrPending")
    void should_throwConflict_when_nameAlreadyExistsOrPending() {
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("409 CONFLICT");

        verify(platformCategoryRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("should_queryOnlyApprovedAndPendingStatuses_when_checkingForConflict")
    void should_queryOnlyApprovedAndPendingStatuses_when_checkingForConflict() {
        // The blocking set passed to the derived query must be exactly {APPROVED, PENDING}
        // — REJECTED is deliberately excluded so a previously rejected name can be resubmitted.
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlatformCategoryStatus>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        verify(platformCategoryRepository)
                .existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), statusesCaptor.capture());
        assertThat(statusesCaptor.getValue())
                .as("conflict guard must block APPROVED and PENDING but NOT REJECTED")
                .containsExactlyInAnyOrder(PlatformCategoryStatus.APPROVED, PlatformCategoryStatus.PENDING)
                .doesNotContain(PlatformCategoryStatus.REJECTED);
    }

    @Test
    @DisplayName("should_persist_when_previouslyRejectedNameResubmitted")
    void should_persist_when_previouslyRejectedNameResubmitted() {
        // A name whose only prior row is REJECTED is NOT in the {APPROVED, PENDING} set,
        // so the derived query returns false and resubmission proceeds to save.
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(publicBaseUrlProperties.getPublicBaseUrl()).thenReturn("https://x");
        when(platformCategoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID());

        assertThat(response.status()).isEqualTo("PENDING");
        verify(platformCategoryRepository).save(any(PlatformCategory.class));
    }

    // ── submitRequest: initialServiceName persistence (behavior #2) ─────────────

    @Test
    @DisplayName("should_persistTrimmedInitialServiceName_when_provided")
    void should_persistTrimmedInitialServiceName_when_provided() {
        stubSubmitHappyPath();

        service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", "  Класичний манікюр  "),
                UUID.randomUUID());

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialServiceName())
                .as("a provided initial service name must persist, trimmed")
                .isEqualTo("Класичний манікюр");
    }

    @Test
    @DisplayName("should_persistNullInitialServiceName_when_absent")
    void should_persistNullInitialServiceName_when_absent() {
        stubSubmitHappyPath();

        service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID());

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialServiceName())
                .as("an absent initial service name must leave the column null")
                .isNull();
    }

    @Test
    @DisplayName("should_normalizeBlankInitialServiceNameToNull_when_blank")
    void should_normalizeBlankInitialServiceNameToNull_when_blank() {
        stubSubmitHappyPath();

        service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", "   "), UUID.randomUUID());

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialServiceName())
                .as("a blank initial service name must be normalized to null (trimToNull)")
                .isNull();
    }

    // ── approve / reject ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_approveAndClearToken_when_pendingAndTokenValid")
    void should_approveAndClearToken_when_pendingAndTokenValid() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(pending.getStatus()).isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(pending.isActive()).isTrue();
        assertThat(pending.getTokenHash()).isNull();
        assertThat(pending.getDecidedAt()).isNotNull();
    }

    // ── approve: initial-service suggestion side effect (behaviors #3, #4) ──────

    @Test
    @DisplayName("should_createPendingSuggestion_when_approvedCategoryCarriesInitialServiceName")
    void should_createPendingSuggestion_when_approvedCategoryCarriesInitialServiceName() {
        UUID requester = UUID.randomUUID();
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), requester, "Класичний манікюр");
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        // suggestion submitted for the approved slug (category.getName()), requester carried, description null
        verify(serviceTypeSuggestionService).submitSuggestionInternal(
                eq("NAIL_ART"), eq("Класичний манікюр"), isNull(), eq(requester));
    }

    @Test
    @DisplayName("should_notCreateSuggestion_when_approvedCategoryHasNoInitialServiceName")
    void should_notCreateSuggestion_when_approvedCategoryHasNoInitialServiceName() {
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), UUID.randomUUID(), null);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        verifyNoInteractions(serviceTypeSuggestionService);
    }

    @Test
    @DisplayName("should_keepApprovalCommitted_when_suggestionThrows409Conflict")
    void should_keepApprovalCommitted_when_suggestionThrows409Conflict() {
        // The REQUIRES_NEW boundary isolates the inner suggestion rollback; a 409 dedup
        // must be swallowed so the category approval still stands.
        UUID requester = UUID.randomUUID();
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), requester, "Дубль");
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));
        doThrow(new BusinessException(HttpStatus.CONFLICT, "A matching suggestion is already pending review"))
                .when(serviceTypeSuggestionService)
                .submitSuggestionInternal(eq("NAIL_ART"), eq("Дубль"), isNull(), eq(requester));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome)
                .as("a swallowed 409 must not propagate; approval outcome stands")
                .isEqualTo(DecisionOutcome.APPROVED);
        assertThat(pending.getStatus())
                .as("category must end APPROVED despite the inner suggestion conflict")
                .isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(pending.isActive()).isTrue();
    }

    @Test
    @DisplayName("should_propagate_when_suggestionThrowsNon409BusinessException")
    void should_propagate_when_suggestionThrowsNon409BusinessException() {
        // Only CONFLICT is swallowed; any other BusinessException must bubble up so a
        // genuine fault is not silently lost behind the approval.
        UUID requester = UUID.randomUUID();
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), requester, "Помилка");
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));
        doThrow(new BusinessException(HttpStatus.BAD_REQUEST, "boom"))
                .when(serviceTypeSuggestionService)
                .submitSuggestionInternal(eq("NAIL_ART"), eq("Помилка"), isNull(), eq(requester));

        assertThatThrownBy(() -> service.approve(RAW_TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("400 BAD_REQUEST");
    }

    @Test
    @DisplayName("should_rejectAndClearToken_when_pendingAndTokenValid")
    void should_rejectAndClearToken_when_pendingAndTokenValid() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.reject(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(pending.getStatus()).isEqualTo(PlatformCategoryStatus.REJECTED);
        assertThat(pending.isActive()).isFalse();
        assertThat(pending.getTokenHash()).isNull();
    }

    @Test
    @DisplayName("should_returnInvalidOrExpired_when_tokenExpired")
    void should_returnInvalidOrExpired_when_tokenExpired() {
        PlatformCategory expired = pendingCategory(OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(expired));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.INVALID_OR_EXPIRED);
        assertThat(expired.getStatus()).isEqualTo(PlatformCategoryStatus.PENDING);
    }

    @Test
    @DisplayName("should_returnAlreadyDecided_when_tokenUnknown")
    void should_returnAlreadyDecided_when_tokenUnknown() {
        when(tokenGenerator.hash(anyString())).thenReturn("nomatch");
        when(platformCategoryRepository.findByTokenHash("nomatch")).thenReturn(Optional.empty());

        DecisionOutcome outcome = service.approve("some-wrong-token");

        assertThat(outcome).isEqualTo(DecisionOutcome.ALREADY_DECIDED);
    }

    @Test
    @DisplayName("should_returnAlreadyDecided_when_secondDecisionAfterApproval")
    void should_returnAlreadyDecided_when_secondDecisionAfterApproval() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        service.approve(RAW_TOKEN);
        // Token is now cleared on the entity; the second lookup by the stale hash
        // still resolves the row (test stub), but status is no longer PENDING.
        DecisionOutcome second = service.approve(RAW_TOKEN);

        assertThat(second).isEqualTo(DecisionOutcome.ALREADY_DECIDED);
    }

    @Test
    @DisplayName("should_returnAlreadyDecided_when_storedHashMismatchesAfterConstantTimeCheck")
    void should_returnAlreadyDecided_when_storedHashMismatches() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        // Lookup returns a row whose stored hash differs from the recomputed hash:
        // the constant-time filter must drop it.
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn("different-hash");
        when(platformCategoryRepository.findByTokenHash("different-hash")).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.ALREADY_DECIDED);
        assertThat(pending.getStatus()).isEqualTo(PlatformCategoryStatus.PENDING);
    }

    // ── loadForReview ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnValidView_when_pendingTokenLoadedForReview")
    void should_returnValidView_when_pendingTokenLoadedForReview() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        var view = service.loadForReview(RAW_TOKEN);

        assertThat(view.valid()).isTrue();
        assertThat(view.categoryName()).isEqualTo("NAIL_ART");
        assertThat(view.displayName()).isEqualTo("Нейл-арт");
    }

    @Test
    @DisplayName("should_returnInvalidView_when_reviewTokenBlank")
    void should_returnInvalidView_when_reviewTokenBlank() {
        var view = service.loadForReview("  ");

        assertThat(view.valid()).isFalse();
        verifyNoInteractions(platformCategoryRepository);
    }

    // ── listApproved ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_mapApprovedCategories_when_listApproved")
    void should_mapApprovedCategories_when_listApproved() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));

        var result = service.listApproved();

        assertThat(result).singleElement()
                .satisfies(c -> {
                    assertThat(c.name()).isEqualTo("MANICURE");
                    assertThat(c.displayName()).isEqualTo("Манікюр");
                });
    }
}
