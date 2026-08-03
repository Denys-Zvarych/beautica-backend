package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingElapsedResponse;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * BE-4 full-HTTP-stack QA matrix for the four appointment-level (visit) status transitions
 * ({@code PATCH /appointments/{id}/(cancel|decline|complete|not-complete)}), completing the coverage
 * begun by the dev's {@link AppointmentTransitionIT} smoke and the single-booking-child
 * {@link BookingAppointmentChildTransitionGuardIT} guard. The smoke proves the happy path on
 * two-item independent visits; this suite adds the dimensions it could not reach:
 *
 * <ol>
 *   <li><b>N-item lockstep + item/header note coupling</b> — three-item visits prove the whole chain
 *       (not just a pair) moves atomically, and that the note lives ONLY on the header while every
 *       item carries the coupled {@code cancellation_reason} but never the note text; complete writes
 *       neither note nor reason anywhere.</li>
 *   <li><b>Authorization matrix</b> — foreign-client cancel (403), wrong-role on each endpoint (403),
 *       and the BE-3 salon-employed-master gap: a {@code SALON_OWNER} acting on a visit whose master
 *       belongs to their salon (positive), a foreign salon owner (403), and the read-only
 *       {@code SALON_MASTER} (403).</li>
 *   <li><b>Illegal / duplicate transitions</b> — cancel-a-COMPLETED, decline-a-CANCELLED — the same
 *       400 the single-booking path returns, state unchanged, no second notification.</li>
 *   <li><b>Elapsed guard</b> — the branch the smoke could NOT cover: a client cancel of a visit whose
 *       last item's {@code endsAt} is already in the past yields {@code 409 BOOKING_ALREADY_ELAPSED}
 *       (visit untouched), while the provider resolution path is still allowed; a foreign client on an
 *       elapsed visit gets a uniform 403 with no elapsed oracle.</li>
 *   <li><b>Exactly one notification per transition</b> — one {@code STATUS_CHANGED} outbox row on each
 *       success, zero on every rejection.</li>
 * </ol>
 *
 * <p><b>Visits are seeded directly via SQL</b> (header + N chained CONFIRMED items) rather than through
 * {@code POST /appointments}: the endpoint under test is the TRANSITION, and SQL seeding is
 * deterministic (no working-hours/slot plumbing), lets a PAST visit exist at all — the create path
 * rejects past starts — and is the established house pattern for the sibling booking-transition ITs
 * ({@link BookingCompletionSecurityIT}, {@link BookingElapsedClientGuardIT}). {@code NotificationOutboxService}
 * is NOT mocked so the {@code STATUS_CHANGED} rows are real and countable (point 5); the base class
 * mocks only the SMTP/push sinks, so the always-on drain never sends against seeded rows.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{id}/(cancel|decline|complete|not-complete) — BE-4 QA matrix")
class AppointmentTransitionMatrixIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Point 1 + 2 + 6 — three-item lockstep, header-only notes, item reason coupling, one notification
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-item lockstep + note coupling (3 items)")
    class LockstepAndNoteCoupling {

        @Test
        @DisplayName("client cancel — header + ALL 3 items → CANCELLED; note only on the header, each "
                + "item carries CLIENT_CANCELLED but NOT the note; exactly one STATUS_CHANGED")
        void should_moveAllThreeItemsToCancelled_andKeepNoteOnHeaderOnly_when_clientCancels() throws Exception {
            IndependentVisit v = seedIndependentVisit("cancel3", 3, futureStart());

            ResponseEntity<String> resp = patch(v.clientToken(), v.id(), "cancel",
                    "{\"clientCancellationNote\":\"Захворіла, переношу\"}");

            assertThat(resp.getStatusCode())
                    .as("client cancel of a 3-item visit must succeed — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "CANCELLED");
            assertAllItemsStatus(v.id(), "CANCELLED", 3);

            Map<String, Object> header = header(v.id());
            assertThat(header.get("cancellation_reason")).isEqualTo("CLIENT_CANCELLED");
            assertThat(header.get("client_cancellation_note")).isEqualTo("Захворіла, переношу");
            assertThat(header.get("provider_comment")).as("client cancel writes no provider note").isNull();

            for (Map<String, Object> item : items(v.id())) {
                assertThat(item.get("cancellation_reason"))
                        .as("each item carries the coupled reason so a single-item read stays consistent")
                        .isEqualTo("CLIENT_CANCELLED");
                assertThat(item.get("client_cancellation_note"))
                        .as("the note lives on the header ONLY — never denormalized onto items")
                        .isNull();
                assertThat(item.get("provider_comment")).isNull();
            }
            assertThat(statusChangedCount(v.id()))
                    .as("exactly ONE STATUS_CHANGED for the whole visit, not one per item")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("provider decline — header + ALL 3 items → DECLINED; provider note only on the "
                + "header, each item carries PROVIDER_UNAVAILABLE but NOT the note; one STATUS_CHANGED")
        void should_moveAllThreeItemsToDeclined_andKeepNoteOnHeaderOnly_when_providerDeclines() throws Exception {
            IndependentVisit v = seedIndependentVisit("decline3", 3, futureStart());

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "decline",
                    "{\"providerComment\":\"Майстер захворів\"}");

            assertThat(resp.getStatusCode())
                    .as("provider decline of a 3-item visit must succeed — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "DECLINED");
            assertAllItemsStatus(v.id(), "DECLINED", 3);

            Map<String, Object> header = header(v.id());
            assertThat(header.get("cancellation_reason")).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(header.get("provider_comment")).isEqualTo("Майстер захворів");
            assertThat(header.get("client_cancellation_note")).isNull();

            for (Map<String, Object> item : items(v.id())) {
                assertThat(item.get("cancellation_reason")).isEqualTo("PROVIDER_UNAVAILABLE");
                assertThat(item.get("provider_comment"))
                        .as("the provider note lives on the header ONLY — never on items")
                        .isNull();
            }
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("provider complete — header + ALL 3 items → COMPLETED; NO note and NO reason "
                + "anywhere (header or items); exactly one STATUS_CHANGED")
        void should_moveAllThreeItemsToCompleted_andWriteNoNotesOrReason_when_providerCompletes() throws Exception {
            IndependentVisit v = seedIndependentVisit("complete3", 3, futureStart());

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "complete", null);

            assertThat(resp.getStatusCode())
                    .as("provider complete of a 3-item visit must succeed — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "COMPLETED");
            assertAllItemsStatus(v.id(), "COMPLETED", 3);

            Map<String, Object> header = header(v.id());
            assertThat(header.get("cancellation_reason")).as("complete sets no reason").isNull();
            assertThat(header.get("provider_comment")).as("complete writes no note").isNull();
            assertThat(header.get("client_cancellation_note")).isNull();

            for (Map<String, Object> item : items(v.id())) {
                assertThat(item.get("cancellation_reason")).isNull();
                assertThat(item.get("provider_comment")).isNull();
                assertThat(item.get("client_cancellation_note")).isNull();
            }
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("provider no-show — header + ALL 3 items → NOT_COMPLETED; provider note only on "
                + "the header, each item carries CLIENT_NO_SHOW but NOT the note; one STATUS_CHANGED")
        void should_moveAllThreeItemsToNotCompleted_andKeepNoteOnHeaderOnly_when_providerMarksNoShow() throws Exception {
            // not-complete has no temporal guard — an elapsed visit is just the conventional
            // no-show fixture here, not a requirement.
            IndependentVisit v = seedIndependentVisit("noshow3", 3, pastStart());

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "not-complete",
                    "{\"providerComment\":\"Клієнт не прийшов\"}");

            assertThat(resp.getStatusCode())
                    .as("provider no-show of a 3-item visit must succeed — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "NOT_COMPLETED");
            assertAllItemsStatus(v.id(), "NOT_COMPLETED", 3);

            Map<String, Object> header = header(v.id());
            assertThat(header.get("cancellation_reason")).isEqualTo("CLIENT_NO_SHOW");
            assertThat(header.get("provider_comment")).isEqualTo("Клієнт не прийшов");

            for (Map<String, Object> item : items(v.id())) {
                assertThat(item.get("cancellation_reason")).isEqualTo("CLIENT_NO_SHOW");
                assertThat(item.get("provider_comment")).isNull();
            }
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Point 3 — authorization matrix (foreign client, wrong role, salon-employed master)
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Authorization matrix")
    class Authorization {

        @Test
        @DisplayName("cancel — a DIFFERENT client cancelling another client's visit is denied 403; the "
                + "visit + all items stay CONFIRMED and no notification is enqueued")
        void should_return403AndKeepConfirmed_when_foreignClientCancels() throws Exception {
            IndependentVisit v = seedIndependentVisit("foreign-client", 2, futureStart());
            String strangerEmail = "appt-mx-stranger-" + System.nanoTime() + "@beautica.test";
            fixtures.createUser(strangerEmail, "CLIENT", null);
            String strangerToken = fixtures.tokenFor(strangerEmail);

            ResponseEntity<String> resp = patch(strangerToken, v.id(), "cancel",
                    "{\"clientCancellationNote\":\"не моє\"}");

            assertThat(resp.getStatusCode())
                    .as("only the owning client may cancel — a foreign client must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("cancel — a provider (INDEPENDENT_MASTER) hitting the client-only cancel endpoint "
                + "is denied 403 by the role gate; the visit stays CONFIRMED")
        void should_return403_when_providerHitsClientCancelEndpoint() throws Exception {
            IndependentVisit v = seedIndependentVisit("role-cancel", 2, futureStart());

            // v.masterToken() is an INDEPENDENT_MASTER — not a CLIENT — so hasRole('CLIENT') rejects it.
            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "cancel", "{}");

            assertThat(resp.getStatusCode())
                    .as("cancel is client-only (hasRole('CLIENT')) — a provider role must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertVisitStatus(v.id(), "CONFIRMED");
        }

        @Test
        @DisplayName("decline — a CLIENT hitting the provider-only decline endpoint is denied 403 by "
                + "the role gate; the visit stays CONFIRMED")
        void should_return403_when_clientHitsProviderDeclineEndpoint() throws Exception {
            IndependentVisit v = seedIndependentVisit("role-decline", 2, futureStart());

            ResponseEntity<String> resp = patch(v.clientToken(), v.id(), "decline", "{}");

            assertThat(resp.getStatusCode())
                    .as("decline is provider-only — a CLIENT role must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertVisitStatus(v.id(), "CONFIRMED");
        }

        // ── salon-employed master visit (the BE-3 gap) ──────────────────────────────────────────────

        @Test
        @DisplayName("complete — the SALON_OWNER of the visit's master completes a SALON-master visit "
                + "(salon_id on header + items); header + all items → COMPLETED, one STATUS_CHANGED")
        void should_complete_when_salonOwnerCompletesOwnSalonMasterVisit() throws Exception {
            SalonVisit v = seedSalonVisit("salon-complete", 2, futureStart());

            ResponseEntity<String> resp = patch(v.ownerToken(), v.id(), "complete", null);

            assertThat(resp.getStatusCode())
                    .as("the salon owner may complete their salon-master's visit — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "COMPLETED");
            assertAllItemsStatus(v.id(), "COMPLETED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("decline — the SALON_OWNER declines a SALON-master visit; header + all items → "
                + "DECLINED with the provider note on the header, and every item keeps its salon_id")
        void should_decline_when_salonOwnerDeclinesOwnSalonMasterVisit() throws Exception {
            SalonVisit v = seedSalonVisit("salon-decline", 2, futureStart());

            ResponseEntity<String> resp = patch(v.ownerToken(), v.id(), "decline",
                    "{\"providerComment\":\"Салон зачинено того дня\"}");

            assertThat(resp.getStatusCode())
                    .as("the salon owner may decline their salon-master's visit — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "DECLINED");
            assertAllItemsStatus(v.id(), "DECLINED", 2);
            assertThat(header(v.id()).get("provider_comment")).isEqualTo("Салон зачинено того дня");
            for (Map<String, Object> item : items(v.id())) {
                assertThat(item.get("salon_id"))
                        .as("a salon visit's items keep their salon_id through the transition")
                        .isEqualTo(v.salonId());
                assertThat(item.get("cancellation_reason")).isEqualTo("PROVIDER_UNAVAILABLE");
            }
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("complete — a FOREIGN salon owner (owns a different salon) completing this salon's "
                + "visit is denied 403; the visit + all items stay CONFIRMED, no notification")
        void should_return403AndKeepConfirmed_when_foreignSalonOwnerCompletes() throws Exception {
            SalonVisit v = seedSalonVisit("salon-foreign", 2, futureStart());
            BookingTestFixtures.SalonFixture other =
                    fixtures.createSalon("appt-mx-foreign-owner-" + System.nanoTime() + "@beautica.test");
            String foreignOwnerToken = fixtures.tokenFor(other.ownerEmail());

            ResponseEntity<String> resp = patch(foreignOwnerToken, v.id(), "complete", null);

            assertThat(resp.getStatusCode())
                    .as("a salon owner with no authority over the visit's salon must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("decline — the read-only SALON_MASTER attempting to decline their OWN visit is "
                + "denied 403 by the role gate; the visit stays CONFIRMED")
        void should_return403_when_salonMasterAttemptsDeclineOwnVisit() throws Exception {
            SalonVisit v = seedSalonVisit("salon-master-ro", 2, futureStart());
            String salonMasterToken = fixtures.tokenFor(v.masterEmail());

            ResponseEntity<String> resp = patch(salonMasterToken, v.id(), "decline", "{}");

            assertThat(resp.getStatusCode())
                    .as("SALON_MASTER is read-only — excluded from the provider role gate → 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        // ── anti-oracle pins: siblings of BookingProviderCancelAuthorizationIT's /not-complete pin ──
        // Every role-only provider transition on AppointmentController (decline / complete /
        // not-complete / per-service decline) is @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN',
        // 'INDEPENDENT_MASTER')") — matching the sibling booking endpoints. Before the MEDIUM oracle
        // fix, the service loaded the visit via loadVisitOrThrow → 404 for an unknown appointment id
        // BEFORE the ownership guard, leaking existence (404 for missing vs. 403 for foreign — the
        // foreignSalonOwner case above). The fix collapses existence + ownership to a uniform 403 on
        // every one of these endpoints; these pins hold that behaviour for all four uniformly.
        @Test
        @DisplayName("not-complete — a valid provider hitting a NONEXISTENT appointment id must be denied "
                + "403 (uniform with the foreign-owner denial), NOT 404 — anti existence-oracle pin")
        void should_return403_not404_when_providerNotCompletesNonexistentVisit() throws Exception {
            // A fully-valid SALON_OWNER provider — the role gate passes, so only the service-layer
            // existence/ownership handling decides the status code.
            BookingTestFixtures.SalonFixture salon =
                    fixtures.createSalon("appt-mx-nc-oracle-owner-" + System.nanoTime() + "@beautica.test");
            UUID nonexistentAppointmentId = UUID.randomUUID();

            ResponseEntity<String> resp = patch(fixtures.tokenFor(salon.ownerEmail()),
                    nonexistentAppointmentId, "not-complete", "{\"providerComment\":\"немає такого візиту\"}");

            assertThat(resp.getStatusCode())
                    .as("a nonexistent appointment id must be indistinguishable from a foreign one — both "
                            + "403, never a 404 that confirms the id does not exist (existence oracle)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("decline — a valid provider hitting a NONEXISTENT appointment id must be denied 403 "
                + "(uniform with the foreign-owner denial), NOT 404 — anti existence-oracle pin")
        void should_return403_not404_when_providerDeclinesNonexistentVisit() throws Exception {
            BookingTestFixtures.SalonFixture salon =
                    fixtures.createSalon("appt-mx-dc-oracle-owner-" + System.nanoTime() + "@beautica.test");
            UUID nonexistentAppointmentId = UUID.randomUUID();

            ResponseEntity<String> resp = patch(fixtures.tokenFor(salon.ownerEmail()),
                    nonexistentAppointmentId, "decline", "{\"providerComment\":\"немає такого візиту\"}");

            assertThat(resp.getStatusCode())
                    .as("a nonexistent appointment id must be indistinguishable from a foreign one — both "
                            + "403, never a 404 that confirms the id does not exist (existence oracle)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("complete — a valid provider hitting a NONEXISTENT appointment id must be denied 403 "
                + "(uniform with the foreign-owner denial), NOT 404 — anti existence-oracle pin")
        void should_return403_not404_when_providerCompletesNonexistentVisit() throws Exception {
            BookingTestFixtures.SalonFixture salon =
                    fixtures.createSalon("appt-mx-cp-oracle-owner-" + System.nanoTime() + "@beautica.test");
            UUID nonexistentAppointmentId = UUID.randomUUID();

            ResponseEntity<String> resp = patch(fixtures.tokenFor(salon.ownerEmail()),
                    nonexistentAppointmentId, "complete", null);

            assertThat(resp.getStatusCode())
                    .as("a nonexistent appointment id must be indistinguishable from a foreign one — both "
                            + "403, never a 404 that confirms the id does not exist (existence oracle)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("decline-service — a valid provider declining a service of a NONEXISTENT appointment "
                + "id must be denied 403, NOT 404 — the missing-visit case collapses to 403 BEFORE the "
                + "bookingId-not-a-child 404, so no existence oracle")
        void should_return403_not404_when_providerDeclinesServiceOfNonexistentVisit() throws Exception {
            BookingTestFixtures.SalonFixture salon =
                    fixtures.createSalon("appt-mx-psd-oracle-owner-" + System.nanoTime() + "@beautica.test");
            UUID nonexistentAppointmentId = UUID.randomUUID();
            UUID anyBookingId = UUID.randomUUID();

            ResponseEntity<String> resp = patchServiceDecline(fixtures.tokenFor(salon.ownerEmail()),
                    nonexistentAppointmentId, anyBookingId, "{\"providerComment\":\"немає такого візиту\"}");

            assertThat(resp.getStatusCode())
                    .as("a nonexistent appointment id must be indistinguishable from a foreign one — both "
                            + "403; the missing-visit case must collapse to 403 BEFORE the child-not-found 404")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Point 4 — illegal / duplicate transitions (mirror the single-booking 400; nothing changes)
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Illegal / duplicate transitions")
    class IllegalTransitions {

        @Test
        @DisplayName("cancel a COMPLETED visit → 400; the visit stays COMPLETED, no second "
                + "STATUS_CHANGED (only the original complete's remains)")
        void should_return400AndChangeNothing_when_clientCancelsCompletedVisit() throws Exception {
            IndependentVisit v = seedIndependentVisit("cancel-completed", 2, futureStart());
            // provider completes first (one STATUS_CHANGED), then the client tries to cancel it.
            assertThat(patch(v.masterToken(), v.id(), "complete", null).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            ResponseEntity<String> resp = patch(v.clientToken(), v.id(), "cancel",
                    "{\"clientCancellationNote\":\"пізно\"}");

            assertThat(resp.getStatusCode())
                    .as("cancelling a terminal (COMPLETED) visit is illegal — 400, as the single path returns")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertVisitStatus(v.id(), "COMPLETED");
            assertAllItemsStatus(v.id(), "COMPLETED", 2);
            assertThat(header(v.id()).get("client_cancellation_note"))
                    .as("the rejected cancel must not stamp a client note on a COMPLETED visit")
                    .isNull();
            assertThat(statusChangedCount(v.id()))
                    .as("the rejected cancel must not enqueue a second notification")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("decline a CANCELLED visit → 400; the visit stays CANCELLED, no second "
                + "STATUS_CHANGED (only the original cancel's remains)")
        void should_return400AndChangeNothing_when_providerDeclinesCancelledVisit() throws Exception {
            IndependentVisit v = seedIndependentVisit("decline-cancelled", 2, futureStart());
            // client cancels first (one STATUS_CHANGED), then the provider tries to decline it.
            assertThat(patch(v.clientToken(), v.id(), "cancel", "{}").getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "decline",
                    "{\"providerComment\":\"вже скасовано\"}");

            assertThat(resp.getStatusCode())
                    .as("declining a terminal (CANCELLED) visit is illegal — 400")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertVisitStatus(v.id(), "CANCELLED");
            assertAllItemsStatus(v.id(), "CANCELLED", 2);
            assertThat(header(v.id()).get("provider_comment"))
                    .as("the rejected decline must not overwrite the header with a provider note")
                    .isNull();
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Point 5 — elapsed guard (client cancel of a past visit → 409; provider still allowed; no oracle)
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Elapsed-visit client guard")
    class ElapsedGuard {

        @Test
        @DisplayName("cancel — 409 BOOKING_ALREADY_ELAPSED when the owning client cancels a visit whose "
                + "last item's endsAt has already passed; the visit + all items stay CONFIRMED, no notification")
        void should_return409Elapsed_when_clientCancelsElapsedVisit() throws Exception {
            // Past 2-item visit: items run [-3h,-2h) and [-2h,-1h) so the LAST item's endsAt (-1h) < now.
            IndependentVisit v = seedIndependentVisit("elapsed", 2, pastStart());

            ResponseEntity<String> resp = patch(v.clientToken(), v.id(), "cancel",
                    "{\"clientCancellationNote\":\"передумала\"}");

            assertElapsedConflict(resp);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertThat(statusChangedCount(v.id()))
                    .as("a rejected elapsed cancel enqueues nothing")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("cancel — a FOREIGN client cancelling an elapsed visit gets a uniform 403 with NO "
                + "elapsed oracle in the body (ownership is checked before the elapsed guard)")
        void should_return403WithoutOracle_when_foreignClientCancelsElapsedVisit() throws Exception {
            IndependentVisit v = seedIndependentVisit("elapsed-idor", 2, pastStart());
            String strangerEmail = "appt-mx-elapsed-stranger-" + System.nanoTime() + "@beautica.test";
            fixtures.createUser(strangerEmail, "CLIENT", null);
            String strangerToken = fixtures.tokenFor(strangerEmail);

            ResponseEntity<String> resp = patch(strangerToken, v.id(), "cancel", "{}");

            assertThat(resp.getStatusCode())
                    .as("ownership is enforced before the elapsed guard — a non-owner gets a uniform 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(resp.getBody() == null ? "" : resp.getBody())
                    .as("the elapsed guard must not become an oracle for a non-owner")
                    .doesNotContain(BookingElapsedException.ERROR_CODE);
            assertVisitStatus(v.id(), "CONFIRMED");
        }

        @Test
        @DisplayName("complete — the provider may still complete an ELAPSED visit → COMPLETED (the "
                + "elapsed guard is client-only; resolving a past visit is the provider's job)")
        void should_allowProviderCompleteElapsedVisit() throws Exception {
            IndependentVisit v = seedIndependentVisit("elapsed-provider", 2, pastStart());

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "complete", null);

            assertThat(resp.getStatusCode())
                    .as("the provider is NOT elapsed-guarded — completing a past visit must succeed")
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "COMPLETED");
            assertAllItemsStatus(v.id(), "COMPLETED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("decline — the provider may decline an ELAPSED visit → DECLINED (decline has no "
                + "temporal guard — a provider may resolve a past visit by declining it, not only via "
                + "complete/not-complete)")
        void should_allowProviderDeclineElapsedVisit() throws Exception {
            IndependentVisit v = seedIndependentVisit("elapsed-decline", 2, pastStart());

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "decline",
                    "{\"providerComment\":\"Клієнт не з'явився\"}");

            assertThat(resp.getStatusCode())
                    .as("declining an already-elapsed visit must succeed — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "DECLINED");
            assertAllItemsStatus(v.id(), "DECLINED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("no-show — the provider may mark an ELAPSED visit a no-show → NOT_COMPLETED "
                + "(same elapsed predicate as complete, satisfied by a past visit)")
        void should_allowProviderMarkNoShowElapsedVisit() throws Exception {
            IndependentVisit v = seedIndependentVisit("elapsed-noshow", 2, pastStart());

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "not-complete", null);

            assertThat(resp.getStatusCode())
                    .as("marking an elapsed visit a no-show must succeed — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "NOT_COMPLETED");
            assertAllItemsStatus(v.id(), "NOT_COMPLETED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("no-show — the provider may also mark a FUTURE (not-yet-started) visit a "
                + "no-show → NOT_COMPLETED (not-complete has no temporal guard)")
        void should_allowProviderMarkNoShowFutureVisit() throws Exception {
            IndependentVisit v = seedIndependentVisit("future-noshow", 2, futureStart());

            ResponseEntity<String> resp = patch(v.masterToken(), v.id(), "not-complete", null);

            assertThat(resp.getStatusCode())
                    .as("marking a not-yet-started visit a no-show must succeed — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertVisitStatus(v.id(), "NOT_COMPLETED");
            assertAllItemsStatus(v.id(), "NOT_COMPLETED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        /**
         * Regression test for {@code AppointmentTransitionService#assertVisitNotElapsedForClient}'s
         * {@code max(endsAt)} reduction over ALL items, terminal included (phase 30.1/30.7 "the
         * endsAt defect") — pinned here at the CLIENT whole-visit CANCEL guard specifically.
         * {@code AppointmentItemRescheduleIT#should_reportHeaderEndsAtAsTerminalSibling_when_terminalSiblingEndsLast}
         * already pins this reduction for the READ-side {@code AppointmentDetailResponse} projection,
         * but nothing previously exercised it for this WRITE-side elapsed guard, which is a distinct
         * code path ({@code assertVisitNotElapsedForClient}, not the response mapper) that could
         * regress independently — e.g. a future refactor that narrows the guard's stream to only
         * still-CONFIRMED items (a natural-looking "simplification" that would silently start
         * rejecting exactly the case below).
         */
        @Test
        @DisplayName("cancel — a terminal (DECLINED) sibling whose window still extends into the "
                + "future keeps the WHOLE visit reported as NOT elapsed, even though the only "
                + "remaining CONFIRMED item's own window already ended — max(endsAt) is computed over "
                + "ALL items, terminal included, not just the CONFIRMED ones")
        void should_allowClientCancel_when_terminalSiblingOutlastsTheOnlyRemainingConfirmedItem()
                throws Exception {
            // Both items start elapsed: item0 [-3h,-2h), item1 [-2h,-1h).
            IndependentVisit v = seedIndependentVisit("terminal-outlasts", 2, pastStart());
            UUID item0 = firstChildOf(v.id());
            UUID item1 = secondChildOf(v.id());

            // Push item1 — the later item — into the FUTURE (bypassing HTTP, mirrors the
            // markOngoing/markElapsed precedent elsewhere in this package): its OWN endsAt is now
            // ahead of "now" while item0 stays elapsed at -2h. Once item1 is declined below, it is
            // the ONLY item whose endsAt is still in the future, yet it is no longer CONFIRMED —
            // exactly the "terminal sibling outlasts the last CONFIRMED item" shape.
            OffsetDateTime futureWindow = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            jdbcTemplate.update(
                    "UPDATE bookings SET starts_at = ?, ends_at = ? WHERE id = ?",
                    futureWindow, futureWindow.plusHours(1), item1);

            ResponseEntity<String> declineResp = patchServiceDecline(v.masterToken(), v.id(), item1, null);
            assertThat(declineResp.getStatusCode())
                    .as("provider decline of item1 must succeed — body: %s", declineResp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(item1)).isEqualTo("DECLINED");
            // item0 remains CONFIRMED, so the header never collapses to DECLINED.
            assertVisitStatus(v.id(), "CONFIRMED");

            // item0 (the ONLY remaining CONFIRMED item) already ended two hours ago. A regression
            // that reduced max(endsAt) over only still-CONFIRMED items would report this visit as
            // elapsed and reject the cancel below with 409. The locked design includes the
            // now-DECLINED item1 — whose endsAt is still an hour in the future — in that max, so the
            // visit must NOT be considered elapsed.
            ResponseEntity<String> cancelResp = patch(v.clientToken(), v.id(), "cancel", "{}");

            assertThat(cancelResp.getStatusCode())
                    .as("the visit must NOT be reported as elapsed — the terminal sibling's future "
                            + "endsAt keeps max(endsAt) in the future even though the sole CONFIRMED "
                            + "item already ended — body: %s", cancelResp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(item0))
                    .as("the client cancel must actually apply to the still-CONFIRMED item0")
                    .isEqualTo("CANCELLED");
            assertThat(childStatus(item1))
                    .as("the already-terminal item1 is untouched by the cancel — transitionItems "
                            + "only moves still-CONFIRMED items")
                    .isEqualTo("DECLINED");
            assertVisitStatus(v.id(), "CANCELLED");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Per-service (per-child) decline authority matrix —
    // PATCH /appointments/{id}/services/{bookingId}/decline
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Per-service decline — authority matrix")
    class PerServiceDeclineAuthorization {

        @Test
        @DisplayName("allow — the INDEPENDENT_MASTER who owns the visit declines one child; that child → "
                + "DECLINED, the sibling stays CONFIRMED, the header stays CONFIRMED, one STATUS_CHANGED")
        void should_declineChild_when_owningIndependentMaster() throws Exception {
            IndependentVisit v = seedIndependentVisit("psd-im-owner", 2, futureStart());
            UUID child0 = firstChildOf(v.id());
            UUID child1 = secondChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(v.masterToken(), v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("the visit's own independent master may decline one of its services — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(child0)).isEqualTo("DECLINED");
            assertThat(childStatus(child1)).as("the sibling stays CONFIRMED").isEqualTo("CONFIRMED");
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("allow — the SALON_OWNER of the visit's master declines one child of a salon visit; "
                + "that child → DECLINED, the sibling stays CONFIRMED, the header stays CONFIRMED")
        void should_declineChild_when_salonOwnerOfTheMaster() throws Exception {
            SalonVisit v = seedSalonVisit("psd-owner", 2, futureStart());
            UUID child0 = firstChildOf(v.id());
            UUID child1 = secondChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(v.ownerToken(), v.id(), child0,
                    "{\"providerComment\":\"Цю послугу того дня не робимо\"}");

            assertThat(resp.getStatusCode())
                    .as("the salon owner may decline one service of their salon-master's visit — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(child0)).isEqualTo("DECLINED");
            assertThat(childStatus(child1)).isEqualTo("CONFIRMED");
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("allow — a SALON_ADMIN assigned to the visit's salon declines one child; that child "
                + "→ DECLINED, the sibling stays CONFIRMED (SALON_ADMIN shares the provider authority)")
        void should_declineChild_when_salonAdminAssignedToTheSalon() throws Exception {
            SalonVisit v = seedSalonVisit("psd-admin", 2, futureStart());
            String adminEmail = "appt-mx-psd-admin-" + System.nanoTime() + "@beautica.test";
            fixtures.createUser(adminEmail, "SALON_ADMIN", v.salonId()); // assigned to THIS salon
            String adminToken = fixtures.tokenFor(adminEmail);
            UUID child0 = firstChildOf(v.id());
            UUID child1 = secondChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(adminToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("a salon admin assigned to the salon may decline one service — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(child0)).isEqualTo("DECLINED");
            assertThat(childStatus(child1)).isEqualTo("CONFIRMED");
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("deny 403 — the read-only SALON_MASTER declining a service of their OWN visit is "
                + "rejected by the role gate; both children stay CONFIRMED, no notification")
        void should_return403_when_salonMasterDeclinesChild() throws Exception {
            SalonVisit v = seedSalonVisit("psd-master-ro", 2, futureStart());
            String salonMasterToken = fixtures.tokenFor(v.masterEmail());
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(salonMasterToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("SALON_MASTER is read-only — excluded from the provider role gate → 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("deny 403 — a CLIENT (even the visit's OWN client) hitting the provider-only "
                + "per-service decline endpoint is rejected by the role gate; nothing changes")
        void should_return403_when_clientDeclinesChild() throws Exception {
            IndependentVisit v = seedIndependentVisit("psd-client", 2, futureStart());
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(v.clientToken(), v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("per-service decline is provider-only — a CLIENT must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("deny 403 — a FOREIGN independent master (no authority over the visit's master) "
                + "declining a child is rejected by the ownership guard; nothing changes")
        void should_return403_when_foreignIndependentMasterDeclinesChild() throws Exception {
            IndependentVisit v = seedIndependentVisit("psd-foreign-im", 2, futureStart());
            String foreignEmail = "appt-mx-psd-foreign-im-" + System.nanoTime() + "@beautica.test";
            fixtures.createIndependentMaster(foreignEmail);
            String foreignToken = fixtures.tokenFor(foreignEmail);
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(foreignToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("a provider with no authority over the visit's master must be forbidden")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("deny 403 — a FOREIGN salon owner (owns a different salon) declining a child of "
                + "this salon's visit is rejected by the ownership guard; nothing changes")
        void should_return403_when_foreignSalonOwnerDeclinesChild() throws Exception {
            SalonVisit v = seedSalonVisit("psd-foreign-owner", 2, futureStart());
            BookingTestFixtures.SalonFixture other =
                    fixtures.createSalon("appt-mx-psd-foreign-owner-" + System.nanoTime() + "@beautica.test");
            String foreignOwnerToken = fixtures.tokenFor(other.ownerEmail());
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(foreignOwnerToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("a salon owner with no authority over the visit's salon must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /** A seeded independent-master CONFIRMED visit plus the owning client + master tokens. */
    private record IndependentVisit(UUID id, String clientToken, String masterToken) {}

    /** A seeded salon-master CONFIRMED visit plus the salon owner + salon-master identities. */
    private record SalonVisit(UUID id, UUID salonId, String ownerToken, String masterEmail) {}

    /**
     * Seeds a CONFIRMED independent-master visit directly: one appointment header (salon_id NULL) plus
     * {@code itemCount} contiguous one-hour CONFIRMED bookings, each a distinct master-service, chained
     * from {@code firstStart}. Returns the header id and both actors' tokens.
     */
    private IndependentVisit seedIndependentVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        String masterEmail = "appt-mx-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-mx-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createIndependentMasterService(masterId);
        }
        UUID appointmentId = seedVisitRows(clientId, masterId, null, serviceIds, firstStart);
        return new IndependentVisit(appointmentId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

    /**
     * Seeds a CONFIRMED salon-master visit: a full salon graph (owner + salon + SALON_MASTER master),
     * an appointment header with salon_id set, and {@code itemCount} contiguous CONFIRMED salon-service
     * items — each also carrying salon_id (mirrors AppointmentService, which stamps master.getSalon()
     * onto both header and items).
     */
    private SalonVisit seedSalonVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("appt-mx-" + tag + "-owner-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "appt-mx-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createSalonService(salon.salonId(), salon.masterId());
        }
        UUID appointmentId = seedVisitRows(clientId, salon.masterId(), salon.salonId(), serviceIds, firstStart);
        return new SalonVisit(appointmentId, salon.salonId(),
                fixtures.tokenFor(salon.ownerEmail()), salon.masterEmail());
    }

    /**
     * Inserts the {@code appointments} header (APP, CONFIRMED) and one CONFIRMED {@code bookings} row
     * per service, chained back-to-back in one-hour blocks from {@code firstStart}. {@code salonId}
     * (nullable) is stamped on the header and every item. Contiguous, non-overlapping windows satisfy
     * the {@code no_overlapping_bookings} EXCLUDE constraint.
     */
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

    /** A clearly-future anchor (2 days out, 10:00 UTC) so no visit is accidentally elapsed. */
    private static OffsetDateTime futureStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    /** A past anchor so a 2-item visit runs [-3h,-2h)+[-2h,-1h): the last item's endsAt (-1h) is elapsed. */
    private static OffsetDateTime pastStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
    }

    private ResponseEntity<String> patch(String token, UUID appointmentId, String action, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/" + action, HttpMethod.PATCH, entity, String.class);
    }

    /** PATCH the per-service decline route {@code /appointments/{id}/services/{bookingId}/decline}. */
    private ResponseEntity<String> patchServiceDecline(String token, UUID appointmentId, UUID bookingId, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, entity, String.class);
    }

    private UUID firstChildOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1",
                UUID.class, appointmentId);
    }

    private UUID secondChildOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at OFFSET 1 LIMIT 1",
                UUID.class, appointmentId);
    }

    private String childStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private void assertElapsedConflict(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode())
                .as("an elapsed client cancel must map to 409 Conflict — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<BookingElapsedResponse> parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<BookingElapsedResponse>>() {});
        assertThat(parsed.success()).as("elapsed conflict — success must be false").isFalse();
        assertThat(parsed.data().code())
                .as("data.code must be the stable BOOKING_ALREADY_ELAPSED constant")
                .isEqualTo(BookingElapsedException.ERROR_CODE)
                .isEqualTo("BOOKING_ALREADY_ELAPSED");
    }

    private void assertVisitStatus(UUID appointmentId, String expected) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
        assertThat(status).as("appointment header status").isEqualTo(expected);
    }

    private void assertAllItemsStatus(UUID appointmentId, String expected, int expectedCount) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                String.class, appointmentId);
        assertThat(statuses)
                .as("every chained item must move in lockstep with the header")
                .hasSize(expectedCount)
                .allSatisfy(s -> assertThat(s).isEqualTo(expected));
    }

    private Map<String, Object> header(UUID appointmentId) {
        return jdbcTemplate.queryForMap(
                "SELECT cancellation_reason, client_cancellation_note, provider_comment "
                        + "FROM appointments WHERE id = ?", appointmentId);
    }

    private List<Map<String, Object>> items(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT cancellation_reason, client_cancellation_note, provider_comment, salon_id "
                        + "FROM bookings WHERE appointment_id = ? ORDER BY starts_at", appointmentId);
    }

    private Long statusChangedCount(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId);
    }
}
