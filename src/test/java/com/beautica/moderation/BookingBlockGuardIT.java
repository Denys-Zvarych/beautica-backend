package com.beautica.moderation;

import com.beautica.common.exception.BookingUnavailableException;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 398 D1/D4/D5/D8 — the APP (CLIENT account) create paths against real Postgres and the real security chain:
 * {@code POST /bookings} and {@code POST /appointments} answer {@code 409 BOOKING_UNAVAILABLE} when the client's
 * account is blocked in the TARGET's scope (independent master, or the whole salon of the booked master); other
 * providers, unblock, STAFF walk-ins and no cross-type match from a GUEST block on the client's profile phone are
 * unaffected. Existing future bookings are DECLINED by the block (phase 398 user decision 2026-10-28, supersedes D5 —
 * the cascade itself is pinned by {@link BlockCancelsFutureBookingsIT}); these rows only pin what the client sees.
 */
@DisplayName("Booking block guard — APP create paths (phase 398 IT)")
class BookingBlockGuardIT extends AbstractBookingBlockIT {

    private static final String PHONE = "+380671112233";

    private record BlockedClient(UUID userId, String token) {}

    @Test
    @DisplayName("independent master's block → POST /bookings 409 BOOKING_UNAVAILABLE (master wording), nothing created")
    void should_reject_when_independentMasterBlockedClient() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(master.masterId());
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        block(tokenForUser(master.userId()), insertIndependentBooking(client, master.masterId()));

        ResponseEntity<String> resp = appBook(tokenFor(email), master.masterId(), service, slot(3, 12));

