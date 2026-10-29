package com.beautica.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 397 QA — the per-user POST/DELETE {@code /blocks} budget over real HTTP, with the capacity pinned to 2 so
 * the third write of one user is the real 429 (standard body + {@code Retry-After} = the 1-hour window). Every test
 * uses fresh users, so the shared bucket bean never leaks between tests.
 */
@TestPropertySource(properties = "app.rate-limit.client-block-write-capacity=2")
@DisplayName("Client blocks — phase 397 rate limit IT (capacity 2)")
class ClientBlockRateLimitIT extends AbstractModerationIT {

    private static final String RETRY_AFTER_SECONDS = "3600";

    @Test
    @DisplayName("third POST of one owner is 429 with Retry-After and the generic body; a repeat still spends a token")
    void should_return429_when_thirdPostExceedsBudget() throws Exception {
        SalonFixture salon = createSalon();
        String token = tokenFor(salon.ownerEmail());
        UUID first = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
        UUID second = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");

        ResponseEntity<String> created = postBlock(token, first);
        ResponseEntity<String> repeat = postBlock(token, first);
        ResponseEntity<String> limited = postBlock(token, second);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(RETRY_AFTER_SECONDS);
        assertThat(objectMapper.readTree(limited.getBody()).path("success").asBoolean()).isFalse();
        assertThat(limited.getBody()).doesNotContain("capacity").doesNotContain("\"2\"");
        assertThat(countBlocks()).as("the limited call wrote nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("POST and DELETE share one budget: two DELETEs leave no token for a POST")
    void should_return429OnPost_when_deletesSpentTheSharedBudget() throws Exception {
        SalonFixture salon = createSalon();
        String token = tokenFor(salon.ownerEmail());
        UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");

        ResponseEntity<String> delete1 = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), token);
        ResponseEntity<String> delete2 = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), token);
        ResponseEntity<String> post = postBlock(token, bookingId);
        ResponseEntity<String> delete3 = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), token);

        assertThat(delete1.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete2.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(delete3.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(delete3.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(RETRY_AFTER_SECONDS);
        assertThat(countBlocks()).isZero();
    }

    @Test
    @DisplayName("404 probes of foreign booking ids spend the budget too (bounds booking-id probing)")
    void should_return429_when_foreignBookingProbesSpentBudget() throws Exception {
        SalonFixture mine = createSalon();
        SalonFixture other = createSalon();
        String token = tokenFor(mine.ownerEmail());
        UUID foreign = insertBooking(createUser(unique("client"), "CLIENT", null), other, "COMPLETED");
        UUID own = insertBooking(createUser(unique("client"), "CLIENT", null), mine, "COMPLETED");

        ResponseEntity<String> probe1 = postBlock(token, foreign);
        ResponseEntity<String> probe2 = postBlock(token, UUID.randomUUID());
        ResponseEntity<String> ownBlock = postBlock(token, own);

        assertThat(probe1.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(probe2.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ownBlock.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(countBlocks()).isZero();
    }

    @Test
    @DisplayName("the budget is per user: an exhausted admin does not throttle the owner of the same salon")
    void should_keepOwnerBudget_when_adminOfSameSalonExhausted() throws Exception {
        SalonFixture salon = createSalon();
        String adminEmail = unique("admin");
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        String adminToken = tokenFor(adminEmail);
        String ownerToken = tokenFor(salon.ownerEmail());
        UUID bookingId = insertBooking(createUser(unique("client"), "CLIENT", null), salon, "COMPLETED");
        exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), adminToken);
        exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), adminToken);

        ResponseEntity<String> adminPost = postBlock(adminToken, bookingId);
        ResponseEntity<String> ownerPost = postBlock(ownerToken, bookingId);

        assertThat(adminPost.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ownerPost.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("GET /blocks is not budgeted: it stays 200 after the write budget is gone")
    void should_keepListing_when_writeBudgetExhausted() throws Exception {
        SalonFixture salon = createSalon();
        String token = tokenFor(salon.ownerEmail());
        exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), token);
        exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + UUID.randomUUID(), token);

        ResponseEntity<String> list = exchange(HttpMethod.GET, BLOCKS_URL, token);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
