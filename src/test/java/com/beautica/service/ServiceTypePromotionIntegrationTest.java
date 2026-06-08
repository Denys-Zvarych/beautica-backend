package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.MasterServiceResponse;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * <p><strong>CRITICAL fix verified here.</strong> The promotion writer persists the draft
 * with {@code base_duration_minutes = 0}. V6's {@code chk_service_def_base_duration CHECK
 * (base_duration_minutes > 0)} originally rejected this, rolling back every master-attributed
 * approval. V79 relaxed the CHECK to {@code (is_draft OR base_duration_minutes > 0)} so the
 * 0-minute draft sentinel persists while the {@code > 0} invariant is preserved for real
 * (non-draft) services. {@link #should_persistDraftAndKeepItOutOfBrowseAndSearchFloor_when_masterSuggestionApproved()}
 * is the acceptance test pinning that contract end-to-end against PostgreSQL — a regression
 * to the CHECK (or to the draft sentinels) re-breaks it. Unit tests (mocked repository)
 * cannot see the DB constraint; only this IT does.
 *
 * <p>HAIRDRESSING is seeded APPROVED+active (V74) and maps to the Hair System-A bucket
 * (V78). {@link com.beautica.notification.EmailService} is a {@code @MockBean} (inherited) —
 * the raw token is captured to drive the approve step.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Service-type promotion — full requester→master→draft integration")
class ServiceTypePromotionIntegrationTest extends AbstractIntegrationTest {

    private static final String CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED = "ITPromote Hot Oil Treatment";
    private static final String DESCRIPTION = "Promotion IT suggestion";
    private static final String TOKEN_QUERY_PARAM = "token=";

    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private TokenGenerator tokenGenerator;
    // jdbcTemplate + emailService inherited (protected) from AbstractIntegrationTest.

    @AfterEach
    void cleanPromotionArtifacts() {
        // service_type_suggestion + service_types are seed/standalone tables NOT in
        // AbstractIntegrationTest.cleanDb(); remove only this test's rows. master_services,
        // service_definitions, masters, users ARE cleaned by the base class.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY);
        jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", SUGGESTED);
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

    private String submitAndCaptureRawToken(UUID requesterUserId) {
        serviceCatalogService.suggestServiceType(
                new SuggestServiceTypeRequest(SUGGESTED, CATEGORY, DESCRIPTION), requesterUserId);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), eq(CATEGORY), eq(SUGGESTED), eq(DESCRIPTION), urlCaptor.capture());
        String url = urlCaptor.getValue();
        return url.substring(url.indexOf(TOKEN_QUERY_PARAM) + TOKEN_QUERY_PARAM.length());
    }

    @Test
    @DisplayName("approve promotes a persisted DRAFT for the requesting master, excluded from public browse + search floor; replay adds nothing")
    void should_persistDraftAndKeepItOutOfBrowseAndSearchFloor_when_masterSuggestionApproved() {
        UUID userId = seedIndependentMaster();
        UUID masterId = masterIdFor(userId);
        String rawToken = submitAndCaptureRawToken(userId);

        // Act — approve runs promote() in the same @Transactional boundary.
        DecisionOutcome outcome = suggestionService.approve(rawToken);
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);

        // ── Assert 1: a service_types row was created for the suggested name. ──
        UUID serviceTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_types WHERE name_uk = ? AND platform_category_name = ?",
                UUID.class, SUGGESTED, CATEGORY);
        assertThat(serviceTypeId).as("approval created the catalog ServiceType").isNotNull();

        // ── Assert 2: a DRAFT service_definitions row, sentinel pricing, master-owned. ──
        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT owner_type, owner_id, name, category, base_price, price_type, "
                        + "base_duration_minutes, is_active, is_draft, service_type_id "
                        + "FROM service_definitions WHERE name = ? AND is_draft = TRUE", SUGGESTED);
        assertThat(draft.get("is_draft")).as("materialized service is a draft").isEqualTo(true);
        assertThat(draft.get("is_active")).as("draft inactive → excluded from search + browse").isEqualTo(false);
        assertThat(draft.get("owner_type")).isEqualTo("INDEPENDENT_MASTER");
        assertThat(draft.get("owner_id")).isEqualTo(masterId);
        assertThat(draft.get("category")).isEqualTo(CATEGORY);
        assertThat(draft.get("price_type")).isEqualTo("FIXED");
        assertThat(((BigDecimal) draft.get("base_price"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(draft.get("service_type_id"))
                .as("draft links the created ServiceType (mapped-bucket branch)").isEqualTo(serviceTypeId);

        // ── Assert 3: an INACTIVE master_services assignment links master ↔ draft. ──
        Boolean assignmentInactive = jdbcTemplate.queryForObject(
                "SELECT NOT ms.is_active FROM master_services ms "
                        + "JOIN service_definitions sd ON sd.id = ms.service_def_id "
                        + "WHERE ms.master_id = ? AND sd.name = ?", Boolean.class, masterId, SUGGESTED);
        assertThat(assignmentInactive).as("assignment created and inactive").isTrue();

        // ── Assert 4: the public browse path excludes the draft. ──
        List<MasterServiceResponse> browse = serviceCatalogService.getMasterServices(masterId);
        assertThat(browse)
                .as("draft (is_active=false) must never appear in the public GET /masters/{id}/services")
                .noneMatch(s -> SUGGESTED.equals(s.serviceDefinition().name()));

        // ── Assert 5: the search floor (masters.min_effective_price) is untouched (null). ──
        BigDecimal floor = jdbcTemplate.queryForObject(
                "SELECT min_effective_price FROM masters WHERE id = ?", BigDecimal.class, masterId);
        assertThat(floor)
                .as("a ₴0 draft must not become the search price floor").isNull();

        // ── Assert 6: replay is a no-op — no duplicate draft / type. ──
        long draftsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = ?", Long.class, SUGGESTED);
        long typesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE name_uk = ?", Long.class, SUGGESTED);

        assertThat(suggestionService.approve(rawToken)).isEqualTo(DecisionOutcome.ALREADY_DECIDED);

        long draftsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = ?", Long.class, SUGGESTED);
        long typesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE name_uk = ?", Long.class, SUGGESTED);
        assertThat(draftsAfter).as("replayed approve creates no second draft").isEqualTo(draftsBefore);
        assertThat(typesAfter).as("replayed approve creates no second type").isEqualTo(typesBefore);
    }
}
