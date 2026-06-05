package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.entity.ServiceTypeSuggestionStatus;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.service.service.ServiceCatalogService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.beautica.service.service.ServiceTypeSuggestionService.DecisionOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Cross-layer round trip for the Phase 16.8 service-type suggestion + email-approval
 * workflow against a real PostgreSQL (Testcontainers, {@code @ActiveProfiles("test")}).
 * Mirrors {@code CategoryRequestWorkflowIntegrationTest}.
 *
 * <p>Proves the contract that unit mocks cannot:
 * <ol>
 *   <li>The V76 migration created {@code service_type_suggestion} with its CHECK
 *       constraints (the token-pending cross-field CHECK is exercised implicitly by
 *       the persist + clear-on-decision path) and indexes.</li>
 *   <li>{@code suggestServiceType} (delegating to {@code submitSuggestion}) persists a
 *       PENDING row whose stored {@code token_hash} is the HASH, never the raw token
 *       captured from the notification.</li>
 *   <li>{@code loadForReview} does NOT mutate the row (defeats email-scanner pre-fetch).</li>
 *   <li>{@code approve} flips it to APPROVED, stamps {@code decided_at}, clears the token,
 *       and writes NO {@code service_types} row.</li>
 *   <li>An identical re-suggest while PENDING is rejected with a 409.</li>
 * </ol>
 *
 * <p>{@link com.beautica.notification.EmailService} is a {@code @MockBean} (inherited
 * from {@link AbstractIntegrationTest}) so no real SMTP fires; the raw token is captured
 * from the {@code sendServiceTypeSuggestionNotification} call to drive the approve step.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Service-type suggestion workflow — full-flow integration")
class ServiceTypeSuggestionWorkflowIntegrationTest extends AbstractIntegrationTest {

    // HAIRDRESSING is seeded APPROVED + active in V74 — passes the 16.7 category guard.
    private static final String CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED = "ITWorkflow Hot Towel Shave";
    private static final String DESCRIPTION = "Workflow IT suggestion";
    private static final String TOKEN_QUERY_PARAM = "token=";

    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private ServiceTypeSuggestionRepository suggestionRepository;
    @Autowired private TokenGenerator tokenGenerator;
    // jdbcTemplate + emailService are inherited (protected) from AbstractIntegrationTest.

    @AfterEach
    void cleanSuggestions() {
        // service_type_suggestion is not in AbstractIntegrationTest.cleanDb(); remove
        // only the rows this test created so sibling tests are unaffected.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY);
    }

    private SuggestServiceTypeRequest request(String name) {
        return new SuggestServiceTypeRequest(name, CATEGORY, DESCRIPTION);
    }

    @Test
    @DisplayName("submit → review (no mutation) → approve persists state transitions, no service_types write")
    void should_completeSuggestionApprovalRoundTrip_when_adminApproves() {
        // requested_by_user_id is nullable (FK ON DELETE SET NULL); a null requester is a
        // supported anonymous-suggestion path and avoids needing a users fixture here.
        UUID requester = null;
        long serviceTypesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types", Long.class);

        // Act 1 — submit via the catalog facade (validates category + delegates).
        serviceCatalogService.suggestServiceType(request(SUGGESTED), requester);

        // Assert 1 — PENDING row persisted with a HASHED token (stored != raw token).
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), eq(CATEGORY), eq(SUGGESTED), eq(DESCRIPTION),
                urlCaptor.capture());
        String rawToken = urlCaptor.getValue()
                .substring(urlCaptor.getValue().indexOf(TOKEN_QUERY_PARAM) + TOKEN_QUERY_PARAM.length());

        ServiceTypeSuggestion persisted = suggestionRepository
                .findByTokenHash(tokenGenerator.hash(rawToken))
                .orElseThrow(() -> new AssertionError("PENDING row not found by token hash"));
        assertThat(persisted.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.PENDING);
        assertThat(persisted.getTokenHash())
                .as("stored token must be the hash, never the raw token")
                .isNotNull()
                .isNotEqualTo(rawToken)
                .isEqualTo(tokenGenerator.hash(rawToken));
        assertThat(persisted.getCreatedAt())
                .as("@DynamicInsert lets the DB DEFAULT NOW() populate created_at")
                .isNotNull();

        // Assert 2 — dedup: an identical PENDING re-suggest (case-insensitive) → 409.
        assertThatThrownBy(() ->
                serviceCatalogService.suggestServiceType(request(SUGGESTED.toLowerCase()), requester))
                .as("matching PENDING suggestion blocks a duplicate")
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("409 CONFLICT");

        // Act 3 — load for review: MUST NOT mutate the row.
        var review = suggestionService.loadForReview(rawToken);
        assertThat(review.valid()).isTrue();
        assertThat(review.suggestedName()).isEqualTo(SUGGESTED);
        ServiceTypeSuggestion afterReview = suggestionRepository.findById(persisted.getId()).orElseThrow();
        assertThat(afterReview.getStatus())
                .as("GET review must not change state").isEqualTo(ServiceTypeSuggestionStatus.PENDING);
        assertThat(afterReview.getTokenHash())
                .as("GET review must not clear the token").isNotNull();

        // Act 4 — approve.
        DecisionOutcome outcome = suggestionService.approve(rawToken);

        // Assert 4 — APPROVED, decided_at stamped, token cleared (single-use), and the
        // cross-field CHECK (token null on a non-PENDING row) was satisfied at commit.
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        ServiceTypeSuggestion approved = suggestionRepository.findById(persisted.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.APPROVED);
        assertThat(approved.getDecidedAt()).as("decided_at stamped on approval").isNotNull();
        assertThat(approved.getTokenHash())
                .as("token_hash cleared after the decision (single-use)").isNull();
        assertThat(approved.getTokenExpiresAt()).isNull();

        // Assert 5 — approve must NOT insert a service_types row (Phase 16.8 decision).
        long serviceTypesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types", Long.class);
        assertThat(serviceTypesAfter)
                .as("approve marks the record only — no catalog auto-promotion")
                .isEqualTo(serviceTypesBefore);

        // Act 6 — second approve is idempotent (token nulled) → neutral already-decided.
        assertThat(suggestionService.approve(rawToken)).isEqualTo(DecisionOutcome.ALREADY_DECIDED);
    }

    @Test
    @DisplayName("V76 created the table with the expected indexes (token-hash + dedup)")
    void should_haveCreatedTableWithIndexes_when_migrationApplied() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'service_type_suggestion'",
                String.class);

        assertThat(indexes)
                .as("V76 partial indexes backing the token lookup and the dedup guard")
                .contains("idx_service_type_suggestion_token_hash",
                        "idx_service_type_suggestion_dedup");
    }
}
