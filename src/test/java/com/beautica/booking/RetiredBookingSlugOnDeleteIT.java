package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.PublicBookingPageFixtures.IndependentMaster;
import com.beautica.booking.PublicBookingPageFixtures.SalonPage;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.service.RetiredSlugHasher;
import com.beautica.config.BookingLinksProperties;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 379 D7 — every delete path that removes a public booking page tombstones its slug
 * ({@code reason = DELETED}, hash-only) in the deleting transaction, so the old link answers 410 and the slug
 * is never re-issued: salon deletion ({@code DELETE /salons/{id}} — the salon row is deactivated for good),
 * salon-master removal (staff disposal deletes the masters row) and independent-master self-delete.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Deleted master / salon → slug tombstoned DELETED → 410 (phase 379 D7, Testcontainers)")
class RetiredBookingSlugOnDeleteIT extends AbstractIntegrationTest {

    private static final String BOOK = "/api/v1/book/";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingLinksProperties bookingLinksProperties;
    @MockBean private SmsService smsService;

    private PublicBookingPageFixtures pages;

    private boolean originalShareEnabled;

    @BeforeEach
    void setUp() {
        originalShareEnabled = bookingLinksProperties.isShareEnabled();
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
    }

    /** {@link BookingLinksProperties} is a context singleton shared with other ITs: restore the kill-switch. */
    @AfterEach
    void restoreShareEnabled() {
        bookingLinksProperties.setShareEnabled(originalShareEnabled);
    }

