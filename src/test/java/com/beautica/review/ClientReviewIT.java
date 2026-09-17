package com.beautica.review;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 27.5 (REVERSES the previously deferred/out-of-scope status of master&rarr;client reviews)
 * — full-stack matrix for {@code POST /api/v1/client-reviews}: a provider rating the CLIENT of a
 * completed booking. Mirrors the shape of the existing {@code reviews} integration coverage
 * ({@code ReviewIntegrationTest}/{@code ReviewSecurityTest}), swapping the authority direction.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /client-reviews — provider reviews the client (Phase 27.5)")
class ClientReviewIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/client-reviews";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Phase 320 — the actor moved from the salon OWNER to the PERFORMING MASTER. Locked product
     * decision: "salon owner or salon admin can complete the booking, and after it only salon
     * master can leave the feedback". The owner now gets 403 here (pinned by
     * {@link #should_return403_when_salonOwnerReviewsClientOfTheirMastersBooking}); everything else
     * this test asserts — the 201 body, the {@code client_reviews} row, the after-commit
     * recalculation of {@code users.avg_rating}/{@code review_count} — is unchanged.
     */
    @Test
    @DisplayName("201 when the PERFORMING SALON_MASTER reviews the client of a COMPLETED booking, and "
            + "the client's users.avg_rating/review_count are recalculated after commit")
    void should_return201_and_recalculateRating_when_performingMasterReviewsClientOfCompletedBooking() throws Exception {
        Salon salon = createSalon("clirev-happy-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-happy-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":5,\"comment\":\"Чудовий клієнт\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("the master who performed this completed booking must be able to review its "
                        + "client — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(data.path("clientId").asText()).isEqualTo(clientId.toString());
        assertThat(data.path("rating").asInt()).isEqualTo(5);
        assertThat(data.path("comment").asText()).isEqualTo("Чудовий клієнт");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isEqualTo(1);

        BigDecimal avgRating = jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM users WHERE id = ?", BigDecimal.class, clientId);
        Integer reviewCount = jdbcTemplate.queryForObject(
                "SELECT review_count FROM users WHERE id = ?", Integer.class, clientId);
        assertThat(avgRating).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(reviewCount).isEqualTo(1);
    }

    @Test
    @DisplayName("400 when the booking is CONFIRMED and its endsAt has NOT yet elapsed — a "
            + "still-open booking stays unreviewable, same as an elapsed-but-unclosed one (see "
            + "the sibling test below)")
    void should_return400_when_bookingConfirmedAndNotYetElapsed() throws Exception {
        Salon salon = createSalon("clirev-notcompleted-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-notcompleted-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertFutureBooking(clientId, salon.masterId, salon.salonId, "CONFIRMED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("the PERFORMING MASTER clears the authorization gate, so this 400 is the "
                        + "eligibility rule speaking, not a 403 in disguise: reviewing the client "
                        + "of a still-open, non-elapsed CONFIRMED booking must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("400 when the booking is CONFIRMED but its endsAt has already ELAPSED — unlike the "
            + "client-side ReviewService widening, the PROVIDER direction does NOT treat an "
            + "elapsed-but-unclosed CONFIRMED booking as reviewable: the provider must actually "
            + "close the booking (PATCH .../complete) before rating the client")
    void should_return400_when_bookingConfirmedButElapsed() throws Exception {
        Salon salon = createSalon("clirev-elapsed-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-elapsed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "CONFIRMED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("an elapsed-but-unclosed CONFIRMED booking must stay unreviewable on the "
                        + "provider->client direction — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // GlobalExceptionHandler#handleBusiness genericises every BAD_REQUEST BusinessException
        // message to "Invalid request" (anti-enumeration — see its javadoc); ClientReviewService's
        // specific "This booking must be marked completed before you can review the client" string
        // never reaches the wire for this status, so the wire-level contract this IT can pin is the
        // generic message, matching the same-shape assertion in BookingMyBookingsSortIT.
        assertThat(objectMapper.readTree(resp.getBody()).path("message").asText(""))
                .as("pins the wire-level 400 message contract for this branch — body: %s", resp.getBody())
                .isEqualTo("Invalid request");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("400 when the booking is NOT_COMPLETED (no-show), even with an elapsed endsAt — "
            + "guards against over-widening the eligibility check beyond CONFIRMED")
    void should_return400_when_bookingNotCompletedNoShow() throws Exception {
        Salon salon = createSalon("clirev-noshow-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-noshow-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "NOT_COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("NOT_COMPLETED is an explicit no-show marking, never reviewable regardless of endsAt")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("409 on a duplicate client review for the same booking")
    void should_return409_when_duplicateReview() throws Exception {
        Salon salon = createSalon("clirev-dup-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-dup-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");
        String token = tokenFor(salon.masterEmail);
        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":3}";

        ResponseEntity<String> first = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(token)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(token)), String.class);

        assertThat(second.getStatusCode())
                .as("a second review for the same booking must be rejected with 409")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("400 when the booking is a guest (LINK, null-client) booking — no account to review")
    void should_return400_when_bookingIsGuestWithNoClient() throws Exception {
        Salon salon = createSalon("clirev-guest-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertGuestBooking(salon.masterId, salon.salonId);

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("reviewing a guest (null-client) booking must be rejected — no account exists to review")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("403 when a FOREIGN provider (not this booking's provider) attempts to review the client")
    void should_return403_when_foreignProviderAttemptsReview() throws Exception {
        Salon salonA = createSalon("clirev-foreign-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("clirev-foreign-b-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-foreign-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salonA.masterId, salonA.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":2}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salonB.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("a provider with no authority over this booking must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to POST /client-reviews (provider-only endpoint)")
    void should_return403_when_clientAttemptsToReviewAClient() throws Exception {
        Salon salon = createSalon("clirev-clientrole-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-clientrole-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");
        String otherClientEmail = "clirev-clientrole-other-" + System.nanoTime() + "@beautica.test";
        createUser(otherClientEmail, "CLIENT", null);

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(otherClientEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("CLIENT role must be denied POST /client-reviews with 403 — this is a provider-only endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Phase 316 — INVERTED. This test previously asserted 403 on the premise that the
     * {@code @PreAuthorize} role list "never admits" {@code SALON_MASTER}, "same as
     * decline/complete/reschedule". The role list now names {@code SALON_MASTER} and the SpEL
     * {@code @authz.canReviewClient} decides per booking. The three sibling actions named in that
     * old premise are UNCHANGED and still 403 for this role — {@code BookingCompletionSecurityIT},
     * {@code BookingProviderRescheduleIT} and {@code BookingSecurityTest} are the gates that say so,
     * and the negative case immediately below keeps the narrowness of this one honest.
     */
    @Test
    @DisplayName("201 when a SALON_MASTER reviews the client of the booking THEY performed "
            + "(phase 316 — the one provider write the read-only role holds)")
    void should_return201_when_salonMasterReviewsClientOfOwnPerformedBooking() throws Exception {
        Salon salon = createSalon("clirev-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4,\"comment\":\"Пунктуальна клієнтка\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("the salon master PERFORMED this booking — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .as("the review is actually persisted, not merely un-403'd at the gate")
                .isEqualTo(1);
    }

    /**
     * The narrowness half of phase 316 on the WRITE path, and the test that would go red if the new
     * predicate were ever folded into {@code hasProviderAuthorityOverBooking}'s salon arm: two
     * masters at ONE salon, the non-performing one attempting the write.
     */
    @Test
    @DisplayName("403 when a SALON_MASTER attempts to review the client of a COLLEAGUE's booking at "
            + "the SAME salon — phase 316 is per-booking, never per-salon")
    void should_return403_when_salonMasterReviewsColleaguesBookingsClient() throws Exception {
        Salon salon = createSalon("clirev-sm-peer-owner-" + System.nanoTime() + "@beautica.test");
        String peerEmail = "clirev-sm-peer-" + System.nanoTime() + "@beautica.test";
        createSalonMaster(salon.salonId, peerEmail);
        UUID clientId = createUser("clirev-sm-peer-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        // Booking belongs to the salon's FIRST master; the peer is the one calling.
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(peerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("a colleague did not perform this visit — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    /**
     * Phase 316 narrowness across the SALON boundary — the arm the colleague case above cannot
     * reach. Both tests end in 403, but for structurally different reasons, and only this one
     * exercises the claim that <b>salon identity never enters the performer arm at all</b>:
     *
     * <ul>
     *   <li>the colleague case ({@link #should_return403_when_salonMasterReviewsColleaguesBookingsClient})
     *       has {@code v.salonId() == actor's salon}, so it pins that a SHARED salon does not
     *       promote a non-performer;</li>
     *   <li>this case has {@code v.salonId() != actor's salon}, so it pins that {@code
     *       AuthorizationService#isPerformingMasterOfRow} reads {@code masterUserId} and NOTHING
     *       else. A refactor that "helpfully" widened the performer arm to
     *       {@code masterUserId.equals(actor) || salonId.equals(actorSalonId)} — the shape a reader
     *       reaches for when asked to let a salon's masters review "their salon's" clients — leaves
     *       the colleague case red but would ALSO have left it red before phase 316; only a
     *       cross-salon fixture separates "the grant is per-booking" from "the grant is per-salon
     *       and this actor's salon happens to match".</li>
     * </ul>
     *
     * <p>Note the actor is a {@code SALON_MASTER} at a REAL, different salon rather than a
     * salon-less one: an actor with {@code users.salon_id = NULL} would be denied by any
     * salon-comparing mutant too (null never equals a salon id), and the test would go green
     * against the very widening it exists to catch.
     */
    @Test
    @DisplayName("403 when a SALON_MASTER attempts to review the client of ANOTHER SALON's booking "
            + "— the performer arm reads masters.user_id and never a salon id")
    void should_return403_when_salonMasterReviewsAnotherSalonsBookingsClient() throws Exception {
        Salon performingSalon = createSalon("clirev-sm-xsalon-a-owner-" + System.nanoTime() + "@beautica.test");
        Salon foreignSalon = createSalon("clirev-sm-xsalon-b-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-sm-xsalon-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        // The booking is performed by salon A's master, at salon A. The caller is salon B's master.
        UUID bookingId = insertBooking(clientId, performingSalon.masterId, performingSalon.salonId, "COMPLETED");
        assertThat(foreignSalon.salonId)
                .as("premise — the two salons really are different, or this degenerates into the "
                        + "colleague case")
                .isNotEqualTo(performingSalon.salonId);

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(tokenFor(foreignSalon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("a master at a DIFFERENT salon performed nothing here — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .as("a denied write must leave no row behind")
                .isZero();
    }

    /**
     * <b>The phase-316 security MEDIUM, now fixed and pinned.</b> Was {@code @Disabled} and red
     * (201 CREATED) for exactly one commit; the {@code masters.is_active} conjunct on
     * {@code AuthorizationService#isPerformingMasterOfRow} turned it green.
     *
     * <p>The finding, kept because it is the reason this test exists.
     * {@code DELETE /masters/&#123;masterId&#125;}
     * ({@code MasterService#deactivateMaster}) flips {@code masters.is_active = false} and NOTHING
     * else: the {@code users} row keeps {@code is_active = true} (so the account still logs in),
     * keeps {@code role = SALON_MASTER} (so {@code @PreAuthorize}'s role list still admits it) and
     * {@code masters.user_id} is untouched (so
     * {@code AuthorizationService#isPerformingMasterOfRow} still matches). A master the salon has
     * removed therefore RETAINS the phase-316 write on every booking they ever performed, for as
     * long as their account exists.
     *
     * <p>Contrast {@code MasterDetachmentContractIT} case 17, which was green even before the fix:
     * a DETACHED master ({@code user_id IS NULL}) already failed closed, because that path nulls
     * the very column this one leaves in place.
     *
     * <p><b>The fix is NOT "deny any inactive master" in
     * {@code hasProviderAuthorityOverBooking}.</b> That kernel also gates
     * complete/decline/reschedule for OWNERS and admins, and an owner's salon-arm authority has
     * nothing to do with a {@code masters} row's activity — it stays byte-unchanged. The conjunct
     * lives only on the phase-316 predicate and its {@link
     * com.beautica.booking.repository.BookingReviewAccess} projection twin. This test and
     * {@link #should_return201_when_salonMasterReviewsClientOfOwnPerformedBooking} are the pair
     * that keeps it that shape: the grant is revoked by DEACTIVATION, not by being a salon master.
     */
    @Test
    @DisplayName("403 when a SALON_MASTER who has been DEACTIVATED (DELETE /masters/{id}) reviews "
            + "the client of a booking they performed while still active")
    void should_return403_when_deactivatedSalonMasterReviewsClientOfOwnPastBooking() throws Exception {
        Salon salon = createSalon("clirev-sm-deact-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-sm-deact-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");
        // The master's token is minted BEFORE the deactivation, which is the realistic shape: a
        // still-valid access token in a phone that was not logged out. Re-logging in after the
        // flip would test the login gate instead of the authorization gate.
        String masterToken = tokenFor(salon.masterEmail);

        ResponseEntity<String> deactivation = restTemplate.exchange(
                "/api/v1/masters/" + salon.masterId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail))), String.class);
        assertThat(deactivation.getStatusCode())
                .as("premise — the owner's removal of the master must succeed, body=%s",
                        deactivation.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = ?", Boolean.class, salon.masterId))
                .as("premise — the masters row really is deactivated")
                .isFalse();

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(masterToken)), String.class);

        assertThat(resp.getStatusCode())
                .as("a master the salon has removed must not keep writing about its clients — "
                        + "body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .as("and leaves no row behind")
                .isZero();
    }

    /**
     * Phase 316 D1 (locked) — FIRST WRITER WINS, and the loser is a 409, never a second row. The
     * {@code client_reviews.booking_id} uniqueness is unchanged and there are no per-author
     * reviews.
     *
     * <p><b>Phase 320 rewrote the FIRST writer.</b> This used to have the salon OWNER create review
     * #1 and the performing master lose the race. The owner can no longer create it at all (403 —
     * {@link #should_return403_when_salonOwnerReviewsClientOfTheirMastersBooking}), so that
     * precondition is unreachable. The rule it pins is not about WHO wrote first, only that the
     * booking already carries a review, so the same master now posts twice: attempt #1 is the
     * 201 that establishes the row, attempt #2 is the 409. That is also the shape the mobile app
     * actually produces — a double-tap on the CTA.
     */
    @Test
    @DisplayName("409 when the performing SALON_MASTER posts a second client review for a booking "
            + "that already has one — one review per booking, first writer wins (phase 316 D1)")
    void should_return409_when_performingMasterReviewsTheSameBookingTwice() throws Exception {
        Salon salon = createSalon("clirev-sm-race-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-sm-race-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");
        String masterToken = tokenFor(salon.masterEmail);

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":5}";
        ResponseEntity<String> first = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(masterToken)), String.class);
        assertThat(first.getStatusCode())
                .as("premise — the performing master writes FIRST; body=%s", first.getBody())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(masterToken)), String.class);

        assertThat(second.getStatusCode())
                .as("D1 — one review per booking, first writer wins; body=%s", second.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .as("booking_id uniqueness is untouched — exactly one row, ever")
                .isEqualTo(1);
    }

    /**
     * Phase 320, the NEW gate. The locked product decision splits closing from reviewing: this
     * owner may (and in production does) {@code PATCH .../complete} the booking, and is then
     * refused its client review. Asserted through the write endpoint because that is where the
     * decision is enforced; the read-side mirror is
     * {@code ProviderCanReviewClientIT#should_returnFalse_when_salonOwnerViewsBookingPerformedByTheirMaster}.
     */
    @Test
    @DisplayName("403 when the SALON_OWNER tries to review the client of a booking one of their "
            + "MASTERS performed — completing is theirs, reviewing is the performing master's (320)")
    void should_return403_when_salonOwnerReviewsClientOfTheirMastersBooking() throws Exception {
        Salon salon = createSalon("clirev-320-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-320-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":5}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("the owner is not this booking's masters.user_id — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .as("no row may be written by a denied actor")
                .isZero();
    }

    /**
     * Phase 320 — the SALON_ADMIN twin. The admin is rejected TWICE over now: the controller's
     * {@code hasAnyRole(...)} no longer names {@code SALON_ADMIN}, and {@code @authz.canReviewClient}
     * would deny them anyway because {@code MasterType} has no admin member, so no
     * {@code masters.user_id} can ever be an admin. Pinned so that re-adding the role to the
     * controller cannot silently re-grant the write.
     */
    @Test
    @DisplayName("403 when an ASSIGNED SALON_ADMIN tries to review the client of a booking at the "
            + "salon they administer (phase 320)")
    void should_return403_when_assignedSalonAdminReviewsClientOfSalonBooking() throws Exception {
        Salon salon = createSalon("clirev-320-admin-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "clirev-320-admin-" + System.nanoTime() + "@beautica.test";
        createUser(adminEmail, "SALON_ADMIN", salon.salonId);
        UUID clientId = createUser("clirev-320-admin-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":5}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(adminEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("an assigned admin may complete this booking but never review its client — "
                        + "body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("400 when rating is out of range (0)")
    void should_return400_when_ratingOutOfRange() throws Exception {
        Salon salon = createSalon("clirev-badrating-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-badrating-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":0}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Multi-service visit — master→client cardinality (locked rule: the master
    //  leaves ONE client-rating PER CHILD BOOKING, never one per visit, mirroring
    //  the client→provider 1-booking-1-feedback rule pinned in AppointmentReviewIT).
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a 3-service visit yields THREE independent client reviews — the master rating all "
            + "three children returns three 201s, three distinct client_reviews rows keyed on "
            + "distinct booking_id, and the client's aggregate (users.avg_rating/review_count) "
            + "reflects all three samples")
    void should_allowOneClientReviewPerChildBooking_when_theVisitHasThreeServices() throws Exception {
        BookingTestFixtures fixtures =
                new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("clirev-visit3", 3);
        completeVisit(visit);
        List<UUID> children = childIdsOf(visit.id());
        assertThat(children).hasSize(3);
        int[] ratings = {5, 3, 4};

        for (int i = 0; i < children.size(); i++) {
            String body = "{\"bookingId\":\"" + children.get(i) + "\",\"rating\":" + ratings[i] + "}";
            ResponseEntity<String> resp = restTemplate.exchange(
                    URL, HttpMethod.POST,
                    new HttpEntity<>(body, bearerHeaders(visit.masterToken())), String.class);
            assertThat(resp.getStatusCode())
                    .as("reviewing child %s must succeed — body: %s", children.get(i), resp.getBody())
                    .isEqualTo(HttpStatus.CREATED);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT booking_id FROM client_reviews WHERE booking_id = ANY(?)",
                (Object) children.toArray(new UUID[0]));
        assertThat(rows).as("exactly one client review row per child booking").hasSize(3);
        assertThat(rows.stream().map(r -> r.get("booking_id")).distinct().count())
                .as("all three client reviews are keyed on distinct booking_id").isEqualTo(3L);

        BigDecimal avgRating = jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM users WHERE id = ?", BigDecimal.class, visit.clientId());
        Integer reviewCount = jdbcTemplate.queryForObject(
                "SELECT review_count FROM users WHERE id = ?", Integer.class, visit.clientId());
        assertThat(reviewCount)
                .as("review_count must reflect all three per-booking client reviews").isEqualTo(3);
        assertThat(avgRating)
                .as("avg_rating must be the mean of 5, 3, 4").isEqualByComparingTo(new BigDecimal("4"));
    }

    /** Ordered child booking ids of a visit — mirrors {@code AppointmentReviewIT#childIdsOf}. */
    private List<UUID> childIdsOf(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);
    }

    /**
     * Provider completes the whole visit (no elapse guard on complete) so its children become
     * reviewable — mirrors {@code AppointmentReviewIT#completeVisit(VisitFixture)}.
     */
    private void completeVisit(BookingTestFixtures.VisitFixture visit) {
        ResponseEntity<String> completed = restTemplate.exchange(
                "/api/v1/appointments/" + visit.id() + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(visit.masterToken())), String.class);
        assertThat(completed.getStatusCode())
                .as("visit completion must succeed — body: %s", completed.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ── seeding fixtures (local by house convention — mirrors BookingCompletionSecurityIT) ───────

    private record Salon(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        String masterEmail = "clirev-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId, masterEmail);
    }

    /** A SECOND {@code SALON_MASTER} at an existing salon — phase 316's per-booking narrowness tests. */
    private UUID createSalonMaster(UUID salonId, String email) {
        UUID userId = createUser(email, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID createSalonService(UUID salonId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID salonId, String status) {
        UUID masterServiceId = createSalonService(salonId, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }

    /** Same shape as {@link #insertBooking}, but with a FUTURE starts_at/ends_at — for the
     * still-open, not-yet-elapsed CONFIRMED case that must stay unreviewable. */
    private UUID insertFutureBooking(UUID clientId, UUID masterId, UUID salonId, String status) {
        UUID masterServiceId = createSalonService(salonId, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() + interval '1 hour', NOW() + interval '2 hours', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }

    /** Guest (LINK) booking, COMPLETED, client_id NULL — mirrors GuestBookingLifecycleContractIT's shape. */
    private UUID insertGuestBooking(UUID masterId, UUID salonId) {
        UUID masterServiceId = createSalonService(salonId, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', '+380509998877', NOW(), NOW())",
                bookingId, masterId, masterServiceId, salonId);
        return bookingId;
    }
}
