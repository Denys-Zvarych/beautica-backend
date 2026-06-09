package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.PlatformServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Full-stack promotion path against a real PostgreSQL (Testcontainers): a seeded
 * {@code User} + INDEPENDENT_MASTER suggests a service type; admin approval runs
 * {@code ServiceTypeSuggestionService.approve} which delegates to
 * {@code ServiceTypePromotionService.promote} inside the SAME transaction.
 *
 * <p><strong>Option A (catalog-only) acceptance.</strong> Phase 16.9 was reversed from
 * Option B (auto-create draft master service) to Option A: approval creates ONLY a platform
 * {@code ServiceType} under the approved category — so it appears in the mobile
 * "Тип послуги" dropdown ({@code GET /service-types?categoryName=}, Phase 16.2) — and creates
 * NO {@code ServiceDefinition} (draft) and NO {@code MasterServiceAssignment} on the
 * requester's own list. The master adds their service later by picking the now-available
 * type. This IT pins the dropdown contract end-to-end against PostgreSQL.
 *
 * <p>HAIRDRESSING is seeded APPROVED+active (V74) and maps to the Hair System-A bucket
 * (V78). {@link com.beautica.notification.EmailService} is a {@code @MockBean} (inherited) —
 * the raw token is captured to drive the approve step.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Service-type promotion — catalog-only (Option A) integration")
class ServiceTypePromotionIntegrationTest extends AbstractIntegrationTest {

    private static final String CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED = "ITPromote Hot Oil Treatment";
    private static final String DESCRIPTION = "Promotion IT suggestion";
    private static final String TOKEN_QUERY_PARAM = "token=";

    // ── novel-category (null System-A bucket) regression fixtures ──────────────────
    // A brand-new self-service category created via the category-request workflow that
    // V78's bucket backfill never touched, so platform_categories.service_category_id is
    // NULL. This is the ORIGINAL failing case: promotion must fall back to the permanent
    // "Other / Інше" System-A bucket (V13) to satisfy the service_types.category_id NOT NULL.
    private static final String NOVEL_CATEGORY = "ITPROMO_NOVEL_CATEGORY";
    private static final String NOVEL_SUGGESTED = "ITPromote Scalp Detox Ritual";
    private static final UUID OTHER_BUCKET_ID =
            UUID.fromString("11111111-0008-0000-0000-000000000000");

    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private TokenGenerator tokenGenerator;
    // jdbcTemplate + emailService inherited (protected) from AbstractIntegrationTest.

    @AfterEach
    void cleanPromotionArtifacts() {
        // service_type_suggestion + service_types are seed/standalone tables NOT in
        // AbstractIntegrationTest.cleanDb(); remove only this test's rows. masters + users
        // ARE cleaned by the base class. (Option A writes no service_definitions /
        // master_services, so there is nothing extra to clean there.)
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY);
        jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", SUGGESTED);

