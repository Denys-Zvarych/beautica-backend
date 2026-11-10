package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.beautica.favorite.dto.AddFavoriteRequest;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 466 — read paths when one owner holds two COPIES of one service type («Класичний масаж»
 * 60 хв / 500 + 90 хв / 700, same name — D2 lets copies differ by duration alone). Pins search
 * (owner listed once, correct totals, «від» = cheaper copy), favourites (per copy), the visit create
 * (the same type twice in one visit is allowed — track D4 — and the block sums both copies) and the
 * multi-service slot list (sized to that sum). Acceptance 1–4 on an independent master, 5 on a salon
 * whose master is assigned both salon copies. Nothing here is new behaviour; it is a regression pin.
 *
 * <p>Negative pins (security review): an INACTIVE copy — its definition deactivated, or only its
 * assignment — leaves search and the «від» floor and cannot be favourited; a copy cannot be booked
 * from a non-bookable master, nor mixed with another master's assignment in one visit.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 466 — service copies on search, favourites, visit and slots")
class ServiceCopiesReadPathsIT extends AbstractIntegrationTest {

    private static final String COPY_NAME = "Класичний масаж";
    private static final int SHORT_MIN = 60;
    private static final int LONG_MIN = 90;
    private static final int BUFFER_MIN = 5;
    private static final BigDecimal SHORT_PRICE = new BigDecimal("500.00");
    private static final BigDecimal LONG_PRICE = new BigDecimal("700.00");
    /** Σ (duration + buffer) of both copies — the D4 visit block. */
    private static final int PAIR_BLOCK_MIN = SHORT_MIN + BUFFER_MIN + LONG_MIN + BUFFER_MIN;
    /** Plain password of every {@link BookableMasterSeeder} staff row (its BCrypt cost-4 hash). */
    private static final String SEEDED_PASSWORD = "test-password";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private BookabilityHttp http;
    private BookingTestFixtures bookings;
    private UUID typeId;
    private String typeSlug;

    @BeforeEach
    void setUp() {
        http = new BookabilityHttp(restTemplate, objectMapper);
        bookings = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        typeId = jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
        typeSlug = jdbcTemplate.queryForObject("SELECT slug FROM service_types WHERE id = ?", String.class, typeId);
    }

    // ── AC1 + AC2 — search ──────────────────────────────────────────────────────

    @Test
    @DisplayName("AC1/AC2: independent master with copies 60 + 90 appears ONCE under the type filter; "
            + "totals count it once; «від» is the cheaper copy")
    void should_listMasterOnceWithCheaperFloor_when_masterHoldsTwoCopiesOfFilteredType() throws Exception {
        Copies copies = independentMasterWithCopies("Copyholder");
        UUID control = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Singlecopy");
        assign(control, insertDefinition("INDEPENDENT_MASTER", control, SHORT_MIN, SHORT_PRICE));
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, control);

        JsonNode page = http.get(masterSearchUrl(20));
        JsonNode pagedByOne = http.get(masterSearchUrl(1));

        assertThat(ids(page, "masterId"))
                .containsExactlyInAnyOrder(copies.ownerId().toString(), control.toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(2L);
        assertThat(pagedByOne.path("totalPages").asInt()).as("one row per master at size=1").isEqualTo(2);
        assertThat(rowOf(page, "masterId", copies.ownerId()).path("minEffectivePrice").decimalValue())
                .isEqualByComparingTo(SHORT_PRICE);
    }

