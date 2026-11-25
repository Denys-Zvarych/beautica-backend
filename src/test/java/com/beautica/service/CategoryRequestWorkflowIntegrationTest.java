package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.CategoryRequestService.DecisionOutcome;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Cross-layer round trip for the self-service category request workflow against a real PostgreSQL
 * (Testcontainers, {@code @ActiveProfiles("test")}).
 *
 * <p>Proves the contract that unit mocks cannot:
 * <ol>
 *   <li>{@code submitRequest} persists a PENDING row and emails the operator the portal link (B545: no token).</li>
 *   <li>While PENDING, the category is rejected by the service-create validation gate.</li>
 *   <li>{@code approveById} (the portal decision, the only one since B545) flips it to APPROVED + active.</li>
 *   <li>After approval, the category is accepted by the service-create validation gate.</li>
 * </ol>
 *
 * <p>{@link com.beautica.notification.EmailService} is a {@code @MockBean} (inherited
 * from {@link AbstractIntegrationTest}) so no real SMTP fires.
 */
@Import(TestSecurityConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Category request workflow — full-flow integration")
class CategoryRequestWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final String CATEGORY_NAME = "ITWORKFLOW_NAILART";
    /** test profile {@code app.frontend.base-url} + the portal requests page. */
    private static final String PORTAL_URL = "http://localhost:3000/admin/requests";

    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private com.beautica.service.repository.ServiceTypeSuggestionRepository serviceTypeSuggestionRepository;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    /**
     * Pays the cold-start cost (first login + salon-create HTTP round trips, BCrypt, the submit / approve service path)
     * once, outside any timed test, so whichever test JUnit runs first is not measured against
     * {@code SlowTestExtension}'s 10 s ceiling for it (it used to spend 50-90 % of it here). Same pattern as
     * {@code MultiServiceNotificationIT#warmUpColdStart}. Then wipes every row it made, so no test sees it.
     */
    @BeforeAll
    void warmUpColdStart() throws Exception {
        setUpFixtures();
        UUID requesterId = newRequesterWithSalon("it-cat-warmup").requesterId();
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт", null), requesterId);
        categoryRequestService.approveById(pendingRow().getId());
        cleanCategory();
        cleanDb();
    }

    @BeforeEach
    void setUpFixtures() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    /** A logged-in SALON_OWNER with one salon; the owner is the request's requester. */
    private record RequesterWithSalon(UUID requesterId, UUID salonId) {}

    private RequesterWithSalon newRequesterWithSalon(String emailPrefix) throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(emailPrefix + "-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Category Workflow Salon");
        UUID requesterId = jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
        return new RequesterWithSalon(requesterId, salonId);
    }

    @AfterEach
    void cleanCategory() {
        // platform_categories is not in AbstractIntegrationTest.cleanDb() (the 7 seeds
        // must survive for sibling tests). Remove only the rows this test created.
        // service_type_suggestion has no FK to platform_categories (slug stored as a
        // plain string), so order between these two deletes is irrelevant.
        jdbcTemplate.update(
                "DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY_NAME);
        // Remove any service_type seeded by the round-trip test for this runtime category, plus the
        // service_definitions that FK-reference it (service_definitions_service_type_id_fkey). This
        // @AfterEach runs BEFORE the superclass cleanDb(), so the definitions must be cleared here in
        // FK order first. platform_category_name itself is a plain slug (no FK to platform_categories).
        jdbcTemplate.update(
                "DELETE FROM service_definitions WHERE service_type_id IN "
                        + "(SELECT id FROM service_types WHERE platform_category_name = ?)", CATEGORY_NAME);
        jdbcTemplate.update(
                "DELETE FROM service_types WHERE platform_category_name = ?", CATEGORY_NAME);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    private CreateServiceDefinitionRequest serviceWithCategory(String category) {
        // serviceTypeId null: used by the negative paths (PENDING / retired OTHER) that are
        // rejected by validateCategoryActive BEFORE the mandatory-type guard is reached.
        return serviceWithCategory(category, null);
    }

    private CreateServiceDefinitionRequest serviceWithCategory(String category, UUID serviceTypeId) {
        return new CreateServiceDefinitionRequest(
                "Workflow Service", "desc", category, 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, serviceTypeId);
    }

    private PlatformCategory pendingRow() {
        return platformCategoryRepository.findByName(CATEGORY_NAME)
                .orElseThrow(() -> new AssertionError("PENDING row not found by name"));
    }

    @Test
    @DisplayName("submit (portal-link email) → approve by id flips state and gates service creation")
    void should_completeRequestApprovalRoundTrip_when_adminApproves() throws Exception {
        // Arrange — a salon + owner so addServiceToSalon has a real salon to validate against.
        RequesterWithSalon arranged = newRequesterWithSalon("it-cat-owner");
        UUID salonId = arranged.salonId();
        UUID requesterId = arranged.requesterId();

        // Act 1 — submit the request.
        CategoryRequestResponse submitted = categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт", null), requesterId);

        // Assert 1 — row persisted PENDING; the operator email carries only the portal link.
        assertThat(submitted.status()).isEqualTo("PENDING");
        verify(emailService).sendCategoryRequestNotification(
                anyString(), eq(requesterId.toString()), eq(CATEGORY_NAME), eq("Воркфлоу-арт"), eq(PORTAL_URL));

        PlatformCategory persisted = pendingRow();
        assertThat(persisted.getStatus()).isEqualTo(PlatformCategoryStatus.PENDING);
        assertThat(persisted.isActive()).isFalse();

        // Assert 2 — while PENDING, service creation with this category is rejected.
        assertThatThrownBy(() -> serviceCatalogService.addServiceToSalon(salonId, serviceWithCategory(CATEGORY_NAME)))
                .as("PENDING category must not be selectable at service-create time")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category");

        // Act 2 — approve by id (the portal decision).
        DecisionOutcome outcome = categoryRequestService.approveById(persisted.getId());

        // Assert 3 — APPROVED + active.
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        PlatformCategory approved = platformCategoryRepository.findById(persisted.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(approved.isActive()).isTrue();

        // Assert 4 — service creation now succeeds (was rejected while PENDING).
        // V111 makes service_type_id mandatory and category-consistent (Phase 16.3), so seed an
        // active service_type under the now-APPROVED runtime category. platform_category_name is a
        // plain slug column (V73: no FK to platform_categories), so this row does not block the
        // @AfterEach platform_categories delete; it is removed by cleanCategory() in FK-free order.
        jdbcTemplate.update(
                "INSERT INTO service_types (id, category_id, name_uk, name_en, slug, is_active, "
                        + "platform_category_name) "
                        + "SELECT ?, sc.id, ?, ?, ?, TRUE, ? FROM service_categories sc LIMIT 1",
                UUID.randomUUID(), "Воркфлоу тип", "Workflow type",
                "itworkflow-nailart-" + System.nanoTime(), CATEGORY_NAME);
        UUID seededTypeId = fixtures.resolveServiceTypeIdForCategory(CATEGORY_NAME);
        var created = serviceCatalogService.addServiceToSalon(
                salonId, serviceWithCategory(CATEGORY_NAME, seededTypeId));
        assertThat(created.category())
                .as("category accepted by the service-create gate once APPROVED + active")
                .isEqualTo(CATEGORY_NAME);
    }

    @Test
    @DisplayName("approve with initial service name persists a PENDING service_type_suggestion (behavior #3)")
    void should_createPendingSuggestion_when_approvingCategoryWithInitialServiceName() throws Exception {
        // Arrange — a real persisted user (FK fk_platform_categories_requested_by).
        UUID requesterId = newRequesterWithSalon("it-cat-suggest").requesterId();

        // Submit a request carrying an initial service-type name.
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт", "  Класичний манікюр  "),
                requesterId);


        // Act — approve the category.
        DecisionOutcome outcome = categoryRequestService.approveById(pendingRow().getId());

        // Assert — category APPROVED and a PENDING suggestion now exists for its slug.
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        var suggestions = serviceTypeSuggestionRepository.findAll().stream()
                .filter(s -> CATEGORY_NAME.equals(s.getCategoryName()))
                .toList();
        assertThat(suggestions)
                .as("approval must create exactly one PENDING suggestion for the approved slug")
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getSuggestedName())
                            .as("trimmed initial service name is carried into the suggestion")
                            .isEqualTo("Класичний манікюр");
                    assertThat(s.getDescription()).isNull();
                    assertThat(s.getRequestedByUserId()).isEqualTo(requesterId);
                    assertThat(s.isPending())
                            .as("suggestion is created PENDING — no auto-promotion")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("approve without initial service name creates no suggestion (behavior #3 negative)")
    void should_notCreateSuggestion_when_approvingCategoryWithoutInitialServiceName() throws Exception {
        UUID requesterId = newRequesterWithSalon("it-cat-nosuggest").requesterId();
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт", null), requesterId);


        categoryRequestService.approveById(pendingRow().getId());

        assertThat(serviceTypeSuggestionRepository.findAll().stream()
                .filter(s -> CATEGORY_NAME.equals(s.getCategoryName())).toList())
                .as("no initial service name → no suggestion created on approval")
                .isEmpty();
    }

    @Test
    @DisplayName("retired OTHER category (V66) is excluded from approved list and rejected on service create")
    void should_excludeAndRejectOther_when_categoryRetired() throws Exception {
        // Arrange — a salon to validate service creation against.
        UUID salonId = newRequesterWithSalon("it-cat-other").salonId();

        // Act + Assert 1 — OTHER must not appear in the approved (selectable) list.
        assertThat(platformCategoryRepository.findApprovedActive())
                .as("V66 soft-disabled OTHER; it must drop out of the approved picker")
                .extracting(PlatformCategory::getName)
                .doesNotContain("OTHER");

        // Act + Assert 2 — creating a service with category=OTHER is rejected (400).
        assertThatThrownBy(() ->
                serviceCatalogService.addServiceToSalon(salonId, serviceWithCategory("OTHER")))
                .as("retired OTHER must fail the service-create validation gate")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category");
    }
}