        // Novel-category regression rows. FK order: service_types -> service_type_suggestion
        // -> platform_categories (the suggestion references the category by name slug, not FK,
        // but the promoted type carries platform_category_name = NOVEL_CATEGORY, so clear the
        // type first). platform_categories is seed reference data NOT in cleanDb(); the row we
        // insert here is test-local and must be removed so the seeded taxonomy is untouched.
        jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", NOVEL_SUGGESTED);
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", NOVEL_CATEGORY);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", NOVEL_CATEGORY);
    }

    /**
     * Inserts a brand-new APPROVED + active platform category whose System-A bucket mapping
     * (V78 {@code service_category_id}) is NULL — exactly the shape a self-service category
     * created through the category-request workflow has before any bucket backfill. Returns
     * after asserting the precondition the regression depends on: the bucket IS null.
     */
    private void seedNovelUnmappedCategory() {
        jdbcTemplate.update(
                "INSERT INTO platform_categories (name, display_name, status, active, service_category_id) " +
                "VALUES (?, ?, 'APPROVED', true, NULL)",
                NOVEL_CATEGORY, "Промо нова категорія");
        Object bucket = jdbcTemplate.queryForObject(
                "SELECT service_category_id FROM platform_categories WHERE name = ?",
                Object.class, NOVEL_CATEGORY);
        assertThat(bucket)
                .as("precondition: novel category has NO System-A bucket (the original failing case)")
                .isNull();
    }

    private String submitAndCaptureRawToken(String categoryName, String suggestedName, UUID requesterUserId) {
        serviceCatalogService.suggestServiceType(
                new SuggestServiceTypeRequest(suggestedName, categoryName, DESCRIPTION), requesterUserId);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), eq(categoryName), eq(suggestedName), eq(DESCRIPTION),
                urlCaptor.capture());
        String url = urlCaptor.getValue();
        return url.substring(url.indexOf(TOKEN_QUERY_PARAM) + TOKEN_QUERY_PARAM.length());
    }

    private UUID seedIndependentMaster() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) " +
                "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true)",
                userId, "promote-it-" + userId + "@test.local",
                "$2a$10$0000000000000000000000000000000000000000000000000000");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, review_count, created_at, updated_at) " +
                "VALUES (?, ?, 'INDEPENDENT_MASTER', true, 0, NOW(), NOW())",
                masterId, userId);
        return userId;
    }

    private UUID masterIdFor(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM masters WHERE user_id = ?", UUID.class, userId);
    }

    @Test
    @DisplayName("approve creates only a catalog ServiceType visible in the dropdown; NO draft service / assignment for the requester; replay adds nothing")
    void should_createOnlyCatalogTypeVisibleInDropdown_when_masterSuggestionApproved() {
        UUID userId = seedIndependentMaster();
        UUID masterId = masterIdFor(userId);
        String rawToken = submitAndCaptureRawToken(CATEGORY, SUGGESTED, userId);

        // Act — approve runs promote() in the same @Transactional boundary.
        DecisionOutcome outcome = suggestionService.approve(rawToken);
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);

        // ── Assert 1: the dropdown contract — GET /service-types?categoryName= now lists it. ──
        List<PlatformServiceTypeResponse> dropdown =
                serviceCatalogService.findServiceTypesByPlatformCategory(CATEGORY);
        assertThat(dropdown)
                .as("approved suggestion appears in the mobile service-type dropdown (Phase 16.2)")
                .anyMatch(t -> SUGGESTED.equals(t.nameUk()) && CATEGORY.equals(t.categoryName()));

        // ── Assert 2: exactly one service_types row was created for the suggested name. ──
        long typeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE name_uk = ? AND platform_category_name = ?",
                Long.class, SUGGESTED, CATEGORY);
        assertThat(typeCount).as("approval created exactly one catalog ServiceType").isEqualTo(1L);

        // ── Assert 3: NO draft service_definitions row for the suggested name (Option A). ──
        long draftCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = ?", Long.class, SUGGESTED);
        assertThat(draftCount)
                .as("Option A: approval must NOT create any ServiceDefinition (draft)")
                .isZero();

        // ── Assert 4: NO master_services assignment for the requester. ──
        long assignmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ?", Long.class, masterId);
        assertThat(assignmentCount)
                .as("Option A: approval must NOT attach a service to the requester's own list")
                .isZero();

        // ── Assert 5: the search floor (masters.min_effective_price) is untouched (null). ──
        var floor = jdbcTemplate.queryForObject(
                "SELECT min_effective_price FROM masters WHERE id = ?", java.math.BigDecimal.class, masterId);
        assertThat(floor)
                .as("catalog-only approval must not touch the search price floor").isNull();

        // ── Assert 6: replay is a no-op — no duplicate type, still zero drafts. ──
        long typesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE name_uk = ?", Long.class, SUGGESTED);

        assertThat(suggestionService.approve(rawToken)).isEqualTo(DecisionOutcome.ALREADY_DECIDED);

        long typesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE name_uk = ?", Long.class, SUGGESTED);
        long draftsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = ?", Long.class, SUGGESTED);
        assertThat(typesAfter).as("replayed approve creates no second type").isEqualTo(typesBefore);
        assertThat(draftsAfter).as("replayed approve still creates no draft").isZero();
    }

    /**
     * The ORIGINAL failing case as a true regression: a NOVEL category whose
     * {@code platform_categories.service_category_id} is NULL. Under the buggy Option B this
     * path created NO {@code ServiceType} (the name never reached the dropdown) and instead
     * wrote a draft {@code ServiceDefinition} + inactive {@code MasterServiceAssignment}.
     * Option A must (a) create the catalog {@code ServiceType} — falling back to the permanent
     * "Other" System-A bucket so the NOT-NULL {@code category_id} holds against real
     * PostgreSQL — making it visible in the dropdown, and (b) write zero drafts / zero
     * assignments. The unit test mocks the repositories, so only this IT proves the fallback
     * bucket row actually exists and the FK is satisfied at commit.
     */
    @Test
    @DisplayName("novel category with NULL System-A bucket — approve binds the type to the permanent 'Other' bucket, lists it in the dropdown, writes NO draft/assignment")
    void should_promoteToOtherBucketAndDropdown_when_novelCategoryHasNoSystemABucket() {
        seedNovelUnmappedCategory();
        UUID userId = seedIndependentMaster();
        UUID masterId = masterIdFor(userId);
        String rawToken = submitAndCaptureRawToken(NOVEL_CATEGORY, NOVEL_SUGGESTED, userId);

        // Act — approve must NOT 500 despite the null bucket (the original break point).
        DecisionOutcome outcome = suggestionService.approve(rawToken);
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);

        // ── positive: the suggested name surfaces in the dropdown for the novel category. ──
        List<PlatformServiceTypeResponse> dropdown =
                serviceCatalogService.findServiceTypesByPlatformCategory(NOVEL_CATEGORY);
        assertThat(dropdown)
                .as("novel-category suggestion appears in the service-type dropdown (was missing under Option B)")
                .anyMatch(t -> NOVEL_SUGGESTED.equals(t.nameUk()) && NOVEL_CATEGORY.equals(t.categoryName()));

        // ── the type persisted, bound to the permanent 'Other' System-A bucket. ──
        UUID boundBucketId = jdbcTemplate.queryForObject(
                "SELECT category_id FROM service_types WHERE name_uk = ? AND platform_category_name = ?",
                UUID.class, NOVEL_SUGGESTED, NOVEL_CATEGORY);
        assertThat(boundBucketId)
                .as("null-mapped category falls back to the permanent 'Other' bucket (V13) so category_id NOT NULL holds")
                .isEqualTo(OTHER_BUCKET_ID);

        // ── negative: Option B's draft service + master assignment must NOT exist. ──
        long draftCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = ?", Long.class, NOVEL_SUGGESTED);
        assertThat(draftCount)
                .as("Option A: novel-category approval must NOT create a draft ServiceDefinition")
                .isZero();
        long assignmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ?", Long.class, masterId);
        assertThat(assignmentCount)
                .as("Option A: novel-category approval must NOT attach a service to the requester")
                .isZero();
    }
}
