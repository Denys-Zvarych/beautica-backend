package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.support.BookableMasterSeeder;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 378 D12 — {@code Master.bookingSlug} is {@code updatable = false}: a {@code Master} loaded BEFORE a
 * regenerate and flushed AFTER it (any profile edit on a stale snapshot) must not write the retired slug back.
 * Falsified 2026-10-23: with {@code updatable = false} removed, the stored slug reverts to the old one (red).
 */
@DisplayName("Master.bookingSlug cannot be written by an entity flush (phase 378 D12)")
class MasterBookingSlugImmutabilityIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MasterRepository masterRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private BookingLinksProperties bookingLinksProperties;

    @Test
    @DisplayName("load Master in tx A → regenerate commits in tx B → tx A flushes a profile edit → stored slug is B's new one")
    void should_keepRegeneratedSlug_when_staleSnapshotFlushedAfterRegenerate() {
        bookingLinksProperties.setShareEnabled(true);
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Kalyna");
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'kalyna-kalyna-ab12' WHERE id = ?", masterId);
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        String token = jwtTokenProvider.generateAccessToken(userId, "u-" + userId + "@beautica.test",
                Role.INDEPENDENT_MASTER);

        String[] regenerated = new String[1];
        transactionTemplate.executeWithoutResult(status -> {
            Master stale = masterRepository.findById(masterId).orElseThrow();
            assertThat(stale.getBookingSlug()).isEqualTo("kalyna-kalyna-ab12");

            regenerated[0] = regenerate(token, "kalyna-kalyna-ab12");   // tx B, own connection, commits

            stale.setMinEffectivePrice(new BigDecimal("123.00"));        // dirty → UPDATE on tx A's commit
        });

        assertThat(jdbcTemplate.queryForObject("SELECT min_effective_price FROM masters WHERE id = ?",
                BigDecimal.class, masterId)).isEqualByComparingTo("123.00");
        assertThat(jdbcTemplate.queryForObject("SELECT booking_slug FROM masters WHERE id = ?", String.class, masterId))
                .isEqualTo(regenerated[0])
                .isNotEqualTo("kalyna-kalyna-ab12");
    }

    private String regenerate(String token, String currentSlug) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/booking-links/master/regenerate",
                HttpMethod.POST, new HttpEntity<>(Map.of("currentSlug", currentSlug), headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) ((Map<?, ?>) response.getBody().get("data")).get("slug");
    }
}
