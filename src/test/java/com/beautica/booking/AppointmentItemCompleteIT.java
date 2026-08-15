package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import jakarta.persistence.EntityManagerFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

/**
 * Full-HTTP-stack suite for the NEW {@code PATCH /appointments/{appointmentId}/services/{bookingId}/complete}
 * route — the additive per-service counterpart of the whole-visit {@code PATCH /appointments/{id}/complete}.
 * Fixes the reported bug where completing ONE service card of a multi-service visit silently completed
 * every sibling, because the only complete route was whole-visit.
 *
 * <p>Mirrors the established per-item suite shape: {@link AppointmentItemCancelIT} (client cancel),
 * the per-service decline tests in {@link AppointmentTransitionIT}, and the authorization matrix in
 * {@link BookingCompletionSecurityIT}. Deliberately does NOT duplicate the per-item decline suite's own
 * coverage — {@link #should_collapseHeaderOnlyOnLastItem_whenDecliningThreeServiceVisitSequentially()}
 * below is the one exception, added specifically as a regression guard for the shared
 * {@code collapseHeaderAfterItemTransition} helper's {@code void -> boolean} signature change, which
 * this feature's complete path now also depends on.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{appointmentId}/services/{bookingId}/complete — per-item provider complete")
class AppointmentItemCompleteIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory emf;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── 1. The multi-step collapse sequence — the whole point of this feature ──────────────────────

    @Test
    @DisplayName("completing a 3-service visit ONE ITEM AT A TIME leaves the header CONFIRMED after "
            + "item 1 and item 2, with EVERY sibling untouched at each step, and collapses the header to "
            + "COMPLETED only after the THIRD (last) item — this is the exact symptom the whole-visit-only "
            + "route used to break: completing one card completed every sibling")
    void should_collapseHeaderOnlyAfterThirdItem_when_completingThreeServiceVisitSequentially() throws Exception {
        Visit v = seedContiguousVisit("collapse-seq", 3, futureStart());
        List<UUID> legs = itemIds(v.id());
        UUID leg0 = legs.get(0);
        UUID leg1 = legs.get(1);
        UUID leg2 = legs.get(2);

        // Step 1 — complete the FIRST item only.
        ResponseEntity<String> step1 = complete(v.masterToken(), v.id(), leg0);
        assertThat(step1.getStatusCode())
                .as("completing item 1 of 3 must succeed — body: %s", step1.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg0)).as("the completed item").isEqualTo("COMPLETED");
        assertThat(dbStatus(leg1)).as("sibling 2 must be UNTOUCHED after step 1").isEqualTo("CONFIRMED");
        assertThat(dbStatus(leg2)).as("sibling 3 must be UNTOUCHED after step 1").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(v.id()))
                .as("the header must stay CONFIRMED — two siblings are still CONFIRMED").isEqualTo("CONFIRMED");

        // Step 2 — complete the SECOND item.
        ResponseEntity<String> step2 = complete(v.masterToken(), v.id(), leg1);
        assertThat(step2.getStatusCode())
                .as("completing item 2 of 3 must succeed — body: %s", step2.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg0)).as("item 1 must remain COMPLETED, not reverted").isEqualTo("COMPLETED");
        assertThat(dbStatus(leg1)).as("the newly completed item").isEqualTo("COMPLETED");
        assertThat(dbStatus(leg2)).as("sibling 3 must STILL be untouched after step 2").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(v.id()))
                .as("the header must STILL stay CONFIRMED — one sibling (item 3) remains CONFIRMED")
                .isEqualTo("CONFIRMED");

        // Step 3 — complete the LAST remaining CONFIRMED item — must collapse the header.
        ResponseEntity<String> step3 = complete(v.masterToken(), v.id(), leg2);
        assertThat(step3.getStatusCode())
                .as("completing the LAST item must succeed — body: %s", step3.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg0)).isEqualTo("COMPLETED");
        assertThat(dbStatus(leg1)).isEqualTo("COMPLETED");
        assertThat(dbStatus(leg2)).isEqualTo("COMPLETED");
        assertThat(appointmentStatus(v.id()))
                .as("no CONFIRMED sibling remains — the header collapses to COMPLETED only NOW")
                .isEqualTo("COMPLETED");
    }

    // ── 2. Notification cadence — ONE review prompt per VISIT, never one per item ──────────────────

    @Test
    @DisplayName("across a 3-item sequential completion: exactly ONE REVIEW_REQUESTED is enqueued for "
            + "the WHOLE visit — fired only by the call that completes the LAST item, referencing the "
            + "visit's FIRST item — while a STATUS_CHANGED fires once PER item, referencing that item")
    void should_enqueueExactlyOneReviewRequested_acrossThreeSequentialCompletions() throws Exception {
        Visit v = seedContiguousVisit("review-cadence", 3, futureStart());
        List<UUID> legs = itemIds(v.id());
        UUID leg0 = legs.get(0);
        UUID leg1 = legs.get(1);
        UUID leg2 = legs.get(2);

        assertThat(complete(v.masterToken(), v.id(), leg0).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reviewRequestedTotalForAppointment(v.id()))
                .as("no review prompt yet — the visit is not closed after item 1").isEqualTo(0L);
        assertThat(statusChangedCountForBooking(leg0))
                .as("STATUS_CHANGED for item 1, referencing item 1 itself").isEqualTo(1L);

        assertThat(complete(v.masterToken(), v.id(), leg1).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reviewRequestedTotalForAppointment(v.id()))
                .as("still no review prompt — the visit is not closed after item 2 either").isEqualTo(0L);
        assertThat(statusChangedCountForBooking(leg1))
                .as("STATUS_CHANGED for item 2, referencing item 2 itself").isEqualTo(1L);

        assertThat(complete(v.masterToken(), v.id(), leg2).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statusChangedCountForBooking(leg2))
                .as("STATUS_CHANGED for item 3, referencing item 3 itself (never item 0)").isEqualTo(1L);

        assertThat(reviewRequestedTotalForAppointment(v.id()))
                .as("EXACTLY ONE review prompt for the whole visit, fired by the call that closed it")
                .isEqualTo(1L);
        assertThat(reviewRequestedCountForBooking(leg0))
                .as("the single review prompt references the visit's FIRST item, never the just-completed "
                        + "leg2 that actually triggered it")
                .isEqualTo(1L);
        assertThat(reviewRequestedCountForBooking(leg2))
                .as("no review prompt is ever enqueued against the item that triggered the collapse").isEqualTo(0L);
        assertThat(statusChangedCountForAppointment(v.id()))
                .as("exactly THREE STATUS_CHANGED rows total — one per completed item, never one per visit")
                .isEqualTo(3L);
    }

    // ── 3. 204 happy path — minimal single-service visit (edge case: serviceCount == 1) ────────────

    @Test
    @DisplayName("completing the ONLY item of a single-service visit returns 204, that item is COMPLETED, "
            + "the header collapses to COMPLETED immediately, and the single review prompt fires")
    void should_return204AndCollapseImmediately_when_completingSingleServiceVisit() throws Exception {
        Visit v = seedContiguousVisit("single-item", 1, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> resp = complete(v.masterToken(), v.id(), leg0);

        assertThat(resp.getStatusCode())
                .as("completing the only item of a single-service visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg0)).isEqualTo("COMPLETED");
        assertThat(appointmentStatus(v.id()))
                .as("with only one item, the header collapses on the very first completion").isEqualTo("COMPLETED");
        assertThat(reviewRequestedCountForBooking(leg0)).isEqualTo(1L);
    }

    // ── 4. 404 — bookingId is not a child of appointmentId ──────────────────────────────────────────

    @Test
    @DisplayName("completing a bookingId that is not a child of the appointment returns 404 — for a "
            + "random UUID AND for a real booking that belongs to a DIFFERENT visit (no cross-visit oracle)")
    void should_return404_when_bookingIdIsNotChildOfAppointment() throws Exception {
        Visit v = seedContiguousVisit("complete-404", 2, futureStart());

        ResponseEntity<String> randomAttempt = complete(v.masterToken(), v.id(), UUID.randomUUID());
        assertThat(randomAttempt.getStatusCode())
                .as("a bookingId absent from the appointment is a 404").isEqualTo(HttpStatus.NOT_FOUND);

        Visit other = seedContiguousVisit("complete-404-other", 1, futureStart().plusDays(1));
        UUID foreignLeg = itemIds(other.id()).get(0);
        ResponseEntity<String> foreignAttempt = complete(v.masterToken(), v.id(), foreignLeg);
        assertThat(foreignAttempt.getStatusCode())
                .as("a real booking id from another visit must be a uniform 404, not a leak")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(dbStatus(foreignLeg)).as("the foreign visit's item must be untouched").isEqualTo("CONFIRMED");
    }

    // ── 5. 409 — target already terminal ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("re-completing an already-COMPLETED item returns 409, changes nothing, and does NOT "
            + "enqueue a second STATUS_CHANGED")
    void should_return409AndChangeNothing_when_recompletingAnAlreadyCompletedItem() throws Exception {
        Visit v = seedContiguousVisit("complete-replay", 2, futureStart());
        List<UUID> legs = itemIds(v.id());
        UUID leg0 = legs.get(0);
        UUID leg1 = legs.get(1);
        assertThat(complete(v.masterToken(), v.id(), leg0).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> replay = complete(v.masterToken(), v.id(), leg0);

        assertThat(replay.getStatusCode())
                .as("re-completing a terminal item is a 409 — the per-item transition guard — body: %s",
                        replay.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0)).isEqualTo("COMPLETED");
        assertThat(dbStatus(leg1)).as("the untouched sibling stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(v.id())).isEqualTo("CONFIRMED");
        assertThat(statusChangedCountForBooking(leg0))
                .as("the rejected replay must not enqueue a second notification").isEqualTo(1L);
    }

    @Test
    @DisplayName("completing an already-DECLINED item returns 409 — a per-service decline blocks a "
            + "later per-service complete on the SAME leg")
    void should_return409_when_completingAnAlreadyDeclinedItem() throws Exception {
        Visit v = seedContiguousVisit("complete-after-decline", 2, futureStart());
        List<UUID> legs = itemIds(v.id());
        UUID leg0 = legs.get(0);
        assertThat(decline(v.masterToken(), v.id(), leg0, "{}").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = complete(v.masterToken(), v.id(), leg0);

        assertThat(resp.getStatusCode())
                .as("a DECLINED leg cannot subsequently be COMPLETED — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0)).isEqualTo("DECLINED");
    }

    // ── 6. Authorization matrix (mirrors BookingCompletionSecurityIT) ───────────────────────────────

    @Test
    @DisplayName("SALON_OWNER completing one service line of their own salon's visit returns 204")
    void should_return204_when_salonOwnerCompletesItem() throws Exception {
        SalonVisit sv = seedSalonVisit("owner", 2);

        ResponseEntity<String> resp = complete(sv.ownerToken(), sv.appointmentId(), sv.legs().get(0));

        assertThat(resp.getStatusCode())
                .as("salon owner completing own-salon item must return 204 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(sv.legs().get(0))).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("SALON_ADMIN assigned to the salon completing one service line returns 204")
    void should_return204_when_salonAdminCompletesItem() throws Exception {
        SalonVisit sv = seedSalonVisit("admin", 2);
        String adminEmail = "item-complete-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", sv.salonId());
        String adminToken = fixtures.tokenFor(adminEmail);

        ResponseEntity<String> resp = complete(adminToken, sv.appointmentId(), sv.legs().get(0));

        assertThat(resp.getStatusCode())
                .as("assigned SALON_ADMIN completing an item must return 204 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(sv.legs().get(0))).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER completing one service line of their own visit returns 204")
    void should_return204_when_independentMasterCompletesOwnItem() throws Exception {
        Visit v = seedContiguousVisit("im-positive", 2, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> resp = complete(v.masterToken(), v.id(), leg0);

        assertThat(resp.getStatusCode())
                .as("independent master completing own item must return 204 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg0)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("CLIENT is denied with 403 — the per-item complete route has no client arm")
    void should_return403_when_clientAttemptsCompleteItem() throws Exception {
        Visit v = seedContiguousVisit("client-denied", 1, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> resp = complete(v.clientToken(), v.id(), leg0);

        assertThat(resp.getStatusCode())
                .as("CLIENT must be denied per-item complete — 403").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(leg0)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a read-only SALON_MASTER attempting per-item complete is denied with 403")
    void should_return403_when_salonMasterReadOnlyAttemptsCompleteItem() throws Exception {
        SalonVisit sv = seedSalonVisit("readonly", 1);
        String salonMasterToken = fixtures.tokenFor(sv.masterEmail());

        ResponseEntity<String> resp = complete(salonMasterToken, sv.appointmentId(), sv.legs().get(0));

        assertThat(resp.getStatusCode())
                .as("read-only SALON_MASTER must be denied — 403").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(sv.legs().get(0))).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("an owner of a DIFFERENT salon completing a foreign visit's item is denied with 403, "
            + "byte-identical to the 403 for a non-existent appointment id — no existence oracle")
    void should_return403Identically_when_crossTenantOwnerAttemptsCompleteItem() throws Exception {
        SalonVisit salonA = seedSalonVisit("xt-a", 1);
        SalonVisit salonB = seedSalonVisit("xt-b", 1);

        ResponseEntity<String> foreignAttempt = complete(salonB.ownerToken(), salonA.appointmentId(), salonA.legs().get(0));
        ResponseEntity<String> nonexistentAttempt = complete(salonB.ownerToken(), UUID.randomUUID(), salonA.legs().get(0));

        assertThat(foreignAttempt.getStatusCode())
                .as("owner of a different salon must be denied with 403 — body: %s", foreignAttempt.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nonexistentAttempt.getStatusCode())
                .as("a non-existent appointment id must be refused with the SAME status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignAttempt.getBody())
                .as("the foreign-visit response body must be byte-identical to the nonexistent-appointment "
                        + "response — no existence oracle leaks through a differently-worded message")
                .isEqualTo(nonexistentAttempt.getBody());
        assertThat(dbStatus(salonA.legs().get(0))).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("ordering proof: an unauthorized caller passing a NON-CHILD bookingId on someone else's "
            + "appointment still gets 403, never 404 — authorization on the visit precedes bookingId membership")
    void should_return403NotFound404_when_unauthorizedCallerPassesNonChildBookingId() throws Exception {
        Visit v = seedContiguousVisit("complete-ordering", 1, futureStart());
        String foreignEmail = "item-complete-ordering-foreign-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignEmail, "CLIENT", null);
        String foreignToken = fixtures.tokenFor(foreignEmail);

        ResponseEntity<String> resp = complete(foreignToken, v.id(), UUID.randomUUID());

        assertThat(resp.getStatusCode())
                .as("an unauthorized caller must be rejected at AUTHORIZATION (403) before bookingId "
                        + "membership is ever checked (which would be 404)")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 7. Regression guard for the collapseHeaderAfterItemTransition void->boolean change ─────────

    @Test
    @DisplayName("REGRESSION GUARD (shared helper void->boolean change): per-service DECLINE, sequenced "
            + "across a 3-item visit, still collapses the header to DECLINED only on the LAST CONFIRMED "
            + "item, with siblings untouched at each step — unaffected by completeAppointmentItem now "
            + "sharing the same collapseHeaderAfterItemTransition helper")
    void should_collapseHeaderOnlyOnLastItem_whenDecliningThreeServiceVisitSequentially() throws Exception {
        Visit v = seedContiguousVisit("decline-regression", 3, futureStart());
        List<UUID> legs = itemIds(v.id());
        UUID leg0 = legs.get(0);
        UUID leg1 = legs.get(1);
        UUID leg2 = legs.get(2);

        assertThat(decline(v.masterToken(), v.id(), leg0, "{}").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg1)).as("sibling 2 untouched after decline step 1").isEqualTo("CONFIRMED");
        assertThat(dbStatus(leg2)).as("sibling 3 untouched after decline step 1").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(v.id())).as("header stays CONFIRMED after decline step 1").isEqualTo("CONFIRMED");

        assertThat(decline(v.masterToken(), v.id(), leg1, "{}").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg2)).as("sibling 3 STILL untouched after decline step 2").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(v.id())).as("header STILL CONFIRMED after decline step 2").isEqualTo("CONFIRMED");

        ResponseEntity<String> last = decline(v.masterToken(), v.id(), leg2, "{}");
        assertThat(last.getStatusCode())
                .as("declining the LAST CONFIRMED item must succeed — body: %s", last.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(appointmentStatus(v.id()))
                .as("only NOW, with no CONFIRMED sibling left, does the header collapse to DECLINED")
                .isEqualTo("DECLINED");
        assertThat(dbStatus(leg0)).isEqualTo("DECLINED");
        assertThat(dbStatus(leg1)).isEqualTo("DECLINED");
        assertThat(dbStatus(leg2)).isEqualTo("DECLINED");
    }

    // ── 8. V124 constraint pin — a COMPLETED header must carry a NULL cancellation_reason ──────────

    @Test
    @DisplayName("V124 pin: once the header collapses to COMPLETED via per-item completion, "
            + "cancellation_reason on the header is NULL — chk_appointment_cancellation_reason_status "
            + "requires it, and the complete path must never start passing a reason")
    void should_haveNullCancellationReason_whenHeaderCollapsesToCompletedViaPerItemComplete() throws Exception {
        Visit v = seedContiguousVisit("v124-pin", 1, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        assertThat(complete(v.masterToken(), v.id(), leg0).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(appointmentStatus(v.id())).isEqualTo("COMPLETED");
        assertThat(appointmentCancellationReason(v.id()))
                .as("a COMPLETED header must carry a NULL cancellation_reason (V124 CHECK constraint) — "
                        + "if a future change starts passing a reason on the complete path, this assertion "
                        + "must fail loudly instead of the write failing silently or the CHECK constraint "
                        + "throwing a raw SQL exception at request time")
                .isNull();
    }

    // ── 9. Statement-count regression gate (backend-perf audit, INFO) ──────────────────────────────

    /**
     * Pinned JDBC statement count for ONE {@code completeAppointmentItem} call against a
     * single-service visit for an INDEPENDENT_MASTER actor — the scenario that exercises every step
     * on the method's happy path, including the conditional review-prompt INSERT (this item IS the
     * visit's last CONFIRMED item, so the header collapses and the prompt fires):
     * authz projection, {@code findByAppointmentIdWithGraph}, {@code lockHeaderIfConfirmed},
     * {@code existsConfirmedById} freshness re-check, the target's own {@code save} flush, the
     * conditional header-collapse {@code UPDATE}, the {@code STATUS_CHANGED} outbox {@code INSERT},
     * and the {@code REVIEW_REQUESTED} outbox {@code INSERT}.
     *
     * <p>Follows the same precedent as {@code BookingPriceRangeContractIT}'s
     * {@code OWNER_DETAIL_STATEMENTS_ROTATED} gate and {@code MultiServiceNotificationIT}'s sibling-
     * hydration gate: Hibernate {@link Statistics#getPrepareStatementCount()}, cleared immediately
     * before the call under test. A rise means a query moved from a batched/flush-coalesced site back
     * to a per-call round trip — most plausibly a lost {@code JOIN FETCH} on
     * {@code findByAppointmentIdWithGraph} or an extra lock/lookup added ahead of the freshness
     * re-check.
     */
    private static final long COMPLETE_ITEM_STATEMENTS = 8L;

    @Test
    @DisplayName("STATEMENT-COUNT GATE: completing the only item of a single-service visit costs a "
            + "fixed, pinned number of JDBC statements — a rise means a lost JOIN FETCH or a "
            + "reintroduced N+1 on the per-item complete path")
    void should_costPinnedStatementCount_when_completingSingleServiceVisit() throws Exception {
        Visit v = seedContiguousVisit("qcount-complete", 1, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        ResponseEntity<String> resp = complete(v.masterToken(), v.id(), leg0);
        long statements = statistics.getPrepareStatementCount();

        assertThat(resp.getStatusCode())
                .as("premise — the call under measurement must actually succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(leg0))
                .as("premise — the review-prompt INSERT this gate counts only fires on an actual "
                        + "COMPLETED transition").isEqualTo("COMPLETED");
        assertThat(reviewRequestedCountForBooking(leg0))
                .as("premise — this scenario must exercise the conditional REVIEW_REQUESTED INSERT, "
                        + "or the gate silently stops covering that branch").isEqualTo(1L);
        assertThat(statements)
                .as("absolute JDBC statement count for completeAppointmentItem's happy path "
                        + "(single-item visit, header collapses, review prompt fires). A rise means "
                        + "an association is no longer fetch-joined and is being lazily initialised, "
                        + "or an extra round trip was added ahead of the freshness re-check.")
                .isEqualTo(COMPLETE_ITEM_STATEMENTS);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Visit(UUID id, UUID masterId, String clientToken, String masterToken) {}

    private record SalonVisit(
            UUID appointmentId, UUID salonId, String ownerToken, String masterEmail, List<UUID> legs) {}

    private Visit seedContiguousVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        String masterEmail = "item-complete-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "item-complete-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createIndependentMasterService(masterId);
        }
        addWorkingHoursForEveryDay(masterId);
        UUID appointmentId = seedVisitRows(clientId, masterId, null, serviceIds, firstStart);
        return new Visit(appointmentId, masterId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

    private SalonVisit seedSalonVisit(String tag, int itemCount) throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("item-complete-salon-owner-" + tag + "-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "item-complete-salon-client-" + tag + "-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createSalonService(salon.salonId(), salon.masterId());
        }
        addWorkingHoursForEveryDay(salon.masterId());
        UUID appointmentId = seedVisitRows(clientId, salon.masterId(), salon.salonId(), serviceIds, futureStart());
        return new SalonVisit(
                appointmentId, salon.salonId(), fixtures.tokenFor(salon.ownerEmail()), salon.masterEmail(),
                itemIds(appointmentId));
    }

    private UUID seedVisitRows(UUID clientId, UUID masterId, UUID salonId, UUID[] serviceIds, OffsetDateTime firstStart) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId, salonId);
        OffsetDateTime cursor = firstStart;
        for (UUID serviceId : serviceIds) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, appointment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                    UUID.randomUUID(), clientId, masterId, serviceId, salonId,
                    cursor, cursor.plusHours(1), appointmentId);
            cursor = cursor.plusHours(1);
        }
        return appointmentId;
    }

    private List<UUID> itemIds(UUID appointmentId) {
        return jdbcTemplate.query(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                appointmentId);
    }

    private ResponseEntity<String> complete(String token, UUID appointmentId, UUID bookingId) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/complete",
                HttpMethod.PATCH, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> decline(String token, UUID appointmentId, UUID bookingId, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private String appointmentCancellationReason(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private Long statusChangedCountForBooking(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' AND aggregate_id = ?",
                Long.class, bookingId);
    }

    private Long statusChangedCountForAppointment(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId);
    }

    private Long reviewRequestedCountForBooking(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'REVIEW_REQUESTED' AND aggregate_id = ?",
                Long.class, bookingId);
    }

    private Long reviewRequestedTotalForAppointment(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'REVIEW_REQUESTED' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId);
    }

    private static OffsetDateTime futureStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '00:00', '23:59')",
                    UUID.randomUUID(), scheduleId, day);
        }
    }
}
