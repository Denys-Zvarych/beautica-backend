package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.beautica.client.provider.ProviderClientFixtures.Salon;
import com.beautica.common.security.PhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.beautica.client.provider.ProviderClientFixtures.freshPhone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B550 — the client picker contract end to end (CLI-40 phone mode, CLI-79 name parts, CLI-D14 owner rule). Fills the
 * gaps left by {@link ProviderClientControllerIT} / {@link ProviderClientCardIT}: SALON_ADMIN, the {@code ?masterId=}
 * scope, typed phone formats, the 2-digit floor, a blocked REGISTERED row in phone mode, a SALON_MASTER name match on
 * digits, blank/padded name parts, and a body-level "no existence oracle" check (a foreign number answers exactly like
 * a number nobody has).
 */
@DisplayName("Client picker — B550 phone search + card name parts")
class ProviderClientPickerIT extends AbstractIntegrationTest {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;

    private ProviderClientFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Operator code of the fresh phones this class seeds (+38063xxxxxxx). */
    private static final String OPERATOR = "63";

    /** The {@code data} node (a PageResponse) of a 200 list search. */
    private JsonNode page(String token, String url, String q) throws Exception {
        ResponseEntity<String> resp = fx.get(token, url + (url.contains("?") ? "&" : "?") + "q={q}", q);
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private List<JsonNode> search(String token, String url, String q) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        page(token, url, q).path("data").forEach(out::add);
        return out;
    }

    private static List<String> names(List<JsonNode> rows) {
        return rows.stream().map(r -> r.path("displayName").asText()).toList();
    }

    private UUID verifiedClient(String first, String last, String phone) {
        UUID id = fx.client(first, last);
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?", phone, id);
        return id;
    }

    /** Foreign number: empty page, total 0, and a body byte-identical to a number nobody has (no oracle). */
    private void assertNoOracle(String token, String url, String foreignPhone) throws Exception {
        JsonNode foreign = page(token, url, foreignPhone);
        JsonNode nobody = page(token, url, "+380 99 000 00 00");

        assertThat(foreign.path("data").size()).as("no row for a number held elsewhere: %s", foreign).isZero();
        assertThat(foreign.path("totalElements").asLong()).as("total").isZero();
        assertThat(foreign).as("body identical to a never-existing number").isEqualTo(nobody);
    }

    // ── 1. owner rule per role (CLI-D14) ─────────────────────────────────────

    @Nested
    @DisplayName("owner rule per role")
    class OwnerRule {

        @Test
        @DisplayName("IM: own client found by phone; another IM's guest and a never-booked app user answer like an unknown number")
        void should_findOnlyOwnClient_when_IMSearchesByPhone() throws Exception {
            Independent me = fx.independent();
            Independent other = fx.independent();
            String own = freshPhone(OPERATOR);
            String foreignGuest = freshPhone(OPERATOR);
            String appUser = freshPhone(OPERATOR);
            fx.booking(me.masterId(), null, verifiedClient("Своя", "Клієнтка", own), "COMPLETED", fx.at(-5));
            fx.guestBooking(other.masterId(), null, foreignGuest, "Чужа", "Гостя", "COMPLETED", fx.at(-4));
            verifiedClient("Платформна", "Користувачка", appUser);
            String token = fx.token(me.email());

            assertThat(names(search(token, ME_URL, own))).as("control: own client").containsExactly("Своя Клієнтка");
            assertNoOracle(token, ME_URL, foreignGuest);
            assertNoOracle(token, ME_URL, appUser);
        }

        @Test
        @DisplayName("Salon owner: whole salon (a second master's guest) found; another salon's guest and a never-booked app user answer like an unknown number")
        void should_searchWholeSalon_when_ownerSearchesByPhone() throws Exception {
            Salon s = fx.salon();
            Salon other = fx.salon();
            UUID secondMaster = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
            String ownOfSecondMaster = freshPhone(OPERATOR);
            String foreignGuest = freshPhone(OPERATOR);
            String appUser = freshPhone(OPERATOR);
            fx.guestBooking(secondMaster, s.salonId(), ownOfSecondMaster, "Гостя", "Другої", "COMPLETED", fx.at(-5));
            fx.guestBooking(other.masterId(), other.salonId(), foreignGuest, "Чужа", "Гостя", "COMPLETED", fx.at(-4));
            verifiedClient("Платформна", "Користувачка", appUser);
            String token = fx.token(s.ownerEmail());
            String url = SALON_URL.formatted(s.salonId());

            assertThat(names(search(token, url, ownOfSecondMaster))).containsExactly("Гостя Другої");
            assertNoOracle(token, url, foreignGuest);
            assertNoOracle(token, url, appUser);
        }

