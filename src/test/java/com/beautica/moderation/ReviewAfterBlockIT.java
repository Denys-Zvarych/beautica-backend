package com.beautica.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 398 D6 — a block stops NEW bookings only: a visit completed before the block stays reviewable both ways
 * (client → provider {@code POST /reviews}, provider → client {@code POST /client-reviews}).
 */
@DisplayName("Reviews after a block — phase 398 IT")
class ReviewAfterBlockIT extends AbstractBookingBlockIT {

    @Test
    @DisplayName("client reviews the blocking independent master's pre-block completed visit → 201")
    void should_allowClientReview_when_completedBeforeBlock() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        String clientEmail = unique("client");
        UUID client = createUser(clientEmail, "CLIENT", null);
        UUID completed = insertIndependentBooking(client, master.masterId());
        block(tokenFor(emailOf(master.userId())), completed);

        ResponseEntity<String> resp = post("/api/v1/reviews", tokenFor(clientEmail),
                "{\"bookingId\":\"" + completed + "\",\"rating\":5,\"comment\":\"Дякую\"}");

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("the blocking independent master rates the client on the pre-block completed visit → 201")
    void should_allowProviderReview_when_completedBeforeBlock() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID client = createUser(unique("client"), "CLIENT", null);
        UUID completed = insertIndependentBooking(client, master.masterId());
        String masterToken = tokenFor(emailOf(master.userId()));
        block(masterToken, completed);

        ResponseEntity<String> resp = post("/api/v1/client-reviews", masterToken,
                "{\"bookingId\":\"" + completed + "\",\"rating\":4}");

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