    @Test
    @DisplayName("AC5 (search): salon whose master holds salon copies 60 + 90 appears ONCE; «від» is the cheaper copy")
    void should_listSalonOnceWithCheaperFloor_when_salonHoldsTwoCopiesOfFilteredType() throws Exception {
        SalonCopies salon = salonWithCopies("Copies Studio");

        JsonNode page = http.get(salonSearchUrl());

        assertThat(ids(page, "salonId")).containsExactly(salon.salonId().toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(page.path("data").path(0).path("priceMin").decimalValue()).isEqualByComparingTo(SHORT_PRICE);
    }

    // ── AC3 — favourites ────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC3: favouriting the 90 copy lists only the 90 copy, not the 60 one")
    void should_favouriteOnlyThatCopy_when_independentMasterCopyFavourited() throws Exception {
        Copies copies = independentMasterWithCopies("Favcopy");
        String client = newClientToken("fav");

        http.addFavorite(client, FavoriteTargetType.SERVICE, copies.longAssignment());
        JsonNode favourites = http.get("/api/v1/favorites/services", client);

        assertThat(ids(favourites, "masterServiceId")).containsExactly(copies.longAssignment().toString());
        assertThat(favourites.path("data").path(0).path("durationMinutes").asInt()).isEqualTo(LONG_MIN);
    }

    @Test
    @DisplayName("AC5 (favourites): salon copy 90 favourited on both arms lists only the 90 copy")
    void should_favouriteOnlyThatCopy_when_salonCopyFavourited() throws Exception {
        SalonCopies salon = salonWithCopies("Fav Studio");
        String client = newClientToken("salonfav");

        http.addFavorite(client, FavoriteTargetType.SERVICE, salon.copies().longAssignment());
        http.addFavorite(client, FavoriteTargetType.SALON_SERVICE, salon.longDefinition());
        JsonNode favourites = http.get("/api/v1/favorites/services", client);

        assertThat(ids(favourites, "serviceDefId"))
                .containsExactly(salon.longDefinition().toString(), salon.longDefinition().toString());
        assertThat(ids(favourites, "masterServiceId")).contains(salon.copies().longAssignment().toString())
                .doesNotContain(salon.copies().shortAssignment().toString());
    }

    // ── AC4 — visit + slots ─────────────────────────────────────────────────────

    @Test
    @DisplayName("AC4: copy 60 + copy 90 in one visit → 201, items keep their own duration/price, block = 160")
    void should_bookBothCopiesInOneVisit_when_independentMasterHoldsCopies() throws Exception {
        Copies copies = independentMasterWithCopies("Visitcopy");

        assertVisitBooksBothCopies(copies, newClientToken("visit"));
    }

    @Test
    @DisplayName("AC4: the slot list for copy 60 + copy 90 is sized to the 160-min summed block")
    void should_sizeSlotsToSummedCopies_when_independentMasterHoldsCopies() throws Exception {
        Copies copies = independentMasterWithCopies("Slotcopy");

        assertSlotsSizedToPair(copies, newClientToken("slots"));
    }

    @Test
    @DisplayName("AC5 (visit): salon master books salon copies 60 + 90 in one visit → 201, block = 160")
    void should_bookBothCopiesInOneVisit_when_salonMasterHoldsCopies() throws Exception {
        SalonCopies salon = salonWithCopies("Visit Studio");

        assertVisitBooksBothCopies(salon.copies(), newClientToken("salonvisit"));
    }

    @Test
    @DisplayName("AC5 (slots): salon master's slot list for copies 60 + 90 is sized to 160 min")
    void should_sizeSlotsToSummedCopies_when_salonMasterHoldsCopies() throws Exception {
        SalonCopies salon = salonWithCopies("Slot Studio");

        assertSlotsSizedToPair(salon.copies(), newClientToken("salonslots"));
    }

    // ── negative pins — inactive copies, non-bookable master, cross-master mix ──

    @Test
    @DisplayName("Inactive copy (definition deactivated via API) leaves the master's «від»: floor becomes the 90 copy")
    void should_floorMasterAtRemainingCopy_when_cheaperCopyDefinitionDeactivated() throws Exception {
        Copies copies = independentMasterWithCopies("Deadcopy");
        deleteAsOk("/api/v1/services/" + definitionOf(copies.shortAssignment()), masterToken(copies.ownerMasterId()));

        JsonNode page = http.get(masterSearchUrl(20));

        assertThat(ids(page, "masterId")).containsExactly(copies.ownerId().toString());
        assertThat(rowOf(page, "masterId", copies.ownerId()).path("minEffectivePrice").decimalValue())
                .as("«від» must skip the deactivated 60 copy").isEqualByComparingTo(LONG_PRICE);
    }

    @Test
    @DisplayName("Inactive copy (assignment only inactive, definition active) leaves the master's «від»")
    void should_floorMasterAtRemainingCopy_when_cheaperCopyAssignmentInactive() throws Exception {
        Copies copies = independentMasterWithCopies("Offcopy");
        // No endpoint deactivates an independent master's assignment alone (its DELETE retires the
        // definition) — flip the row directly, the definition stays active.
        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", copies.shortAssignment());

        JsonNode page = http.get(masterSearchUrl(20));

        assertThat(ids(page, "masterId")).containsExactly(copies.ownerId().toString());
        assertThat(rowOf(page, "masterId", copies.ownerId()).path("minEffectivePrice").decimalValue())
                .as("«від» must skip the inactive 60 assignment").isEqualByComparingTo(LONG_PRICE);
    }

    @Test
    @DisplayName("Inactive salon copy (definition deactivated by the owner) leaves the salon's «від»")
    void should_floorSalonAtRemainingCopy_when_cheaperCopyDefinitionDeactivated() throws Exception {
        SalonCopies salon = salonWithCopies("Deadcopy Studio");
        deleteAsOk("/api/v1/services/" + definitionOf(salon.copies().shortAssignment()), ownerToken(salon.salonId()));

        JsonNode page = http.get(salonSearchUrl());

        assertThat(ids(page, "salonId")).containsExactly(salon.salonId().toString());
        assertThat(page.path("data").path(0).path("priceMin").decimalValue())
                .as("salon «від» must skip the deactivated 60 copy").isEqualByComparingTo(LONG_PRICE);
    }

    @Test
    @DisplayName("Inactive salon copy (unassigned from its only master by the owner) leaves the salon's «від»")
    void should_floorSalonAtRemainingCopy_when_cheaperCopyUnassigned() throws Exception {
        SalonCopies salon = salonWithCopies("Offcopy Studio");
        Copies copies = salon.copies();
        deleteAsOk("/api/v1/salons/" + salon.salonId() + "/masters/" + copies.ownerMasterId() + "/services/"
                + definitionOf(copies.shortAssignment()), ownerToken(salon.salonId()));

        JsonNode page = http.get(salonSearchUrl());

        assertThat(ids(page, "salonId")).containsExactly(salon.salonId().toString());
        assertThat(page.path("data").path(0).path("priceMin").decimalValue())
                .as("salon «від» must skip the unassigned (unbookable) 60 copy").isEqualByComparingTo(LONG_PRICE);
    }

    @Test
    @DisplayName("A copy of a non-bookable master (no schedule) is refused on POST /appointments; nothing is written")
    void should_refuseVisit_when_copiesBelongToNonBookableMaster() throws Exception {
        Copies copies = independentMasterWithCopies("Closedcopy");
        BookableMasterSeeder.removeSchedule(jdbcTemplate, copies.ownerMasterId());

        ResponseEntity<String> resp = postVisit(newClientToken("closed"), copies.ownerMasterId(),
                List.of(copies.shortAssignment(), copies.longAssignment()), visitStart());

        assertThat(resp.getStatusCode().is4xxClientError()).as("status %s body %s", resp.getStatusCode(), resp.getBody())
                .isTrue();
        assertThat(bookingRowsOf(copies.ownerMasterId())).isZero();
    }

    @Test
    @DisplayName("A copy cannot be mixed with another master's assignment in one visit → 404, nothing written")
    void should_refuseVisit_when_copyMixedWithAnotherMastersAssignment() throws Exception {
        Copies mine = independentMasterWithCopies("Mixmine");
        Copies theirs = independentMasterWithCopies("Mixtheirs");

        ResponseEntity<String> resp = postVisit(newClientToken("mix"), mine.ownerMasterId(),
                List.of(mine.shortAssignment(), theirs.longAssignment()), visitStart());

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).as("no id enumeration oracle").doesNotContain(theirs.longAssignment().toString());
        assertThat(bookingRowsOf(mine.ownerMasterId()) + bookingRowsOf(theirs.ownerMasterId())).isZero();
    }

