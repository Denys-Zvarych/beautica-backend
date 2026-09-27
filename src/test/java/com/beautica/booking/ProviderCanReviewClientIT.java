package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
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
 * reuses {@code AuthorizationService#isPerformingMasterOfBooking} — the exact same predicate
 * {@code ClientReviewService.create} enforces before persisting — so this suite's authority matrix
 * mirrors {@link com.beautica.review.ClientReviewIT}'s 403/400 matrix, just read through
 * {@code GET /bookings/{id}} instead of driving a write.
 *
 * <p><b>Phase 320 — the matrix INVERTED for salon owner and admin.</b> Locked product decision:
 * "salon owner or salon admin can complete the booking, and after it only salon master can leave
 * the feedback". The {@code hasProviderAuthorityOverBooking} disjunct is gone from all four review
 * call sites, so an owner reads {@code false} on a booking one of their masters performed — on the
 * detail endpoint and on every list row — while keeping {@code /complete}, {@code /not-complete},
 * {@code /decline} and {@code /reschedule}, which run off that untouched kernel. Every fixture
 * here is built by {@link #createSalon}, whose master is a distinct {@code SALON_MASTER} user, so
 * "the owner" is never the performer; an owner-as-master row ({@code master_type = 'SALON_OWNER'})
 * would still read {@code true}, which is why the predicate compares {@code booking.master.user_id}
 * rather than a role.
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

    /**
     * Phase 320 — INVERTED, and renamed with it. This asserted {@code true} from Phase 27.5 until
     * the locked product decision moved client feedback to the performing master alone. The owner
     * here CLOSED this booking and still may (the {@code /complete} gate is
     * {@code hasProviderAuthorityOverBooking}, untouched); what they lost is the right to rate a
     * client they did not serve.
     */
    @Test
    @DisplayName("FALSE — SALON_OWNER views a COMPLETED booking one of their MASTERS performed: "
            + "they may complete it, but only the performing master may review its client (320)")
    void should_returnFalse_when_salonOwnerViewsBookingPerformedByTheirMaster() throws Exception {
        Salon salon = createSalon("pcrc-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("the owner is NOT this booking's masters.user_id, so the phase-320 predicate "
                        + "denies them the client-review CTA even at their own salon; the same "
                        + "booking reads true for the master who performed it "
                        + "(should_returnTrue_when_salonMasterViewsOwnPerformedBooking)")
                .isFalse();
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

    /**
     * Phase 320 — the actor moved from the salon OWNER to the performing MASTER. The owner can no
     * longer create the review at all (403), so driving the fixture through them would assert the
     * "already reviewed" conjunct against a precondition that never happened. The master is now the
     * only actor for whom this conjunct is reachable, which is precisely why it must be tested
     * through them.
     */
    @Test
    @DisplayName("false — after the performing master has already left a client review for this booking")
    void should_returnFalse_when_providerAlreadyReviewedTheClient() throws Exception {
        Salon salon = createSalon("pcrc-dup-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-dup-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");
        String masterToken = tokenFor(salon.masterEmail());

        assertThat(getBookingDetail(bookingId, masterToken).path("providerCanReviewClient").asBoolean())
                .as("control — before the review the performing master IS offered the CTA; without "
                        + "this half a hard-deny mutant would satisfy the assertion below")
                .isTrue();

        ResponseEntity<String> reviewResp = restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + bookingId + "\",\"rating\":5}", bearerHeaders(masterToken)),
                String.class);
        assertThat(reviewResp.getStatusCode())
                .as("fixture sanity — the review must actually be persisted, body=%s", reviewResp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode detail = getBookingDetail(bookingId, masterToken);

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

    /**
     * Phase 316 — INVERTED. This test previously asserted {@code false} on the premise that a
     * {@code SALON_MASTER} is "never admitted to provider-only actions like
     * decline/complete/reschedule/review". Three quarters of that premise still hold and are pinned
     * elsewhere ({@code BookingCompletionSecurityIT}, {@code BookingProviderRescheduleIT},
     * {@code BookingSecurityTest} — all still red for this role). The REVIEW quarter was carved out:
     * a master who performed the visit is the person with something to say about the client.
     */
    @Test
    @DisplayName("TRUE — SALON_MASTER viewing the COMPLETED booking they themselves performed "
            + "(phase 316: the one provider action the read-only role holds)")
    void should_returnTrue_when_salonMasterViewsOwnPerformedBooking() throws Exception {
        Salon salon = createSalon("pcrc-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.masterEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("the salon master PERFORMED this booking — phase 316 admits exactly them, and "
                        + "only for this booking")
                .isTrue();
    }

    /**
     * Phase 316's narrowness, as far as THIS surface can express it — which is "not at all", and
     * that is the finding worth pinning. {@code enforceCanViewBooking}'s {@code SALON_MASTER} branch
     * admits only {@code masters.user_id == actor}, so a colleague never reaches the DTO to read a
     * flag off it: the detail endpoint answers 403 BEFORE {@code computeProviderCanReviewClient}
     * runs. Structurally identical to
     * {@link #should_return403_when_assignedSalonAdminViewsBookingDetail} — a role-level view gate,
     * not a review-authority one — and the reason the peer-vs-performer distinction is pinned on the
     * two surfaces that CAN observe it: {@code ClientReviewIT
     * #should_return403_when_salonMasterReviewsColleaguesBookingsClient} (the write path) and
     * {@code AuthorizationServiceTest
     * #should_returnFalse_when_salonMasterCallsCanReviewClientForColleaguesBooking} (the predicate).
     *
     * <p>Kept here anyway: if {@code enforceCanViewBooking} is ever widened to admit a peer master,
     * this test flips from 403 to 200 and the {@code providerCanReviewClient} assertion below starts
     * doing real work — at which point the phase-316 grant had better still be per-booking.
     */
    @Test
    @DisplayName("403 (NOT the DTO) — a SALON_MASTER cannot even VIEW a COLLEAGUE's booking at the "
            + "same salon, so phase 316's flag is unreachable for them on this surface")
    void should_return403_when_salonMasterViewsColleaguesBooking() throws Exception {
        Salon salon = createSalon("pcrc-sm-peer-owner-" + System.nanoTime() + "@beautica.test");
        String peerEmail = "pcrc-sm-peer-" + System.nanoTime() + "@beautica.test";
        UUID peerMasterId = createSalonMaster(salon.salonId(), peerEmail);
        UUID clientId = createUser("pcrc-sm-peer-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        // The booking belongs to the SALON's own master, NOT to the peer who reads it.
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");
        assertThat(peerMasterId).isNotEqualTo(salon.masterId());

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenFor(peerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("the view gate denies a peer master before any review flag is computed — "
                        + "body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
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
    // now computes the same conjunction the detail path does, page-scoped
    // (BookingService#loadProviderReviewBatch). These cases mirror the detail matrix above through
    // the list, so the two surfaces cannot drift back apart.
    //
    // PHASE 320 — THE SALON ARM IS GONE FROM BOTH SURFACES, and with it the batched lookup that
    // resolved it (AuthorizationService#filterBookingIdsWithProviderAuthority, deleted along with
    // its AuthorizationServiceTest section). The flag is now exactly
    // `master.user_id == actor && master.is_active`, evaluated in memory off the fetch-joined
    // graph, so the listing issues ZERO authorization statements for every provider role. The
    // salon-membership predicate those deleted unit cases pinned no longer participates in this
    // flag at all; the per-row hasProviderAuthorityOverBooking cases in AuthorizationServiceTest
    // still pin it for the endpoints that DO use it (/complete, /not-complete, /decline,
    // /reschedule — all unchanged, all still owner/admin).
    //
    // What the list tier can and cannot separate is therefore simpler than it was: the SALON_MASTER
    // row and the SALON_OWNER row now read OPPOSITE values on the identical fixture
    // (should_returnTrueOnListRow_when_salonMasterListsOwnPerformedBooking vs
    // should_returnFalseOnListRow_when_salonOwnerListsBookingPerformedByTheirMaster), which is a
    // stronger separation than this block previously had — a mutant that re-adds the salon arm
    // flips the owner row and is caught here rather than only in the unit tier.

    /**
     * Phase 320 — INVERTED and renamed, the list twin of
     * {@link #should_returnFalse_when_salonOwnerViewsBookingPerformedByTheirMaster}. The row is
     * still ON the owner's page (the ID page is scoped by their salons, untouched); only the flag
     * changed. This is now the fixture that catches a re-added salon arm on the LIST path, which
     * was previously unobservable here — see the block comment above.
     */
    @Test
    @DisplayName("LIST false — SALON_OWNER listing GET /bookings/me sees false on a booking one of "
            + "their MASTERS performed, matching GET /bookings/{id} (phase 320)")
    void should_returnFalseOnListRow_when_salonOwnerListsBookingPerformedByTheirMaster() throws Exception {
        Salon salon = createSalon("pcrc-list-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-list-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode row = listRow(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("the owner is not this booking's performing master, so the LISTING must deny "
                        + "the client-review CTA exactly as GET /bookings/{id} does — the row "
                        + "itself is still on their page, only the flag is false")
                .isFalse();
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
     * Phase 316 — INVERTED, and RENAMED with it (a name still saying "returnFalse" over an
     * {@code isTrue()} is how a later reader is told the opposite of what runs). This was the one
     * list case able to catch a loosened salon-ownership membership test; it no longer is, and the
     * block comment above records where that coverage went instead.
     *
     * <p>What it pins NOW is the parity that phase 316 made non-trivial: {@code
     * loadProviderReviewBatch}'s performer partition must reach the same answer the detail path's
     * {@code computeProviderCanReviewClient} reaches ({@code
     * should_returnTrue_when_salonMasterViewsOwnPerformedBooking}), or the master's list screen and
     * their booking screen disagree about the same booking's CTA.
     */
    @Test
    @DisplayName("LIST true — SALON_MASTER listing the COMPLETED booking they themselves performed "
            + "IS offered the client-review CTA, matching the detail path (phase 316)")
    void should_returnTrueOnListRow_when_salonMasterListsOwnPerformedBooking() throws Exception {
        Salon salon = createSalon("pcrc-list-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-list-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode row = listRow(bookingId, tokenFor(salon.masterEmail()));

        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("phase 316 — the batched gate (loadProviderReviewBatch) must reproduce the "
                        + "DETAIL path's answer for the performing master, or the list and the "
                        + "detail screen disagree about the same booking's CTA")
                .isTrue();
        // canReview is deliberately viewer-AGNOSTIC on both surfaces (BookingService#canReview takes
        // no actor at all — it is the booking's own "a client could review this" state), so it reads
        // true on a provider's row too.
        //
        // PHASE 316 DEFANGED THIS PAIR AS A COLLAPSE DETECTOR, and saying so is the point of this
        // comment. It used to read the two flags as OPPOSITES on one row, which is what proved they
        // were independently computed; both now read true, so `providerCanReviewClient = canReview`
        // would satisfy both assertions. Verified by mutation (QA, 2026-09-15): that collapse
        // applied to GET /bookings/me leaves THIS test green and is caught instead by
        // should_flipToFalseOnTheSameListRequest_when_providerLeavesTheClientReview and
        // should_returnFalseOnListRow_when_bookingIsConfirmedButElapsed, whose rows still carry the two
        // flags apart. The assertion stays — it is a correct statement about this row — but do not
        // delete either of those two on the belief that this one backstops them.
        assertThat(row.path("canReview").asBoolean())
                .as("the CLIENT-side canReview is a different, viewer-agnostic predicate — on THIS "
                        + "row the two agree; the rows that hold them apart are named in the "
                        + "comment above")
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
     * <p><b>Phase 320 — assertion 1 INVERTED, assertion 2 unchanged.</b> The flag no longer
     * "follows the live salon" because it no longer consults a salon at all: only the performing
     * master may review the client, so NEITHER owner gets the CTA and the master keeps it across
     * the rotation. Assertion 2 is about LIST SCOPING, a different mechanism this change does not
     * touch, and it stays green.
     *
     * <ul>
     *   <li><b>Owner B sees the row and gets {@code false}</b> — they now own the salon the master
     *       works at, and may complete the booking, but they did not perform it. A mutant that
     *       re-adds the salon arm to the review predicate flips this to {@code true} and is caught
     *       here.</li>
     *   <li><b>The performing master keeps {@code true} across the rotation</b> — the predicate is
     *       {@code booking.master.user_id}, which the {@code UPDATE masters SET salon_id} does not
     *       touch. Without this half a hard-deny mutant would satisfy both owner assertions.</li>
     *   <li><b>Owner A does not see the row at all</b> — the provider ID page is scoped by the live
     *       salon ({@code BookingSpecifications#salonIdIn}), so a rotated-away booking leaves the
     *       old owner's page entirely rather than appearing with a {@code false} flag. UNCHANGED by
     *       phase 320, and asserted explicitly so a widening of that upstream scope cannot land
     *       unnoticed and start showing one owner another owner's client.</li>
     * </ul>
     */
    @Test
    @DisplayName("LIST — after the performing master rotates to another owner's salon they KEEP the "
            + "client-review CTA, the NEW owner does not get it, and the row leaves the OLD owner's "
            + "page entirely (phase 320)")
    void should_keepTheRight_when_performingMasterRotatesAway() throws Exception {
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
                .as("phase 320 — the NEW owner may complete this booking but did not perform it, "
                        + "so no client-review CTA; re-adding the salon arm flips this to true")
                .isFalse();

        assertThat(listRow(bookingId, tokenFor(salonA.masterEmail())).path("providerCanReviewClient").asBoolean())
                .as("the PERFORMING master keeps the CTA across the rotation — the predicate reads "
                        + "booking.master.user_id, which the salon_id UPDATE does not touch; "
                        + "without this half a hard-deny mutant would pass the owner assertions")
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
        // Phase 320 — the actor is the PERFORMING MASTER, not the salon owner. The owner can no
        // longer post the review at all (403), so the true->false transition this test exists to
        // prove is only observable through the master. The regression it guards is unchanged: the
        // list flag must be derived from state, never a constant.
        String masterToken = tokenFor(salon.masterEmail());

        assertThat(listRow(bookingId, masterToken).path("providerCanReviewClient").asBoolean())
                .as("BEFORE the review the listing must offer the CTA. This is the half the "
                        + "pre-fix literal `false` could never produce — remove it and this test "
                        + "would pass against the very bug it exists to catch.")
                .isTrue();

        ResponseEntity<String> reviewResp = restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + bookingId + "\",\"rating\":5}", bearerHeaders(masterToken)),
                String.class);
        assertThat(reviewResp.getStatusCode())
                .as("fixture sanity — the client review must actually be persisted, body=%s", reviewResp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(listRow(bookingId, masterToken).path("providerCanReviewClient").asBoolean())
                .as("AFTER the review the identical request — the one a pull-to-refresh re-issues — "
                        + "must clear the CTA. The reported bug was that it never did, because this "
                        + "field was a constant on the list path.")
                .isFalse();
    }

    // ── the DEACTIVATED performing master (read access) ──────────────────────────
    // DELETE /masters/{masterId} (MasterService#deactivateMasterInternal) flips masters.is_active
    // and NOTHING else — the staff users row, its SALON_MASTER role and its login all survive,
    // because AuthService gates on user.isActive(). Both tests below therefore capture the token
    // BEFORE deactivation and reuse it afterwards: that is the real exposure, an already-issued
    // JWT in a fired stylist's app, not a fresh login.
    //
    // Each asserts 200 and 403 on the SAME fixture with the SAME token, separated only by the
    // is_active UPDATE. A mutant that drops the liveness conjunct fails the second half; a mutant
    // that denies every salon master fails the first. Neither half is assertable alone.

    @Test
    @DisplayName("DETAIL 200 → 403 — a SALON_MASTER reads the booking they performed, then the SAME "
            + "token is refused the moment masters.is_active goes false")
    void should_return403OnDetail_when_performingSalonMasterIsDeactivated() throws Exception {
        Salon salon = createSalon("pcrc-deact-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-deact-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");
        String masterToken = tokenFor(salon.masterEmail());

        assertThat(getBookingDetailStatus(bookingId, masterToken))
                .as("control — while employed, the performing master reads their own booking")
                .isEqualTo(HttpStatus.OK);

        deactivateMaster(salon.masterId());

        assertThat(getBookingDetailStatus(bookingId, masterToken))
                .as("BookingDetailResponse carries the client's name, phone, price and service — a "
                        + "deactivated stylist on an unexpired JWT must not read it")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("LIST 200 → 403 — GET /bookings/me serves the performing SALON_MASTER their page, "
            + "then refuses the SAME token once masters.is_active goes false (the provider-scope "
            + "resolution had no active filter, so the whole history came back)")
    void should_return403OnList_when_performingSalonMasterIsDeactivated() throws Exception {
        Salon salon = createSalon("pcrc-deact-list-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-deact-list-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");
        String masterToken = tokenFor(salon.masterEmail());

        assertThat(listRowIfPresent(bookingId, masterToken))
                .as("control — while employed the master's own performed booking is on their page; "
                        + "without this half a hard-deny mutant would pass")
                .isPresent();

        deactivateMaster(salon.masterId());

        ResponseEntity<String> afterResp = restTemplate.exchange(
                BOOKINGS_URL + "/me?size=50", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(masterToken)), String.class);
        assertThat(afterResp.getStatusCode())
                .as("403, NOT a 200 with an empty page: a deactivated master is denied the scope, "
                        + "the same answer GET /bookings/{id} gives them for any single row of it — "
                        + "body=%s", afterResp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DETAIL 200 — the SALON_OWNER still reads a booking whose performing master has "
            + "been deactivated; the liveness conjunct is scoped to the SALON_MASTER view leg")
    void should_return200OnDetail_when_ownerViewsBookingOfDeactivatedMaster() throws Exception {
        Salon salon = createSalon("pcrc-deact-ownerview-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-deact-ownerview-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        deactivateMaster(salon.masterId());

        assertThat(getBookingDetailStatus(bookingId, tokenFor(salon.ownerEmail())))
                .as("an owner must keep reading their own salon's book after firing a stylist — "
                        + "they are admitted by isAuthorizedToManageBooking, which carries no "
                        + "liveness term and must not acquire one")
                .isEqualTo(HttpStatus.OK);
        // A SALON_ADMIN control is deliberately absent: enforceCanViewBooking excludes that role
        // unconditionally, deactivated master or not — pinned by
        // should_return403_when_assignedSalonAdminViewsBookingDetail above.
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

    /** Status only — for the cases where the 403 IS the claim and there is no body to read. */
    private HttpStatus getBookingDetailStatus(UUID bookingId, String token) {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        return HttpStatus.valueOf(resp.getStatusCode().value());
    }

    /**
     * Exactly what {@code MasterService#deactivateMasterInternal} persists — {@code
     * masters.is_active = false} and nothing else. Driving it in SQL rather than through {@code
     * DELETE /masters/&#123;masterId&#125;} keeps the fixture independent of that endpoint's own
     * authorization, and makes it explicit that the {@code users} row, its {@code SALON_MASTER}
     * role and its login are all left intact — which is the whole premise of these tests.
     */
    private void deactivateMaster(UUID masterId) {
        int updated = jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterId);
        assertThat(updated).as("fixture sanity — the master row must exist to be deactivated").isEqualTo(1);
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
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        String masterEmail = "pcrc-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId, masterEmail);
    }

    /**
     * A SECOND {@code SALON_MASTER} at an already-created salon (phase 316). Needed only by the
     * per-booking-vs-per-salon narrowness tests: every other fixture here uses the single master
     * {@link #createSalon} already provisions.
     */
    private UUID createSalonMaster(UUID salonId, String email) {
        UUID userId = createUser(email, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
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