        @Test
        @DisplayName("Salon admin: whole salon found by phone; another salon's verified client and a never-booked app user answer like an unknown number")
        void should_searchWholeSalon_when_adminSearchesByPhone() throws Exception {
            Salon s = fx.salon();
            Salon other = fx.salon();
            UUID secondMaster = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
            String own = freshPhone(OPERATOR);
            String foreignClient = freshPhone(OPERATOR);
            String appUser = freshPhone(OPERATOR);
            fx.booking(secondMaster, s.salonId(), verifiedClient("Своя", "Клієнтка", own), "COMPLETED", fx.at(-5));
            fx.booking(other.masterId(), other.salonId(), verifiedClient("Чужа", "Клієнтка", foreignClient),
                    "COMPLETED", fx.at(-4));
            verifiedClient("Платформна", "Користувачка", appUser);
            String token = fx.token(fx.admin(s.salonId()));
            String url = SALON_URL.formatted(s.salonId());

            assertThat(names(search(token, url, own))).containsExactly("Своя Клієнтка");
            assertNoOracle(token, url, foreignClient);
            assertNoOracle(token, url, appUser);
        }

        @Test
        @DisplayName("Salon owner with ?masterId=: phone search stays inside that master's clients")
        void should_keepMasterFilter_when_ownerSearchesByPhoneWithMasterId() throws Exception {
            Salon s = fx.salon();
            UUID secondMaster = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
            String ofSecond = freshPhone(OPERATOR);
            fx.guestBooking(secondMaster, s.salonId(), ofSecond, "Гостя", "Другої", "COMPLETED", fx.at(-5));
            String token = fx.token(s.ownerEmail());
            String salonUrl = SALON_URL.formatted(s.salonId());

            assertThat(search(token, salonUrl + "?masterId=" + s.masterId(), ofSecond)).as("first master").isEmpty();
            assertThat(names(search(token, salonUrl + "?masterId=" + secondMaster, ofSecond)))
                    .as("control: second master").containsExactly("Гостя Другої");
        }
    }

    // ── 2. phone formats + name-mode floor ───────────────────────────────────

    @Nested
    @DisplayName("phone formats")
    class Formats {

        /** {@code +38063ABCDEFG} written the way a person types it. */
        private static String format(String e164, String style) {
            String op = e164.substring(4, 6);     // 63
            String sub = e164.substring(6);       // 7 digits
            String a = sub.substring(0, 3);
            String b = sub.substring(3, 5);
            String c = sub.substring(5);
            return switch (style) {
                case "intl-spaced" -> "+380 " + op + " " + a + " " + b + " " + c;
                case "local-brackets" -> "(0" + op + ")" + a + "-" + b + "-" + c;
                case "local-plain" -> "0" + op + sub;
                case "intl-no-plus" -> "380" + op + sub;
                default -> throw new IllegalArgumentException(style);
            };
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"intl-spaced", "local-brackets", "local-plain", "intl-no-plus"})
        @DisplayName("every typed format of a number finds the same verified client and the same guest")
        void should_findSameClient_when_phoneTypedInAnyFormat(String style) throws Exception {
            Salon s = fx.salon();
            String registered = freshPhone(OPERATOR);
            String guest = freshPhone(OPERATOR);
            fx.booking(s.masterId(), s.salonId(), verifiedClient("Марія", "Формат", registered), "COMPLETED",
                    fx.at(-5));
            fx.guestBooking(s.masterId(), s.salonId(), guest, "Оля", "Формат", "COMPLETED", fx.at(-4));
            String token = fx.token(s.ownerEmail());
            String url = SALON_URL.formatted(s.salonId());

            assertThat(names(search(token, url, format(registered, style)))).as(format(registered, style))
                    .containsExactly("Марія Формат");
            assertThat(names(search(token, url, format(guest, style)))).as(format(guest, style))
                    .containsExactly("Оля Формат");
        }

        @Test
        @DisplayName("a 2-digit q is a name search: digits contained in every phone match nothing")
        void should_stayNameSearch_when_qHasTwoDigits() throws Exception {
            Salon s = fx.salon();
            fx.guestBooking(s.masterId(), s.salonId(), freshPhone(OPERATOR), "Оля", "Гостя", "COMPLETED", fx.at(-5));

            JsonNode result = page(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()), "63");

