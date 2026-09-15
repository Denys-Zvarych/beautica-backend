package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.service.BookingService;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.review.repository.ReviewRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 317 — {@code GET /bookings/{id}}'s {@code reviewByClient} field: the review THIS booking's
 * client left about the master, rating plus the full comment, readable by the provider.
 *
 * <p><b>D2 (locked) — the full comment ships, never rating-only.</b> The same review text is
 * already world-readable through the {@code permitAll} {@code GET /masters/&#123;id&#125;/reviews}
 * listing; what that listing cannot do is tell you WHICH booking a review belongs to, because it
 * carries no {@code bookingId} (deliberately — Anti-Bug &sect;I-2 keeps internal ids out of
 * {@code permitAll} responses). This field is that missing join, served only to a caller already
 * past {@code enforceCanViewBooking}. The assertions below therefore pin the comment's EXACT text,
 * not merely its presence: a defensive truncation or mask would be a silent product regression.
 *
 * <p>Fixtures are seeded through raw SQL and the review is written through the real
 * {@code POST /reviews} endpoint, so the row under test is exactly the one
 * {@code ReviewService#createReview} produces — never a hand-built {@code reviews} row that could
 * disagree with the writer about nullability or rating width.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/{id} — reviewByClient (Phase 317)")
class BookingClientReviewExposureIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String REVIEWS_URL = "/api/v1/reviews";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String REVIEW_COMMENT =
            "Дуже уважна майстриня, все сподобалось — прийду ще!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private EntityManagerFactory emf;

    /**
     * A SPY, never a mock — every call still hits the real repository, so the payload tests above
     * are unaffected and the statement counts below are the production ones. It exists solely so the
     * phase-317 "swap, not addition" claim can be asserted by METHOD rather than inferred from a
     * total: {@code findViewByBookingId} once, {@code existsByBookingId} never, on the detail path.
     */
    @SpyBean
    private ReviewRepository reviewRepository;

    @Test
    @DisplayName("the SALON_OWNER reads the client's rating AND the full comment on the booking")
    void should_exposeRatingAndFullComment_when_ownerReadsAReviewedBooking() throws Exception {
        Fixture fx = seedCompletedBooking("bcre-owner-");
        leaveClientReview(fx, 5, REVIEW_COMMENT);

        JsonNode review = getBookingDetail(fx.bookingId(), tokenFor(fx.ownerEmail())).path("reviewByClient");

        assertThat(review.isMissingNode() || review.isNull())
                .as("the field must be populated on the detail path for a reviewed booking")
                .isFalse();
        assertThat(review.path("rating").asInt()).isEqualTo(5);
        assertThat(review.path("comment").asText())
                .as("D2 — the FULL comment, byte-for-byte. Truncating or masking it would be "
                        + "theatre: the same text is already public on GET /masters/{id}/reviews.")
                .isEqualTo(REVIEW_COMMENT);
    }

    /**
     * The phase-316 audience is the phase-317 reader: a salon master who may now leave feedback
     * about the client wants to read what that client said about them first. Pinned so the two
     * phases cannot drift apart — a future narrowing of {@code reviewByClient} to owner/admin would
     * silently blank this screen for the exact role the sibling phase just enabled.
     */
    @Test
    @DisplayName("the performing SALON_MASTER reads the same review on the same booking")
    void should_exposeTheReview_when_thePerformingSalonMasterReadsTheBooking() throws Exception {
        Fixture fx = seedCompletedBooking("bcre-master-");
        leaveClientReview(fx, 4, REVIEW_COMMENT);

        JsonNode review = getBookingDetail(fx.bookingId(), tokenFor(fx.masterEmail())).path("reviewByClient");

        assertThat(review.path("rating").asInt()).isEqualTo(4);
        assertThat(review.path("comment").asText()).isEqualTo(REVIEW_COMMENT);
    }

    @Test
    @DisplayName("null when the booking carries no review at all")
    void should_returnNull_when_theBookingHasNoReview() throws Exception {
        Fixture fx = seedCompletedBooking("bcre-none-");

        JsonNode detail = getBookingDetail(fx.bookingId(), tokenFor(fx.ownerEmail()));

        assertThat(detail.path("reviewByClient").isNull())
                .as("absent review must serialise as an explicit null, never an empty object")
                .isTrue();
        assertThat(detail.path("canReview").asBoolean())
                .as("premise — the booking really is reviewable-but-unreviewed, so the null above "
                        + "is 'no review yet' and not 'the fetch was skipped'")
                .isTrue();
    }

    /**
     * A rating may be left with no text ({@code reviews.comment} is nullable). The nested record
     * must then carry a non-null rating and a null comment — never collapse to a null
     * {@code reviewByClient}, which would make "rated 2 stars, said nothing" indistinguishable from
     * "never reviewed" on the provider's screen.
     */
    @Test
    @DisplayName("a comment-less rating still populates the field, with a null comment")
    void should_exposeRatingWithNullComment_when_theClientRatedWithoutWriting() throws Exception {
        Fixture fx = seedCompletedBooking("bcre-nocomment-");
        leaveClientReview(fx, 2, null);

        JsonNode review = getBookingDetail(fx.bookingId(), tokenFor(fx.ownerEmail())).path("reviewByClient");

        assertThat(review.path("rating").asInt()).isEqualTo(2);
        assertThat(review.path("comment").isNull()).isTrue();
    }

    /**
     * The documented surface contract, pinned rather than left to prose: {@code reviewByClient} is
     * served ONLY by {@code GET /bookings/{id}}. A future change that populates it on one listing
     * surface but not the others would make a null mean two different things depending on which
     * list the row came from — see the field's own {@code @Schema}.
     */
    @Test
    @DisplayName("the GET /bookings/me listing row sends null for the SAME reviewed booking — the "
            + "field is detail-only by contract")
    void should_returnNullOnListRow_when_theSameBookingIsReviewed() throws Exception {
        Fixture fx = seedCompletedBooking("bcre-list-");
        leaveClientReview(fx, 5, REVIEW_COMMENT);
        String ownerToken = tokenFor(fx.ownerEmail());

        JsonNode detail = getBookingDetail(fx.bookingId(), ownerToken);
        JsonNode row = listRow(fx.bookingId(), ownerToken);

        assertThat(detail.path("reviewByClient").path("rating").asInt())
                .as("premise — the detail surface DOES serve it, so the null below is the contract "
                        + "and not an unreviewed fixture")
                .isEqualTo(5);
        assertThat(row.path("reviewByClient").isNull()).isTrue();
    }

    // ── statement-cost gate ──────────────────────────────────────────────────────
    //
    // Phase 317's whole cost argument is "findViewByBookingId REPLACES existsByBookingId on the
    // detail path, so the statement count is byte-identical". Until this gate existed that claim was
    // true by READING and pinned by nothing that can go red: the two existing detail gates
    // (BookingPriceRangeContractIT#OWNER_DETAIL_STATEMENTS_ALIGNED / _ROTATED, :1781 and :1784) both
    // drive a FUTURE-DATED CONFIRMED booking, which is the one branch where BookingService#getBooking's
    // `hasClient && eligibleByStatusAndTime` gate is CLOSED and neither the old probe nor the new
    // fetch ever fires. An "addition" regression — restoring existsByBookingId alongside the new
    // fetch, the single most likely way this unravels during a later merge — leaves every one of
    // those gates at its pinned number.

    /**
     * Statements for a detail read whose review gate is OPEN — COMPLETED, real client, elapsed.
     * <b>DERIVED FROM A RUN, never predicted</b> (first measured 2026-09-15: gate-closed 2,
     * gate-open 4).
     *
     * <p>Reconciliation, and the reason the delta asserted below is TWO rather than the one phase
     * 317's javadoc might lead a reader to expect. {@code hasClient && eligibleByStatusAndTime}
     * opens <b>two</b> independent lookups at once, on the same fixture, and no fixture can separate
     * them — {@code BookingClosureRule#isReviewEligible} and {@code #isProviderReviewEligible} are
     * both false for every shape that closes one of them:
     * <ol>
     *   <li>{@code ReviewRepository#findViewByBookingId} — phase 317's own read, the one that
     *       REPLACED {@code existsByBookingId};</li>
     *   <li>{@code ClientReviewRepository#existsByBookingId} — the phase-27.5 probe behind
     *       {@code providerCanReviewClient}. A different TABLE ({@code client_reviews}), a different
     *       direction, untouched by this phase, and present on this branch since long before it.</li>
     * </ol>
     * So the absolute and the delta below are <b>containment</b> gates: they catch any third
     * statement appearing on the open branch. What pins phase 317's actual claim — one {@code
     * reviews} read, not two — is the {@code @SpyBean} verification inside the test, which names the
     * repository methods directly and is immune to an unrelated statement drifting into either
     * count.
     *
     * <p>The closed-gate side is deliberately NOT a constant: it is measured in-test and used only
     * as the baseline. It reads 2 here, not the 3 of
     * {@code BookingPriceRangeContractIT#OWNER_DETAIL_STATEMENTS_ALIGNED} (:1781), and that is not a
     * contradiction — this class's fixture stamps a {@code city_id} but no district, so
     * {@code DiscoveryLocationResolver} issues one label query where the other class's
     * {@code stampSalonLocality} fixture issues two. Re-deriving it means a legitimate change to the
     * base detail graph moves both sides together and this gate stays about the review branch alone.
     */
    private static final long OWNER_DETAIL_STATEMENTS_REVIEW_GATE_OPEN = 4L;

    /**
     * <b>Mutation-verified (QA, 2026-09-15).</b> Restoring the pre-317 shape —
     * {@code reviewRepository.existsByBookingId(bookingId)} back inside the {@code canReview}
     * conjunction ALONGSIDE the new {@code findViewByBookingId}, which is how this most plausibly
     * unravels in a later merge — moves the open-gate measurement 4 &rarr; <b>5</b> and the delta
     * 2 &rarr; <b>3</b>, and trips the {@code never()} verification, while
     * {@code BookingPriceRangeContractIT}'s two detail gates (both on the CLOSED branch) and every
     * payload assertion in this class stay green. That split is what this gate exists for.
     *
     * <p>The reviewed and unreviewed COMPLETED readings are taken separately and asserted EQUAL on
     * purpose: {@code findViewByBookingId} returning an empty {@link java.util.Optional} must cost
     * the same single statement as returning a row. An "optimisation" that kept
     * {@code existsByBookingId} as a cheap pre-check and only fetched the view when it said yes
     * would leave the unreviewed reading at 4 and push the reviewed one to 5 — caught here, and
     * nowhere else.
     */
    @Test
    @DisplayName("GET /bookings/{id} for a COMPLETED + REVIEWED booking issues exactly ONE reviews "
            + "read and NO existsByBookingId — a swap, not an addition (phase 317)")
    void should_costExactlyOneReviewStatement_when_theDetailReadsAReviewedBooking() throws Exception {
        Fixture openReviewed = seedBooking("bcre-stmt-reviewed-", BookingShape.COMPLETED_ELAPSED);
        leaveClientReview(openReviewed, 5, REVIEW_COMMENT);
        Fixture openUnreviewed = seedBooking("bcre-stmt-unreviewed-", BookingShape.COMPLETED_ELAPSED);
        Fixture closedGate = seedBooking("bcre-stmt-future-", BookingShape.CONFIRMED_FUTURE);

        Statistics statistics = statistics();

        statistics.clear();
        var closedDetail = bookingService.getBooking(closedGate.ownerUserId(), closedGate.bookingId());
        long statementsGateClosed = statistics.getPrepareStatementCount();

        // leaveClientReview() above drove POST /reviews, whose own duplicate-write gate legitimately
        // calls existsByBookingId. Clear before measuring, or the never() below would indict the
        // WRITE path for something the READ path did not do.
        clearInvocations(reviewRepository);
        statistics.clear();
        var reviewedDetail = bookingService.getBooking(openReviewed.ownerUserId(), openReviewed.bookingId());
        long statementsReviewed = statistics.getPrepareStatementCount();

        // THE phase-317 assertion, and the only one here immune to an unrelated statement drifting
        // into the counts: one reviews read, and the probe it replaced is genuinely gone from this
        // path rather than merely joined by a cheaper sibling.
        verify(reviewRepository, times(1)).findViewByBookingId(openReviewed.bookingId());
        verify(reviewRepository, never()).existsByBookingId(any());

        statistics.clear();
        bookingService.getBooking(openUnreviewed.ownerUserId(), openUnreviewed.bookingId());
        long statementsUnreviewed = statistics.getPrepareStatementCount();

        // Premises — a statement count means nothing until the branch under the needle is pinned.
        // Without these, two fixtures that both closed the gate would read "delta 0" and this gate
        // would pass while measuring nothing at all.
        assertThat(closedDetail.reviewByClient())
                .as("premise — the closed-gate fixture must genuinely take the branch that fetches "
                        + "NOTHING; a populated field here means it is on the open branch too")
                .isNull();
        assertThat(reviewedDetail.reviewByClient())
                .as("premise — the open-gate fixture must genuinely carry the review, or the extra "
                        + "statements below belong to something else")
                .isNotNull();

        assertThat(statementsReviewed)
                .as("absolute JDBC statement count for a COMPLETED+reviewed owner detail read. "
                        + "gate-closed=%s, reviewed=%s, unreviewed=%s.",
                        statementsGateClosed, statementsReviewed, statementsUnreviewed)
                .isEqualTo(OWNER_DETAIL_STATEMENTS_REVIEW_GATE_OPEN);
        assertThat(statementsReviewed - statementsGateClosed)
                .as("containment — opening the review gate buys exactly TWO statements and no "
                        + "third: phase 317's reviews read plus the pre-existing phase-27.5 "
                        + "client_reviews probe. See the constant's javadoc for why these two "
                        + "cannot be separated by any fixture (gate-closed=%s, reviewed=%s).",
                        statementsGateClosed, statementsReviewed)
                .isEqualTo(2L);
        assertThat(statementsUnreviewed)
                .as("an ABSENT review must cost the same single statement as a present one — a "
                        + "cheap-exists-then-fetch shape would split these two (reviewed=%s, "
                        + "unreviewed=%s)", statementsReviewed, statementsUnreviewed)
                .isEqualTo(statementsReviewed);
    }

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private JsonNode getBookingDetail(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/{id} must succeed for this fixture, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).get("data");
    }

    private JsonNode listRow(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?size=50", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/me must succeed for this fixture, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        for (JsonNode row : objectMapper.readTree(resp.getBody()).path("data").path("data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("booking " + bookingId + " did not appear on GET /bookings/me");
    }

    /**
     * Writes the review through the REAL client endpoint, never a hand-built {@code reviews} row.
     *
     * <p>Serialised by {@code ObjectMapper}, not concatenated. This class asserts a comment
     * BYTE-FOR-BYTE, so a fixture string carrying a quote, a backslash or a control character would
     * otherwise corrupt the request body and the test would fail somewhere far from the cause — or,
     * worse, pass against a silently mangled round trip. The null branch OMITS the field rather than
     * sending an explicit {@code null}, which is what a client rating without writing actually does.
     */
    private void leaveClientReview(Fixture fx, int rating, String comment) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", fx.bookingId().toString());
        payload.put("rating", rating);
        if (comment != null) {
            payload.put("comment", comment);
        }
        String body = objectMapper.writeValueAsString(payload);
        ResponseEntity<String> resp = restTemplate.exchange(
                REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.clientEmail()))), String.class);
        assertThat(resp.getStatusCode())
                .as("premise — the client's review must actually be created, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

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

    // ── seeding fixtures (local by house convention — mirrors ProviderCanReviewClientIT) ─────────

    private record Fixture(
            UUID salonId, String ownerEmail, UUID ownerUserId, UUID masterId, String masterEmail,
            String clientEmail, UUID bookingId) {}

    /**
     * The two booking shapes the statement gate has to tell apart, and the ONLY thing that differs
     * between them — {@code BookingService#getBooking}'s review fetch is gated on {@code hasClient
     * && BookingClosureRule#isReviewEligible(status, endsAt, now)}, so status-plus-times is the
     * whole of the discriminator. Everything else about the two fixtures is byte-identical by
     * construction, which is what makes the measured difference attributable.
     */
    private enum BookingShape {
        /** Gate OPEN — elapsed and closed out, so the review fetch fires. */
        COMPLETED_ELAPSED("COMPLETED", "NOW() - interval '2 hours'", "NOW() - interval '1 hour'"),
        /** Gate CLOSED — the shape the two existing detail gates measure; no review statement. */
        CONFIRMED_FUTURE("CONFIRMED", "NOW() + interval '1 hour'", "NOW() + interval '2 hours'");

        private final String status;
        private final String startsAt;
        private final String endsAt;

        BookingShape(String status, String startsAt, String endsAt) {
            this.status = status;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
        }
    }

    private Fixture seedCompletedBooking(String prefix) {
        return seedBooking(prefix, BookingShape.COMPLETED_ELAPSED);
    }

    private Fixture seedBooking(String prefix, BookingShape shape) {
        String ownerEmail = prefix + "owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        String masterEmail = prefix + "master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        String clientEmail = prefix + "client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);

        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, '" + shape.status + "', " + shape.startsAt + ", "
                        + shape.endsAt + ", 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);

        return new Fixture(salonId, ownerEmail, ownerId, masterId, masterEmail, clientEmail, bookingId);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
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
}