    @Test
    @DisplayName("salon delete → SALON tombstone DELETED (salon_id kept, no plaintext) → /s/{slug}/info 410")
    void should_tombstoneSalonSlug_when_salonDeleted() throws Exception {
        SalonPage salon = pages.salon("Салон на видалення");
        assertThat(get(BOOK + "s/" + salon.slug() + "/info").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> deleted = authorized(HttpMethod.DELETE, "/api/v1/salons/" + salon.salonId(),
                salon.ownerToken());

        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = tombstone(BookingLinkKind.SALON, salon.slug());
        assertThat(row.get("reason")).isEqualTo("DELETED");
        assertThat(row.get("salon_id")).isEqualTo(salon.salonId());
        assertThat(get(BOOK + "s/" + salon.slug() + "/info").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("salon master removed (bookingless → masters row deleted) → MASTER tombstone DELETED "
            + "(master_id SET NULL by the FK) → /{slug}/info 410")
    void should_tombstoneMasterSlug_when_salonMasterRemoved() throws Exception {
        SalonPage salon = pages.salon("Салон з майстром");
        UUID masterId = pages.salonMaster(salon.salonId(), "Ірина", "Лис");
        String slug = pages.masterSlugOf(masterId);
        assertThat(get(BOOK + slug + "/info").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> removed = authorized(HttpMethod.DELETE,
                "/api/v1/salons/" + salon.salonId() + "/masters/" + masterId, salon.ownerToken());

        assertThat(removed.getStatusCode()).as(removed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM masters WHERE id = ?", Integer.class, masterId))
                .as("bookingless master row is hard-deleted").isZero();
        Map<String, Object> row = tombstone(BookingLinkKind.MASTER, slug);
        assertThat(row.get("reason")).isEqualTo("DELETED");
        assertThat(row.get("master_id")).isNull();
        assertThat(get(BOOK + slug + "/info").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("independent master self-delete → MASTER tombstone DELETED → /{slug}/info 410")
    void should_tombstoneMasterSlug_when_independentMasterDeletesOwnAccount() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        assertThat(get(BOOK + master.slug() + "/info").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> deleted = authorized(HttpMethod.DELETE, "/api/v1/users/me", pages.tokenOf(master));

        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tombstone(BookingLinkKind.MASTER, master.slug()).get("reason")).isEqualTo("DELETED");
        assertThat(get(BOOK + master.slug() + "/info").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("salon delete with a salon master → BOTH the salon slug and the master's slug 410 "
            + "(salon tombstone + staff-disposal tombstone, one deletion)")
    void should_tombstoneSalonAndItsMasterSlug_when_salonWithStaffDeleted() throws Exception {
        SalonPage salon = pages.salon("Салон з командою");
        UUID masterId = pages.salonMaster(salon.salonId(), "Оксана", "Мельник");
        String masterSlug = pages.masterSlugOf(masterId);
        assertThat(get(BOOK + masterSlug + "/info").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> deleted = authorized(HttpMethod.DELETE, "/api/v1/salons/" + salon.salonId(),
                salon.ownerToken());

        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tombstone(BookingLinkKind.MASTER, masterSlug).get("reason")).isEqualTo("DELETED");
        assertRetiredBody(get(BOOK + masterSlug + "/info"));
        assertRetiredBody(get(BOOK + "s/" + salon.slug() + "/info"));
        assertRetiredBody(get(BOOK + "s/" + salon.slug() + "/reviews"));
    }

    @Test
    @DisplayName("salon regenerated, then deleted → original slug keeps REGENERATED, current slug DELETED; "
            + "both 410")
    void should_tombstoneCurrentSlugAndKeepPrior_when_regeneratedSalonDeleted() throws Exception {
        bookingLinksProperties.setShareEnabled(true);
        SalonPage salon = pages.salon("Салон перевипуск");
        ResponseEntity<String> regenerated = authorizedJson(HttpMethod.POST,
                "/api/v1/booking-links/salons/" + salon.salonId() + "/regenerate", salon.ownerToken(),
                Map.of("currentSlug", salon.slug()));
        assertThat(regenerated.getStatusCode()).as(regenerated.getBody()).isEqualTo(HttpStatus.OK);
        String current = objectMapper.readTree(regenerated.getBody()).path("data").path("slug").asText();

        ResponseEntity<String> deleted = authorized(HttpMethod.DELETE, "/api/v1/salons/" + salon.salonId(),
                salon.ownerToken());

        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tombstone(BookingLinkKind.SALON, salon.slug()).get("reason")).isEqualTo("REGENERATED");
        assertThat(tombstone(BookingLinkKind.SALON, current).get("reason")).isEqualTo("DELETED");
        assertRetiredBody(get(BOOK + "s/" + salon.slug() + "/info"));
        assertRetiredBody(get(BOOK + "s/" + current + "/info"));
    }

    @Test
    @DisplayName("live salon slug somehow already tombstoned → delete still 204 (ON CONFLICT DO NOTHING), "
            + "existing row untouched")
    void should_notAbortDeletion_when_liveSlugAlreadyTombstoned() throws Exception {
        SalonPage salon = pages.salon("Салон дубль");
        jdbcTemplate.update("INSERT INTO retired_booking_slugs (kind, slug_hash, reason, retired_at) "
                + "VALUES ('SALON', ?, 'REVOKED', NOW())", RetiredSlugHasher.hash(BookingLinkKind.SALON, salon.slug()));

        ResponseEntity<String> deleted = authorized(HttpMethod.DELETE, "/api/v1/salons/" + salon.salonId(),
                salon.ownerToken());

        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = tombstone(BookingLinkKind.SALON, salon.slug());
        assertThat(row.get("reason")).isEqualTo("REVOKED");
        assertThat(row.get("salon_id")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM salons WHERE id = ?", Boolean.class,
                salon.salonId())).isFalse();
    }

    @Test
    @DisplayName("salon whose link was revoked (no live slug) deleted → 204, no DELETED tombstone written")
    void should_writeNoDeletedTombstone_when_salonHasNoLiveSlug() throws Exception {
        bookingLinksProperties.setShareEnabled(true);
        SalonPage salon = pages.salon("Салон без посилання");
        ResponseEntity<String> revoked = authorizedJson(HttpMethod.DELETE,
                "/api/v1/booking-links/salons/" + salon.salonId(), salon.ownerToken(),
                Map.of("currentSlug", salon.slug()));
        assertThat(revoked.getStatusCode()).as(revoked.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> deleted = authorized(HttpMethod.DELETE, "/api/v1/salons/" + salon.salonId(),
                salon.ownerToken());

        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retired_booking_slugs WHERE salon_id = ? AND reason = 'DELETED'",
                Integer.class, salon.salonId())).isZero();
        assertRetiredBody(get(BOOK + "s/" + salon.slug() + "/info"));
    }

    private void assertRetiredBody(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.GONE);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean(true)).isFalse();
        assertThat(body.path("data").path("code").asText()).isEqualTo("BOOKING_LINK_RETIRED");
        assertThat(body.path("message").asText()).isEqualTo("Booking link is no longer valid");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=0, public, s-maxage=3600");
    }

    private ResponseEntity<String> authorizedJson(HttpMethod method, String url, String token, Object body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class);
    }

    private Map<String, Object> tombstone(BookingLinkKind kind, String slug) {
        return jdbcTemplate.queryForMap(
                "SELECT master_id, salon_id, reason FROM retired_booking_slugs WHERE kind = ? AND slug_hash = ?",
                kind.name(), RetiredSlugHasher.hash(kind, slug));
    }

    private ResponseEntity<String> authorized(HttpMethod method, String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url, method, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> get(String url) {
        return pages.anonymousGet(url, PublicBookingPageFixtures.randomIp());
    }
}
