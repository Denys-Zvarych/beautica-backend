package com.beautica.booking;

import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.common.TimeZones;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 435 follow-up QA — the short guest link end to end, on the PRODUCTION origin {@code https://beautica.app}:
 * the confirmation SMS the guest actually receives (Turbosms wire) carries {@code https://beautica.app/c/<16 base62>},
 * that token opens {@code GET /api/v1/book/cancel/{token}}; a row issued before the short format (legacy UUID, sealed)
 * is still linked as {@code /c/<uuid>} and still opens; the salon «Адреса: … (note)» line and its URL-like-note drop.
 *
 * <p>Own context ({@code app.frontend.base-url} overridden) so the literal production link shape is asserted, not a
 * {@code localhost} stand-in. Every booking is created through the public LINK route, so the token, its hash and its
 * sealed copy are exactly what production writes.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.frontend.base-url=https://beautica.app"
})
@DisplayName("GuestShortLinkEndToEndIT — phase 435 follow-up: /c/<16> link from SMS to page, legacy UUID rows, note line")
class GuestShortLinkEndToEndIT extends AbstractWalkInBookingSmsIT {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern SHORT_LINK = Pattern.compile("https://beautica\\.app/c/([0-9A-Za-z]{16})$");

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private GuestCancelTokenCipher guestCancelTokenCipher;

    @Test
    @DisplayName("LINK salon booking → confirmation SMS is the exact salon copy with «Адреса: Хрещатик 10 (вхід з двору)» "
            + "and «Скасувати: https://beautica.app/c/<16>»; that token opens the page (CONFIRMED, same address + note)")
    void should_sendShortLinkThatOpensPage_when_linkBookingConfirmed() throws Exception {
        // Arrange
        setSalon("Студія Лілія", "вхід з двору");
        OffsetDateTime startsAt = tomorrowAtNoon();

        // Act
        ResponseEntity<String> created = createGuestBooking(startsAt);
        drainWorker.drain();

        // Assert — the SMS on the wire
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        String sms = sentText();
        Matcher link = SHORT_LINK.matcher(sms);
        assertThat(link.find()).as("SMS ends with the short production link, sms=%s", sms).isTrue();
        String token = link.group(1);
        ZonedDateTime kyiv = startsAt.atZoneSameInstant(TimeZones.KYIV);
        assertThat(sms).isEqualTo("Beautica: Ваш запис підтверджено!\n\n"
                + DATE_FMT.format(kyiv) + " о " + HH_MM.format(kyiv) + "\n"
                + "Салон Студія Лілія, майстер " + MASTER_NAME + ".\n"
                + "Адреса: Хрещатик 10 (вхід з двору)\n"
                + "Скасувати: https://beautica.app/c/" + token);
        assertThat(readJson(created.getBody()).path("cancelUrl").asText())
                .as("create response and SMS carry the same link").isEqualTo("https://beautica.app/c/" + token);

        // Assert — the token from the SMS opens the guest page
        ResponseEntity<String> page = getPage(token);
        assertThat(page.getStatusCode()).as(page.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(page.getBody());
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("cancellable").asBoolean()).isTrue();
        assertThat(body.path("salonName").asText()).isEqualTo("Студія Лілія");
        assertThat(body.path("address").asText()).isEqualTo("Хрещатик 10");
        assertThat(body.path("locationNote").asText()).isEqualTo("вхід з двору");
        assertThat(logMessages()).as("the raw token is never logged").noneMatch(line -> line.contains(token));
    }

    @Test
    @DisplayName("legacy row (UUID token issued before the short format, sealed) → 24h reminder links "
            + "https://beautica.app/c/<uuid> and that UUID still opens the page")
    void should_linkAndOpenLegacyUuidToken_when_rowIssuedBeforeShortFormat() throws Exception {
        // Arrange — a public-route row, then its token swapped for a legacy UUID one (hash + sealed copy)
        setSalon("Студія Лілія", null);
        ResponseEntity<String> created = createGuestBooking(tomorrowAtNoon());
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID bookingId = UUID.fromString(readJson(created.getBody()).path("bookingId").asText());
        drainWorker.drain();
        resetTurbosms();
        UUID legacy = UUID.randomUUID();
        jdbcTemplate.update("UPDATE bookings SET cancel_token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex'), "
                        + "cancel_token_sealed = ? WHERE id = ?", legacy.toString(),
                guestCancelTokenCipher.seal(legacy, GuestCancelTokenCipher.Row.booking(bookingId)), bookingId);

        // Act
        runReminderSweep(bookingId);

        // Assert
        assertThat(sentText()).as("legacy token keeps its UUID spelling under the short /c/ path")
                .contains("\nАдреса: Хрещатик 10\n")
                .endsWith("\nСкасувати запис: https://beautica.app/c/" + legacy);
        ResponseEntity<String> page = getPage(legacy.toString());
        assertThat(page.getStatusCode()).as(page.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(page.getBody()).path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("salon note that looks like a link («пишіть t.me/studio») → reminder keeps «Адреса: Хрещатик 10» with "
            + "NO brackets, the page returns locationNote null; the note text never reaches the SMS")
    void should_dropNoteKeepAddress_when_salonLocationNoteIsUrlLike() throws Exception {
        // Arrange
        setSalon("Студія Лілія", "пишіть t.me/studio");
        ResponseEntity<String> created = createGuestBooking(tomorrowAtNoon());
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        String cancelUrl = readJson(created.getBody()).path("cancelUrl").asText();
        UUID bookingId = UUID.fromString(readJson(created.getBody()).path("bookingId").asText());
        drainWorker.drain();
        resetTurbosms();

        // Act
        runReminderSweep(bookingId);

        // Assert
        OffsetDateTime remindedStart = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
        String time = HH_MM.format(remindedStart.atZoneSameInstant(TimeZones.KYIV));
        assertThat(sentText()).isEqualTo("Beautica: Нагадуємо, що завтра у Вас запис!\n\n"
                + "Студія Лілія | " + MASTER_NAME + " | " + SERVICE_NAME + " о " + time + "\n"
                + "Адреса: Хрещатик 10\n"
                + "Скасувати запис: " + cancelUrl).doesNotContain("t.me");
        JsonNode page = readJson(getPage(cancelUrl.substring(cancelUrl.lastIndexOf('/') + 1)).getBody());
        assertThat(page.path("address").asText()).isEqualTo("Хрещатик 10");
        assertThat(page.get("locationNote").isNull()).as("URL-like note is null on the page too").isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void setSalon(String name, String locationNote) {
        jdbcTemplate.update("UPDATE salons SET name = ?, street = 'Хрещатик', building_no = '10', location_note = ? "
                + "WHERE id = ?", name, locationNote, salon.salonId());
    }

    private ResponseEntity<String> getPage(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        return restTemplate.exchange("/api/v1/book/cancel/" + token, HttpMethod.GET, new HttpEntity<>(headers),
                String.class);
    }
}
