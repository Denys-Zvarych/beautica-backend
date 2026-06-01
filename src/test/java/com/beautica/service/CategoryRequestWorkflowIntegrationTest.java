package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
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
        // must survive for sibling tests). Remove only the row this test created.
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    private CreateServiceDefinitionRequest serviceWithCategory(String category) {
        return new CreateServiceDefinitionRequest(
                "Workflow Service", "desc", category, 60, new BigDecimal("500.00"), 0, null);
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
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Воркфлоу-арт"), requesterId);

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
}
