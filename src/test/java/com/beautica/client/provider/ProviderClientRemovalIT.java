package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.AttachReason;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.beautica.client.provider.ProviderClientFixtures.Salon;
import com.beautica.client.provider.dto.ClientListRemovalRequest;
import com.beautica.client.service.ClientPhoneAttachService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.beautica.common.security.PhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beautica.client.provider.ProviderClientFixtures.freshPhone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B564 — remove a client from the «Клієнти» list / undo (CLI-82..86, CLI-E11..E14, MOD-40) end to end against real
 * Postgres: the four endpoints, the list filter they drive, the untouched card / visits / blocks, and the GST-51 purge.
 * Seeding is raw SQL through {@link ProviderClientFixtures}; every token is a real login. A booking "created after the
 * removal" is a fixture insert made after the removal call ({@code created_at = NOW()} then), exactly the order a
 * walk-in / app booking would have.
 */
@DisplayName("ProviderClientController — B564 list removals")
class ProviderClientRemovalIT extends AbstractIntegrationTest {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";
    private static final String REMOVALS = "/removals";
    private static final String RESTORE = "/removals/restore";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ClientPhoneAttachService attachService;
    @Autowired private GuestPiiAnonymisationService anonymisationService;

    private ProviderClientFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private ResponseEntity<String> post(String token, String url, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String user(UUID clientId) {
        return json(new ClientListRemovalRequest(clientId, null));
    }

    private String guest(UUID bookingId) {
        return json(new ClientListRemovalRequest(null, bookingId));
    }

    private static String salon(UUID salonId) {
        return SALON_URL.formatted(salonId);
    }

    private void noContent(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private JsonNode page(String token, String url) throws Exception {
        ResponseEntity<String> resp = fx.get(token, url);
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private List<JsonNode> rows(String token, String url) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        page(token, url).path("data").forEach(out::add);
        return out;
    }

    /** The list's identities: {@code U:<userId>} for a registered row, {@code G:<displayName>} for a guest row. */
    private List<String> ids(String token, String url) throws Exception {
        return rows(token, url).stream()
                .map(r -> "REGISTERED".equals(r.path("kind").asText())
                        ? "U:" + r.path("userId").asText() : "G:" + r.path("displayName").asText())
                .toList();
    }

    private long total(String token, String url) throws Exception {
        return page(token, url).path("totalElements").asLong();
    }

    private JsonNode rowOf(String token, String url, UUID userId) throws Exception {
        return rows(token, url).stream().filter(r -> userId.toString().equals(r.path("userId").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + userId));
    }

    private String withoutRequestId(ResponseEntity<String> resp) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(resp.getBody());
        body.remove("requestId");
        return body.toString();
    }

    private int removalRows() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_list_removals", Integer.class);
        return n == null ? 0 : n;
    }

