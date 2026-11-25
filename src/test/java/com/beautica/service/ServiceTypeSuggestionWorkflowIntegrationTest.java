package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
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
 * Cross-layer round trip for the Phase 16.8 service-type suggestion workflow (decided in the portal since B545)
 * against a real PostgreSQL (Testcontainers, {@code @ActiveProfiles("test")}).
 * Mirrors {@code CategoryRequestWorkflowIntegrationTest}.
 *
 * <p>Proves the contract that unit mocks cannot:
 * <ol>
 *   <li>The V76 migration created {@code service_type_suggestion} with its indexes (the token columns and index stay
 *       until phase 555; V266 only dropped the token CHECK, B545).</li>
 *   <li>{@code suggestServiceType} (delegating to {@code submitSuggestion}) persists a
 *       PENDING row and emails the operator the portal link (no token).</li>
 *   <li>{@code approveById} flips it to APPROVED, stamps {@code decided_at},
 *       and — for a category with a mapped System-A bucket — writes a {@code service_types}
 *       row (Phase 16.9, Option A catalog-only; supersedes the 16.8 "no auto-promotion"
 *       decision). Under Option A approval creates ONLY the catalog ServiceType and NEVER a
 *       draft {@code service_definitions} row — for ANY requester, not just a null one. The
 *       dropdown-visibility contract is covered by {@code ServiceTypePromotionIntegrationTest}.</li>
 *   <li>An identical re-suggest while PENDING is rejected with a 409.</li>
 * </ol>
 *
 * <p>{@link com.beautica.notification.EmailService} is a {@code @MockBean} (inherited
 * from {@link AbstractIntegrationTest}) so no real SMTP fires.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Service-type suggestion workflow — full-flow integration")
class ServiceTypeSuggestionWorkflowIntegrationTest extends AbstractIntegrationTest {

    // HAIRDRESSING is seeded APPROVED + active in V74 — passes the 16.7 category guard.
    private static final String CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED = "ITWorkflow Hot Towel Shave";
    private static final String DESCRIPTION = "Workflow IT suggestion";
    /** test profile {@code app.frontend.base-url} + the portal requests page. */
    private static final String PORTAL_URL = "http://localhost:3000/admin/requests";

    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private ServiceTypeSuggestionRepository suggestionRepository;
    // jdbcTemplate + emailService are inherited (protected) from AbstractIntegrationTest.

    @AfterEach
    void cleanSuggestions() {
        // service_type_suggestion is not in AbstractIntegrationTest.cleanDb(); remove
        // only the rows this test created so sibling tests are unaffected.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY);
        // Phase 16.9: approve now promotes the suggestion into a service_types row.
        // Remove the type this test created (matched by its generated name) so the seeded
        // taxonomy and sibling tests are unaffected. service_types is seed reference data
        // and is intentionally NOT in AbstractIntegrationTest.cleanDb().
        jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", SUGGESTED);
    }

    private SuggestServiceTypeRequest request(String name) {
        return new SuggestServiceTypeRequest(name, CATEGORY, DESCRIPTION);
    }