    @Test
    @DisplayName("A deactivated copy cannot be favourited (SERVICE arm) → 400, favourites stay empty")
    void should_refuseFavourite_when_copyDefinitionDeactivated() throws Exception {
        Copies copies = independentMasterWithCopies("Favdead");
        deleteAsOk("/api/v1/services/" + definitionOf(copies.longAssignment()), masterToken(copies.ownerMasterId()));
        String client = newClientToken("favdead");

        ResponseEntity<String> resp = postFavorite(client, FavoriteTargetType.SERVICE, copies.longAssignment());

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ids(http.get("/api/v1/favorites/services", client), "masterServiceId")).isEmpty();
    }

    @Test
    @DisplayName("An unassigned salon copy cannot be favourited (SERVICE arm) → 400, favourites stay empty")
    void should_refuseFavourite_when_salonCopyUnassigned() throws Exception {
        SalonCopies salon = salonWithCopies("Favoff Studio");
        Copies copies = salon.copies();
        deleteAsOk("/api/v1/salons/" + salon.salonId() + "/masters/" + copies.ownerMasterId() + "/services/"
                + salon.longDefinition(), ownerToken(salon.salonId()));
        String client = newClientToken("favoff");

        ResponseEntity<String> resp = postFavorite(client, FavoriteTargetType.SERVICE, copies.longAssignment());

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ids(http.get("/api/v1/favorites/services", client), "masterServiceId")).isEmpty();
    }

    // ── shared assertions ───────────────────────────────────────────────────────

    private void assertVisitBooksBothCopies(Copies copies, String clientToken) throws Exception {
        ResponseEntity<String> resp = postVisit(clientToken, copies.ownerMasterId(),
                List.of(copies.shortAssignment(), copies.longAssignment()), visitStart());

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("totalDurationMinutes").asInt()).isEqualTo(PAIR_BLOCK_MIN);
        JsonNode items = data.path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(1).path("startsAt").asText()).isEqualTo(items.get(0).path("endsAt").asText());
        UUID appointmentId = UUID.fromString(data.path("id").asText());
        assertThat(jdbcTemplate.queryForList(
                "SELECT duration_minutes_at_booking FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                Integer.class, appointmentId)).containsExactly(SHORT_MIN, LONG_MIN);
        assertThat(jdbcTemplate.queryForList(
                "SELECT price_at_booking FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                BigDecimal.class, appointmentId))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(SHORT_PRICE, LONG_PRICE);
    }

    private void assertSlotsSizedToPair(Copies copies, String clientToken) throws Exception {
        LocalDate date = visitDate();

        JsonNode slots = http.get("/api/v1/masters/" + copies.ownerMasterId() + "/slots?date=" + date
                + "&serviceId=" + copies.shortAssignment() + "&serviceId=" + copies.longAssignment(), clientToken)
                .path("slots");

        assertThat(slots).isNotEmpty();
        List<Long> blockMinutes = new ArrayList<>();
        slots.forEach(slot -> blockMinutes.add(Duration.between(
                ZonedDateTime.parse(slot.path("startsAt").asText()),
                ZonedDateTime.parse(slot.path("endsAt").asText())).toMinutes()));
        assertThat(blockMinutes).containsOnly((long) PAIR_BLOCK_MIN);
        // 09:00–17:00 day on a 30-min grid: the last start whose 160-min block fits is 14:00.
        assertThat(ZonedDateTime.parse(slots.get(slots.size() - 1).path("startsAt").asText())
                .withZoneSameInstant(TimeZones.KYIV).toLocalTime()).hasToString("14:00");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /**
     * Two copies held by one master: the owning master id (for search/booking), and the two
     * {@code master_services} ids (the booking + SERVICE-favourite identity).
     */
    private record Copies(UUID ownerId, UUID ownerMasterId, UUID shortAssignment, UUID longAssignment) {
    }

    private record SalonCopies(UUID salonId, UUID longDefinition, Copies copies) {
    }

    private Copies independentMasterWithCopies(String name) {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, name);
        UUID shortAssignment = assign(masterId, insertDefinition("INDEPENDENT_MASTER", masterId, SHORT_MIN, SHORT_PRICE));
        UUID longAssignment = assign(masterId, insertDefinition("INDEPENDENT_MASTER", masterId, LONG_MIN, LONG_PRICE));
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        return new Copies(masterId, masterId, shortAssignment, longAssignment);
    }

    private SalonCopies salonWithCopies(String name) {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, name);
        UUID masterId = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId);
        UUID longDefinition = insertDefinition("SALON", salonId, LONG_MIN, LONG_PRICE);
        UUID shortAssignment = assign(masterId, insertDefinition("SALON", salonId, SHORT_MIN, SHORT_PRICE));
        UUID longAssignment = assign(masterId, longDefinition);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        return new SalonCopies(salonId, longDefinition, new Copies(salonId, masterId, shortAssignment, longAssignment));
    }

    /** One copy: same type, same name, its own duration + price (D2 — copies differ by duration). */
    private UUID insertDefinition(String ownerType, UUID ownerId, int duration, BigDecimal price) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'FIXED', ?, ?, true, NOW(), NOW())",
                defId, ownerType, ownerId, COPY_NAME, typeId, duration, price, BUFFER_MIN);
        return defId;
    }

    private UUID assign(UUID masterId, UUID defId) {
        UUID assignmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                assignmentId, masterId, defId);
        return assignmentId;
    }

    private String newClientToken(String tag) throws Exception {
        String email = "copies466-" + tag + "-" + System.nanoTime() + "@beautica.test";
        bookings.createUser(email, "CLIENT", null);
        return bookings.tokenFor(email);
    }

    private String masterSearchUrl(int size) {
        return "/api/v1/search/masters?serviceTypeSlugs=" + typeSlug + "&page=0&size=" + size;
    }

    private String salonSearchUrl() {
        return "/api/v1/search/salons?serviceTypeSlugs=" + typeSlug + "&size=20";
    }

    private UUID definitionOf(UUID assignmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT service_def_id FROM master_services WHERE id = ?", UUID.class, assignmentId);
    }

    private int bookingRowsOf(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings WHERE master_id = ?", Integer.class, masterId);
    }

    /** Token of the {@link BookableMasterSeeder} user behind {@code masterId}. */
    private String masterToken(UUID masterId) throws Exception {
        return loginSeeded(jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN masters m ON m.user_id = u.id WHERE m.id = ?", String.class, masterId));
    }

    /** Token of the seeded SALON_OWNER of {@code salonId}. */
    private String ownerToken(UUID salonId) throws Exception {
        return loginSeeded(jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN salons s ON s.owner_id = u.id WHERE s.id = ?", String.class, salonId));
    }

    private String loginSeeded(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, SEEDED_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login %s: %s", email, resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("accessToken").asText();
    }

    private void deleteAsOk(String url, String token) {
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.DELETE,
                new HttpEntity<>(bookings.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("DELETE %s → %s", url, resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<String> postFavorite(String clientToken, FavoriteTargetType type, UUID targetId) {
        return restTemplate.exchange("/api/v1/favorites", HttpMethod.POST,
                new HttpEntity<>(new AddFavoriteRequest(type, targetId), bookings.bearerHeaders(clientToken)),
                String.class);
    }

    private static JsonNode rowOf(JsonNode page, String idField, UUID id) {
        for (JsonNode row : page.path("data")) {
            if (row.path(idField).asText().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("no row with " + idField + " = " + id);
    }

    private static LocalDate visitDate() {
        return LocalDate.now(TimeZones.KYIV).plusDays(3);
    }

    private static ZonedDateTime visitStart() {
        return visitDate().atTime(10, 0).atZone(TimeZones.KYIV);
    }

    /** {@code POST /appointments}; the body is serialised by the template's Jackson converter (Q16). */
    private ResponseEntity<String> postVisit(String clientToken, UUID masterId, List<UUID> assignmentIds,
                                             ZonedDateTime startsAt) {
        Map<String, Object> body = Map.of("masterId", masterId, "masterServiceIds", assignmentIds,
                "startsAt", startsAt.toOffsetDateTime().toString());
        return restTemplate.exchange("/api/v1/appointments", HttpMethod.POST,
                new HttpEntity<>(body, bookings.bearerHeaders(clientToken)), String.class);
    }
}
