package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.beautica.client.provider.ProviderClientFixtures.Salon;
import com.beautica.common.security.PhoneHasher;
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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B518 (GST-D14, GST-80) — the registered client's SMS-VERIFIED phone on booking detail (clientPhone) and the client
 * card (phone), for the independent master / salon owner / assigned admin only; SALON_MASTER never; unverified never;
 * lists never; guestPhone keeps its legacy guest-only semantics.
 */
@DisplayName("Provider-visible registered client phone — B518")
class ProviderClientPhoneIT extends AbstractIntegrationTest {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";
    private static final String BOOKINGS = "/api/v1/bookings";
    private static final String VERIFIED_PHONE = "+380671234567";
    private static final String UNVERIFIED_PHONE = "+380931112233";
    private static final String GUEST_PHONE = "+380501112233";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;

    private ProviderClientFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    private UUID client(String phone, boolean verified) {
        UUID id = fx.client("Петро", "Сидоренко");
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = "
                + (verified ? "NOW()" : "NULL") + " WHERE id = ?", phone, id);
        return id;
    }

    private JsonNode ok(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private static boolean absent(JsonNode n) {
        return n.isMissingNode() || n.isNull();
    }

    private void openSchedule(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                + "VALUES (?, ?, DATE '2020-01-01', NULL)", scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update("INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                    + "VALUES (?, ?, ?, '08:00', '20:00')", UUID.randomUUID(), scheduleId, day);
        }
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private ResponseEntity<String> send(String token, HttpMethod method, String url, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(json, h), String.class);
    }

    @Test
    @DisplayName("IM, owner and admin see the VERIFIED registered phone on the card only; booking detail carries none (CLI-D08)")
    void should_showVerifiedPhone_when_viewerIsAudience() throws Exception {
        Salon s = fx.salon();
        Independent indep = fx.independent();
        UUID c = client(VERIFIED_PHONE, true);
        UUID salonBooking = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        UUID soloBooking = fx.booking(indep.masterId(), null, c, "COMPLETED", fx.at(-4));
        String adminEmail = fx.admin(s.salonId());

        JsonNode ownerDetail = ok(fx.get(fx.token(s.ownerEmail()), BOOKINGS + "/" + salonBooking));
        JsonNode adminDetail = ok(fx.get(fx.token(adminEmail), BOOKINGS + "/" + salonBooking));
        JsonNode soloDetail = ok(fx.get(fx.token(indep.email()), BOOKINGS + "/" + soloBooking));
        JsonNode ownerCard = ok(fx.get(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()) + "/users/" + c));
        JsonNode adminCard = ok(fx.get(fx.token(adminEmail), SALON_URL.formatted(s.salonId()) + "/users/" + c));
        JsonNode soloCard = ok(fx.get(fx.token(indep.email()), ME_URL + "/users/" + c));

        Map<String, JsonNode> details = new LinkedHashMap<>();
        details.put("owner detail", ownerDetail);
        details.put("admin detail", adminDetail);
        details.put("independent master detail", soloDetail);
        details.forEach((who, detail) -> {
            assertThat(absent(detail.path("clientPhone"))).as(who + " clientPhone").isTrue();
            assertThat(absent(detail.path("guestPhone"))).as(who + " guestPhone").isTrue();
            assertThat(detail.toString()).as(who + " body").doesNotContain(VERIFIED_PHONE.substring(4));
        });
        Map<String, JsonNode> cards = new LinkedHashMap<>();
        cards.put("owner card", ownerCard);
        cards.put("admin card", adminCard);
        cards.put("independent master card", soloCard);
        cards.forEach((who, card) ->
                assertThat(card.path("phone").asText()).as(who + " phone").isEqualTo(VERIFIED_PHONE));
    }

    @Test
    @DisplayName("detail + card: an UNVERIFIED registered phone is null for every audience role")
    void should_hideUnverifiedPhone_when_viewerIsAudience() throws Exception {
        Salon s = fx.salon();
        Independent indep = fx.independent();
        UUID c = client(UNVERIFIED_PHONE, false);
        UUID salonBooking = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        UUID soloBooking = fx.booking(indep.masterId(), null, c, "COMPLETED", fx.at(-4));
        String adminEmail = fx.admin(s.salonId());

        Map<String, ResponseEntity<String>> responses = new LinkedHashMap<>();
        responses.put("owner detail", fx.get(fx.token(s.ownerEmail()), BOOKINGS + "/" + salonBooking));
        responses.put("admin detail", fx.get(fx.token(adminEmail), BOOKINGS + "/" + salonBooking));
        responses.put("independent master detail", fx.get(fx.token(indep.email()), BOOKINGS + "/" + soloBooking));
        responses.put("owner card", fx.get(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()) + "/users/" + c));
        responses.put("admin card", fx.get(fx.token(adminEmail), SALON_URL.formatted(s.salonId()) + "/users/" + c));
        responses.put("independent master card", fx.get(fx.token(indep.email()), ME_URL + "/users/" + c));

        for (Map.Entry<String, ResponseEntity<String>> e : responses.entrySet()) {
            String who = e.getKey();
            JsonNode data = ok(e.getValue());
            assertThat(absent(data.path("clientPhone"))).as(who + " clientPhone").isTrue();
            assertThat(absent(data.path("phone"))).as(who + " phone").isTrue();
            assertThat(e.getValue().getBody()).as(who + " body").doesNotContain("931112233");
        }
    }

    @Test
    @DisplayName("SALON_MASTER sees no phone for registered-verified, registered-unverified or guest, on detail and card")
    void should_hidePhoneForEveryKind_when_viewerIsSalonMaster() throws Exception {
        Salon s = fx.salon();
        UUID verified = client(VERIFIED_PHONE, true);
        UUID unverified = client(UNVERIFIED_PHONE, false);
        UUID b1 = fx.booking(s.masterId(), s.salonId(), verified, "COMPLETED", fx.at(-5));
        UUID b2 = fx.booking(s.masterId(), s.salonId(), unverified, "COMPLETED", fx.at(-4));
        UUID b3 = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Майстра", "COMPLETED", fx.at(-3));
        String master = fx.token(s.masterEmail());

        List<ResponseEntity<String>> responses = List.of(
                fx.get(master, BOOKINGS + "/" + b1), fx.get(master, BOOKINGS + "/" + b2),
                fx.get(master, BOOKINGS + "/" + b3),
                fx.get(master, ME_URL + "/users/" + verified), fx.get(master, ME_URL + "/users/" + unverified),
                fx.get(master, ME_URL + "/guests/" + b3));

        for (ResponseEntity<String> resp : responses) {
            JsonNode data = ok(resp);
            assertThat(absent(data.path("clientPhone"))).isTrue();
            assertThat(absent(data.path("phone"))).isTrue();
            assertThat(absent(data.path("guestPhone"))).isTrue();
            assertThat(resp.getBody()).doesNotContain("671234567").doesNotContain("501112233");
        }
    }

    @Test
    @DisplayName("guest booking: booking detail carries no phone (GST-80); the card shows it")
    void should_showGuestPhoneOnCardOnly_when_guestBooking() throws Exception {
        Salon s = fx.salon();
        UUID b = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Салону", "COMPLETED", fx.at(-5));

        JsonNode detail = ok(fx.get(fx.token(s.ownerEmail()), BOOKINGS + "/" + b));
        JsonNode card = ok(fx.get(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()) + "/guests/" + b));

        assertThat(absent(detail.path("guestPhone"))).isTrue();
        assertThat(absent(detail.path("clientPhone"))).isTrue();
        assertThat(detail.toString()).doesNotContain(GUEST_PHONE.substring(4));
        assertThat(card.path("phone").asText()).isEqualTo(GUEST_PHONE);
    }

    @Test
    @DisplayName("another salon's admin gets 403/404 on detail and card, and no digits leak")
    void should_refuse_when_adminOfAnotherSalon() throws Exception {
        Salon s = fx.salon();
        Salon other = fx.salon();
        UUID c = client(VERIFIED_PHONE, true);
        UUID b = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        String foreignAdmin = fx.token(fx.admin(other.salonId()));

        ResponseEntity<String> detail = fx.get(foreignAdmin, BOOKINGS + "/" + b);
        ResponseEntity<String> card = fx.get(foreignAdmin, SALON_URL.formatted(s.salonId()) + "/users/" + c);

        assertThat(detail.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(card.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(detail.getBody()).doesNotContain("671234567");
        assertThat(card.getBody()).doesNotContain("671234567");
    }

    @Test
    @DisplayName("lists (client list B507, bookings board) carry no registered phone, even a verified one")
    void should_carryNoRegisteredPhone_when_listing() throws Exception {
        Salon s = fx.salon();
        UUID c = client(VERIFIED_PHONE, true);
        UUID b = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        String owner = fx.token(s.ownerEmail());

        List<ResponseEntity<String>> lists = List.of(
                fx.get(owner, SALON_URL.formatted(s.salonId())),
                fx.get(owner, BOOKINGS + "/salon/" + s.salonId() + "?size=50"));

        for (ResponseEntity<String> list : lists) {
            assertThat(list.getStatusCode()).as(list.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(list.getBody()).doesNotContain("671234567");
            objectMapper.readTree(list.getBody()).findValues("clientPhone")
                    .forEach(v -> assertThat(v.isNull()).isTrue());
        }
        assertThat(lists.get(1).getBody()).contains(b.toString());
    }

    @Test
    @DisplayName("provider reschedule answer (200) carries no registered phone, verified or not")
    void should_carryNoClientPhone_when_providerReschedulesRegisteredBooking() throws Exception {
        Salon s = fx.salon();
        openSchedule(s.masterId());
        UUID c = client(VERIFIED_PHONE, true);
        UUID b = fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", fx.at(2));
        ZonedDateTime newStart = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(4)
                .withHour(11).withMinute(0).withSecond(0).withNano(0);

        ResponseEntity<String> resp = send(fx.token(s.ownerEmail()), HttpMethod.PATCH, BOOKINGS + "/" + b + "/reschedule",
                "{\"newStartsAt\":\"" + newStart.toOffsetDateTime() + "\"}");

        JsonNode data = ok(resp);
        assertThat(data.path("id").asText()).isEqualTo(b.toString());
        assertThat(absent(data.path("clientPhone"))).as("clientPhone on reschedule answer, body=%s", resp.getBody()).isTrue();
        assertThat(absent(data.path("guestPhone"))).isTrue();
        assertThat(resp.getBody()).doesNotContain("671234567");
    }

    @Test
    @DisplayName("client create answer (201) carries no phone even when the client's own phone is verified")
    void should_carryNoClientPhone_when_clientCreatesBooking() throws Exception {
        Salon s = fx.salon();
        openSchedule(s.masterId());
        UUID c = client(VERIFIED_PHONE, true);
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        String json = "{\"masterId\":\"" + s.masterId() + "\",\"masterServiceId\":\""
                + fx.serviceOf(s.masterId(), s.salonId()) + "\",\"startsAt\":\"" + startsAt.toOffsetDateTime() + "\"}";

        ResponseEntity<String> resp = send(fx.token(emailOf(c)), HttpMethod.POST, BOOKINGS, json);

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("masterId").asText()).isEqualTo(s.masterId().toString());
        assertThat(absent(data.path("clientPhone"))).isTrue();
        assertThat(absent(data.path("guestPhone"))).isTrue();
        assertThat(resp.getBody()).doesNotContain("671234567");
    }

    @Test
    @DisplayName("a verified phone later replaced by an unverified one: neither number reaches detail or card")
    void should_hideBothNumbers_when_verifiedPhoneChangedToUnverified() throws Exception {
        Salon s = fx.salon();
        UUID c = client(VERIFIED_PHONE, true);
        UUID b = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NULL WHERE id = ?",
                UNVERIFIED_PHONE, c);
        String owner = fx.token(s.ownerEmail());

        ResponseEntity<String> detail = fx.get(owner, BOOKINGS + "/" + b);
        ResponseEntity<String> card = fx.get(owner, SALON_URL.formatted(s.salonId()) + "/users/" + c);

        for (ResponseEntity<String> resp : List.of(detail, card)) {
            JsonNode data = ok(resp);
            assertThat(absent(data.path("clientPhone"))).isTrue();
            assertThat(absent(data.path("phone"))).isTrue();
            assertThat(resp.getBody()).doesNotContain("931112233").doesNotContain("671234567");
        }
    }

    @Test
    @DisplayName("the owning CLIENT reads own booking without clientPhone; another CLIENT gets 403 with no phone")
    void should_hideClientPhone_when_viewerIsClient() throws Exception {
        Salon s = fx.salon();
        UUID c = client(VERIFIED_PHONE, true);
        UUID other = client(UNVERIFIED_PHONE, true);
        UUID b = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));

        ResponseEntity<String> own = fx.get(fx.token(emailOf(c)), BOOKINGS + "/" + b);
        ResponseEntity<String> foreign = fx.get(fx.token(emailOf(other)), BOOKINGS + "/" + b);

        JsonNode data = ok(own);
        assertThat(data.path("id").asText()).isEqualTo(b.toString());
        assertThat(absent(data.path("clientPhone"))).isTrue();
        assertThat(own.getBody()).doesNotContain("671234567");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreign.getBody()).doesNotContain("671234567");
    }

    @Test
    @DisplayName("GET /bookings/me (the CLIENT's own list) carries no clientPhone for a registered verified client")
    void should_carryNoClientPhone_when_clientListsOwnBookings() throws Exception {
        Salon s = fx.salon();
        UUID c = client(VERIFIED_PHONE, true);
        UUID b = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));

        ResponseEntity<String> resp = fx.get(fx.token(emailOf(c)), BOOKINGS + "/me?size=50");

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains(b.toString());
        assertThat(resp.getBody()).doesNotContain("671234567");
        objectMapper.readTree(resp.getBody()).findValues("clientPhone")
                .forEach(v -> assertThat(v.isNull()).as("clientPhone on /bookings/me").isTrue());
    }
}