    @Test
    @DisplayName("submit (portal-link email) → approve by id persists state transitions + promotes a service_types row (catalog-only, null requester)")
    void should_completeSuggestionApprovalRoundTrip_when_adminApproves() {
        // requested_by_user_id is nullable (FK ON DELETE SET NULL); a null requester is a
        // supported anonymous-suggestion path and avoids needing a users fixture here.
        UUID requester = null;
        long serviceTypesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types", Long.class);

        // Act 1 — submit via the catalog facade (validates category + delegates).
        serviceCatalogService.suggestServiceType(request(SUGGESTED), requester);

        // Assert 1 — PENDING row persisted; the operator email carries only the portal link.
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), eq(CATEGORY), eq(SUGGESTED), eq(DESCRIPTION), eq(PORTAL_URL));

        ServiceTypeSuggestion persisted = suggestionRepository.findAll().stream()
                .filter(s -> CATEGORY.equals(s.getCategoryName()) && SUGGESTED.equals(s.getSuggestedName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PENDING row not found"));
        assertThat(persisted.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.PENDING);
        assertThat(persisted.getCreatedAt())
                .as("@DynamicInsert lets the DB DEFAULT NOW() populate created_at")
                .isNotNull();

        // Assert 2 — dedup: an identical PENDING re-suggest (case-insensitive) → 409.
        assertThatThrownBy(() ->
                serviceCatalogService.suggestServiceType(request(SUGGESTED.toLowerCase()), requester))
                .as("matching PENDING suggestion blocks a duplicate")
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("409 CONFLICT");

        // Act 3 — approve by id (the portal decision).
        DecisionOutcome outcome = suggestionService.approveById(persisted.getId());

        // Assert 4 — APPROVED, decided_at stamped.
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        ServiceTypeSuggestion approved = suggestionRepository.findById(persisted.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.APPROVED);
        assertThat(approved.getDecidedAt()).as("decided_at stamped on approval").isNotNull();

        // Assert 5 — Phase 16.9 (Option A, catalog-only): approve promotes the suggestion
        // into a single new service_types row (+1). HAIRDRESSING maps to a System-A bucket
        // (V78). Under Option A NO draft service_definitions row is ever written — for any
        // requester, not merely because this one is null.
        long serviceTypesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types", Long.class);
        assertThat(serviceTypesAfter)
                .as("approve promotes the suggestion into a new service_types row (novel name)")
                .isEqualTo(serviceTypesBefore + 1);
        Long createdType = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE name_uk = ? AND platform_category_name = ?",
                Long.class, SUGGESTED, CATEGORY);
        assertThat(createdType)
                .as("created ServiceType carries the suggested name + approved category")
                .isEqualTo(1L);
        Long draftCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = ?",
                Long.class, SUGGESTED);
        assertThat(draftCount)
                .as("Option A catalog-only: no service_definitions row is ever created (the draft "
                        + "surface was removed in V80)")
                .isEqualTo(0L);

        // Act 6 — a second approve is refused (REQUEST_NOT_PENDING) and creates NO second service_types row.
        assertThatThrownBy(() -> suggestionService.approveById(persisted.getId()))
                .isInstanceOf(CatalogRequestConflictException.class);
        long serviceTypesAfterReplay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types", Long.class);
        assertThat(serviceTypesAfterReplay)
                .as("replayed approve creates no duplicate type")
                .isEqualTo(serviceTypesAfter);
    }

    @Test
    @DisplayName("V197/V198: unique pending dedup present, V76 non-unique dedup dropped; token-hash index kept until phase 555")
    void should_haveCreatedTableWithIndexes_when_migrationApplied() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'service_type_suggestion'",
                String.class);

        assertThat(indexes)
                .as("V197 partial UNIQUE dedup index; V198 dropped V76's non-unique one; V266 kept the token index")
                .contains("ux_service_type_suggestion_pending_dedup", "idx_service_type_suggestion_token_hash")
                .doesNotContain("idx_service_type_suggestion_dedup");
    }

    @Test
    @DisplayName("V197 index is UNIQUE + partial on PENDING; V198 applied successfully and dropped the old index")
    void should_haveUniquePartialIndexAndAppliedV198_when_migrationsApplied() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_service_type_suggestion_pending_dedup'",
                String.class);
        Integer oldIndexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = 'idx_service_type_suggestion_dedup'",
                Integer.class);
        Boolean v198Success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '198'", Boolean.class);

        assertThat(indexDef)
                .startsWith("CREATE UNIQUE INDEX")
                .contains("lower((suggested_name)::text)")
                .contains("WHERE ((status)::text = 'PENDING'::text)");
        assertThat(oldIndexCount).as("V198 transactional DROP INDEX removed the V76 index").isZero();
        assertThat(v198Success).as("V198 recorded as successfully applied").isTrue();
    }
}
