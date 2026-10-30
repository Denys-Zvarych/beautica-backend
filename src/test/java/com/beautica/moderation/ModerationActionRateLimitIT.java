package com.beautica.moderation;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 414 D13 — the moderation-action GET page and POST decision have their OWN per-IP buckets, and the (N+1)th
 * request answers 429 as an HTML page. Own context with tiny capacities (the shared test profile lifts both to
 * 100000 so the flow ITs never trip them).
 */
@TestPropertySource(properties = {
        "app.rate-limit.moderation-action-page-capacity=3",
        "app.rate-limit.moderation-action-decision-capacity=2"
})
@DisplayName("Moderation e-mail action links — rate limit (phase 414 D13)")
class ModerationActionRateLimitIT extends AbstractIntegrationTest {

    private static final String TOKEN = "q".repeat(43);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("4th GET from one IP → 429 HTML; the POST bucket is untouched by GETs (3rd POST → 429)")
    void should_throttlePageAndDecisionSeparately_when_capacityExceeded() {
        for (int i = 0; i < 3; i++) {
            assertThat(get().getStatusCode()).isEqualTo(HttpStatus.GONE);
        }
        ResponseEntity<String> fourthGet = get();
        ResponseEntity<String> firstPost = post();
        ResponseEntity<String> secondPost = post();
        ResponseEntity<String> thirdPost = post();

        assertThat(fourthGet.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourthGet.getHeaders().getContentType()).isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
        assertThat(fourthGet.getBody()).contains("Забагато запитів");
        assertThat(fourthGet.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(firstPost.getStatusCode()).as("GETs never spend the POST budget").isEqualTo(HttpStatus.GONE);
        assertThat(secondPost.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(thirdPost.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(thirdPost.getBody()).contains("Забагато запитів");
    }

    private ResponseEntity<String> get() {
        return restTemplate.exchange("/api/v1/moderation/actions/" + TOKEN, HttpMethod.GET, HttpEntity.EMPTY,
                String.class);
    }

    private ResponseEntity<String> post() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", TOKEN);
        form.add("nonce", TOKEN);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.exchange("/api/v1/moderation/actions/confirm", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }
}