    private int blockRows() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_blocks", Integer.class);
        return n == null ? 0 : n;
    }

    private UUID registeredWithBooking(Salon s, String first, String last, int daysFromNow) {
        UUID c = fx.client(first, last);
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(daysFromNow));
        return c;
    }

    // ── 1–4: salon list shared by owner + admin; salon master list separate both ways ─────────────────────

    @Test
    @DisplayName("1: owner removes a registered client — gone for owner AND admin; total drops by 1; paging parity")
    void should_hideRowForOwnerAndAdmin_when_ownerRemovesRegisteredClient() throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        UUID petro = registeredWithBooking(s, "Петро", "Сидоренко", -8);
        UUID olha = registeredWithBooking(s, "Ольга", "Мельник", -6);
        String owner = fx.token(s.ownerEmail());
        String admin = fx.token(fx.admin(s.salonId()));
        assertThat(total(owner, salon(s.salonId()))).isEqualTo(3);

        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(petro)));

        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + olha, "U:" + anna);
        assertThat(ids(admin, salon(s.salonId()))).containsExactly("U:" + olha, "U:" + anna);
        assertThat(total(admin, salon(s.salonId()))).isEqualTo(2);
        List<String> paged = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            JsonNode pg = page(owner, salon(s.salonId()) + "?size=1&page=" + p);
            assertThat(pg.path("totalElements").asLong()).as("page %d total", p).isEqualTo(2);
            pg.path("data").forEach(r -> paged.add("U:" + r.path("userId").asText()));
        }
        assertThat(paged).containsExactly("U:" + olha, "U:" + anna);
    }

    @Test
    @DisplayName("2: admin removes — the owner's list is also without the row")
    void should_hideRowForOwner_when_adminRemoves() throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        UUID petro = registeredWithBooking(s, "Петро", "Сидоренко", -8);
        String admin = fx.token(fx.admin(s.salonId()));

        noContent(post(admin, salon(s.salonId()) + REMOVALS, user(anna)));

        assertThat(ids(fx.token(s.ownerEmail()), salon(s.salonId()))).containsExactly("U:" + petro);
    }

    @Test
    @DisplayName("3: salon-list removal leaves the salon master's own list unchanged; owner ?masterId= view hides it")
    void should_keepSalonMasterList_when_ownerRemoves() throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String owner = fx.token(s.ownerEmail());
        String master = fx.token(s.masterEmail());

        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));

        assertThat(ids(master, ME_URL)).containsExactly("U:" + anna);
        assertThat(ids(owner, salon(s.salonId()) + "?masterId=" + s.masterId())).isEmpty();
        assertThat(total(owner, salon(s.salonId()) + "?masterId=" + s.masterId())).isZero();
    }

    @Test
    @DisplayName("4: salon master removes — own list without the row; owner and admin lists (± masterId) unchanged")
    void should_keepSalonLists_when_salonMasterRemoves() throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String master = fx.token(s.masterEmail());
        String owner = fx.token(s.ownerEmail());
        String admin = fx.token(fx.admin(s.salonId()));

        noContent(post(master, ME_URL + REMOVALS, user(anna)));

        assertThat(ids(master, ME_URL)).isEmpty();
        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + anna);
        assertThat(ids(admin, salon(s.salonId()))).containsExactly("U:" + anna);
        assertThat(ids(owner, salon(s.salonId()) + "?masterId=" + s.masterId())).containsExactly("U:" + anna);
        assertThat(ids(admin, salon(s.salonId()) + "?masterId=" + s.masterId())).containsExactly("U:" + anna);
    }

    @Test
    @DisplayName("5: independent master removes and restores on their own list")
    void should_removeAndRestore_when_independentMaster() throws Exception {
        Independent im = fx.independent();
        UUID anna = fx.client("Анна", "Коваль");
        fx.booking(im.masterId(), null, anna, "COMPLETED", fx.at(-5));
        String token = fx.token(im.email());

        noContent(post(token, ME_URL + REMOVALS, user(anna)));
        assertThat(ids(token, ME_URL)).isEmpty();

        noContent(post(token, ME_URL + RESTORE, user(anna)));
        assertThat(ids(token, ME_URL)).containsExactly("U:" + anna);
    }

    // ── 6: guests ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("6: a guest is removed by any of their bookings; every booking of that phone drops out")
    void should_hideGuestRow_when_removedByGuestBookingId() throws Exception {
        Salon s = fx.salon();
        String phone = freshPhone("50");
        UUID older = fx.guestBooking(s.masterId(), s.salonId(), phone, "Іванна", "Гість", "COMPLETED", fx.at(-9));
        fx.guestBooking(s.masterId(), s.salonId(), "STAFF", phone, "Іванна", "Гість", "COMPLETED", fx.at(-3));
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String owner = fx.token(s.ownerEmail());
        assertThat(ids(owner, salon(s.salonId()))).containsExactly("G:Іванна Гість", "U:" + anna);

        noContent(post(owner, salon(s.salonId()) + REMOVALS, guest(older)));

        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + anna);
        assertThat(jdbcTemplate.queryForObject("SELECT guest_phone FROM client_list_removals", String.class))
                .isEqualTo(phone);
    }

    @Test
    @DisplayName("6: anonymised / out-of-scope / registered anchor → 404 with the card's identical body")
    void should_return404LikeCard_when_guestAnchorInvalid() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID anonymised = fx.anonymisedGuestBooking(s.masterId(), s.salonId(), fx.at(-5));
        UUID foreignGuest = fx.guestBooking(foreign.masterId(), foreign.salonId(), freshPhone("67"), "Чужий",
                "Гість", "COMPLETED", fx.at(-5));
        UUID registeredBooking = fx.booking(s.masterId(), s.salonId(), fx.client("Анна", "Коваль"), "COMPLETED",
                fx.at(-5));
        String owner = fx.token(s.ownerEmail());
        ResponseEntity<String> card = fx.get(owner, salon(s.salonId()) + "/guests/" + anonymised);
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        List<ResponseEntity<String>> responses = List.of(
                post(owner, salon(s.salonId()) + REMOVALS, guest(anonymised)),
                post(owner, salon(s.salonId()) + REMOVALS, guest(foreignGuest)),
                post(owner, salon(s.salonId()) + REMOVALS, guest(registeredBooking)),
                post(owner, salon(s.salonId()) + REMOVALS, guest(UUID.randomUUID())),
                post(owner, salon(s.salonId()) + REMOVALS, user(UUID.randomUUID())),
                post(owner, salon(s.salonId()) + RESTORE, guest(foreignGuest)));

        for (ResponseEntity<String> resp : responses) {
            assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(resp)).isEqualTo(withoutRequestId(card));
        }
        assertThat(removalRows()).isZero();
    }

    // ── 7–11: return rule, upsert, restore ───────────────────────────────────

    @Test
    @DisplayName("7: a booking created after the removal brings the row back with FULL history (list, card, visits)")
    void should_returnWithFullHistory_when_bookedAfterRemoval() throws Exception {
        Salon s = fx.salon();
        UUID anna = fx.client("Анна", "Коваль");
        fx.booking(s.masterId(), s.salonId(), anna, "COMPLETED", fx.at(-30));
        fx.booking(s.masterId(), s.salonId(), anna, "COMPLETED", fx.at(-20));
        String owner = fx.token(s.ownerEmail());
        String cardUrl = salon(s.salonId()) + "/users/" + anna;
        JsonNode rowBefore = rowOf(owner, salon(s.salonId()), anna);
        JsonNode visitsBefore = page(owner, cardUrl + "/visits").path("data");

        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));
        assertThat(ids(owner, salon(s.salonId()))).isEmpty();
        UUID walkIn = fx.insertBooking(s.masterId(), s.salonId(), fx.serviceOf(s.masterId(), s.salonId()), anna,
                "COMPLETED", fx.at(-1), null, "STAFF", null, null, null, false);

        JsonNode row = rowOf(owner, salon(s.salonId()), anna);
        assertThat(row.path("completedVisits").asInt()).isEqualTo(rowBefore.path("completedVisits").asInt() + 1)
                .isEqualTo(3);
        assertThat(Instant.parse(row.path("lastVisitAt").asText())).isAfter(
                Instant.parse(rowBefore.path("lastVisitAt").asText()));
        JsonNode visits = page(owner, cardUrl + "/visits").path("data");
        assertThat(visits).hasSize(3);
        assertThat(visits.get(0).path("items").get(0).path("bookingId").asText()).isEqualTo(walkIn.toString());
        assertThat(visits.get(1)).isEqualTo(visitsBefore.get(0));
        assertThat(visits.get(2)).isEqualTo(visitsBefore.get(1));
        assertThat(page(owner, cardUrl).path("stats").path("totalVisits").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("7b: salon removal → rebook with master B → back in the ?masterId=A view too (salon-wide return)")
    void should_returnInEveryMasterView_when_rebookedWithAnotherSalonMaster() throws Exception {
        Salon s = fx.salon();
        UUID masterB = (UUID) fx.secondMaster(s.salonId(), "Борис")[0];
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String owner = fx.token(s.ownerEmail());
        String admin = fx.token(fx.admin(s.salonId()));
        String viewA = salon(s.salonId()) + "?masterId=" + s.masterId();
        String masterA = fx.token(s.masterEmail());
        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));
        noContent(post(masterA, ME_URL + REMOVALS, user(anna)));
        assertThat(ids(owner, viewA)).as("hidden in A's view before the rebook").isEmpty();

        fx.booking(masterB, s.salonId(), anna, "CONFIRMED", fx.at(4));

        assertThat(ids(masterA, ME_URL)).as("CLI-88: A's own list counts only A's bookings — B's does not return it")
                .isEmpty();

        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + anna);
        assertThat(ids(owner, viewA)).containsExactly("U:" + anna);
        assertThat(ids(admin, viewA)).containsExactly("U:" + anna);
        assertThat(total(owner, viewA)).isEqualTo(1);
        assertThat(ids(owner, salon(s.salonId()) + "?masterId=" + masterB)).containsExactly("U:" + anna);
        assertThat(rowOf(owner, viewA, anna).path("completedVisits").asInt())
                .as("A's view still counts only A's visits").isEqualTo(1);
    }

    @Test
    @DisplayName("8 (CLI-E11): a future CONFIRMED booking created before the removal keeps it hidden; card still 200")
    void should_stayHidden_when_futureBookingPredatesRemoval() throws Exception {
        Salon s = fx.salon();
        UUID anna = fx.client("Анна", "Коваль");
        fx.booking(s.masterId(), s.salonId(), anna, "COMPLETED", fx.at(-10));
        fx.booking(s.masterId(), s.salonId(), anna, "CONFIRMED", fx.at(7));
        String owner = fx.token(s.ownerEmail());

        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));

        assertThat(ids(owner, salon(s.salonId()))).isEmpty();
        JsonNode card = page(owner, salon(s.salonId()) + "/users/" + anna);
        assertThat(card.path("stats").path("totalVisits").asInt()).isEqualTo(2);
        assertThat(card.path("stats").path("upcomingVisits").asInt()).isEqualTo(1);
        assertThat(page(owner, salon(s.salonId()) + "/users/" + anna + "/visits").path("data")).hasSize(2);
    }

    @Test
    @DisplayName("9: a booking created after the removal and then cancelled still brings the client back")
    void should_return_when_newBookingIsCancelled() throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String owner = fx.token(s.ownerEmail());
        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));

        fx.booking(s.masterId(), s.salonId(), anna, "CANCELLED", fx.at(3));

        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + anna);
    }

    @Test
    @DisplayName("10: re-removing after a return hides the row again (the upsert moves removed_at)")
    void should_hideAgain_when_reRemovedAfterReturn() throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String owner = fx.token(s.ownerEmail());
        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));
        Instant first = jdbcTemplate.queryForObject("SELECT removed_at FROM client_list_removals", Instant.class);
        fx.booking(s.masterId(), s.salonId(), anna, "COMPLETED", fx.at(-1));
        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + anna);

        noContent(post(fx.token(fx.admin(s.salonId())), salon(s.salonId()) + REMOVALS, user(anna)));

        assertThat(ids(owner, salon(s.salonId()))).isEmpty();
        assertThat(removalRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT removed_at FROM client_list_removals", Instant.class))
                .isAfter(first);
    }

    @Test
    @DisplayName("11: restore brings the row back at once; restore with nothing removed is 204")
    void should_showRowAtOnce_when_restored() throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String owner = fx.token(s.ownerEmail());
        String admin = fx.token(fx.admin(s.salonId()));
        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));

        noContent(post(admin, salon(s.salonId()) + RESTORE, user(anna)));

        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + anna);
        assertThat(removalRows()).isZero();
        noContent(post(owner, salon(s.salonId()) + RESTORE, user(anna)));
    }

    // ── 12–13: search, blocks ────────────────────────────────────────────────

    @Test
    @DisplayName("12 (CLI-85): name and phone-digit search never find a removed client (the picker's source)")
    void should_excludeFromSearch_when_removed() throws Exception {
        Salon s = fx.salon();
        String phone = freshPhone("50");
        UUID g = fx.guestBooking(s.masterId(), s.salonId(), phone, "Іванна", "Пошукова", "COMPLETED", fx.at(-4));
        UUID anna = fx.client("Ганна", "Пошукова");
        String annaPhone = freshPhone("63");
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?",
                annaPhone, anna);
        fx.booking(s.masterId(), s.salonId(), anna, "COMPLETED", fx.at(-6));
        String owner = fx.token(s.ownerEmail());
        String byName = salon(s.salonId()) + "?q=Пошукова";
        String byGuestDigits = salon(s.salonId()) + "?q=" + phone.substring(4);
        String byUserDigits = salon(s.salonId()) + "?q=" + annaPhone.substring(4);
        assertThat(ids(owner, byName)).as("non-vacuous: both found before").hasSize(2);
        assertThat(ids(owner, byGuestDigits)).hasSize(1);
        assertThat(ids(owner, byUserDigits)).hasSize(1);

        noContent(post(owner, salon(s.salonId()) + REMOVALS, guest(g)));
        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));

        assertThat(ids(owner, byName)).isEmpty();
        assertThat(total(owner, byName)).isZero();
        assertThat(ids(owner, byGuestDigits)).isEmpty();
        assertThat(ids(owner, byUserDigits)).isEmpty();
    }

    @Test
    @DisplayName("13 (MOD-40, CLI-E14): block/unblock keeps list membership; remove/restore never touch client_blocks")
    void should_keepMembershipAndBlocks_when_blockingRemovedClient() throws Exception {
        Salon s = fx.salon();
        UUID anna = fx.client("Анна", "Коваль");
        UUID booking = fx.booking(s.masterId(), s.salonId(), anna, "COMPLETED", fx.at(-5));
        String owner = fx.token(s.ownerEmail());
        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));

        ResponseEntity<String> blocked = post(owner, "/api/v1/blocks", json(Map.of("bookingId", booking)));
        assertThat(blocked.getStatusCode()).as(blocked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ids(owner, salon(s.salonId()))).as("block does not return the row").isEmpty();
        String blockId = objectMapper.readTree(blocked.getBody()).path("data").path("id").asText();
        ResponseEntity<String> unblocked = restTemplate.exchange("/api/v1/blocks/" + blockId, HttpMethod.DELETE,
                new HttpEntity<>(bearer(owner)), String.class);
        assertThat(unblocked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ids(owner, salon(s.salonId()))).as("unblock does not return the row").isEmpty();

        fx.blockClientInSalon(s.salonId(), anna);
        noContent(post(owner, salon(s.salonId()) + RESTORE, user(anna)));
        noContent(post(owner, salon(s.salonId()) + REMOVALS, user(anna)));
        noContent(post(owner, salon(s.salonId()) + RESTORE, user(anna)));

        assertThat(blockRows()).as("remove/restore never touch the block").isEqualTo(1);
        JsonNode row = rowOf(owner, salon(s.salonId()), anna);
        assertThat(row.path("blocked").asBoolean()).isTrue();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    // ── 14: roles + body ─────────────────────────────────────────────────────

    /** 14 — one denied caller per invocation, so one failure cannot hide another. */
    enum DeniedCaller { CLIENT_SALON_PATH, CLIENT_ME_PATH, SALON_MASTER_SALON_REMOVE, SALON_MASTER_SALON_RESTORE,
        OTHER_SALON_ADMIN }

    @ParameterizedTest
    @EnumSource(DeniedCaller.class)
    @DisplayName("14: CLIENT (both paths), SALON_MASTER on the salon path, another salon's admin → 403, nothing written")
    void should_return403_when_callerMayNotUseThePath(DeniedCaller caller) throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String salonPath = salon(s.salonId());

        ResponseEntity<String> resp = switch (caller) {
            case CLIENT_SALON_PATH -> post(clientToken(), salonPath + REMOVALS, user(anna));
            case CLIENT_ME_PATH -> post(clientToken(), ME_URL + REMOVALS, user(anna));
            case SALON_MASTER_SALON_REMOVE -> post(fx.token(s.masterEmail()), salonPath + REMOVALS, user(anna));
            case SALON_MASTER_SALON_RESTORE -> post(fx.token(s.masterEmail()), salonPath + RESTORE, user(anna));
            case OTHER_SALON_ADMIN -> post(fx.token(fx.admin(fx.salon().salonId())), salonPath + REMOVALS, user(anna));
        };

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(removalRows()).isZero();
    }

    private String clientToken() throws Exception {
        String clientEmail = fx.email("client");
        fx.user(clientEmail, "CLIENT", null, "Клієнт", "Тест");
        return fx.token(clientEmail);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("14: both ids or neither id → 400, nothing written, the row stays listed")
    void should_return400_when_bodyDoesNotCarryExactlyOneId(boolean bothIds) throws Exception {
        Salon s = fx.salon();
        UUID anna = registeredWithBooking(s, "Анна", "Коваль", -10);
        String owner = fx.token(s.ownerEmail());
        String body = json(bothIds ? new ClientListRemovalRequest(anna, UUID.randomUUID())
                : new ClientListRemovalRequest(null, null));

        ResponseEntity<String> resp = post(owner, salon(s.salonId()) + REMOVALS, body);

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(removalRows()).isZero();
        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + anna);
    }

    // ── 15–16: GST-51 purge, attach ──────────────────────────────────────────

    @Test
    @DisplayName("15 (D3): retention anonymisation deletes the guest's removal rows; a phone still open keeps its row")
    void should_purgeRemovalRows_when_guestPiiAnonymised() throws Exception {
        Salon s = fx.salon();
        Independent im = fx.independent();
        String gone = freshPhone("50");
        String open = freshPhone("67");
        UUID salonGuest = fx.guestBooking(s.masterId(), s.salonId(), gone, "Іванна", "Стара", "COMPLETED", fx.at(-30));
        UUID imGuest = fx.guestBooking(im.masterId(), null, gone, "Іванна", "Стара", "COMPLETED", fx.at(-29));
        UUID openPast = fx.guestBooking(s.masterId(), s.salonId(), open, "Олег", "Живий", "COMPLETED", fx.at(-28));
        fx.guestBooking(s.masterId(), s.salonId(), "STAFF", open, "Олег", "Живий", "CONFIRMED", fx.at(30));
        String owner = fx.token(s.ownerEmail());
        noContent(post(owner, salon(s.salonId()) + REMOVALS, guest(salonGuest)));
        noContent(post(fx.token(im.email()), ME_URL + REMOVALS, guest(imGuest)));
        noContent(post(owner, salon(s.salonId()) + REMOVALS, guest(openPast)));
        assertThat(removalRows()).isEqualTo(3);

        anonymisationService.anonymiseDue(Instant.now().minusSeconds(3600L * 24 * 10));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_list_removals WHERE guest_phone = ?", Integer.class, gone)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_list_removals WHERE guest_phone = ?", Integer.class, open))
                .as("the phone's future booking is still open: the removal stands").isEqualTo(1);
        assertThat(ids(owner, salon(s.salonId()))).as("still hidden").isEmpty();
    }

    @Test
    @DisplayName("15 (D3): the manual erase deletes every removal row of the phone")
    void should_purgeRemovalRows_when_guestErasedByPhone() throws Exception {
        Salon s = fx.salon();
        String phone = freshPhone("50");
        UUID g = fx.guestBooking(s.masterId(), s.salonId(), phone, "Іванна", "Стерта", "COMPLETED", fx.at(-3));
        noContent(post(fx.token(s.ownerEmail()), salon(s.salonId()) + REMOVALS, guest(g)));
        noContent(post(fx.token(s.masterEmail()), ME_URL + REMOVALS, guest(g)));

        anonymisationService.eraseByPhone(phone, null);

        assertThat(removalRows()).isZero();
    }

    @Test
    @DisplayName("16 (CLI-E13): a removed guest's phone attached to an account → the registered row is listed")
    void should_listRegisteredRow_when_removedGuestPhoneIsAttached() throws Exception {
        Salon s = fx.salon();
        String phone = freshPhone("50");
        UUID g = fx.guestBooking(s.masterId(), s.salonId(), phone, "Іванна", "Гість", "COMPLETED", fx.at(-5));
        String owner = fx.token(s.ownerEmail());
        noContent(post(owner, salon(s.salonId()) + REMOVALS, guest(g)));
        assertThat(ids(owner, salon(s.salonId()))).isEmpty();
        UUID account = fx.client("Іванна", "Акаунт");
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?",
                phone, account);

        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(phone, AttachReason.PHONE_VERIFIED));

        assertThat(ids(owner, salon(s.salonId()))).containsExactly("U:" + account);
    }

    /**
     * B564 attach fix (security MEDIUM, {@code ClientPhoneAttachService#attachRows}): attaching
     * the phone must delete the guest-keyed removal rows of that phone in every scope, otherwise an orphan row holding
     * a plain E.164 phone outlives the guest identity (D3 / CLI-D18 purge contract).
     */
    @Test
    @DisplayName("16b (D3): attaching a removed guest's phone leaves no removal row with it")
    void should_purgeGuestRemovalRows_when_removedGuestPhoneIsAttached() throws Exception {
        Salon s = fx.salon();
        String phone = freshPhone("50");
        UUID g = fx.guestBooking(s.masterId(), s.salonId(), phone, "Іванна", "Гість", "COMPLETED", fx.at(-5));
        noContent(post(fx.token(s.ownerEmail()), salon(s.salonId()) + REMOVALS, guest(g)));
        noContent(post(fx.token(s.masterEmail()), ME_URL + REMOVALS, guest(g)));
        assertThat(guestRemovalRows(phone)).as("non-vacuous: salon + master rows exist").isEqualTo(2);
        UUID account = fx.client("Іванна", "Акаунт");
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?",
                phone, account);

        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(phone, AttachReason.PHONE_VERIFIED));

        assertThat(guestRemovalRows(phone)).as("no orphan removal row keeps the attached phone").isZero();
    }

    private int guestRemovalRows(String phone) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_list_removals WHERE guest_phone = ?", Integer.class, phone);
        return n == null ? 0 : n;
    }

    // ── IDOR: identities outside the caller's scope (security LOW) ───────────

    @Test
    @DisplayName("IDOR: a registered client of a foreign salon → owner's remove/restore 404 like the card, nothing written")
    void should_return404LikeCard_when_ownerTargetsForeignSalonsRegisteredClient() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID foreignClient = registeredWithBooking(foreign, "Чужа", "Клієнтка", -5);
        String owner = fx.token(s.ownerEmail());
        assertThat(ids(fx.token(foreign.ownerEmail()), salon(foreign.salonId())))
                .as("non-vacuous: the client is real and listed in the foreign salon")
                .containsExactly("U:" + foreignClient);
        ResponseEntity<String> card = fx.get(owner, salon(s.salonId()) + "/users/" + foreignClient);
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        List<ResponseEntity<String>> responses = List.of(
                post(owner, salon(s.salonId()) + REMOVALS, user(foreignClient)),
                post(owner, salon(s.salonId()) + RESTORE, user(foreignClient)));

        for (ResponseEntity<String> resp : responses) {
            assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(resp)).isEqualTo(withoutRequestId(card));
        }
        assertThat(removalRows()).isZero();
    }

    @Test
    @DisplayName("IDOR: master A removes/restores master B's registered client or guest on /masters/me → 404 like the card")
    void should_return404LikeCard_when_masterTargetsAnotherMastersClients() throws Exception {
        Salon s = fx.salon();
        Object[] b = fx.secondMaster(s.salonId(), "Борис");
        UUID masterB = (UUID) b[0];
        UUID bClient = fx.client("Віра", "Борисова");
        fx.booking(masterB, s.salonId(), bClient, "COMPLETED", fx.at(-5));
        UUID bGuest = fx.guestBooking(masterB, s.salonId(), freshPhone("67"), "Гліб", "Борисів", "COMPLETED",
                fx.at(-4));
        String masterA = fx.token(s.masterEmail());
        assertThat(ids(fx.token((String) b[1]), ME_URL)).as("non-vacuous: both are B's clients").hasSize(2);
        ResponseEntity<String> userCard = fx.get(masterA, ME_URL + "/users/" + bClient);
        ResponseEntity<String> guestCard = fx.get(masterA, ME_URL + "/guests/" + bGuest);
        assertThat(userCard.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(guestCard.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        List<ResponseEntity<String>> userResponses = List.of(
                post(masterA, ME_URL + REMOVALS, user(bClient)),
                post(masterA, ME_URL + RESTORE, user(bClient)));
        List<ResponseEntity<String>> guestResponses = List.of(
                post(masterA, ME_URL + REMOVALS, guest(bGuest)),
                post(masterA, ME_URL + RESTORE, guest(bGuest)));

        for (ResponseEntity<String> resp : userResponses) {
            assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(resp)).isEqualTo(withoutRequestId(userCard));
        }
        for (ResponseEntity<String> resp : guestResponses) {
            assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(resp)).isEqualTo(withoutRequestId(guestCard));
        }
        assertThat(removalRows()).isZero();
    }
}
