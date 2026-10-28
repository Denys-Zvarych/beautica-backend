package com.beautica.moderation;

import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.common.exception.BookingUnavailableException;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.ParityRequestId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 398 — shared helpers of the block-enforcement ITs: bookable masters / slugs on top of the
 * {@link AbstractModerationIT} seeding, the four create calls (APP single + visit, LINK master + salon route),
 * real {@code POST /blocks} and the refusal assertion. Every block goes through the real API, every client and
 * provider token is a real login, and every guest token is minted by the real {@link GuestTokenProvider}.
 */
abstract class AbstractBookingBlockIT extends AbstractModerationIT {

    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    static final String PRIVACY_VERSION = "2026-10-20";

    @Autowired
    protected GuestTokenProvider guestTokenProvider;

    // ── seeding ──────────────────────────────────────────────────────────────────

    /** Adds a fresh bookable service + a 09:00–17:00 every-day schedule to a salon master; returns the assignment. */
    protected UUID makeSalonMasterBookable(UUID salonId, UUID masterId) {
        UUID defId = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salonId, masterId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        return assignmentOf(defId);
    }

    /** Independent-master counterpart of {@link #makeSalonMasterBookable}. */
    protected UUID makeIndependentBookable(UUID masterId) {
        UUID defId = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        return assignmentOf(defId);
    }

    /** A second service for an already-scheduled independent master (multi-service visits). */
    protected UUID addIndependentService(UUID masterId) {
        return assignmentOf(BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId));
    }

    protected UUID assignmentOf(UUID serviceDefId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, serviceDefId);
    }

    protected String setMasterSlug(UUID masterId) {
        String slug = "blk-m-" + masterId.toString().substring(0, 8);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterId);
        return slug;
    }

    protected String setSalonSlug(UUID salonId) {
        String slug = "blk-s-" + salonId.toString().substring(0, 8);
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", slug, salonId);
        return slug;
    }

    protected void setProfilePhone(UUID userId, String phone) {
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", phone, userId);
    }

    /** {@code daysAhead} days from today at {@code hour}:00 Kyiv — inside the seeded 09–17 schedule. */
    protected static ZonedDateTime slot(int daysAhead, int hour) {
        return ZonedDateTime.now(KYIV).plusDays(daysAhead).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    // ── blocks ───────────────────────────────────────────────────────────────────

    /** Real {@code POST /blocks} from a booking in the provider's scope; returns the block id. */
    protected UUID block(String providerToken, UUID bookingId) {
        ResponseEntity<String> resp = postBlock(providerToken, bookingId);
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(data(resp).path("id").asText());
    }

    // ── create calls ─────────────────────────────────────────────────────────────

    protected ResponseEntity<String> appBook(String clientToken, UUID masterId, UUID masterServiceId,
                                             ZonedDateTime startsAt) {
        String body = objectMapper.createObjectNode()
                .put("masterId", masterId.toString())
                .put("masterServiceId", masterServiceId.toString())
                .put("startsAt", startsAt.toOffsetDateTime().toString())
                .toString();
        return restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, json(clientToken)), String.class);
    }

    protected ResponseEntity<String> appVisit(String clientToken, UUID masterId, List<UUID> masterServiceIds,
                                              ZonedDateTime startsAt) {
        ObjectNode node = objectMapper.createObjectNode()
                .put("masterId", masterId.toString())
                .put("startsAt", startsAt.toOffsetDateTime().toString());
        node.set("masterServiceIds", idArray(masterServiceIds));
        String body = node.toString();
        return restTemplate.exchange("/api/v1/appointments", HttpMethod.POST,
                new HttpEntity<>(body, json(clientToken)), String.class);
    }

    /** {@code POST /book/{slug}/booking} — legacy single-service body ({@code serviceId}). */
    protected ResponseEntity<String> guestBookSingle(String guestToken, String masterSlug, UUID masterServiceId,
                                                     ZonedDateTime startsAt) {
        return restTemplate.exchange("/api/v1/book/" + masterSlug + "/booking", HttpMethod.POST,
                new HttpEntity<>(guestBody(startsAt).put("serviceId", masterServiceId.toString()).toString(),
                        guestHeaders(guestToken)), String.class);
    }

    /** {@code POST /book/{slug}/booking} — visit body ({@code masterServiceIds}). */
    protected ResponseEntity<String> guestBookVisit(String guestToken, String masterSlug, List<UUID> masterServiceIds,
                                                    ZonedDateTime startsAt) {
        return restTemplate.exchange("/api/v1/book/" + masterSlug + "/booking", HttpMethod.POST,
                new HttpEntity<>(guestVisitBody(masterServiceIds, startsAt),
                        guestHeaders(guestToken)), String.class);
    }

    /** {@code POST /book/s/{slug}/masters/{masterId}/booking} — the salon route (the website sends a visit body). */
    protected ResponseEntity<String> guestBookSalon(String guestToken, String salonSlug, UUID masterId,
                                                    List<UUID> masterServiceIds, ZonedDateTime startsAt) {
        return restTemplate.exchange("/api/v1/book/s/" + salonSlug + "/masters/" + masterId + "/booking",
                HttpMethod.POST,
                new HttpEntity<>(guestVisitBody(masterServiceIds, startsAt),
                        guestHeaders(guestToken)), String.class);
    }

    // ── assertions ───────────────────────────────────────────────────────────────

    /** 409 with {@code data.code = BOOKING_UNAVAILABLE} and the scope's neutral message — nothing else. */
    protected void assertUnavailable(ResponseEntity<String> resp, String expectedMessage) {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readTree(resp);
        assertThat(body.path("success").asBoolean(true)).isFalse();
        assertThat(body.path("data").path("code").asText()).isEqualTo(BookingUnavailableException.BOOKING_UNAVAILABLE);
        assertThat(body.path("data").size()).as("data carries the code only").isEqualTo(1);
        assertThat(body.path("message").asText()).isEqualTo(expectedMessage);
        assertThat(resp.getBody()).doesNotContainIgnoringCase("block").doesNotContainIgnoringCase("заблок");
    }

    protected void assertCreated(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    protected JsonNode readTree(ResponseEntity<String> resp) {
        try {
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected int countBookingsOf(UUID masterId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = ?", Integer.class, masterId, status);
    }

    // ── internals ────────────────────────────────────────────────────────────────

    /** Every create call pins the same {@code X-Request-Id}, so two refusal bodies compare byte-for-byte. */
    private static HttpHeaders json(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ParityRequestId.on(headers);
    }

    /** Fresh source IP per call so the per-IP guest-booking bucket never carries budget across tests. */
    private static HttpHeaders guestHeaders(String guestToken) {
        HttpHeaders headers = json(guestToken);
        var random = ThreadLocalRandom.current();
        headers.set("X-Forwarded-For", "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "."
                + random.nextInt(1, 255));
        return headers;
    }

    /** Guest body without its services field — every value escaped by Jackson, never concatenated. */
    private ObjectNode guestBody(ZonedDateTime startsAt) {
        return objectMapper.createObjectNode()
                .put("startsAt", startsAt.toOffsetDateTime().toString())
                .put("name", "Олена")
                .put("surname", "Коваль")
                .put("privacyAccepted", true)
                .put("privacyPolicyVersion", PRIVACY_VERSION);
    }

    private String guestVisitBody(List<UUID> masterServiceIds, ZonedDateTime startsAt) {
        ObjectNode node = guestBody(startsAt);
        node.set("masterServiceIds", idArray(masterServiceIds));
        return node.toString();
    }

    private ArrayNode idArray(List<UUID> ids) {
        ArrayNode array = objectMapper.createArrayNode();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }
}
