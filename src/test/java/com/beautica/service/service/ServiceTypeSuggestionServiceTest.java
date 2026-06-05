package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.entity.ServiceTypeSuggestionStatus;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.service.service.ServiceTypeSuggestionService.DecisionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceTypeSuggestionService}. Structurally a copy of
 * {@code CategoryRequestServiceTest} — same fixed-clock, mocked-collaborator harness.
 *
 * <p>Security-flavoured asserts live here (per Phase 16.8 plan item 4): the persisted
 * token is the hash not the raw token; the constant-time path is exercised; invalid /
 * expired / mismatched tokens map to a neutral outcome.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTypeSuggestionService — unit")
class ServiceTypeSuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-05T10:00:00Z");
    private static final String RAW_TOKEN = "raw-token-value";
    private static final String TOKEN_HASH = "deadbeefcafebabe";
    private static final String ADMIN_EMAIL = "admin@beautica.test";
    private static final String CATEGORY = "HAIR";
    private static final String SUGGESTED = "Балаяж";
    private static final String DESCRIPTION = "Освітлення кінчиків";

    @Mock private ServiceTypeSuggestionRepository suggestionRepository;
    @Mock private TokenGenerator tokenGenerator;
    @Mock private EmailService emailService;
    @Mock private PublicBaseUrlProperties publicBaseUrlProperties;

    private ServiceTypeSuggestionService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ServiceTypeSuggestionService(
                suggestionRepository, tokenGenerator, emailService,
                publicBaseUrlProperties, fixedClock);
        ReflectionTestUtils.setField(service, "adminEmail", ADMIN_EMAIL);
    }

    private ServiceTypeSuggestion pendingSuggestion(OffsetDateTime expiresAt) {
        return ServiceTypeSuggestion.ofPending(
                CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID(), TOKEN_HASH, expiresAt);
    }

    // ── submitSuggestion ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_persistPendingWithHashedTokenAndEmailAdmin_when_suggestionIsNew")
    void should_persistPendingWithHashedTokenAndEmailAdmin_when_suggestionIsNew() {
        UUID requester = UUID.randomUUID();
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(publicBaseUrlProperties.getPublicBaseUrl()).thenReturn("https://api.beautica.app");
        when(suggestionRepository.save(any(ServiceTypeSuggestion.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, requester);

        ArgumentCaptor<ServiceTypeSuggestion> captor = ArgumentCaptor.forClass(ServiceTypeSuggestion.class);
        verify(suggestionRepository).save(captor.capture());
        ServiceTypeSuggestion saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.PENDING);
        assertThat(saved.getCategoryName()).isEqualTo(CATEGORY);
        assertThat(saved.getSuggestedName()).isEqualTo(SUGGESTED);
        assertThat(saved.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(saved.getRequestedByUserId()).isEqualTo(requester);
        assertThat(saved.getTokenHash())
                .as("stored token must be the hash, never the raw token")
                .isEqualTo(TOKEN_HASH)
                .isNotEqualTo(RAW_TOKEN);
        assertThat(saved.getTokenExpiresAt())
                .as("7-day TTL from fixed clock")
                .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(7));

        // The emailed review URL carries the RAW token (never the hash).
        verify(emailService).sendServiceTypeSuggestionNotification(
                eq(ADMIN_EMAIL), eq(requester.toString()), eq(CATEGORY), eq(SUGGESTED), eq(DESCRIPTION),
                eq("https://api.beautica.app/api/v1/service-types/suggestions/review?token=" + RAW_TOKEN));
    }

    @Test
    @DisplayName("should_emailEmDashRequester_when_requesterIdNull")
    void should_emailEmDashRequester_when_requesterIdNull() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(publicBaseUrlProperties.getPublicBaseUrl()).thenReturn("https://x");
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, null);

        verify(emailService).sendServiceTypeSuggestionNotification(
                eq(ADMIN_EMAIL), eq("—"), eq(CATEGORY), eq(SUGGESTED), eq(DESCRIPTION), anyString());
    }

    @Test
    @DisplayName("should_throwConflict_when_identicalPendingSuggestionExists")
    void should_throwConflict_when_identicalPendingSuggestionExists() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("409 CONFLICT");

        verify(suggestionRepository, never()).save(any());
        verifyNoInteractions(emailService, tokenGenerator);
    }

    // ── approve / reject ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_approveSetDecidedAtAndClearToken_when_pendingAndTokenValid")
    void should_approveSetDecidedAtAndClearToken_when_pendingAndTokenValid() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.APPROVED);
        assertThat(pending.getDecidedAt())
                .as("decided_at stamped from the fixed clock on approval")
                .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(pending.getTokenHash())
                .as("token nulled on decision (single-use)").isNull();
        assertThat(pending.getTokenExpiresAt()).isNull();
    }

    @Test
    @DisplayName("should_notWriteServiceTypesRow_when_suggestionApproved")
    void should_notWriteServiceTypesRow_when_suggestionApproved() {
        // Phase 16.8 decision: approve marks the record APPROVED only — it must never
        // touch service_types. The service holds NO ServiceTypeRepository collaborator;
        // assert the only repository it owns sees no inserts beyond the suggestion lookup.
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        service.approve(RAW_TOKEN);

        // No save() on approve — the decision mutates the managed entity in place.
        verify(suggestionRepository, never()).save(any());
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.APPROVED);
    }

    @Test
    @DisplayName("should_rejectAndClearToken_when_pendingAndTokenValid")
    void should_rejectAndClearToken_when_pendingAndTokenValid() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.reject(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.REJECTED);
        assertThat(pending.getDecidedAt()).isNotNull();
        assertThat(pending.getTokenHash()).isNull();
    }

    @Test
    @DisplayName("should_returnInvalidOrExpired_when_tokenExpired")
    void should_returnInvalidOrExpired_when_tokenExpired() {
        ServiceTypeSuggestion expired = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(expired));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.INVALID_OR_EXPIRED);
        assertThat(expired.getStatus())
                .as("expired token must not flip state").isEqualTo(ServiceTypeSuggestionStatus.PENDING);
    }

    @Test
    @DisplayName("should_returnAlreadyDecided_when_tokenUnknown")
    void should_returnAlreadyDecided_when_tokenUnknown() {
        when(tokenGenerator.hash(anyString())).thenReturn("nomatch");
        when(suggestionRepository.findByTokenHash("nomatch")).thenReturn(Optional.empty());

        DecisionOutcome outcome = service.approve("some-wrong-token");

        assertThat(outcome)
                .as("unknown token returns neutral outcome — no existence oracle")
                .isEqualTo(DecisionOutcome.ALREADY_DECIDED);
    }

    @Test
    @DisplayName("should_returnAlreadyDecided_when_secondApproveAfterDecision")
    void should_returnAlreadyDecided_when_secondApproveAfterDecision() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        service.approve(RAW_TOKEN);
        // Token cleared on the entity; the stale-hash lookup (test stub) still resolves
        // the row but status is no longer PENDING → neutral already-decided.
        DecisionOutcome second = service.approve(RAW_TOKEN);

        assertThat(second).isEqualTo(DecisionOutcome.ALREADY_DECIDED);
    }

    @Test
    @DisplayName("should_returnAlreadyDecided_when_storedHashMismatchesConstantTimeCheck")
    void should_returnAlreadyDecided_when_storedHashMismatchesConstantTimeCheck() {
        // Lookup returns a row whose stored hash (TOKEN_HASH) differs from the recomputed
        // hash ("different-hash"): the constant-time MessageDigest.isEqual filter drops it.
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn("different-hash");
        when(suggestionRepository.findByTokenHash("different-hash")).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(DecisionOutcome.ALREADY_DECIDED);
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.PENDING);
    }

    // ── loadForReview (read-only) ──────────────────────────────────────────────

    @Test
    @DisplayName("should_returnValidViewWithoutMutation_when_pendingTokenLoaded")
    void should_returnValidViewWithoutMutation_when_pendingTokenLoaded() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending));

        var view = service.loadForReview(RAW_TOKEN);

        assertThat(view.valid()).isTrue();
        assertThat(view.categoryName()).isEqualTo(CATEGORY);
        assertThat(view.suggestedName()).isEqualTo(SUGGESTED);
        assertThat(view.description()).isEqualTo(DESCRIPTION);
        // Read-only: GET review must never mutate or clear the token.
        verify(suggestionRepository, never()).save(any());
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.PENDING);
        assertThat(pending.getTokenHash()).isNotNull();
    }

    @Test
    @DisplayName("should_returnInvalidView_when_reviewTokenBlank")
    void should_returnInvalidView_when_reviewTokenBlank() {
        var view = service.loadForReview("  ");

        assertThat(view.valid()).isFalse();
        verifyNoInteractions(suggestionRepository);
    }

    @Test
    @DisplayName("should_returnInvalidView_when_reviewTokenExpired")
    void should_returnInvalidView_when_reviewTokenExpired() {
        ServiceTypeSuggestion expired = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(expired));

        var view = service.loadForReview(RAW_TOKEN);

        assertThat(view.valid())
                .as("expired suggestion must render the neutral page").isFalse();
    }
}
