package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.TimeZones;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 338 QA follow-up — gaps the phase's own {@code ClientAccountDeletionFutureBookingsIT} /
 * {@code ClientAccountSelfDeleteOutboxCoherenceIT} left uncovered (backend-qa audit, 2026-09-28):
 *
 * <ul>
 *   <li>the PROVIDER-side LIST endpoints — {@code GET /bookings/me} (an independent master's own
 *       archive) and {@code GET /bookings/salon/{salonId}} (the owner's board/history) — rather
 *       than only the single-booking detail read {@code ClientAccountDeletionFutureBookingsIT}
 *       already covers; both must render the kept, cancelled booking with the sentinel and no
 *       client PII, and must surface the self-delete cancellation note;</li>
 *   <li>a client with TWO SEPARATE visits (salon scheduling is per master — decision {@code
 *       project_salon_scheduling_is_per_master}, so a single {@code Appointment} can never span
 *       two masters; the closest real-world equivalent of "two masters in one cascade" is two
 *       independent visits) — proving {@code enqueueClientCancelledPerVisit}'s {@code
 *       groupingBy(visitKey())} genuinely partitions by visit rather than collapsing to one row or
 *       exploding to one row per leg, across MULTIPLE groups, not just one;</li>
 *   <li>an ELAPSED-but-still-{@code CONFIRMED} booking (past {@code startsAt}, never closed) at
 *       self-delete time — outside the future-only cancel-cascade's {@code startsAt > now} scan,
 *       so it is untouched by the cancel step but IS swept into the uniform step-6 detach loop;
 *       the 2026-09-28 audit pinned this as "ends CONFIRMED + detached", and this class proves that
 *       exact persisted state AND that a provider can still complete/decline/not-complete it
 *       afterwards without error;</li>
 *   <li>re-registration with the SAME email after a self-delete — the new account must never see
 *       the old (kept, detached) bookings in its own lists.</li>
 * </ul>
 */
@DisplayName("DELETE /api/v1/users/me — Phase 338 provider-visibility, multi-visit, elapsed-booking "
        + "and re-registration gaps")
class ClientAccountDeletionProviderVisibilityIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    // ── 1a. independent master's own list ───────────────────────────────────

    @Test
    @DisplayName("an independent master's own GET /bookings/me?status=CANCELLED shows the "
            + "self-deleted client's kept booking with the sentinel name, null clientId, the "
            + "self-delete cancellation note, and no raw client email/phone anywhere in the body — "
            + "regression: if the provider LIST projection ever diverges from the single-booking "
            + "detail read, this fails even though ClientAccountDeletionFutureBookingsIT stays green")
    void should_showCancelledSentinelBooking_onIndependentMastersOwnList() throws Exception {
        String masterEmail = "pvi-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        String masterToken = fixtures.tokenFor(masterEmail);

        String clientEmail = "pvi-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = postBooking(clientToken, masterId, masterServiceId, startsAt);

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> listResp = restTemplate.exchange(
                "/api/v1/bookings/me?status=CANCELLED", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(masterToken)), String.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode row = findById(listResp.getBody(), bookingId);
        assertThat(row)
                .as("the kept booking must appear on the master's own CANCELLED list; body: %s",
                        listResp.getBody())
                .isNotNull();
        assertThat(row.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(row.path("clientId").isNull())
                .as("clientId must be null on the wire, never a fabricated id")
                .isTrue();
        assertThat(row.path("clientFirstName").asText())
                .as("the sentinel, never the real client name")
                .isEqualTo("Видалений клієнт");
        assertThat(row.path("clientLastName").isNull()).isTrue();
        assertThat(row.path("clientCancellationNote").asText())
                .as("the self-delete cancel note must survive onto the provider's LIST read too")
                .isEqualTo("Клієнт видалив акаунт.");
        assertThat(listResp.getBody())
                .as("no raw email of the deleted client leaks into the master's list response")
                .doesNotContain(clientEmail);
    }

    // ── 1b. salon owner's board ─────────────────────────────────────────────

    @Test
    @DisplayName("a salon owner's GET /bookings/salon/{salonId}?status=CANCELLED board shows the "
            + "self-deleted client's kept booking with the sentinel name, null clientId, the "
            + "self-delete cancellation note, and no raw client email anywhere in the body")
    void should_showCancelledSentinelBooking_onSalonOwnerBoard() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientEmail = emailOf(clientId);
        String clientToken = fixtures.tokenFor(clientEmail);
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", OffsetDateTime.now().plusDays(5));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        ResponseEntity<String> boardResp = restTemplate.exchange(
                "/api/v1/bookings/salon/" + salon.salonId() + "?status=CANCELLED", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(boardResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode row = findById(boardResp.getBody(), bookingId);
        assertThat(row)
                .as("the kept booking must appear on the salon owner's CANCELLED board; body: %s",
                        boardResp.getBody())
                .isNotNull();
        assertThat(row.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(row.path("clientId").isNull()).isTrue();
        assertThat(row.path("clientFirstName").asText()).isEqualTo("Видалений клієнт");
        assertThat(row.path("clientCancellationNote").asText()).isEqualTo("Клієнт видалив акаунт.");
        assertThat(boardResp.getBody())
                .as("no raw email of the deleted client leaks into the salon board response")
                .doesNotContain(clientEmail);
    }

    @Test
    @DisplayName("a salon ADMIN's GET /bookings/salon/{salonId}?status=CANCELLED board (the same "
            + "endpoint an owner reads) ALSO shows the kept, sentinel-detached booking — the "
            + "provider-visibility gate is per-salon, not per-role, so the owner-only test above "
            + "cannot stand in for the admin's own read")
    void should_showCancelledSentinelBooking_onSalonAdminBoard() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID adminId = csd.createSalonAdmin(salon);
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", OffsetDateTime.now().plusDays(5));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);

        String adminToken = fixtures.tokenFor(emailOf(adminId));
        ResponseEntity<String> boardResp = restTemplate.exchange(
                "/api/v1/bookings/salon/" + salon.salonId() + "?status=CANCELLED", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(adminToken)), String.class);
        assertThat(boardResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode row = findById(boardResp.getBody(), bookingId);
        assertThat(row)
                .as("the kept booking must appear on the salon ADMIN's CANCELLED board too; body: %s",
                        boardResp.getBody())
                .isNotNull();
        assertThat(row.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(row.path("clientId").isNull()).isTrue();
        assertThat(row.path("clientFirstName").asText()).isEqualTo("Видалений клієнт");
    }

    // ── 2. two SEPARATE visits (salon scheduling is per master — an appointment cannot span two
    //      masters, so this is the real-world shape of "two masters in one cascade") ────────────

    @Test
    @DisplayName("a client with TWO SEPARATE visits — a two-leg multi-service visit at master A "
            + "and a standalone booking at master B — gets exactly TWO CLIENT_CANCELLED outbox "
            + "rows, one per visit, each keyed to the correct aggregate: proves "
            + "enqueueClientCancelledPerVisit's groupingBy(visitKey()) partitions across MULTIPLE "
            + "groups correctly, not just the single-group case the phase's own tests already cover")
    void should_enqueueDistinctClientCancelledPerVisit_forTwoSeparateVisitsAtDifferentMasters()
            throws Exception {
        String masterAEmail = "pvi-mA-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = fixtures.createIndependentMaster(masterAEmail);
        UUID serviceA1 = fixtures.createIndependentMasterService(masterAId, "Service A1");
        UUID serviceA2 = fixtures.createIndependentMasterService(masterAId, "Service A2");
        fixtures.addWorkingHoursForEveryDay(masterAId);

        String masterBEmail = "pvi-mB-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = fixtures.createIndependentMaster(masterBEmail);
        UUID serviceB = fixtures.createIndependentMasterService(masterBId, "Service B");
        fixtures.addWorkingHoursForEveryDay(masterBId);

        String clientEmail = "pvi-2visit-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime visitStart = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(6).withHour(9).withMinute(0).withSecond(0).withNano(0);
        UUID appointmentId = postAppointment(clientToken, masterAId, List.of(serviceA1, serviceA2), visitStart);
        List<UUID> legIds = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);
        assertThat(legIds).hasSize(2);

        ZonedDateTime standaloneStart = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(7).withHour(15).withMinute(0).withSecond(0).withNano(0);
        UUID standaloneBookingId = postBooking(clientToken, masterBId, serviceB, standaloneStart);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Every booking in BOTH visits is cancelled and kept.
        for (UUID legId : legIds) {
            assertThat(csd.bookingStatusOf(legId)).isEqualTo("CANCELLED");
            assertThat(csd.clientIdOf(legId)).isNull();
        }
        assertThat(csd.bookingStatusOf(standaloneBookingId)).isEqualTo("CANCELLED");
        assertThat(csd.clientIdOf(standaloneBookingId)).isNull();

        // Exactly ONE representative CLIENT_CANCELLED row for the two-leg visit...
        assertThat(csd.clientCancelledOutboxCount(legIds.get(0), legIds.get(1)))
                .as("D12 — one CLIENT_CANCELLED row for the whole two-leg visit at master A")
                .isEqualTo(1);
        // ...and its representative is one of the visit's own legs, never the unrelated booking.
        assertThat(csd.clientCancelledOutboxAggregateIdAmong(legIds.get(0), legIds.get(1)))
                .isIn(legIds.get(0), legIds.get(1));

        // Exactly ONE CLIENT_CANCELLED row for the standalone booking at master B, keyed to
        // ITSELF (a visit of one — visitKey() == bookingId when appointmentId is null).
        assertThat(csd.clientCancelledOutboxCount(standaloneBookingId))
                .as("the standalone booking at master B is its own one-booking visit")
                .isEqualTo(1);
        assertThat(csd.clientCancelledOutboxAggregateIdAmong(standaloneBookingId))
                .isEqualTo(standaloneBookingId);

        // Across all THREE booking ids, exactly TWO CLIENT_CANCELLED rows exist — never one
        // (which would mean the two visits were wrongly merged into a single group) and never
        // three (which would mean grouping collapsed to per-leg instead of per-visit).
        assertThat(csd.clientCancelledOutboxCount(legIds.get(0), legIds.get(1), standaloneBookingId))
                .as("two visits => two CLIENT_CANCELLED rows, never one merged row and never "
                        + "three per-leg rows")
                .isEqualTo(2);
    }

    // ── 3. elapsed-but-CONFIRMED booking: untouched by the cancel cascade (startsAt > now scan
    //      excludes it), but swept into the uniform detach loop — pins the exact resulting state
    //      and that the provider can still resolve it afterwards ─────────────────────────────────

    @Test
    @DisplayName("an elapsed (past startsAt) but still-CONFIRMED, unresolved booking survives "
            + "self-delete AS CONFIRMED (the future-only cancel cascade never touches it) but IS "
            + "detached with the sentinel — then the provider can COMPLETE it without error")
    void should_keepElapsedConfirmedBookingConfirmedAndDetached_andAllowProviderComplete()
            throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", OffsetDateTime.now().minusHours(3));

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(csd.bookingStatusOf(bookingId))
                .as("an elapsed CONFIRMED booking is OUTSIDE the future-only cancel cascade's "
                        + "startsAt > now scan — it must stay CONFIRMED, never CANCELLED")
                .isEqualTo("CONFIRMED");
        assertThat(csd.clientIdOf(bookingId))
                .as("but it IS still swept into the uniform step-6 detach loop")
                .isNull();
        assertThat(csd.guestNameOf(bookingId)).isEqualTo("Видалений клієнт");

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        ResponseEntity<Void> completeResp = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), Void.class);
        assertThat(completeResp.getStatusCode())
                .as("the provider must be able to close out a detached, client-less elapsed "
                        + "booking without a 500/NPE on the missing client")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingStatusOf(bookingId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("...and the provider can DECLINE an elapsed detached booking without error")
    void should_allowProviderDecline_onElapsedConfirmedDetachedBooking() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", OffsetDateTime.now().minusHours(3));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);
        assertThat(csd.bookingStatusOf(bookingId)).isEqualTo("CONFIRMED");
        assertThat(csd.clientIdOf(bookingId)).isNull();

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        String body = objectMapper.writeValueAsString(Map.of("cancellationReason", "PROVIDER_UNAVAILABLE"));
        HttpHeaders headers = fixtures.bearerHeaders(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> declineResp = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), Void.class);
        assertThat(declineResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingStatusOf(bookingId)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("...and the provider can mark an elapsed detached booking NOT_COMPLETED (no-show) "
            + "without error")
    void should_allowProviderNotComplete_onElapsedConfirmedDetachedBooking() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", OffsetDateTime.now().minusHours(3));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);
        assertThat(csd.bookingStatusOf(bookingId)).isEqualTo("CONFIRMED");
        assertThat(csd.clientIdOf(bookingId)).isNull();

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        String body = objectMapper.writeValueAsString(Map.of("cancellationReason", "CLIENT_NO_SHOW"));
        HttpHeaders headers = fixtures.bearerHeaders(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> notCompleteResp = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), Void.class);
        assertThat(notCompleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingStatusOf(bookingId)).isEqualTo("NOT_COMPLETED");
    }

    // ── 4. re-registration with the SAME email ──────────────────────────────

    @Test
    @DisplayName("a NEW account registered with the SAME email after a self-delete sees NO kept "
            + "bookings in its own GET /bookings/me — the old (detached) rows must never resurface "
            + "as if they belonged to the new account")
    void should_showNoKeptBookings_toNewAccountRegisteredWithSameEmail() throws Exception {
        String sharedEmail = "pvi-reuse-" + System.nanoTime() + "@beautica.test";
        UUID oldClientId = csd.createUser(sharedEmail, "CLIENT", null, "Оксана", "Іванова");
        String oldClientToken = fixtures.tokenFor(sharedEmail);
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID keptBookingId = csd.insertBooking(oldClientId, salon, "CONFIRMED", OffsetDateTime.now().plusDays(5));

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(oldClientToken)), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(oldClientId)).isFalse();

        // The SAME email is free to reuse — the old users row was hard-deleted.
        UUID newClientId = csd.createUser(sharedEmail, "CLIENT", null, "Новий", "Клієнт");
        assertThat(newClientId).isNotEqualTo(oldClientId);
        String newClientToken = fixtures.tokenFor(sharedEmail);

        ResponseEntity<String> listResp = restTemplate.exchange(
                "/api/v1/bookings/me", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(newClientToken)), String.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(findById(listResp.getBody(), keptBookingId))
                .as("the new account (same email, different id) must not see the old client's "
                        + "kept booking; body: %s", listResp.getBody())
                .isNull();

        // The kept booking itself is unaffected by the new registration — still detached,
        // still pointing at nobody.
        assertThat(csd.clientIdOf(keptBookingId))
                .as("re-registering the email must never re-link the old booking to the new account")
                .isNull();
        assertThat(csd.bookingStatusOf(keptBookingId)).isEqualTo("CANCELLED");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JsonNode findById(String responseBody, UUID id) throws Exception {
        JsonNode rows = objectMapper.readTree(responseBody).path("data").path("data");
        for (JsonNode row : rows) {
            if (id.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        return null;
    }

    private UUID postBooking(String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceId", masterServiceId.toString(),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.exchange(
                "/api/v1/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode())
                .as("booking setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
    }

    private UUID postAppointment(
            String clientToken, UUID masterId, List<UUID> masterServiceIds, ZonedDateTime startsAt)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceIds", masterServiceIds.stream().map(UUID::toString).toList(),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.exchange(
                "/api/v1/appointments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode())
                .as("visit setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