            assertThat(result.path("data").size()).as("'63' is in the phone but q < 3 digits: %s", result).isZero();
            assertThat(result.path("totalElements").asLong()).isZero();
        }

        @Test
        @DisplayName("digits followed by letters ('0631234x') stay a name search")
        void should_stayNameSearch_when_digitsMixedWithLetters() throws Exception {
            Salon s = fx.salon();
            String phone = freshPhone(OPERATOR);
            fx.guestBooking(s.masterId(), s.salonId(), phone, "Оля", "Гостя", "COMPLETED", fx.at(-5));
            String token = fx.token(s.ownerEmail());
            String url = SALON_URL.formatted(s.salonId());
            String digits = phone.substring(4);

            assertThat(search(token, url, digits + "x")).as("mixed").isEmpty();
            assertThat(search(token, url, digits)).as("control: digits alone match").hasSize(1);
        }
    }

    // ── 4. blocked registered client in phone mode ───────────────────────────

    @Test
    @DisplayName("a blocked REGISTERED client found by its verified phone is listed with blocked=true")
    void should_flagBlockedRegisteredClient_when_foundByPhone() throws Exception {
        Salon s = fx.salon();
        String phone = freshPhone(OPERATOR);
        UUID c = verifiedClient("Заблокована", "Клієнтка", phone);
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        fx.blockClientInSalon(s.salonId(), c);

        List<JsonNode> rows = search(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()), phone);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.path("userId").asText()).isEqualTo(c.toString());
            assertThat(r.path("blocked").asBoolean(false)).as("blocked").isTrue();
        });
    }

    // ── 6. SALON_MASTER: digits are a name search ────────────────────────────

    @Test
    @DisplayName("SALON_MASTER: a digit q matches a name holding those digits, never the guest whose phone holds them")
    void should_matchNameOnly_when_salonMasterSearchesDigits() throws Exception {
        Salon s = fx.salon();
        String phone = "+380630" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        String digits = phone.substring(4, 10); // 6 digits of the phone
        fx.guestBooking(s.masterId(), s.salonId(), phone, "Телефонна", "Гостя", "COMPLETED", fx.at(-5));
        fx.guestBooking(s.masterId(), s.salonId(), "+380501112233", "Кімната", digits, "COMPLETED", fx.at(-4));

        List<JsonNode> asMaster = search(fx.token(s.masterEmail()), ME_URL, digits);
        List<JsonNode> asOwner = search(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()), digits);

        assertThat(names(asMaster)).as("name match only").containsExactly("Кімната " + digits);
        assertThat(names(asOwner)).as("control: owner gets name + phone matches")
                .containsExactlyInAnyOrder("Кімната " + digits, "Телефонна Гостя");
    }

    // ── 5. card name parts, null-safe (CLI-79) ───────────────────────────────

    @Nested
    @DisplayName("card name parts")
    class NameParts {

        private JsonNode card(String token, String url) throws Exception {
            ResponseEntity<String> resp = fx.get(token, url);
            assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
            return objectMapper.readTree(resp.getBody()).path("data");
        }

        @Test
        @DisplayName("registered single-word name: firstName set, a NULL last name is null")
        void should_returnNullLastName_when_registeredHasNoLastName() throws Exception {
            Salon s = fx.salon();
            UUID c = fx.client("Мадонна", null);
            fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));

            JsonNode data = card(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()) + "/users/" + c);

            assertThat(data.path("firstName").asText()).isEqualTo("Мадонна");
            assertThat(data.path("lastName").isNull()).as("lastName null: %s", data.path("lastName")).isTrue();
        }

        @Test
        @DisplayName("registered blank first name is null and a padded last name is stripped")
        void should_nullBlankAndStripPadded_when_registeredNamePartsUntidy() throws Exception {
            Independent im = fx.independent();
            UUID c = fx.client("   ", "  Коваль ");
            fx.booking(im.masterId(), null, c, "COMPLETED", fx.at(-5));

            JsonNode data = card(fx.token(im.email()), ME_URL + "/users/" + c);

            assertThat(data.path("firstName").isNull()).as("firstName null: %s", data.path("firstName")).isTrue();
            assertThat(data.path("lastName").asText()).isEqualTo("Коваль");
        }

        @Test
        @DisplayName("guest whitespace-only surname is null and a padded name is stripped")
        void should_nullBlankSurname_when_guestSurnameWhitespace() throws Exception {
            Salon s = fx.salon();
            UUID guest = fx.guestBooking(s.masterId(), s.salonId(), freshPhone(OPERATOR), "  Оля ", "   ", "COMPLETED",
                    fx.at(-5));

            JsonNode data = card(fx.token(s.ownerEmail()), SALON_URL.formatted(s.salonId()) + "/guests/" + guest);

            assertThat(data.path("firstName").asText()).isEqualTo("Оля");
            assertThat(data.path("lastName").isNull()).as("lastName null: %s", data.path("lastName")).isTrue();
        }
    }
}