        assertUnavailable(resp, BookingUnavailableException.MASTER_MESSAGE);
        assertThat(countBookingsOf(master.masterId(), "CONFIRMED")).isZero();
    }

    @Test
    @DisplayName("salon block (made on master A's booking) refuses a booking with salon master B — scope is the "
            + "target's salon")
    void should_reject_when_salonMasterBookingInBlockedSalon() throws Exception {
        SalonFixture salon = createSalon();
        UUID masterB = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon.salonId());
        UUID serviceB = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ?", UUID.class, masterB);
        BlockedClient client = salonBlockedClient(salon);

        ResponseEntity<String> resp = appBook(client.token(), masterB, serviceB, slot(3, 12));

        assertUnavailable(resp, BookingUnavailableException.SALON_MESSAGE);
        assertThat(countBookingsOf(masterB, "CONFIRMED")).isZero();
    }

    @Test
    @DisplayName("multi-service visit: POST /appointments 409 BOOKING_UNAVAILABLE, no appointment or item created")
    void should_reject_when_multiServiceVisitInBlockedScope() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID first = makeIndependentBookable(master.masterId());
        UUID second = addIndependentService(master.masterId());
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        block(tokenForUser(master.userId()), insertIndependentBooking(client, master.masterId()));

        ResponseEntity<String> resp = appVisit(tokenFor(email), master.masterId(), List.of(first, second),
                slot(3, 10));

        assertUnavailable(resp, BookingUnavailableException.MASTER_MESSAGE);
        assertThat(countBookingsOf(master.masterId(), "CONFIRMED")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM appointments", Integer.class)).isZero();
    }

    @Test
    @DisplayName("salon block refuses a multi-service visit with another salon master too")
    void should_rejectVisit_when_salonMasterVisitInBlockedSalon() throws Exception {
        SalonFixture salon = createSalon();
        UUID masterB = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon.salonId());
        UUID serviceB = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ?", UUID.class, masterB);
        BlockedClient client = salonBlockedClient(salon);

        ResponseEntity<String> resp = appVisit(client.token(), masterB, List.of(serviceB, serviceB), slot(3, 10));

        assertUnavailable(resp, BookingUnavailableException.SALON_MESSAGE);
    }

    @Test
    @DisplayName("no cross-type: a GUEST block of a phone equal to the client's profile phone does not refuse APP")
    void should_notReject_when_guestBlockMatchesClientProfilePhone() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        block(tokenFor(salon.ownerEmail()), insertGuestBooking(salon, "LINK", PHONE));
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        setProfilePhone(client, PHONE);

        ResponseEntity<String> resp = appBook(tokenFor(email), salon.masterId(), service, slot(3, 12));

        assertCreated(resp);
    }

    @Test
    @DisplayName("another provider is unaffected: a salon-blocked client still books an independent master")
    void should_allowBooking_when_otherProviderBooked() throws Exception {
        SalonFixture salon = createSalon();
        BlockedClient client = salonBlockedClient(salon);
        IndependentMaster other = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(other.masterId());

        ResponseEntity<String> resp = appBook(client.token(), other.masterId(), service, slot(3, 12));

        assertCreated(resp);
    }

    @Test
    @DisplayName("unblock → the same booking succeeds")
    void should_allowBooking_when_unblocked() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(master.masterId());
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        String masterToken = tokenForUser(master.userId());
        UUID blockId = block(masterToken, insertIndependentBooking(client, master.masterId()));
        String clientToken = tokenFor(email);
        assertUnavailable(appBook(clientToken, master.masterId(), service, slot(3, 12)),
                BookingUnavailableException.MASTER_MESSAGE);

        ResponseEntity<String> unblock = exchange(HttpMethod.DELETE, BLOCKS_URL + "/" + blockId, masterToken);
        ResponseEntity<String> resp = appBook(clientToken, master.masterId(), service, slot(3, 12));

        assertThat(unblock.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertCreated(resp);
    }

    @Test
    @DisplayName("an existing future booking is DECLINED by the block (phase 398 user decision, supersedes D5); the "
            + "client can no longer cancel it")
    void should_declineExistingBooking_when_clientBlockedAfterBooking() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        String clientToken = tokenFor(email);
        ResponseEntity<String> created = appBook(clientToken, salon.masterId(), service, slot(3, 12));
        assertCreated(created);
        UUID existing = UUID.fromString(readTree(created).path("data").path("id").asText());
        block(tokenFor(salon.ownerEmail()), insertCompletedBooking(client, salon));

        String status = jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, existing);
        ResponseEntity<String> cancel = restTemplate.exchange("/api/v1/bookings/" + existing + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>("{\"cancellationReason\":\"CLIENT_CANCELLED\"}",
                        jsonHeaders(clientToken)), String.class);

        assertThat(status).isEqualTo("DECLINED");
        assertThat(cancel.getStatusCode().is4xxClientError()).as(cancel.getBody()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, existing))
                .isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("STAFF walk-in ignores blocks: the owner books a walk-in with a blocked guest phone (D8)")
    void should_ignoreBlock_when_staffWalkInCreated() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String ownerToken = tokenFor(salon.ownerEmail());
        block(ownerToken, insertGuestBooking(salon, "LINK", PHONE));
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("masterServiceIds").add(service.toString());
        body.put("startsAt", slot(3, 12).toOffsetDateTime().toString());
        body.putObject("guest").put("name", "Олена").put("surname", "Коваль").put("phone", PHONE);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/masters/" + salon.masterId() + "/bookings",
                HttpMethod.POST, new HttpEntity<>(body.toString(), jsonHeaders(ownerToken)), String.class);

        assertCreated(resp);
    }

    @Test
    @DisplayName("BOOKING_UNAVAILABLE body parity: the APP and the LINK guest refusals in one salon are identical")
    void should_returnIdenticalBody_when_appAndGuestRefusedInSameSalon() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String salonSlug = setSalonSlug(salon.salonId());
        BlockedClient client = salonBlockedClient(salon);
        block(tokenFor(salon.ownerEmail()), insertGuestBooking(salon, "LINK", PHONE));

        ResponseEntity<String> app = appBook(client.token(), salon.masterId(), service, slot(3, 12));
        ResponseEntity<String> guest = guestBookSalon(guestTokenProvider.generate(PHONE), salonSlug,
                salon.masterId(), List.of(service), slot(3, 12));

        assertUnavailable(app, BookingUnavailableException.SALON_MESSAGE);
        assertUnavailable(guest, BookingUnavailableException.SALON_MESSAGE);
        assertThat(guest.getBody()).isEqualTo(app.getBody());
    }

    @Test
    @DisplayName("POST /bookings retry with an Idempotency-Key minted before the block: the original was DECLINED by "
            + "the block, so there is nothing active to replay — 409, no new row; a fresh key is refused too")
    void should_refuseRetry_when_preBlockBookingDeclinedByBlock() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(master.masterId());
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        String clientToken = tokenFor(email);
        String body = singleBody(master.masterId(), service, slot(3, 12));
        ResponseEntity<String> first = idempotentPost("/api/v1/bookings", clientToken, "pre-block-key-1", body);
        assertCreated(first);
        UUID original = UUID.fromString(readTree(first).path("data").path("id").asText());
        block(tokenForUser(master.userId()), insertIndependentBooking(client, master.masterId()));

        ResponseEntity<String> replay = idempotentPost("/api/v1/bookings", clientToken, "pre-block-key-1", body);
        ResponseEntity<String> fresh = idempotentPost("/api/v1/bookings", clientToken, "post-block-key-2",
                singleBody(master.masterId(), service, slot(4, 12)));

        assertUnavailable(replay, BookingUnavailableException.MASTER_MESSAGE);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, original))
                .isEqualTo("DECLINED");
        assertThat(countBookingsOf(master.masterId(), "CONFIRMED")).as("no new booking row").isZero();
        assertUnavailable(fresh, BookingUnavailableException.MASTER_MESSAGE);
    }

    @Test
    @DisplayName("POST /appointments retry with an Idempotency-Key minted before the block: the original visit was "
            + "DECLINED by the block — 409, no second visit; a fresh key is refused too")
    void should_refuseVisitRetry_when_preBlockVisitDeclinedByBlock() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID first = makeIndependentBookable(master.masterId());
        UUID second = addIndependentService(master.masterId());
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        String clientToken = tokenFor(email);
        String body = visitBody(master.masterId(), List.of(first, second), slot(3, 10));
        ResponseEntity<String> created = idempotentPost("/api/v1/appointments", clientToken, "pre-block-visit-1",
                body);
        assertCreated(created);
        UUID original = UUID.fromString(readTree(created).path("data").path("id").asText());
        block(tokenForUser(master.userId()), insertIndependentBooking(client, master.masterId()));

        ResponseEntity<String> replay = idempotentPost("/api/v1/appointments", clientToken, "pre-block-visit-1",
                body);
        ResponseEntity<String> fresh = idempotentPost("/api/v1/appointments", clientToken, "post-block-visit-2",
                visitBody(master.masterId(), List.of(first, second), slot(4, 10)));

        assertUnavailable(replay, BookingUnavailableException.MASTER_MESSAGE);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, original))
                .isEqualTo("DECLINED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE client_id = ?", Integer.class, client))
                .as("no second visit").isEqualTo(1);
        assertThat(countBookingsOf(master.masterId(), "CONFIRMED")).as("both items declined").isZero();
        assertUnavailable(fresh, BookingUnavailableException.MASTER_MESSAGE);
    }

    @Test
    @DisplayName("a booking made before the block stays readable by the client: GET /bookings/{id} 200, DECLINED")
    void should_keepDeclinedBookingVisible_when_clientBlockedAfterBooking() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(master.masterId());
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        String clientToken = tokenFor(email);
        ResponseEntity<String> created = appBook(clientToken, master.masterId(), service, slot(3, 12));
        assertCreated(created);
        UUID existing = UUID.fromString(readTree(created).path("data").path("id").asText());
        block(tokenForUser(master.userId()), insertIndependentBooking(client, master.masterId()));

        ResponseEntity<String> read = exchange(HttpMethod.GET, "/api/v1/bookings/" + existing, clientToken);

        assertThat(read.getStatusCode()).as(read.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(readTree(read).path("data").path("id").asText()).isEqualTo(existing.toString());
        assertThat(readTree(read).path("data").path("status").asText()).isEqualTo("DECLINED");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A CLIENT with a COMPLETED booking at the salon's master A, blocked by the owner (salon scope). */
    private BlockedClient salonBlockedClient(SalonFixture salon) throws Exception {
        String email = unique("client");
        UUID client = createUser(email, "CLIENT", null);
        block(tokenFor(salon.ownerEmail()), insertCompletedBooking(client, salon));
        return new BlockedClient(client, tokenFor(email));
    }

    private String tokenForUser(UUID userId) throws Exception {
        return tokenFor(jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId));
    }

    private ResponseEntity<String> idempotentPost(String path, String token, String key, String body) {
        HttpHeaders headers = jsonHeaders(token);
        headers.set("Idempotency-Key", key);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String singleBody(UUID masterId, UUID masterServiceId, java.time.ZonedDateTime startsAt) {
        return objectMapper.createObjectNode()
                .put("masterId", masterId.toString())
                .put("masterServiceId", masterServiceId.toString())
                .put("startsAt", startsAt.toOffsetDateTime().toString())
                .toString();
    }

    private String visitBody(UUID masterId, List<UUID> masterServiceIds, java.time.ZonedDateTime startsAt) {
        ObjectNode body = objectMapper.createObjectNode().put("masterId", masterId.toString());
        var ids = body.putArray("masterServiceIds");
        masterServiceIds.forEach(id -> ids.add(id.toString()));
        return body.put("startsAt", startsAt.toOffsetDateTime().toString()).toString();
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
