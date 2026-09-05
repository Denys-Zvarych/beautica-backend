package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ORDERING of the {@code /me} self-read matchers in {@link SecurityConfig} against their
 * wildcard/path-variable siblings.
 *
 * <p><b>Why the status code alone proves nothing.</b> Both outcomes are 401. An anonymous caller
 * that reaches the {@code DispatcherServlet} and is stopped by {@code @PreAuthorize} raises an
 * {@code AuthorizationDeniedException}, and {@code GlobalExceptionHandler#handleAuthorizationDenied}
 * deliberately maps an unauthenticated principal to 401 too — so a matcher-ordering regression
 * leaves the wire status identical and stays invisible. The discriminator is the BODY:
 * <ul>
 *   <li>filter-chain rejection — {@code SecurityConfig#unauthorizedEntryPoint} calls
 *       {@code sendError(401, "Unauthorized")}, producing the container/Boot error shape;</li>
 *   <li>method-security rejection — {@code GlobalExceptionHandler} writes
 *       {@code ApiResponse.error("Authentication required")}.</li>
 * </ul>
 * Asserting the body does NOT carry the method-security message is therefore the assertion that
 * actually goes red when the specific matcher is deleted or moved below its wildcard sibling.
 *
 * <p><b>Why this matters at all</b> (§K, defense in depth): a Spring {@code PathPattern} variable
 * segment {@code "{var}"} and a trailing {@code "**"} both match the literal segment {@code "me"},
 * and Spring Security takes the FIRST matching rule. Without the specific matcher above the general
 * one, an authenticated-only self-read is {@code permitAll()} at the chain and its {@code
 * @PreAuthorize} annotation is the SOLE gate. MVC still routes correctly (a literal
 * {@code @GetMapping("/me")} beats {@code "/{id}"}), so the mismatch is invisible until method
 * security is loosened or fails open.
 */
@DisplayName("SecurityConfig — /me self-read matchers precede their wildcard siblings")
class SelfReadMatcherSecurityTest extends AbstractIntegrationTest {

    /** What {@code GlobalExceptionHandler#handleAuthorizationDenied} writes for an anonymous caller. */
    private static final String METHOD_SECURITY_MESSAGE = "Authentication required";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("anonymous GET /api/v1/reviews/me is rejected by the FILTER CHAIN, not by "
            + "@PreAuthorize — the specific matcher must precede GET /api/v1/reviews/**")
    void should_rejectAtFilterChain_when_anonymousReadsMyReviews() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/reviews/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody() == null ? "" : response.getBody())
                .as("a body carrying the method-security message means the anonymous request "
                        + "reached the DispatcherServlet — i.e. \"/api/v1/reviews/**\" permitAll "
                        + "shadowed \"/api/v1/reviews/me\" again")
                .doesNotContain(METHOD_SECURITY_MESSAGE);
    }

    @Test
    @DisplayName("anonymous GET /api/v1/masters/me is rejected by the FILTER CHAIN, not by "
            + "@PreAuthorize — the specific matcher must precede GET /api/v1/masters/{masterId}")
    void should_rejectAtFilterChain_when_anonymousReadsMyMasterProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/masters/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody() == null ? "" : response.getBody())
                .as("a body carrying the method-security message means the anonymous request "
                        + "reached the DispatcherServlet — i.e. \"/api/v1/masters/{masterId}\" "
                        + "permitAll shadowed \"/api/v1/masters/me\" again")
                .doesNotContain(METHOD_SECURITY_MESSAGE);
    }

    @Test
    @DisplayName("anonymous GET /api/v1/salons/mine is rejected by the FILTER CHAIN, not by "
            + "@PreAuthorize — the specific matcher must precede GET /api/v1/salons/{salonId}")
    void should_rejectAtFilterChain_when_anonymousReadsMySalons() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/salons/mine", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody() == null ? "" : response.getBody())
                .as("a body carrying the method-security message means the anonymous request "
                        + "reached the DispatcherServlet — i.e. \"/api/v1/salons/{salonId}\" "
                        + "permitAll shadowed \"/api/v1/salons/mine\" again")
                .doesNotContain(METHOD_SECURITY_MESSAGE);
    }
}
