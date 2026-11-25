package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.PORTAL_BUTTON;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.RETIRED_PAGE_HEADING;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.RETIRED_PAGE_NOTHING_CHANGED;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.portalHref;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B545 (SVC-61) — the six former emailed review / approve / reject links stay mapped and anonymous, and answer 410
 * with the «use the portal» page for any token (valid-looking, garbage, none). They read no token and take no
 * decision: seeded PENDING requests stay PENDING, and no outbox, audit or in-app row is written.
 *
 * <p>Both seeded rows carry a LIVE legacy token minted by a pre-B545 pod: {@code token_hash = SHA-256 hex} of the
 * valid-looking token (the old {@code TokenGenerator#hash} scheme) with a future expiry. A handler that read the token
 * and looked the row up would therefore find and decide it; the row (status, token, expiry, decided_at) must stay
 * exactly as seeded.
 *
 * <p>Falsified by putting a token method back behind one of the paths (e.g. the old {@code approve(rawToken)} on the
 * category approve POST): the status is no longer 410 and this test fails.
 */
@DisplayName("Retired request e-mail links — 410 portal page, no token read, no decision (B545)")
class RetiredRequestLinksIT extends AbstractIntegrationTest {

    private static final String CATEGORY_NAME = "ITRETIRED_LINK_CAT";
    private static final String SUGGESTED_NAME = "ITRetired Link Ritual";
    private static final String VALID_LOOKING = "A".repeat(43);
    private static final String GARBAGE = "garbage~token!*" + "z".repeat(300);
    /** The pre-B545 stored form of {@link #VALID_LOOKING}: SHA-256 hex (old {@code TokenGenerator#hash}). */
    private static final String LEGACY_TOKEN_HASH = sha256Hex(VALID_LOOKING);

    @Autowired private TestRestTemplate restTemplate;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private long categoryId;
    private UUID suggestionId;
    private Map<String, Object> categoryRowBefore;
    private Map<String, Object> suggestionRowBefore;

    @BeforeEach
    void seedPendingRequests() {
        jdbcTemplate.update("INSERT INTO platform_categories "
                + "(name, display_name, active, status, requested_at, token_hash, token_expires_at) "
                + "VALUES (?, 'Відкликане посилання', false, 'PENDING', NOW(), ?, NOW() + INTERVAL '7 days')",
                CATEGORY_NAME, LEGACY_TOKEN_HASH);
        categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform_categories WHERE name = ?", Long.class, CATEGORY_NAME);
        suggestionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_type_suggestion "
                + "(id, category_name, suggested_name, status, token_hash, token_expires_at) "
                + "VALUES (?, 'HAIRDRESSING', ?, 'PENDING', ?, NOW() + INTERVAL '7 days')",
                suggestionId, SUGGESTED_NAME, LEGACY_TOKEN_HASH);
        categoryRowBefore = categoryRow();
        suggestionRowBefore = suggestionRow();
    }

    @AfterEach
    void removeSeededRequests() {
        // Not in cleanDb(); no FK between the two tables.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE id = ?", suggestionId);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    /** {@code (label, method, path, token)}: every retired path × a valid-looking, a garbage and a missing token. */
    static Stream<Arguments> retiredLinks() {
        List<Arguments> cases = new ArrayList<>();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("valid-looking", VALID_LOOKING);
        tokens.put("garbage", GARBAGE);
        tokens.put("none", null);
        tokens.forEach((kind, token) -> {
            cases.add(Arguments.of("category review GET, " + kind, HttpMethod.GET,
                    "/api/v1/service-categories/requests/review/" + (token == null ? "" : token), null));
            cases.add(Arguments.of("suggestion review GET, " + kind, HttpMethod.GET,
                    "/api/v1/service-types/suggestions/review", token));
            for (String path : List.of(
                    "/api/v1/service-categories/requests/approve",
                    "/api/v1/service-categories/requests/reject",
                    "/api/v1/service-types/suggestions/approve",
                    "/api/v1/service-types/suggestions/reject")) {
                cases.add(Arguments.of(path + ", " + kind, HttpMethod.POST, path, token));
            }
        });
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredLinks")
    @DisplayName("anonymous request → 410 portal page; PENDING rows unchanged; no outbox, audit or in-app row")
    void should_return410PortalPageAndDecideNothing_when_retiredLinkOpened(
            String label, HttpMethod method, String path, String token) {
        long outboxBefore = count("notification_outbox");
        long auditBefore = count("catalog_admin_audit");
        long inAppBefore = count("in_app_notification");

        ResponseEntity<String> response = method == HttpMethod.GET ? get(path, token) : post(path, token);

        assertThat(response.getStatusCode()).as("%s body=%s", label, response.getBody()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getHeaders().getContentType()).isNotNull()
                .matches(ct -> ct.isCompatibleWith(MediaType.TEXT_HTML));
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getBody())
                .contains(RETIRED_PAGE_HEADING, RETIRED_PAGE_NOTHING_CHANGED, PORTAL_BUTTON,
                        portalHref(frontendBaseUrl));
        if (token != null) {
            assertThat(response.getBody()).as("the token is never echoed").doesNotContain(token);
        }
        assertThat(categoryRow()).as("%s: category row (status, token, expiry, decided_at) untouched", label)
                .isEqualTo(categoryRowBefore).containsEntry("status", "PENDING");
        assertThat(suggestionRow()).as("%s: suggestion row (status, token, expiry, decided_at) untouched", label)
                .isEqualTo(suggestionRowBefore).containsEntry("status", "PENDING");
        assertThat(count("notification_outbox")).isEqualTo(outboxBefore);
        assertThat(count("catalog_admin_audit")).isEqualTo(auditBefore);
        assertThat(count("in_app_notification")).isEqualTo(inAppBefore);
    }

    private ResponseEntity<String> get(String path, String token) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
        if (token != null) {
            uri.queryParam("token", token);
        }
        URI target = restTemplate.getRootUri().isEmpty()
                ? URI.create(uri.encode().build().toUriString())
                : URI.create(restTemplate.getRootUri() + uri.encode().build().toUriString());
        return restTemplate.exchange(target, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private ResponseEntity<String> post(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (token != null) {
            form.add("token", token);
            form.add("reason", "відкликане посилання");
        }
        return restTemplate.postForEntity(path, new HttpEntity<>(form, headers), String.class);
    }

    private Map<String, Object> categoryRow() {
        return jdbcTemplate.queryForMap("SELECT status, active, token_hash, token_expires_at, decided_at "
                + "FROM platform_categories WHERE id = ?", categoryId);
    }

    private Map<String, Object> suggestionRow() {
        return jdbcTemplate.queryForMap("SELECT status, token_hash, token_expires_at, decided_at "
                + "FROM service_type_suggestion WHERE id = ?", suggestionId);
    }

    private static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
