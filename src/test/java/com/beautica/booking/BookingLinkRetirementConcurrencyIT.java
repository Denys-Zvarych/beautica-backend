package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.service.SecureRandomSlugSuffixSource;
import com.beautica.booking.service.SlugSuffixSource;
import com.beautica.config.BookingLinksProperties;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Phase 378 D11 — races on a real database: parallel regenerates with one {@code currentSlug} (CAS +
 * slug-text advisory lock: exactly one wins), revoke racing {@code GET /mine} (never re-issued, no stray
 * tombstone), and a retire racing a generator that is FORCED (via the {@link SlugSuffixSource} seam) to
 * propose the slug being retired (the slug is never live again).
 */
@DisplayName("Booking links — regenerate / revoke races (phase 378 D11)")
class BookingLinkRetirementConcurrencyIT extends AbstractIntegrationTest {

    private static final int CALLERS = 6;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private BookingLinksProperties bookingLinksProperties;
    @MockitoSpyBean private SecureRandomSlugSuffixSource slugSuffixSource;

    @BeforeEach
    void setUp() {
        bookingLinksProperties.setShareEnabled(true);
        reset(slugSuffixSource);
    }

    private UUID masterWithSlug(String name, String slug) {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, name);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterId);
        return masterId;
    }

    private String tokenOf(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        return jwtTokenProvider.generateAccessToken(userId, "u-" + userId + "@beautica.test", Role.INDEPENDENT_MASTER);
    }

    private String slugOf(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM masters WHERE id = ?", String.class, masterId);
    }

    private ResponseEntity<String> mutate(HttpMethod method, String url, String token, String currentSlug) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("currentSlug", currentSlug);
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode mineLink(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/booking-links/mine", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("links").get(0);
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("6 parallel regenerates with the same currentSlug → exactly one 200, five 409, ONE tombstone, stored slug = the winner's")
    void should_letExactlyOneRegenerateWin_when_sixRaceWithSameCurrentSlug() throws Exception {
        UUID masterId = masterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = tokenOf(masterId);
        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            tasks.add(() -> mutate(HttpMethod.POST, "/api/v1/booking-links/master/regenerate", token, "kalyna-kalyna-ab12"));
        }

        List<ResponseEntity<String>> responses = runTogether(tasks);

        List<ResponseEntity<String>> winners = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK).toList();
        assertThat(winners).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT).hasSize(CALLERS - 1);
        String winnerSlug = objectMapper.readTree(winners.get(0).getBody()).path("data").path("slug").asText();
        assertThat(slugOf(masterId)).isEqualTo(winnerSlug);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retired_booking_slugs WHERE kind = 'MASTER'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("revoke ∥ 6 × GET /mine → final state revoked, slug NULL, every /mine saw the old slug or REVOKED, ONE tombstone")
    void should_endRevokedWithoutReissue_when_revokeRacesMine() throws Exception {
        UUID masterId = masterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = tokenOf(masterId);
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> String.valueOf(
                mutate(HttpMethod.DELETE, "/api/v1/booking-links/master", token, "kalyna-kalyna-ab12").getStatusCode().value()));
        for (int i = 0; i < CALLERS; i++) {
            tasks.add(() -> {
                JsonNode link = mineLink(token);
                return link.path("status").asText() + ":" + (link.path("slug").isNull() ? "null" : link.path("slug").asText());
            });
        }

        List<String> results = runTogether(tasks);

        assertThat(results.get(0)).isEqualTo("200");
        assertThat(results.subList(1, results.size()))
                .allSatisfy(r -> assertThat(r).isIn("ACTIVE:kalyna-kalyna-ab12", "REVOKED:null"));
        assertThat(slugOf(masterId)).isNull();
        assertThat(mineLink(token).path("status").asText()).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM retired_booking_slugs", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("retire X ∥ another master's lazy issue FORCED to propose X first → X is never live again (5 rounds)")
    void should_neverReissueRetiredSlug_when_generatorProposesItConcurrently() throws Exception {
        for (int round = 0; round < 5; round++) {
            String retired = "kalyna-kalyna-zz0" + round;
            UUID holder = masterWithSlug("Kalyna", retired);
            UUID legacy = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Kalyna");
            String suffix = retired.substring(retired.lastIndexOf('-') + 1);
            AtomicInteger draws = new AtomicInteger();
            doAnswer(invocation -> draws.getAndIncrement() == 0 ? suffix : invocation.callRealMethod())
                    .when(slugSuffixSource).nextSuffix();
            String holderToken = tokenOf(holder);
            String legacyToken = tokenOf(legacy);

            List<String> results = runTogether(List.of(
                    () -> String.valueOf(mutate(HttpMethod.DELETE, "/api/v1/booking-links/master", holderToken, retired)
                            .getStatusCode().value()),
                    () -> mineLink(legacyToken).path("slug").asText()));

            assertThat(results.get(0)).isEqualTo("200");
            assertThat(results.get(1)).as("round %d", round).isNotEqualTo(retired).startsWith("kalyna-kalyna-");
            assertThat(slugOf(legacy)).isNotEqualTo(retired);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM masters WHERE booking_slug = ?", Integer.class, retired)).isZero();
            assertThat(draws.get()).as("the forced candidate was drawn").isPositive();
            reset(slugSuffixSource);
        }
    }
}
