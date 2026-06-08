package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Cross-layer round trip for the self-service category request + email-approval
 * workflow against a real PostgreSQL (Testcontainers, {@code @ActiveProfiles("test")}).
 *
 * <p>Proves the contract that unit mocks cannot:
 * <ol>
 *   <li>{@code submitRequest} persists a PENDING row with a HASHED token
 *       ({@code token_hash} != the raw token captured from the notification).</li>
 *   <li>While PENDING, the category is rejected by the service-create validation gate.</li>
 *   <li>{@code loadForReview} does NOT mutate the row (defeats email-scanner pre-fetch).</li>
 *   <li>{@code approve} flips it to APPROVED + active and clears {@code token_hash}.</li>
 *   <li>After approval, the category is accepted by the service-create validation gate.</li>
 * </ol>
 *
 * <p>{@link com.beautica.notification.EmailService} is a {@code @MockBean} (inherited
 * from {@link AbstractIntegrationTest}) so no real SMTP fires; the raw token is captured
 * from the {@code sendCategoryRequestNotification} call to drive the approve step.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Category request workflow — full-flow integration")
class CategoryRequestWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final String CATEGORY_NAME = "ITWORKFLOW_NAILART";
    private static final String TOKEN_QUERY_PARAM = "token=";

    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private com.beautica.service.repository.ServiceTypeSuggestionRepository serviceTypeSuggestionRepository;
    @Autowired private TokenGenerator tokenGenerator;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @AfterEach
    void cleanCategory() {
        // platform_categories is not in AbstractIntegrationTest.cleanDb() (the 7 seeds
        // must survive for sibling tests). Remove only the rows this test created.
        // service_type_suggestion has no FK to platform_categories (slug stored as a
        // plain string), so order between these two deletes is irrelevant.
        jdbcTemplate.update(
                "DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY_NAME);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    private CreateServiceDefinitionRequest serviceWithCategory(String category) {
        return new CreateServiceDefinitionRequest(
                "Workflow Service", "desc", category, 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);
    }

    @Test
    @DisplayName("submit → review (no mutation) → approve flips state and gates service creation")
    void should_completeRequestApprovalRoundTrip_when_adminApproves() throws Exception {
        // Arrange — a salon + owner so addServiceToSalon has a real salon to validate against.
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "it-cat-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Category Workflow Salon");
        UUID requesterId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);

        // Act 1 — submit the request.
        CategoryRequestResponse submitted = categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт", null), requesterId);

        // Assert 1 — row persisted PENDING with a HASHED token (stored value != raw token).
        assertThat(submitted.status()).isEqualTo("PENDING");
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), eq(requesterId.toString()), eq(CATEGORY_NAME), eq("Воркфлоу-арт"),
                urlCaptor.capture());
        String rawToken = urlCaptor.getValue()
                .substring(urlCaptor.getValue().indexOf(TOKEN_QUERY_PARAM) + TOKEN_QUERY_PARAM.length());

        PlatformCategory persisted = platformCategoryRepository
                .findByTokenHash(tokenGenerator.hash(rawToken))
                .orElseThrow(() -> new AssertionError("PENDING row not found by token hash"));
        assertThat(persisted.getStatus()).isEqualTo(PlatformCategoryStatus.PENDING);
        assertThat(persisted.isActive()).isFalse();
        assertThat(persisted.getTokenHash())
                .as("stored token must be the hash, never the raw token")
                .isNotNull()
                .isNotEqualTo(rawToken)
                .isEqualTo(tokenGenerator.hash(rawToken));

        // Assert 2 — while PENDING, service creation with this category is rejected.
        assertThatThrownBy(() -> serviceCatalogService.addServiceToSalon(salonId, serviceWithCategory(CATEGORY_NAME)))
                .as("PENDING category must not be selectable at service-create time")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category");

        // Act 2 — load for review: MUST NOT mutate the row.
        var review = categoryRequestService.loadForReview(rawToken);
        assertThat(review.valid()).isTrue();
        PlatformCategory afterReview = platformCategoryRepository.findById(persisted.getId()).orElseThrow();
        assertThat(afterReview.getStatus())
                .as("GET review must not change state")
                .isEqualTo(PlatformCategoryStatus.PENDING);
        assertThat(afterReview.getTokenHash())
                .as("GET review must not clear the token")
                .isNotNull();

        // Act 3 — approve.
        DecisionOutcome outcome = categoryRequestService.approve(rawToken);

        // Assert 3 — APPROVED + active, token cleared (single-use).
        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        PlatformCategory approved = platformCategoryRepository.findById(persisted.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(approved.isActive()).isTrue();
        assertThat(approved.getTokenHash())
                .as("token_hash must be cleared after the decision (single-use)")
                .isNull();

        // Assert 4 — service creation now succeeds (was rejected while PENDING).
        var created = serviceCatalogService.addServiceToSalon(salonId, serviceWithCategory(CATEGORY_NAME));
        assertThat(created.category())
                .as("category accepted by the service-create gate once APPROVED + active")
                .isEqualTo(CATEGORY_NAME);
    }

    @Test
    @DisplayName("approve with initial service name persists a PENDING service_type_suggestion (behavior #3)")
    void should_createPendingSuggestion_when_approvingCategoryWithInitialServiceName() throws Exception {
        // Arrange — a real persisted user (FK fk_platform_categories_requested_by).
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "it-cat-suggest-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Suggest Workflow Salon");
        UUID requesterId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);

        // Submit a request carrying an initial service-type name.
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт", "  Класичний манікюр  "),
                requesterId);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), eq(requesterId.toString()), eq(CATEGORY_NAME), eq("Воркфлоу-арт"),
                urlCaptor.capture());
        String rawToken = urlCaptor.getValue()
                .substring(urlCaptor.getValue().indexOf(TOKEN_QUERY_PARAM) + TOKEN_QUERY_PARAM.length());

        // Act — approve the category.
        DecisionOutcome outcome = categoryRequestService.approve(rawToken);

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
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "it-cat-nosuggest-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "No-Suggest Workflow Salon");
        UUID requesterId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт", null), requesterId);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), eq(requesterId.toString()), eq(CATEGORY_NAME), eq("Воркфлоу-арт"),
                urlCaptor.capture());
        String rawToken = urlCaptor.getValue()
                .substring(urlCaptor.getValue().indexOf(TOKEN_QUERY_PARAM) + TOKEN_QUERY_PARAM.length());

        categoryRequestService.approve(rawToken);

        assertThat(serviceTypeSuggestionRepository.findAll().stream()
                .filter(s -> CATEGORY_NAME.equals(s.getCategoryName())).toList())
                .as("no initial service name → no suggestion created on approval")
                .isEmpty();
    }

    @Test
    @DisplayName("retired OTHER category (V66) is excluded from approved list and rejected on service create")
    void should_excludeAndRejectOther_when_categoryRetired() throws Exception {
        // Arrange — a salon to validate service creation against.
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "it-cat-other-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Other Retired Salon");

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
