package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extends track 27.x / Phase 27.5 — {@code GET /bookings/{id}}'s viewer-aware
 * {@code providerCanReviewClient} field, which lets the mobile app pre-gate the "Залишити відгук
 * про клієнта" CTA instead of always showing it and relying on a 409 from
 * {@code POST /client-reviews}.
 *
 * <p>The predicate under test, {@code BookingService#computeProviderCanReviewClient}, deliberately
 * reuses {@code AuthorizationService#hasProviderAuthorityOverBooking} — the exact same predicate
 * {@code ClientReviewService.create} enforces before persisting — so this suite's authority matrix
 * mirrors {@link com.beautica.review.ClientReviewIT}'s 403/400 matrix, just read through
 * {@code GET /bookings/{id}} instead of driving a write.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/{id} — providerCanReviewClient (extends Phase 27.5)")
class ProviderCanReviewClientIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("true — INDEPENDENT_MASTER views their own COMPLETED, non-guest, not-yet-reviewed booking")
    void should_returnTrue_when_independentMasterViewsOwnCompletedUnreviewedBooking() throws Exception {
        String masterEmail = "pcrc-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID clientId = createUser("pcrc-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, masterId, masterServiceId, null, "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(masterEmail));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("an independent master viewing their own completed, unreviewed booking must "
                        + "be offered the client-review CTA")
                .isTrue();
    }

    @Test
    @DisplayName("true — SALON_OWNER views a COMPLETED booking for their salon (real client, not yet reviewed)")
    void should_returnTrue_when_salonOwnerViewsOwnSalonsCompletedUnreviewedBooking() throws Exception {
        Salon salon = createSalon("pcrc-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("the owning SALON_OWNER must be offered the client-review CTA for a completed, "
                        + "unreviewed booking at their own salon")
                .isTrue();
    }

    /**
     * NOT the "true" case the track-27 spec originally asked for. Investigation found that
     * {@code GET /bookings/{id}} is gated by {@code AuthorizationService#enforceCanViewBooking}
     * — a SEPARATE, pre-existing, and deliberately owner-only predicate (see
     * {@code isAuthorizedToManageBooking}'s javadoc: "Do NOT broaden this predicate... the
     * divergence [from the provider-action predicate, which DOES admit SALON_ADMIN] is
     * intentional", locked at Phase 24.2) that NEVER admits {@code SALON_ADMIN} — assigned or
     * not. So an assigned admin 403s on the view gate before {@code providerCanReviewClient} is
     * ever computed, even though {@code hasProviderAuthorityOverBooking} (the predicate this
     * field reuses, and the one {@code POST /client-reviews} enforces) WOULD admit them. This
     * pins that real, pre-existing behaviour rather than asserting a 200+true outcome the system
     * cannot produce — flagged to the requester rather than silently widening the view gate,
     * which is a separate authorization-scope decision this task did not ask for.
     */
    @Test
    @DisplayName("403 (NOT the DTO) — an assigned SALON_ADMIN cannot reach GET /bookings/{id} at "
            + "all; the pre-existing owner-only view gate excludes SALON_ADMIN regardless of "
            + "assignment, so providerCanReviewClient is unreachable for this role via this endpoint")
    void should_return403_when_assignedSalonAdminViewsBookingDetail() throws Exception {
        Salon salon = createSalon("pcrc-admin-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "pcrc-admin-" + System.nanoTime() + "@beautica.test";
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID clientId = createUser("pcrc-admin-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenFor(adminEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("enforceCanViewBooking excludes SALON_ADMIN unconditionally — even an admin "
                        + "assigned to this exact salon — so this 403s before providerCanReviewClient "
                        + "is ever computed; body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("false — after the provider has already left a client review for this booking")
    void should_returnFalse_when_providerAlreadyReviewedTheClient() throws Exception {
        Salon salon = createSalon("pcrc-dup-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-dup-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");
        String ownerToken = tokenFor(salon.ownerEmail());

        ResponseEntity<String> reviewResp = restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + bookingId + "\",\"rating\":5}", bearerHeaders(ownerToken)),
                String.class);
        assertThat(reviewResp.getStatusCode())
                .as("fixture sanity — the review must actually be persisted, body=%s", reviewResp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode detail = getBookingDetail(bookingId, ownerToken);

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("a booking that already has a client review must never re-offer the CTA")
                .isFalse();
    }

    @Test
    @DisplayName("false — the booking is CONFIRMED and its endsAt has NOT yet elapsed (not COMPLETED, not awaiting closure)")
    void should_returnFalse_when_bookingIsConfirmedAndNotYetElapsed() throws Exception {
        Salon salon = createSalon("pcrc-confirmed-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-confirmed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertFutureBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "CONFIRMED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("a still-open, non-elapsed CONFIRMED booking can never be reviewed yet")
                .isFalse();
    }

    @Test
    @DisplayName("false — the booking is CONFIRMED and its endsAt has already ELAPSED, but the "
            + "provider never marked it COMPLETED — unlike the client-side canReview widening, the "
            + "provider direction does NOT extend to an elapsed-but-unclosed CONFIRMED booking")
    void should_returnFalse_when_bookingIsConfirmedButElapsed() throws Exception {
        Salon salon = createSalon("pcrc-elapsed-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-elapsed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "CONFIRMED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("an elapsed-but-unclosed CONFIRMED booking must NOT offer the provider-review "
                        + "CTA — the provider controls their own closing action (PATCH "
                        + ".../complete), so a rating must follow it, never substitute for it")
                .isFalse();
    }

    @Test
    @DisplayName("false — the booking is NOT_COMPLETED (no-show), even with an elapsed endsAt — "
            + "guards against over-widening the eligibility check beyond CONFIRMED")
    void should_returnFalse_when_bookingIsNotCompletedNoShow() throws Exception {
        Salon salon = createSalon("pcrc-noshow-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-noshow-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "NOT_COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("NOT_COMPLETED is an explicit no-show marking, never reviewable regardless of endsAt")
                .isFalse();
    }

    @Test
    @DisplayName("false — guest (LINK, null-client) COMPLETED booking has no account to review")
    void should_returnFalse_when_bookingIsGuestWithNoClient() throws Exception {
        Salon salon = createSalon("pcrc-guest-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createSalonService(salon.salonId(), salon.masterId());
        UUID bookingId = insertGuestBooking(salon.masterId(), masterServiceId, salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("a guest booking has no registered client account — never reviewable")
                .isFalse();
    }

    @Test
    @DisplayName("false — the CLIENT viewer of the same booking (and their own canReview is unaffected)")
    void should_returnFalseForClientViewer_whileCanReviewStaysUnaffected() throws Exception {
        Salon salon = createSalon("pcrc-client-owner-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "pcrc-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(clientEmail));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("CLIENT role must never be offered the provider-side client-review CTA")
                .isFalse();
        assertThat(detail.path("canReview").asBoolean())
                .as("the pre-existing CLIENT-side canReview field must be unaffected by this "
                        + "change — the client can still review the completed, unreviewed booking")
                .isTrue();
    }

    @Test
    @DisplayName("false — SALON_MASTER (read-only role) viewing their own salon's booking")
    void should_returnFalse_when_salonMasterViews() throws Exception {
        Salon salon = createSalon("pcrc-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.masterEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("SALON_MASTER is a read-only role and never admitted to provider-only actions "
                        + "like decline/complete/reschedule/review — same exclusion here")
                .isFalse();
    }

    /**
     * Also NOT the "false via the DTO" case the spec originally asked for — see
     * {@link #should_return403_when_assignedSalonAdminViewsBookingDetail}'s javadoc. Since
     * {@code enforceCanViewBooking} rejects {@code SALON_ADMIN} unconditionally by role, a
     * FOREIGN admin gets the exact same 403 an ASSIGNED admin gets, for the exact same
     * structural reason (never reaching {@code hasProviderAuthorityOverBooking} at all) — not a
     * distinguishable "wrong salon" 403 from the review-authority predicate. Kept as its own test
     * to pin that this really is a role-level exclusion, not a salon-scoped one: if
     * {@code enforceCanViewBooking} were ever widened to admit SOME admins, a foreign one must
     * still be denied by {@code hasProviderAuthorityOverBooking} — this test's fixture (two
     * salons, admin assigned to the wrong one) is what would catch a regression there.
     */
    @Test
    @DisplayName("403 (NOT the DTO) — a foreign SALON_ADMIN assigned to a DIFFERENT salon is also "
            + "denied at the view gate, for the same role-level reason as an assigned admin")
    void should_return403_when_foreignSalonAdminViewsBookingDetail() throws Exception {
        Salon salonA = createSalon("pcrc-foreign-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("pcrc-foreign-b-" + System.nanoTime() + "@beautica.test");
        String foreignAdminEmail = "pcrc-foreign-admin-" + System.nanoTime() + "@beautica.test";
        createUser(foreignAdminEmail, "SALON_ADMIN", salonB.salonId()); // assigned to B, not A
        UUID clientId = createUser("pcrc-foreign-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salonA.masterId(), createSalonService(salonA.salonId(), salonA.masterId()),
                salonA.salonId(), "COMPLETED");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenFor(foreignAdminEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("a SALON_ADMIN assigned to a different salon must be denied — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // THE LIST SURFACE — GET /bookings/me
    // ══════════════════════════════════════════════════════════════════════════════
    //
    // Everything above reads GET /bookings/{id}. The provider LISTING used to hardcode
    // `providerCanReviewClient: false` on every row, so the mobile Archive page had nothing usable
    // to gate the "Залишити відгук про клієнта" CTA on: the flag was a constant, a client that
    // mapped it faithfully could never clear the CTA, and a refresh changed nothing. The listing
    // now computes the same conjunction the detail path does, fed by page-scoped batched lookups
    // (BookingService#loadProviderReviewBatch →
    // AuthorizationService#filterBookingIdsWithProviderAuthority). These cases mirror the detail
    // matrix above through the list, so the two surfaces cannot drift back apart.
    //
    // WHAT THIS TIER CANNOT REACH, and why the unit tier carries it instead. For a SALON_OWNER the
    // ID page is already scoped by the master's LIVE salon
    // (BookingSpecifications#salonIdIn: `JOIN b.master m JOIN m.salon s WHERE s.id IN :salonIds`,
    // fed from findIdsByOwnerIdAndIsActiveTrue), and the batched authority gate tests the SAME
    // ownership of the SAME live salon. Every row an owner can see is therefore a row they own:
    // a laxer membership predicate is TAUTOLOGICALLY invisible on the owner list surface. The role
    // that CAN observe it is SALON_MASTER — scoped by master id with no salon predicate at all, so
    // a loosened `ownedSalonIds::contains` hands the read-only role a review CTA
    // (should_returnFalseOnListRow_when_salonMasterListsOwnSalonsBooking below). The remaining
    // shapes — a foreign salon, a master with a null live salon, de-duplication of the page's salon
    // ids — are unreachable over HTTP for the same structural reason and are pinned directly on
    // AuthorizationServiceTest#filterBookingIdsWithProviderAuthority.

    @Test
    @DisplayName("LIST true — SALON_OWNER listing GET /bookings/me sees the real flag on their own "
            + "salon's COMPLETED, unreviewed booking (it was a hardcoded false before the fix)")
    void should_returnTrueOnListRow_when_salonOwnerListsOwnSalonsCompletedUnreviewedBooking() throws Exception {
        Salon salon = createSalon("pcrc-list-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-list-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode row = listRow(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("the owning SALON_OWNER must be offered the client-review CTA on the LISTING "
                        + "too, exactly as GET /bookings/{id} already offered it")
                .isTrue();
    }

    @Test
    @DisplayName("LIST true — INDEPENDENT_MASTER listing their own COMPLETED, unreviewed booking")
    void should_returnTrueOnListRow_when_independentMasterListsOwnCompletedUnreviewedBooking() throws Exception {
        String masterEmail = "pcrc-list-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID clientId = createUser("pcrc-list-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, masterId, masterServiceId, null, "COMPLETED");

        JsonNode row = listRow(bookingId, tokenFor(masterEmail));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("the independent-master arm of the batched authority gate must answer the same "
                        + "true the per-row form answers on the detail endpoint")
                .isTrue();
    }

    /**
     * The ONE list case with the power to catch a loosened salon-ownership membership test — see
     * the block comment above for why no SALON_OWNER fixture can. A SALON_MASTER's page is scoped
     * purely by master id, so an over-permissive predicate shows up here as a read-only role being
     * handed a write CTA it would then be 403'd on by {@code POST /client-reviews}.
     */
    @Test
    @DisplayName("LIST false — SALON_MASTER (read-only role) listing their own salon's COMPLETED "
            + "booking is NOT offered the client-review CTA")
    void should_returnFalseOnListRow_when_salonMasterListsOwnSalonsBooking() throws Exception {
        Salon salon = createSalon("pcrc-list-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-list-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode row = listRow(bookingId, tokenFor(salon.masterEmail()));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("SALON_MASTER is never admitted to provider-only actions; the batched gate must "
                        + "reproduce that exclusion, or the read-only role gets a CTA the write "
                        + "endpoint will reject")
                .isFalse();
        // canReview is deliberately viewer-AGNOSTIC on both surfaces (BookingService#canReview takes
        // no actor at all — it is the booking's own "a client could review this" state), so it reads
        // true on a provider's row too. Pinned rather than glossed over: with the two flags carrying
        // OPPOSITE values on one row, this row proves they are independently computed. A regression
        // that collapsed providerCanReviewClient into canReview — the laziest possible way to make
        // the regression test below go green — would show up right here.
        assertThat(row.path("canReview").asBoolean())
                .as("the CLIENT-side canReview is a different, viewer-agnostic predicate; the two "
                        + "flags must not collapse into one another")
                .isTrue();
    }

    @Test
    @DisplayName("LIST false — a guest (LINK, null-client) COMPLETED booking has no account to "
            + "review, so the listing's hasClient conjunct must exclude it")
    void should_returnFalseOnListRow_when_bookingIsGuestWithNoClient() throws Exception {
        Salon salon = createSalon("pcrc-list-guest-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertGuestBooking(salon.masterId(),
                createSalonService(salon.salonId(), salon.masterId()), salon.salonId(), "COMPLETED");

        JsonNode row = listRow(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("a guest booking has no registered client account — never reviewable, on the "
                        + "listing exactly as on the detail endpoint")
                .isFalse();
    }

    @Test
    @DisplayName("LIST false — a CONFIRMED booking whose endsAt has NOT elapsed is not yet "
            + "review-eligible, which is also the early return that keeps the page's two extra "
            + "statements unissued")
    void should_returnFalseOnListRow_when_bookingIsConfirmedAndNotYetElapsed() throws Exception {
        Salon salon = createSalon("pcrc-list-future-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-list-future-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertFutureBooking(clientId, salon.masterId(),
                createSalonService(salon.salonId(), salon.masterId()), salon.salonId(), "CONFIRMED");

        JsonNode row = listRow(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("a still-open, non-elapsed CONFIRMED booking can never be reviewed yet — and "
                        + "loadProviderReviewBatch's candidate filter must not change that answer "
                        + "while skipping the lookups")
                .isFalse();
    }

    @Test
    @DisplayName("LIST false — a CONFIRMED booking whose endsAt has already ELAPSED is still NOT "
            + "review-eligible on the LIST surface either — loadProviderReviewBatch's candidate "
            + "pre-filter and the shared providerCanReviewClient conjunction must both apply "
            + "isProviderReviewEligible (COMPLETED strictly), not the wider client-side "
            + "isReviewEligible the detail path's own elapsed-CONFIRMED case above also guards")
    void should_returnFalseOnListRow_when_bookingIsConfirmedButElapsed() throws Exception {
        Salon salon = createSalon("pcrc-list-elapsed-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-list-elapsed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "CONFIRMED");

        JsonNode row = listRow(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("an elapsed-but-unclosed CONFIRMED booking must NOT offer the provider-review "
                        + "CTA on the LISTING either — the provider must actually close the "
                        + "booking (PATCH .../complete) before rating the client")
                .isFalse();
    }

    /**
     * Master rotation, read through the list. The booking's own {@code salon_id} snapshot still
     * points at the salon it was made at (salon A), but the master has since moved to salon B under
     * a DIFFERENT owner. Both halves of this test pin a boundary:
     *
     * <ul>
     *   <li><b>Owner B sees the row and gets {@code true}</b> — authority follows the master's LIVE
     *       salon, which is exactly what {@code AuthorizationService}'s per-row form walks
     *       ({@code master.getSalon().getOwner()}). A batched form "corrected" to read the
     *       {@code bookings.salon_id} snapshot instead would answer {@code false} here and silently
     *       disagree with {@code GET /bookings/&#123;id&#125;}.</li>
     *   <li><b>Owner A does not see the row at all</b> — the provider ID page is scoped by the live
     *       salon too ({@code BookingSpecifications#salonIdIn}), so a rotated-away booking leaves
     *       the old owner's page entirely rather than appearing with a {@code false} flag. Asserted
     *       explicitly so a widening of that upstream scope cannot land unnoticed and start showing
     *       one owner another owner's client.</li>
     * </ul>
     */
    @Test
    @DisplayName("LIST — after the master rotates to another owner's salon, the NEW owner's list row "
            + "carries true (authority follows the live salon) and the row leaves the OLD owner's "
            + "page entirely")
    void should_followTheLiveSalon_when_masterRotatedToAnotherOwnersSalon() throws Exception {
        Salon salonA = createSalon("pcrc-rot-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("pcrc-rot-b-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-rot-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salonA.masterId(),
                createSalonService(salonA.salonId(), salonA.masterId()), salonA.salonId(), "COMPLETED");

        // Rotation: the master now works at salon B. bookings.salon_id is a SNAPSHOT and stays at A.
        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", salonB.salonId(), salonA.masterId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT salon_id FROM bookings WHERE id = ?", UUID.class, bookingId))
                .as("premise — the booking's own salon snapshot must still point at the OLD salon, "
                        + "or the two ids have collapsed and there is no divergence to test")
                .isEqualTo(salonA.salonId());

        JsonNode newOwnerRow = listRow(bookingId, tokenFor(salonB.ownerEmail()));
        assertThat(newOwnerRow.path("providerCanReviewClient").asBoolean())
                .as("provider authority follows the master's LIVE salon, matching what the per-row "
                        + "form on GET /bookings/{id} walks — reading the bookings.salon_id snapshot "
                        + "instead would answer false here and split the two surfaces")
                .isTrue();

        assertThat(listRowIfPresent(bookingId, tokenFor(salonA.ownerEmail())))
                .as("the old salon's owner must not see a booking whose master has rotated away — "
                        + "the provider ID page is scoped by the live salon, so the row leaves the "
                        + "page rather than appearing with a false flag")
                .isEmpty();
    }

    /**
     * THE REGRESSION TEST for the reported bug.
     *
     * <p>Reported symptom: on the mobile Archive page a provider who had completed a service AND
     * already left client feedback still saw the "leave feedback" CTA, and it SURVIVED a refresh.
     * A refresh re-issues exactly this request, so the flag it returns is the whole contract.
     *
     * <p>The load-bearing part is that BOTH states are asserted on the SAME request. The old
     * implementation returned a literal {@code false} on every provider list row, so it would
     * satisfy the post-review half on its own — a test that only checked "false after reviewing"
     * would have passed against the bug. It is the {@code true} BEFORE the review that the literal
     * cannot produce, and the transition between the two that proves the flag is derived from state
     * rather than constant.
     */
    @Test
    @DisplayName("LIST regression — a provider's COMPLETED booking arrives on GET /bookings/me with "
            + "providerCanReviewClient true, and the SAME request returns false once the provider "
            + "has posted the client review (the Archive CTA that survived a refresh)")
    void should_flipToFalseOnTheSameListRequest_when_providerLeavesTheClientReview() throws Exception {
        Salon salon = createSalon("pcrc-regress-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-regress-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");
        String ownerToken = tokenFor(salon.ownerEmail());

        assertThat(listRow(bookingId, ownerToken).path("providerCanReviewClient").asBoolean())
                .as("BEFORE the review the listing must offer the CTA. This is the half the "
                        + "pre-fix literal `false` could never produce — remove it and this test "
                        + "would pass against the very bug it exists to catch.")
                .isTrue();

        ResponseEntity<String> reviewResp = restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + bookingId + "\",\"rating\":5}", bearerHeaders(ownerToken)),
                String.class);
        assertThat(reviewResp.getStatusCode())
                .as("fixture sanity — the client review must actually be persisted, body=%s", reviewResp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(listRow(bookingId, ownerToken).path("providerCanReviewClient").asBoolean())
                .as("AFTER the review the identical request — the one a pull-to-refresh re-issues — "
                        + "must clear the CTA. The reported bug was that it never did, because this "
                        + "field was a constant on the list path.")
                .isFalse();
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    /**
     * The one row for {@code bookingId} from {@code GET /bookings/me}, failing loudly (with the
     * response body) if the provider's page does not contain it — an absent row would otherwise
     * make every flag assertion below read {@code false} off a missing JSON node and pass for the
     * wrong reason.
     */
    private JsonNode listRow(UUID bookingId, String token) throws Exception {
        Optional<JsonNode> row = listRowIfPresent(bookingId, token);
        assertThat(row)
                .as("booking %s must appear on this provider's GET /bookings/me page", bookingId)
                .isPresent();
        return row.orElseThrow();
    }

    /** Absence-tolerant form, for the rotation case where the row leaving the page IS the claim. */
    private Optional<JsonNode> listRowIfPresent(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?size=50", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/me must succeed for this fixture, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        for (JsonNode row : objectMapper.readTree(resp.getBody()).path("data").path("data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
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

    private JsonNode getBookingDetail(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/{id} must succeed for this fixture, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).get("data");
    }

    // ── seeding fixtures (local by house convention — mirrors ClientReviewIT / ────
    // ── BookingProviderCancelAuthorizationIT) ─────────────────────────────────────

    private record Salon(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "pcrc-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId, masterEmail);
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
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

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, String status) {
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
    private UUID insertFutureBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, String status) {
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

    /** Guest (LINK) booking, client_id NULL — mirrors ClientReviewIT/GuestBookingLifecycleContractIT's shape. */
    private UUID insertGuestBooking(UUID masterId, UUID masterServiceId, UUID salonId, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', '+380509998877', NOW(), NOW())",
                bookingId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }
}
