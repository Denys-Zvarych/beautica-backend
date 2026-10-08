package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 355 — end-to-end journey per role for "who completes a booking and who rates its client":
 * complete, then read {@code providerCanReviewClient} on detail and list, then
 * {@code POST /client-reviews}. The flag and the write status must agree for every role (one shared
 * predicate), and at a salon only the booking's OWNER/ADMIN complete AND rate; the invited
 * {@code SALON_MASTER} does neither. The independent master is unchanged.
 *
 * <p>The assigned {@code SALON_ADMIN} cannot read {@code GET /bookings/{id}} or {@code GET
 * /bookings/me} (pre-existing view-gate decision, locked at phase 24.2); their flag is observed on
 * the salon board, {@code GET /bookings/salon/{salonId}}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon client-feedback authority — complete, flag, rate agree per role (phase 355)")
class SalonClientFeedbackAuthorityIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    private BookingTestFixtures fixtures() {
        return fx;
    }

    @Test
    @DisplayName("SALON_OWNER: completes 204, flag true on detail and list, rate 201")
    void should_completeAndRate_when_salonOwnerRunsTheJourney() throws Exception {
        var salon = fx.createSalon("sfa-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String token = fx.tokenFor(salon.ownerEmail());

        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("COMPLETED");

        boolean detail = detailFlag(bookingId, token);
        boolean list = listFlag(bookingId, token).orElseThrow();
        HttpStatus rate = rate(bookingId, token);

        assertThat(detail).as("detail flag").isTrue();
        assertThat(list).as("list flag").isTrue();
        assertThat(rate).as("rate").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("SALON_ADMIN of the booking's salon: completes 204, flag true on the salon board, rate 201")
    void should_completeAndRate_when_assignedSalonAdminRunsTheJourney() throws Exception {
        var salon = fx.createSalon("sfa-admin-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sfa-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String token = fx.tokenFor(adminEmail);

        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("COMPLETED");

        // Phase 356 widens the admin view gate (admin may read GET /bookings/{id}); it will update this
        // 403 assertion and observe the flag on detail as well.
        assertThat(getStatus(BOOKINGS_URL + "/" + bookingId, token))
                .as("pre-existing: the admin view gate excludes GET /bookings/{id}")
                .isEqualTo(HttpStatus.FORBIDDEN);
        boolean board = boardFlag(salon.salonId(), bookingId, token);
        HttpStatus rate = rate(bookingId, token);

        assertThat(board).as("salon-board flag").isTrue();
        assertThat(rate).as("rate").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("SALON_MASTER (performing): complete 403, flag false on detail and list, rate 403")
    void should_denyEverything_when_salonMasterRunsTheJourney() throws Exception {
        var salon = fx.createSalon("sfa-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String token = fx.tokenFor(salon.masterEmail());

        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).as("the denied complete changed nothing").isEqualTo("CONFIRMED");

        // The owner (who alone may) closes it, so the read/rate half below runs on a COMPLETED booking.
        assertThat(patchComplete(bookingId, fx.tokenFor(salon.ownerEmail()))).isEqualTo(HttpStatus.NO_CONTENT);

        boolean detail = detailFlag(bookingId, token);
        boolean list = listFlag(bookingId, token).orElseThrow();
        HttpStatus rate = rate(bookingId, token);

        assertThat(detail).as("detail flag").isFalse();
        assertThat(list).as("list flag").isFalse();
        assertThat(rate).as("rate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId)).isZero();
    }

    @Test
    @DisplayName("Owner/admin of a DIFFERENT salon: complete 403, rate 403")
    void should_denyEverything_when_staffOfAnotherSalonRunsTheJourney() throws Exception {
        var salon = fx.createSalon("sfa-x-owner-" + System.nanoTime() + "@beautica.test");
        var other = fx.createSalon("sfa-x-other-" + System.nanoTime() + "@beautica.test");
        String otherAdmin = "sfa-x-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(otherAdmin, "SALON_ADMIN", other.salonId());
        UUID bookingId = insertConfirmedSalonBooking(salon);

        for (String email : new String[] {other.ownerEmail(), otherAdmin}) {
            assertThat(patchComplete(bookingId, fx.tokenFor(email))).as("complete by %s", email)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(patchComplete(bookingId, fx.tokenFor(salon.ownerEmail()))).isEqualTo(HttpStatus.NO_CONTENT);
        for (String email : new String[] {other.ownerEmail(), otherAdmin}) {
            assertThat(rate(bookingId, fx.tokenFor(email))).as("rate by %s", email)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER (own booking): completes 204, flag true on detail and list, rate 201 — "
            + "unchanged; another independent master is denied the rating")
    void should_completeAndRate_when_independentMasterRunsTheJourney() throws Exception {
        String email = "sfa-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fx.createIndependentMaster(email);
        UUID masterServiceId = fx.createIndependentMasterService(masterId);
        UUID clientId = fx.createUser("sfa-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, null);
        String token = fx.tokenFor(email);

        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.NO_CONTENT);

        boolean detail = detailFlag(bookingId, token);
        boolean list = listFlag(bookingId, token).orElseThrow();
        String otherEmail = "sfa-im-other-" + System.nanoTime() + "@beautica.test";
        fx.createIndependentMaster(otherEmail);
        HttpStatus otherRate = rate(bookingId, fx.tokenFor(otherEmail));
        HttpStatus rate = rate(bookingId, token);

        assertThat(detail).as("detail flag").isTrue();
        assertThat(list).as("list flag").isTrue();
        assertThat(otherRate).as("another independent master's rate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rate).as("rate").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Deactivated INDEPENDENT_MASTER with a live token: flag false on detail and list, rate 403")
    void should_denyRating_when_independentMasterIsDeactivated() throws Exception {
        String email = "sfa-im-deact-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fx.createIndependentMaster(email);
        UUID masterServiceId = fx.createIndependentMasterService(masterId);
        UUID clientId = fx.createUser("sfa-im-deact-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, null);
        String token = fx.tokenFor(email);
        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(detailFlag(bookingId, token)).as("control — live master sees true").isTrue();

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterId);

        // Detail/list may themselves be gated for a deactivated master; the claim is the flag never
        // reads true wherever it is served, and the write is 403.
        ResponseEntity<String> detail = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fx.bearerHeaders(token)), String.class);
        if (detail.getStatusCode() == HttpStatus.OK) {
            assertThat(objectMapper.readTree(detail.getBody()).path("data")
                    .path("providerCanReviewClient").asBoolean()).as("detail flag").isFalse();
        }
        ResponseEntity<String> list = restTemplate.exchange(
                BOOKINGS_URL + "/me?size=50", HttpMethod.GET,
                new HttpEntity<>(fx.bearerHeaders(token)), String.class);
        if (list.getStatusCode() == HttpStatus.OK) {
            assertThat(flagInPage(BOOKINGS_URL + "/me?size=50", bookingId, token).orElse(false))
                    .as("list flag").isFalse();
        }
        assertThat(rate(bookingId, token)).as("rate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId)).isZero();
    }

    @Test
    @DisplayName("SALON_OWNER: /bookings/me flag is true on a completed unrated booking and false once rated")
    void should_flipListFlagFalse_when_ownerRatesTheClient() throws Exception {
        var salon = fx.createSalon("sfa-flip-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String token = fx.tokenFor(salon.ownerEmail());
        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listFlag(bookingId, token)).as("before rating").contains(true);

        HttpStatus rate = rate(bookingId, token);
        Optional<Boolean> listAfter = listFlag(bookingId, token);
        boolean detailAfter = detailFlag(bookingId, token);

        assertThat(rate).as("rate").isEqualTo(HttpStatus.CREATED);
        assertThat(listAfter).as("list flag after rating").contains(false);
        assertThat(detailAfter).as("detail flag after rating").isFalse();
        assertThat(rate(bookingId, token)).as("second rate is a duplicate").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("SALON_ADMIN: salon-board flag is true on a completed unrated booking and flips false after the admin rates")
    void should_flipBoardFlagFalse_when_adminRatesTheClient() throws Exception {
        var salon = fx.createSalon("sfa-bflip-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sfa-bflip-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String token = fx.tokenFor(adminEmail);
        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(boardFlag(salon.salonId(), bookingId, token)).as("before rating").isTrue();

        HttpStatus rate = rate(bookingId, token);
        boolean boardAfter = boardFlag(salon.salonId(), bookingId, token);

        assertThat(rate).as("rate").isEqualTo(HttpStatus.CREATED);
        assertThat(boardAfter).as("board flag after rating").isFalse();
    }

    @Test
    @DisplayName("Rotated master (moved to another salon): authority follows the LIVE salon — old owner 403 + flag false, new owner 201")
    void should_followLiveSalon_when_masterRotatedToAnotherSalonAfterCompletion() throws Exception {
        var oldSalon = fx.createSalon("sfa-rot-old-" + System.nanoTime() + "@beautica.test");
        var newSalon = fx.createSalon("sfa-rot-new-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(oldSalon);
        String oldOwnerToken = fx.tokenFor(oldSalon.ownerEmail());
        String newOwnerToken = fx.tokenFor(newSalon.ownerEmail());
        assertThat(patchComplete(bookingId, oldOwnerToken)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(boardFlag(oldSalon.salonId(), bookingId, oldOwnerToken)).as("control, before rotation").isTrue();

        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", newSalon.salonId(), oldSalon.masterId());

        Optional<Boolean> oldList = listFlag(bookingId, oldOwnerToken);
        Optional<Boolean> oldBoard = flagInPage(
                BOOKINGS_URL + "/salon/" + oldSalon.salonId() + "?size=50", bookingId, oldOwnerToken);
        HttpStatus oldRate = rate(bookingId, oldOwnerToken);
        int reviewsAfterOldRate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId);
        HttpStatus newRate = rate(bookingId, newOwnerToken);

        assertThat(oldList.orElse(false)).as("old owner list flag").isFalse();
        assertThat(oldBoard.orElse(false)).as("old owner board flag").isFalse();
        assertThat(oldRate).as("old owner rate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reviewsAfterOldRate).as("denied rate wrote nothing").isZero();
        assertThat(newRate).as("live-salon owner rate").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Deactivated SALON_ADMIN with a live token: complete 403 and rate 403, nothing written")
    void should_denyCompleteAndRate_when_adminIsDeactivated() throws Exception {
        var salon = fx.createSalon("sfa-deadm-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sfa-deadm-" + System.nanoTime() + "@beautica.test";
        UUID adminId = fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String token = fx.tokenFor(adminEmail);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", adminId);

        HttpStatus complete = patchComplete(bookingId, token);
        assertThat(patchComplete(bookingId, fx.tokenFor(salon.ownerEmail()))).isEqualTo(HttpStatus.NO_CONTENT);
        HttpStatus rate = rate(bookingId, token);

        assertThat(complete).as("complete").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rate).as("rate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId)).isZero();
    }

    @Test
    @DisplayName("SALON_MASTER who is ALSO the salon's owner row (role-blind kernel would admit): detail and list "
            + "flag false on their own completed unrated booking")
    void should_keepFlagFalse_when_salonMasterRoleHoldsOwnershipOfTheSalon() throws Exception {
        var salon = fx.createSalon("sfa-smown-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());
        // The kernel (hasProviderAuthorityOverBooking) admits by ownership and is role-blind, so only the
        // explicit SALON_MASTER rejection in canProviderReviewClient keeps this actor out.
        jdbcTemplate.update("UPDATE salons SET owner_id = ? WHERE id = ?", masterUserId, salon.salonId());
        UUID bookingId = insertConfirmedSalonBooking(salon);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        String token = fx.tokenFor(salon.masterEmail());

        boolean detail = detailFlag(bookingId, token);
        Optional<Boolean> list = listFlag(bookingId, token);

        assertThat(detail).as("detail flag").isFalse();
        assertThat(list).as("list flag").contains(false);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private HttpStatus patchComplete(UUID bookingId, String token) throws Exception {
        return HttpStatus.valueOf(restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(fixtures().bearerHeaders(token)), String.class).getStatusCode().value());
    }

    private HttpStatus getStatus(String url, String token) {
        return HttpStatus.valueOf(restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures().bearerHeaders(token)), String.class)
                .getStatusCode().value());
    }

    private boolean detailFlag(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures().bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("detail body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("providerCanReviewClient").asBoolean();
    }

    private Optional<Boolean> listFlag(UUID bookingId, String token) throws Exception {
        return flagInPage(BOOKINGS_URL + "/me?size=50", bookingId, token);
    }

    private boolean boardFlag(UUID salonId, UUID bookingId, String token) throws Exception {
        return flagInPage(BOOKINGS_URL + "/salon/" + salonId + "?size=50", bookingId, token).orElseThrow();
    }

    private Optional<Boolean> flagInPage(String url, UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures().bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("page body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : objectMapper.readTree(resp.getBody()).path("data").path("data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return Optional.of(row.path("providerCanReviewClient").asBoolean());
            }
        }
        return Optional.empty();
    }

    private HttpStatus rate(UUID bookingId, String token) throws Exception {
        return HttpStatus.valueOf(restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("bookingId", bookingId.toString(), "rating", 5)),
                        fixtures().bearerHeaders(token)),
                String.class).getStatusCode().value());
    }

    // ── seeding ──────────────────────────────────────────────────────────────────

    private UUID insertConfirmedSalonBooking(BookingTestFixtures.SalonFixture salon) {
        UUID clientId = fx.createUser("sfa-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = fx.createSalonService(salon.salonId(), salon.masterId());
        return insertConfirmedBooking(clientId, salon.masterId(), masterServiceId, salon.salonId());
    }

    /** ELAPSED CONFIRMED booking (starts_at 2h in the past) so {@code /complete}'s elapse guard passes. */
    private UUID insertConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }
}
