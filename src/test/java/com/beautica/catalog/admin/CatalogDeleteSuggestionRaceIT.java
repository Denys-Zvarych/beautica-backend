package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B505 (SVC-57) — a PENDING service-type suggestion (string link to its category, no FK) created at the same moment as
 * a catalog-admin category delete. {@code ServiceTypeSuggestionService#submitSuggestion} takes {@code FOR SHARE} on the
 * category; the delete takes {@code FOR UPDATE}; so they serialise in both orders ({@link HeldTransactionRace}, no
 * sleeps). Without the share lock the first case deletes the category (204) under an uncommitted suggestion and the
 * second persists a suggestion naming a deleted category.
 *
 * <p>Own rows: category {@value #CATEGORY}; its suggestions and the category are removed by
 * {@link CatalogRequestFixture#cleanup()}; {@code catalog_admin_audit} is truncated by {@code cleanDb}.
 */
@DisplayName("Category delete vs concurrent suggestion create (IT, B505) — FOR SHARE vs FOR UPDATE")
@TestPropertySource(properties = "app.internal-catalog-key=" + CatalogDeleteSuggestionRaceIT.CATALOG_KEY)
class CatalogDeleteSuggestionRaceIT extends AbstractIntegrationTest {

    static final String CATALOG_KEY = "test-internal-catalog-key-ffffffffffffffff";
    private static final String CATEGORY = "IT505_RACE";
    private static final String SUGGESTED = "Іт505 гоночний тип";
    private static final String ACTOR = "beautica.ua@gmail.com";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenGenerator tokenGenerator;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private CatalogEntryAdminService adminService;

    private CatalogRequestFixture fixture;
    private HeldTransactionRace race;

    @BeforeEach
    void seed() {
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder, tokenGenerator);
        fixture.insertCategory(CATEGORY, "Іт505 гоночна категорія", "APPROVED", null,
                Instant.parse("2020-01-01T10:00:00Z"));
        race = new HeldTransactionRace(transactionManager, jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanup(); // suggestions by category name, then the category
    }

    private ResponseEntity<String> deleteCategory() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", CATALOG_KEY);
        headers.set(AdminActor.HEADER, ACTOR);
        return restTemplate.exchange("/api/v1/internal/catalog/categories/" + CATEGORY, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
    }

    private int pendingSuggestions() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM service_type_suggestion "
                + "WHERE category_name = ? AND status = 'PENDING'", Integer.class, CATEGORY);
    }

    private boolean categoryExists() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM platform_categories WHERE name = ?)", Boolean.class, CATEGORY));
    }

    @Test
    @DisplayName("suggestion create holds its share lock → the delete waits, then counts it: 409 "
            + "{pendingSuggestions: 1}, category kept")
    void should_return409_when_suggestionCreatedBeforeDelete() throws Exception {
        ResponseEntity<String> response = race.run(
                () -> suggestionService.submitSuggestion(CATEGORY, SUGGESTED, null, null),
                this::deleteCategory);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("code").asText()).isEqualTo("CATALOG_IN_USE");
        assertThat(data.path("pendingSuggestions").asLong()).isEqualTo(1L);
        assertThat(categoryExists()).isTrue();
        assertThat(pendingSuggestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("delete holds its row lock → the suggestion create waits, then finds the category gone: 400, "
            + "nothing persisted")
    void should_rejectSuggestion_when_deleteHoldsTheLock() {
        Throwable thrown = catchThrowable(() -> race.run(
                () -> adminService.deleteCategory(AdminActor.of(ACTOR), CATEGORY),
                () -> {
                    suggestionService.submitSuggestion(CATEGORY, SUGGESTED, null, null);
                    return null;
                }));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(categoryExists()).isFalse();
        assertThat(pendingSuggestions()).isZero();
    }
}
