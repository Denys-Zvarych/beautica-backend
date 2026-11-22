package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.AttachReason;
import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.beautica.client.provider.ProviderClientFixtures.Salon;
import com.beautica.client.service.ClientPhoneAttachService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.moderation.service.BlockScope;
import com.beautica.moderation.service.ClientBlockService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B527 (CLI-62..64, D7, D8) — what a PROVIDER sees once guest / walk-in bookings are attached to a client account: one
 * client row and one card per person (cases 10, 11), the account's current verified phone on the booking detail (12),
 * one block answer per person (13, 15) and the provider's rating of an attached walk-in (17). The attach itself runs
 * through the real {@link ClientPhoneAttachService}.
 */
@DisplayName("Provider surfaces after a phone attach — B527")
class ProviderClientAttachedIT extends AbstractIntegrationTest {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String BOOKINGS = "/api/v1/bookings";
    private static final String PHONE = "+380671234567";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ClientPhoneAttachService attachService;
    @Autowired private ClientBlockService clientBlockService;

    private ProviderClientFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    private UUID verifiedClient(String first, String last, String phone) {
        UUID id = fx.client(first, last);
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?", phone, id);
        return id;
    }

    private void attach(String phone) {
        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(phone, AttachReason.PHONE_VERIFIED));
    }

    private JsonNode ok(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private List<JsonNode> rows(ResponseEntity<String> resp) throws Exception {
        return StreamSupport.stream(ok(resp).path("data").spliterator(), false).toList();
    }

    private String listUrl(UUID salonId) {
        return SALON_URL.formatted(salonId);
    }

    /** The account with 1 APP + 1 attached LINK + 1 attached STAFF booking at the salon; returns the LINK booking id. */
    private UUID seedOnePerson(Salon s, UUID account, String guestName) {
        fx.booking(s.masterId(), s.salonId(), account, "COMPLETED", fx.at(-20));
        UUID link = fx.guestBooking(s.masterId(), s.salonId(), "LINK", PHONE, guestName, "Гість", "COMPLETED", fx.at(-10));
        fx.guestBooking(s.masterId(), s.salonId(), "STAFF", PHONE, guestName, "Гість", "COMPLETED", fx.at(-5));
        attach(PHONE);
        return link;
    }

    @Test
    @DisplayName("10: a registered client with 1 APP + an attached LINK + an attached STAFF booking is ONE list row "
            + "and ONE card; counts and visits include all three; the name is the account's")
    void should_showOneClient_when_guestRowsAreAttached() throws Exception {
        Salon s = fx.salon();
        UUID account = verifiedClient("Петро", "Сидоренко", PHONE);
        seedOnePerson(s, account, "Іванна");
        String token = fx.token(s.ownerEmail());

        List<JsonNode> list = rows(fx.get(token, listUrl(s.salonId())));
        JsonNode card = ok(fx.get(token, listUrl(s.salonId()) + "/users/" + account));
        List<JsonNode> visits = StreamSupport.stream(
                ok(fx.get(token, listUrl(s.salonId()) + "/users/" + account + "/visits")).path("data")
                        .spliterator(), false).toList();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("kind").asText()).isEqualTo("REGISTERED");
        assertThat(list.get(0).path("displayName").asText()).isEqualTo("Петро Сидоренко");
        assertThat(list.get(0).path("completedVisits").asInt()).isEqualTo(3);
        assertThat(card.path("displayName").asText()).isEqualTo("Петро Сидоренко");
        assertThat(card.path("stats").path("totalVisits").asInt()).isEqualTo(3);
        assertThat(visits).hasSize(3);
    }

    @Test
    @DisplayName("11: B508 case 16 rewritten — one card via /users/{id}; the old /guests/{anchor} is the uniform 404")
    void should_returnOneCardAndUniform404_when_guestAnchorIsNowAttached() throws Exception {
        Salon s = fx.salon();
        UUID account = verifiedClient("Петро", "Спільний", PHONE);
        UUID anchor = seedOnePerson(s, account, "Петро");
        String token = fx.token(s.ownerEmail());

        JsonNode card = ok(fx.get(token, listUrl(s.salonId()) + "/users/" + account));
        ResponseEntity<String> stale = fx.get(token, listUrl(s.salonId()) + "/guests/" + anchor);

        assertThat(card.path("kind").asText()).isEqualTo("REGISTERED");
        assertThat(card.path("stats").path("totalVisits").asInt()).isEqualTo(3);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rows(fx.get(token, listUrl(s.salonId()))).stream()
                .filter(r -> "GUEST".equals(r.path("kind").asText()))).isEmpty();
    }

    @Test
    @DisplayName("12: booking detail of an attached row carries no phone for any role (GST-80 / CLI-D08: card only)")
    void should_showNoPhoneOnDetail_when_rowIsAttached() throws Exception {
        Salon s = fx.salon();
        Independent indep = fx.independent();
        UUID account = verifiedClient("Петро", "Сидоренко", PHONE);
        UUID salonLink = fx.guestBooking(s.masterId(), s.salonId(), "LINK", PHONE, "Іванна", "Гість", "COMPLETED",
                fx.at(-5));
        UUID soloStaff = fx.guestBooking(indep.masterId(), null, "STAFF", PHONE, "Іванна", "Гість", "COMPLETED",
                fx.at(-4));
        attach(PHONE);
        String adminEmail = fx.admin(s.salonId());

        JsonNode owner = ok(fx.get(fx.token(s.ownerEmail()), BOOKINGS + "/" + salonLink));
        JsonNode admin = ok(fx.get(fx.token(adminEmail), BOOKINGS + "/" + salonLink));
        JsonNode solo = ok(fx.get(fx.token(indep.email()), BOOKINGS + "/" + soloStaff));
        JsonNode master = ok(fx.get(fx.token(s.masterEmail()), BOOKINGS + "/" + salonLink));

        for (JsonNode detail : List.of(owner, admin, solo, master)) {
            assertThat(detail.path("clientPhone").isMissingNode() || detail.path("clientPhone").isNull()).isTrue();
            assertThat(detail.path("guestPhone").isMissingNode() || detail.path("guestPhone").isNull()).isTrue();
            assertThat(detail.toString()).doesNotContain(PHONE.substring(4));
        }
        assertThat(account).isNotNull();
    }

    @Test
    @DisplayName("13 (list/card): a phone-hash block on the verified phone shows the registered client as blocked")
    void should_flagBlocked_when_verifiedPhoneHashIsBlocked() throws Exception {
        Salon s = fx.salon();
        UUID account = verifiedClient("Петро", "Сидоренко", PHONE);
        seedOnePerson(s, account, "Іванна");
        fx.blockGuestInSalon(s.salonId(), PHONE);
        String token = fx.token(s.ownerEmail());

        List<JsonNode> list = rows(fx.get(token, listUrl(s.salonId())));
        JsonNode card = ok(fx.get(token, listUrl(s.salonId()) + "/users/" + account));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("blocked").asBoolean()).isTrue();
        assertThat(card.path("blocked").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("15: unblocking the registered client deletes the account row AND the verified-phone hash row; "
            + "the person may book again")
    void should_clearBothRows_when_registeredClientIsUnblocked() throws Exception {
        Salon s = fx.salon();
        UUID account = verifiedClient("Петро", "Сидоренко", PHONE);
        seedOnePerson(s, account, "Іванна");
        fx.blockClientInSalon(s.salonId(), account);
        fx.blockGuestInSalon(s.salonId(), PHONE);
        UUID accountRow = jdbcTemplate.queryForObject(
                "SELECT id FROM client_blocks WHERE blocked_user_id = ?", UUID.class, account);
        String token = fx.token(s.ownerEmail());
        BlockScope scope = BlockScope.salon(s.salonId());
        assertThat(clientBlockService.isClientBlocked(scope, account)).isTrue();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> deleted = restTemplate.exchange("/api/v1/blocks/" + accountRow, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_blocks WHERE blocker_salon_id = ?",
                Integer.class, s.salonId())).as("both the account row and the phone-hash row are gone").isZero();
        assertThat(clientBlockService.isClientBlocked(scope, account)).isFalse();
    }

    private ResponseEntity<String> rate(Salon s, UUID booking) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fx.token(s.ownerEmail()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/client-reviews", HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + booking + "\",\"rating\":4}", headers), String.class);
    }

    @Test
    @DisplayName("17 (REV-D04, audit MEDIUM): an attached COMPLETED STAFF walk-in is NOT rateable — 400, no review row, "
            + "no rating change")
    void should_return400_when_providerRatesAnAttachedWalkIn() throws Exception {
        Salon s = fx.salon();
        UUID account = verifiedClient("Петро", "Сидоренко", PHONE);
        UUID staff = fx.guestBooking(s.masterId(), s.salonId(), "STAFF", PHONE, "Іванна", "Гість", "COMPLETED", fx.at(-5));
        attach(PHONE);

        ResponseEntity<String> response = rate(s, staff);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?",
                Integer.class, staff)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM users WHERE id = ?", Integer.class, account))
                .isZero();
    }

    @Test
    @DisplayName("17b: an attached COMPLETED LINK booking IS rateable — 201, and users.avg_rating includes it")
    void should_rateAttachedLinkBooking_when_completed() throws Exception {
        Salon s = fx.salon();
        UUID account = verifiedClient("Петро", "Сидоренко", PHONE);
        UUID link = fx.guestBooking(s.masterId(), s.salonId(), "LINK", PHONE, "Іванна", "Гість", "COMPLETED", fx.at(-5));
        attach(PHONE);

        ResponseEntity<String> response = rate(s, link);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM users WHERE id = ?", java.math.BigDecimal.class,
                account)).isEqualByComparingTo("4");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM users WHERE id = ?", Integer.class, account))
                .isOne();
    }
}
