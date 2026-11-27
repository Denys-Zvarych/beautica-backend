package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.review.config.ClientRatingProperties;
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

import java.util.List;
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
 * <p>The assigned {@code SALON_ADMIN} reads {@code GET /bookings/{id}} since phase 356 (not {@code
 * GET /bookings/me}); their flag is observed on detail and on the salon board, {@code GET
 * /bookings/salon/{salonId}}.
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

    @Autowired
    private ClientRatingProperties clientRatingProperties;

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

        // Phase 356: the assigned admin may read GET /bookings/{id}; the flag is observed there too.
        boolean detail = detailFlag(bookingId, token);
        boolean board = boardFlag(salon.salonId(), bookingId, token);
        HttpStatus rate = rate(bookingId, token);

        assertThat(detail).as("detail flag").isTrue();
        assertThat(board).as("salon-board flag").isTrue();
        assertThat(rate).as("rate").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("B547 — SALON_MASTER (performing): complete 403 (BOOK-D08), flag true on detail and list, rate 201; "
            + "the owner then sees no CTA and a second rating is 409 (REV-42)")
    void should_rateButNotComplete_when_salonMasterRunsTheJourney() throws Exception {
        var salon = fx.createSalon("sfa-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String token = fx.tokenFor(salon.masterEmail());
        String ownerToken = fx.tokenFor(salon.ownerEmail());

        assertThat(patchComplete(bookingId, token)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).as("the denied complete changed nothing").isEqualTo("CONFIRMED");

        // The owner (who alone may) closes it, so the read/rate half below runs on a COMPLETED booking.
        assertThat(patchComplete(bookingId, ownerToken)).isEqualTo(HttpStatus.NO_CONTENT);

        boolean detail = detailFlag(bookingId, token);
        boolean list = listFlag(bookingId, token).orElseThrow();
        HttpStatus rate = rate(bookingId, token);

        assertThat(detail).as("detail flag").isTrue();
        assertThat(list).as("list flag").isTrue();
        assertThat(rate).as("rate").isEqualTo(HttpStatus.CREATED);
        assertThat(detailFlag(bookingId, ownerToken)).as("owner flag once the master rated").isFalse();
        assertThat(rate(bookingId, ownerToken)).as("second salon-side rating").isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId)).isEqualTo(1);
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
    @DisplayName("B547 — SALON_MASTER who is ALSO the salon's owner row: detail and list flag true on their own "
            + "completed unrated booking (the performer arm admits them; ownership is never consulted for this role)")
    void should_showFlag_when_salonMasterRoleHoldsOwnershipOfTheSalon() throws Exception {
        var salon = fx.createSalon("sfa-smown-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());
        // B547: a SALON_MASTER actor is decided by the performer arm (booking.master.user == actor), never by the
        // role-blind ownership kernel.
        jdbcTemplate.update("UPDATE salons SET owner_id = ? WHERE id = ?", masterUserId, salon.salonId());
        UUID bookingId = insertConfirmedSalonBooking(salon);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        String token = fx.tokenFor(salon.masterEmail());

        boolean detail = detailFlag(bookingId, token);
        Optional<Boolean> list = listFlag(bookingId, token);

        assertThat(detail).as("detail flag").isTrue();
        assertThat(list).as("list flag").contains(true);
    }

    @Test
    @DisplayName("B547 (REV-42) race — the owner and the performing master POST concurrently: exactly one 201 and one "
            + "409, one row (uq_client_reviews_booking)")
    void should_admitExactlyOneSalonSideRating_when_ownerAndMasterRateConcurrently() throws Exception {
        var salon = fx.createSalon("sfa-race-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        String ownerToken = fx.tokenFor(salon.ownerEmail());
        String masterToken = fx.tokenFor(salon.masterEmail());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<HttpStatus> owner = pool.submit(() -> {
                start.await();
                return rate(bookingId, ownerToken);
            });
            java.util.concurrent.Future<HttpStatus> master = pool.submit(() -> {
                start.await();
                return rate(bookingId, masterToken);
            });

            start.countDown();
            List<HttpStatus> outcomes = List.of(owner.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    master.get(30, java.util.concurrent.TimeUnit.SECONDS));

            assertThat(outcomes).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("B547 (NTF-D07) — the owner completes: no feed row (so no push) for the performing master")
    void should_notNotifyThePerformingMaster_when_ownerCompletes() throws Exception {
        var salon = fx.createSalon("sfa-ntf-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());

        assertThat(patchComplete(bookingId, fx.tokenFor(salon.ownerEmail()))).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ?", Integer.class, masterUserId))
                .as("no completion notification to the master (push mirrors the feed, NTF-12)")
                .isZero();
    }

    @Test
    @DisplayName("B546 1b — an INDEPENDENT_MASTER-typed master that still carries a salon_id: flag true and POST 201 for "
            + "the master; the owner of that salon is denied on both")
    void should_agreeOnTheIndependentArm_when_independentMasterCarriesASalonId() throws Exception {
        var salon = fx.createSalon("sfa-1b-owner-" + System.nanoTime() + "@beautica.test");
        String email = "sfa-1b-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fx.createIndependentMaster(email);
        UUID masterServiceId = fx.createIndependentMasterService(masterId);
        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", salon.salonId(), masterId);
        UUID clientId = fx.createUser("sfa-1b-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, null);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        String masterToken = fx.tokenFor(email);
        String ownerToken = fx.tokenFor(salon.ownerEmail());

        assertThat(listFlag(bookingId, ownerToken)).as("owner list flag (row absent or false)")
                .satisfiesAnyOf(f -> assertThat(f).isEmpty(), f -> assertThat(f).contains(false));
        assertThat(rate(bookingId, ownerToken)).as("owner rate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(detailFlag(bookingId, masterToken)).as("master detail flag").isTrue();
        assertThat(listFlag(bookingId, masterToken)).as("master list flag").contains(true);
        assertThat(rate(bookingId, masterToken)).as("master rate").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("B547 D2 — a COLLEAGUE SALON_MASTER (same salon, not the performer): POST /client-reviews 403, no "
            + "detail (so no CTA) and no list row, nothing written; the performer can still rate afterwards")
    void should_denyRating_when_colleagueSalonMasterOfTheSameSalonPosts() throws Exception {
        var salon = fx.createSalon("sfa-colleague-owner-" + System.nanoTime() + "@beautica.test");
        UUID colleagueMasterId = fx.createExtraSalonMaster(salon.salonId(), "sfa-colleague");
        String colleagueEmail = jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN masters m ON m.user_id = u.id WHERE m.id = ?",
                String.class, colleagueMasterId);
        UUID bookingId = insertConfirmedSalonBooking(salon);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        String colleagueToken = fx.tokenFor(colleagueEmail);

        HttpStatus colleagueRate = rate(bookingId, colleagueToken);
        HttpStatus colleagueDetail = getStatus(BOOKINGS_URL + "/" + bookingId, colleagueToken);
        Optional<Boolean> colleagueListFlag = listFlag(bookingId, colleagueToken);
        int rowsAfterDenied = reviewCount(bookingId);

        assertThat(colleagueRate).as("colleague rate").isEqualTo(HttpStatus.FORBIDDEN);
        // Fix M1: a SALON_MASTER views only the bookings they performed, so the colleague is never served the
        // detail (and so never the CTA) and the booking is not on their /bookings/me page.
        assertThat(colleagueDetail).as("colleague detail").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(colleagueListFlag).as("colleague list row").isEmpty();
        assertThat(rowsAfterDenied).as("the denied rate wrote nothing").isZero();
        assertThat(rate(bookingId, fx.tokenFor(salon.masterEmail()))).as("performer rate after the denial")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("B546 (REV-41) flag OFF (APP_GUEST_CLIENT_RATING_ENABLED=false, the default) — a LINK guest and a "
            + "STAFF walk-in with a phone: detail and list flag false, POST 400 (genericised body), nothing written")
    void should_keepGuestsUnrateable_when_guestRatingFlagIsOff() throws Exception {
        assertThat(clientRatingProperties.guestEnabled()).as("premise: this context runs with the flag off").isFalse();
        var salon = fx.createSalon("sfa-flagoff-owner-" + System.nanoTime() + "@beautica.test");
        String ownerToken = fx.tokenFor(salon.ownerEmail());

        for (String source : List.of("LINK", "STAFF")) {
            UUID bookingId = insertCompletedGuestBooking(salon, source);

            boolean detail = detailFlag(bookingId, ownerToken);
            Optional<Boolean> list = listFlag(bookingId, ownerToken);
            ResponseEntity<String> write = rateResponse(bookingId, ownerToken);

            assertThat(detail).as("%s detail flag", source).isFalse();
            assertThat(list.orElse(false)).as("%s list flag", source).isFalse();
            assertThat(write.getStatusCode()).as("%s write body=%s", source, write.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            // The business-400 message is genericised on the wire (GlobalExceptionHandler#handleBusiness); the
            // flag-off vs no-identity distinction is pinned by ClientReviewServiceTest.
            JsonNode error = objectMapper.readTree(write.getBody());
            assertThat(error.path("success").asBoolean(true)).as("%s success", source).isFalse();
            assertThat(error.path("message").asText()).as("%s message", source).isEqualTo("Invalid request");
            assertThat(reviewCount(bookingId)).as("%s: nothing written", source).isZero();
        }
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
        return HttpStatus.valueOf(rateResponse(bookingId, token).getStatusCode().value());
    }

    private ResponseEntity<String> rateResponse(UUID bookingId, String token) throws Exception {
        return restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("bookingId", bookingId.toString(), "rating", 5)),
                        fixtures().bearerHeaders(token)),
                String.class);
    }

    private int reviewCount(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId);
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

    /** COMPLETED guest ({@code LINK}) or walk-in ({@code STAFF}) booking with a phone and no account, in the past. */
    private UUID insertCompletedGuestBooking(BookingTestFixtures.SalonFixture salon, String source) {
        UUID masterServiceId = fx.createSalonService(salon.salonId(), salon.masterId());
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, ?, 'Гість', 'Тестовий', ?, NOW(), NOW())",
                bookingId, salon.masterId(), masterServiceId, salon.salonId(), source,
                "+38050" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L)));
        return bookingId;
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }
}
