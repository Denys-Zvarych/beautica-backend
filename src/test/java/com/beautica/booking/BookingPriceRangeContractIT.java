package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack contract suite for {@code priceMaxAtBooking} — the booking price CEILING, frozen
 * onto the {@code bookings} row at creation by V119 as the companion to {@code priceAtBooking}'s
 * floor, so the mobile card can render the band the client actually agreed to.
 *
 * <p>Covers three things against a real Postgres instance:
 * <ul>
 *   <li><b>The freeze.</b> Both create paths ({@code POST /bookings} and the guest
 *       {@code POST /book/&#123;slug&#125;/booking}) persist the ceiling, and a subsequent edit to
 *       the {@code service_definitions} row does NOT change what an existing booking reports. This
 *       is the locked product decision: a past booking shows the band agreed AT BOOKING TIME.</li>
 *   <li><b>Read-path agreement.</b> The provider view ({@code BookingDetailResponse} via the
 *       entity-hydrate {@code findAllByIdsWithGraph} path) and the client view (the
 *       {@code ClientBookingDetailProjection} JPQL path) surface the identical value — trivially
 *       now that both read one stored column instead of each re-deriving a rule.</li>
 *   <li><b>Read paths read the COLUMN, not the rule.</b> Agreement alone cannot prove that: with
 *       the equal-value fixtures the rest of this suite uses, a read path that re-derived live
 *       would return the same number and stay green (mutation-proven). The DIVERGENT-FIXTURE block
 *       in the middle of this class exists solely to break that coincidence.</li>
 *   <li><b>No N+1.</b> See the three statement-count gates near the bottom, plus the
 *       hydrated-entity gate that catches the opposite regression — a re-added fetch join, which
 *       costs no statement at all.</li>
 * </ul>
 *
 * <p>The creation-time rule that decides whether a ceiling is frozen at all
 * ({@code priceOverride} set → no band; {@code FIXED} → no band; {@code RANGE} without an override
 * → {@code priceMax}) lives in {@code BookingPriceRange} and is pinned by its own unit test.
 */
@Import(TestSecurityConfig.class)
@DisplayName("priceMaxAtBooking — full HTTP stack over real Postgres")
class BookingPriceRangeContractIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final OffsetDateTime ANCHOR =
            OffsetDateTime.of(2032, 3, 10, 8, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private GuestTokenProvider guestTokenProvider;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THE FREEZE — creation writes the ceiling; later service edits cannot move it
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /bookings freezes the ceiling, and editing the service definition afterwards "
            + "leaves the existing booking's band untouched")
    void should_keepFrozenCeiling_when_providerEditsServiceAfterBooking() throws Exception {
        String masterEmail = "bprc-freeze-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-freeze-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID bookingId = createBookingViaApi(clientToken, masterId, serviceId);
        assertThat(getDetail(clientToken, bookingId).path("priceMaxAtBooking").decimalValue())
                .as("the band agreed at booking time")
                .isEqualByComparingTo("500.00");

        // The provider now doubles the ceiling and lifts the floor on the LIVE service row.
        jdbcTemplate.update(
                "UPDATE service_definitions SET price_max = 999.00, base_price = 400.00 "
                        + "WHERE id = (SELECT service_def_id FROM master_services WHERE id = ?)",
                serviceId);

        JsonNode afterEdit = getDetail(clientToken, bookingId);

        assertThat(afterEdit.path("priceMaxAtBooking").decimalValue())
                .as("a service edit must NOT retroactively rewrite an agreed band")
                .isEqualByComparingTo("500.00");
        assertThat(afterEdit.path("priceAtBooking").decimalValue())
                .as("the floor was already frozen and must not move either")
                .isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("POST /bookings freezes NO ceiling for a service later flipped FIXED -> RANGE — a "
            + "band can never grow onto a booking that never had one")
    void should_keepNullCeiling_when_providerFlipsServiceToRangeAfterBooking() throws Exception {
        String masterEmail = "bprc-flip-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-flip-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);  // FIXED, 500.00
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID bookingId = createBookingViaApi(clientToken, masterId, serviceId);

        jdbcTemplate.update(
                "UPDATE service_definitions SET price_type = 'RANGE', price_max = 900.00 "
                        + "WHERE id = (SELECT service_def_id FROM master_services WHERE id = ?)",
                serviceId);

        assertThat(getDetail(clientToken, bookingId).path("priceMaxAtBooking").isNull())
                .as("the client agreed to a single price; a later RANGE flip must not invent a band")
                .isTrue();
    }

    @Test
    @DisplayName("the guest (LINK) create path freezes the ceiling too — a booking made without an "
            + "account is as immune to a later service edit as an APP one")
    void should_freezeCeiling_when_guestBooksRangeService() throws Exception {
        String slug = "bprc-guest-" + Long.toString(System.nanoTime(), 36);
        String masterEmail = "bprc-guest-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterId);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        addWorkingHoursForEveryDay(masterId);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(15).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"serviceId":"%s","startsAt":"%s","name":"Оксана","surname":"Мельник"}
                """.formatted(serviceId, startsAt.toOffsetDateTime());
        HttpHeaders guestHeaders = new HttpHeaders();
        guestHeaders.setContentType(MediaType.APPLICATION_JSON);
        guestHeaders.setBearerAuth(guestTokenProvider.generate("+380509998877"));

        ResponseEntity<String> createResp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders), String.class);

        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID bookingId = UUID.fromString(
                objectMapper.readTree(createResp.getBody()).path("bookingId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_max_at_booking FROM bookings WHERE id = ?", BigDecimal.class, bookingId))
                .as("the guest create path must write the frozen ceiling, not leave it NULL")
                .isEqualByComparingTo("500.00");
        // Both ends asserted against DIFFERENT values on purpose. Booking.guestBooking takes
        // priceAtBooking and priceMaxAtBooking as adjacent positional BigDecimal parameters, so a
        // swapped pair compiles silently and would charge the guest the CEILING while recording the
        // floor as the band's top. Only a floor assertion can catch that here.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_at_booking FROM bookings WHERE id = ?", BigDecimal.class, bookingId))
                .as("the CHARGED price stays the floor — the ceiling must never land in this column")
                .isEqualByComparingTo("300.00");

        // And it survives a subsequent service edit, exactly as the APP path does.
        jdbcTemplate.update(
                "UPDATE service_definitions SET price_max = 999.00 "
                        + "WHERE id = (SELECT service_def_id FROM master_services WHERE id = ?)",
                serviceId);
        String masterToken = fixtures.tokenFor(masterEmail);

        assertThat(getDetail(masterToken, bookingId).path("priceMaxAtBooking").decimalValue())
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule moves the time window and re-prices NOTHING — "
            + "the band stays frozen even though the service was widened in between")
    void should_keepFrozenBand_when_bookingIsRescheduledAfterAServiceEdit() throws Exception {
        String masterEmail = "bprc-resched-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-resched-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        UUID bookingId = createBookingViaApi(clientToken, masterId, serviceId);

        // The provider widens the band AND lifts the floor on the LIVE service row, then the
        // client moves the booking. A reschedule is the one mutation that already rewrites entity
        // state, so it is the likeliest place for a future "refresh the derived fields" line to
        // land — and re-deriving here would rewrite an agreed band under cover of a time change.
        jdbcTemplate.update(
                "UPDATE service_definitions SET price_max = 999.00, base_price = 400.00 "
                        + "WHERE id = (SELECT service_def_id FROM master_services WHERE id = ?)",
                serviceId);
        ZonedDateTime newStartsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(4).withHour(12).withMinute(0).withSecond(0).withNano(0);
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"newStartsAt":"%s"}
                """.formatted(newStartsAt.toOffsetDateTime());

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode())
                .as("premise of this test — the reschedule must actually succeed, body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode rescheduled = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(rescheduled.path("startsAt").asText())
                .as("premise — the booking must really have moved")
                .isNotEqualTo(rescheduled.path("createdAt").asText());
        assertThat(rescheduled.path("priceMaxAtBooking").decimalValue())
                .as("moving a booking in time must never re-derive the band from the service's "
                        + "CURRENT price_max")
                .isEqualByComparingTo("500.00");
        assertThat(rescheduled.path("priceAtBooking").decimalValue())
                .as("nor re-read the floor from the service's CURRENT base_price")
                .isEqualByComparingTo("300.00");
        // And the row itself, not merely what the response mapper chose to render.
        assertThat(ceilingOf(bookingId))
                .as("the persisted ceiling must be untouched by the reschedule write")
                .isEqualByComparingTo("500.00");
    }

    /**
     * The status band-independence case. "Even a COMPLETED booking still shows the band the client
     * agreed to" is how this phase is framed, but every other freeze test above runs on a
     * {@code CONFIRMED} row — so the headline claim was structurally true (neither
     * {@code BookingPriceRange} nor either read path reads {@code status}) yet entirely unpinned.
     *
     * <p>Asserted through the CLIENT detail endpoint on a terminal, past booking, because that is
     * the scenario the claim is about: a service whose price has since moved on, and a booking that
     * can never be re-priced because it is already done. The status is re-read from the response so
     * a fixture that silently stopped applying it cannot leave this passing as a duplicate of
     * {@link #should_keepFrozenCeiling_when_providerEditsServiceAfterBooking}.
     */
    @Test
    @DisplayName("a COMPLETED booking keeps the band agreed at booking time when the service's "
            + "price is edited afterwards — the freeze is independent of booking status")
    void should_keepFrozenBand_when_bookingIsCompletedAndServiceIsEditedAfterwards() throws Exception {
        String masterEmail = "bprc-completed-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-completed-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        // A COMPLETED booking is by definition in the past; seeding it before ANCHOR (2032) keeps
        // the fixture a shape the real lifecycle can actually produce rather than a future
        // "completed" row that only the absence of a CHECK constraint permits.
        UUID bookingId = insertBooking(clientId, masterId, serviceId, ANCHOR.minusYears(4),
                new BigDecimal("300.00"), new BigDecimal("500.00"), "COMPLETED");
        String clientToken = fixtures.tokenFor(clientEmail);

        // The provider widens the ceiling and lifts the floor on the LIVE service row, long after
        // the appointment happened.
        jdbcTemplate.update(
                "UPDATE service_definitions SET price_max = 999.00, base_price = 400.00 "
                        + "WHERE id = (SELECT service_def_id FROM master_services WHERE id = ?)",
                serviceId);

        JsonNode afterEdit = getDetail(clientToken, bookingId);

        assertThat(afterEdit.path("status").asText())
                .as("premise of this test — the row must really be COMPLETED, or this silently "
                        + "degrades into a duplicate of the CONFIRMED freeze test above")
                .isEqualTo("COMPLETED");
        assertThat(afterEdit.path("priceMaxAtBooking").decimalValue())
                .as("a finished booking must still report the band agreed AT BOOKING TIME")
                .isEqualByComparingTo("500.00");
        assertThat(afterEdit.path("priceAtBooking").decimalValue())
                .as("and its floor, likewise frozen")
                .isEqualByComparingTo("300.00");
        assertThat(ceilingOf(bookingId))
                .as("the persisted ceiling must be untouched by the service edit")
                .isEqualByComparingTo("500.00");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // V120 BACKFILL — the freshness guard
    // ══════════════════════════════════════════════════════════════════════════

    private static final String V120_MIGRATION_PATH =
            "db/migration/V120__backfill_bookings_price_max_at_booking.sql";

    /**
     * Loads the {@code UPDATE bookings …} statement out of the SHIPPED
     * {@code V120__backfill_bookings_price_max_at_booking.sql} on the classpath, so the assertions
     * below exercise the migration itself rather than a copy of it. Flyway has already run it once
     * against this container at a point where no booking existed; re-executing it here drives it
     * against rows this test controls.
     *
     * <p><b>Why not a verbatim string literal here</b> (which is what this suite shipped first): a
     * duplicated literal is only ever as truthful as the last person to keep the two in sync, and
     * nothing fails when they drift. A future edit to the freshness guard would leave this test
     * green against the OLD statement while the NEW one goes to production unproven — the precise
     * failure mode this test exists to prevent, one indirection up. Reading the file removes the
     * class of bug rather than commenting against it.
     *
     * <p>Comments and the two {@code SET LOCAL} timeout statements are stripped (they are
     * transaction-scoped session settings, not part of the backfill's semantics, and outside a
     * migration transaction they are no-ops that only emit a warning). The single-UPDATE assertion
     * is itself load-bearing: if V120 ever grows a second data statement, this test would silently
     * stop covering the rest of the migration, so it fails instead.
     */
    private String v120BackfillStatement() throws java.io.IOException {
        String sql;
        try (var in = new org.springframework.core.io.ClassPathResource(V120_MIGRATION_PATH).getInputStream()) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String stripped = sql.lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .reduce("", (a, b) -> a + "\n" + b);

        List<String> updates = java.util.Arrays.stream(stripped.split(";"))
                .map(String::trim)
                .filter(s -> s.toUpperCase(java.util.Locale.ROOT).startsWith("UPDATE"))
                .toList();

        assertThat(updates)
                .as("V120 must contain exactly ONE data statement, or this test silently stops "
                        + "covering the rest of the migration — found %s", updates.size())
                .hasSize(1);
        return updates.get(0);
    }

    @Test
    @DisplayName("the shipped V120 file contains exactly one UPDATE, targeting "
            + "bookings.price_max_at_booking, carrying EVERY predicate the guard tests below "
            + "exercise — a dropped conjunct fails here as well as behaviourally")
    void should_shipOneBackfillStatement_when_v120IsLoadedFromTheClasspath() throws Exception {
        String backfill = v120BackfillStatement();

        // Every conjunct listed here has a paired behavioural negative test below. The two layers
        // are deliberately redundant: the behavioural test proves the predicate DOES something,
        // this one proves the predicate that did it is still the one that SHIPS. Previously only
        // the two freshness conjuncts were listed, so deleting `sd.price_max >= b.price_at_booking`
        // — the sanity guard whose removal re-materialises defect #3, the inverted «400–350 ₴»
        // band — left the whole suite green.
        assertThat(backfill)
                .as("if V120 ever stops writing this column, the guard tests below would keep "
                        + "passing against a statement that no longer ships")
                .contains("SET price_max_at_booking = sd.price_max")
                .contains("b.price_max_at_booking IS NULL")
                .contains("ms.price_override IS NULL")
                .contains("sd.price_type = 'RANGE'")
                .contains("sd.price_max IS NOT NULL")
                .contains("sd.price_max >= b.price_at_booking")
                .contains("sd.updated_at <= b.created_at")
                .contains("ms.updated_at <= b.created_at");
    }

    @Test
    @DisplayName("V120 backfill leaves the ceiling NULL when the service was edited AFTER the "
            + "booking — reconstructing from current state would fabricate a band never agreed")
    void should_leaveNullCeiling_when_serviceWasEditedAfterBooking() throws Exception {
        String masterEmail = "bprc-backfill-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "bprc-backfill-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        // Both rows are identical to the backfill's other five predicates — RANGE, no override,
        // price_max >= price_at_booking, ceiling still NULL. Only updated_at separates them.
        UUID editedServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        UUID untouchedServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        UUID editedBookingId = insertBooking(clientId, masterId, editedServiceId,
                ANCHOR, new BigDecimal("300.00"), null);
        UUID untouchedBookingId = insertBooking(clientId, masterId, untouchedServiceId,
                ANCHOR.plusMinutes(90), new BigDecimal("300.00"), null);
        alignServiceTimestamps(editedServiceId, editedBookingId, "-1 day");
        alignServiceTimestamps(untouchedServiceId, untouchedBookingId, "-1 day");
        // The provider edits the service AFTER the booking was agreed — a FIXED->RANGE flip, a
        // dropped override or a raised base_price all land here as a bumped updated_at.
        touchServiceDefinitionAfterBooking(editedServiceId, editedBookingId);

        jdbcTemplate.execute(v120BackfillStatement());

        assertThat(ceilingOf(editedBookingId))
                .as("the service moved after the booking, so its CURRENT price_max is not the band "
                        + "the client agreed to — the row must stay NULL and render as the single "
                        + "frozen price_at_booking")
                .isNull();
        assertThat(ceilingOf(untouchedBookingId))
                .as("control: an untouched service IS a faithful reconstruction, so the backfill "
                        + "must still populate it — otherwise this test would pass vacuously")
                .isEqualByComparingTo("500.00");
    }

    // ── the remaining four backfill predicates ───────────────────────────────
    //
    // Until these landed, `sd.updated_at <= b.created_at` was the ONLY conjunct of the seven with
    // a negative test. Deleting the SANITY GUARD `sd.price_max >= b.price_at_booking` — the one
    // whose javadoc says removing it "would materialise exactly the «400–350 ₴» defect this change
    // eliminates" (V120:83-88) — left the entire suite green. Each test below pairs a row that
    // must be SKIPPED with an otherwise-identical CONTROL row that must be POPULATED, so a
    // predicate that stopped filtering fails on the skipped row and a backfill that stopped
    // running altogether fails on the control. Neither can pass vacuously.

    @Test
    @DisplayName("V120 backfill leaves the ceiling NULL when the service's CURRENT price_max is "
            + "BELOW the frozen price_at_booking — reconstructing it would materialise an inverted "
            + "«400–350 ₴» band (the sanity guard, defect #3)")
    void should_leaveNullCeiling_when_backfillWouldInvertTheBand() throws Exception {
        String masterEmail = "bprc-invert-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "bprc-invert-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        // The service now bands 300–350, but the booking was frozen at 400 — the provider has since
        // lowered their ceiling below what this client already agreed to pay. Every OTHER predicate
        // passes on this row (RANGE, no override, ceiling NULL, both timestamps stale), so only the
        // sanity guard can reject it.
        UUID invertingServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("350.00"), null);
        UUID sameCeilingServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("400.00"), null);
        UUID invertedBookingId = insertBooking(clientId, masterId, invertingServiceId,
                ANCHOR, new BigDecimal("400.00"), null);
        UUID boundaryBookingId = insertBooking(clientId, masterId, sameCeilingServiceId,
                ANCHOR.plusMinutes(90), new BigDecimal("400.00"), null);
        alignServiceTimestamps(invertingServiceId, invertedBookingId, "-1 day");
        alignServiceTimestamps(sameCeilingServiceId, boundaryBookingId, "-1 day");

        jdbcTemplate.execute(v120BackfillStatement());

        assertThat(ceilingOf(invertedBookingId))
                .as("350 < 400 would render as «400–350 ₴» — the row must stay NULL and show the "
                        + "single frozen price instead")
                .isNull();
        assertThat(ceilingOf(boundaryBookingId))
                .as("the guard is >=, not >: an equal ceiling is a degenerate but SANE band, so the "
                        + "boundary row must still be populated. This also stops the test passing "
                        + "vacuously if the backfill stopped writing anything at all.")
                .isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("V120 backfill leaves the ceiling NULL for a FIXED-priced service — a booking "
            + "agreed at one price must never acquire a band")
    void should_leaveNullCeiling_when_serviceIsFixed() throws Exception {
        String masterEmail = "bprc-fixed-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "bprc-fixed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        UUID fixedServiceId = createFixedService(masterId, new BigDecimal("300.00"));
        UUID rangeServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        UUID fixedBookingId = insertBooking(clientId, masterId, fixedServiceId,
                ANCHOR, new BigDecimal("300.00"), null);
        UUID rangeBookingId = insertBooking(clientId, masterId, rangeServiceId,
                ANCHOR.plusMinutes(90), new BigDecimal("300.00"), null);
        alignServiceTimestamps(fixedServiceId, fixedBookingId, "-1 day");
        alignServiceTimestamps(rangeServiceId, rangeBookingId, "-1 day");

        jdbcTemplate.execute(v120BackfillStatement());

        // HONEST SCOPE NOTE: this pins the OUTCOME for a FIXED service, not the `sd.price_type =
        // 'RANGE'` conjunct in isolation. chk_service_def_price_mode (V67:41) makes the two
        // co-vary — FIXED requires price_max IS NULL — so `sd.price_max IS NOT NULL` already
        // excludes every legitimately-FIXED row, and a row that falsifies ONE conjunct without the
        // other is unreachable through the CHECK. That redundancy is the design; what has value to
        // pin is that a real FIXED service never grows a ceiling, which is the client-visible claim.
        assertThat(ceilingOf(fixedBookingId))
                .as("a FIXED service has no ceiling to freeze — the booking renders as one price")
                .isNull();
        assertThat(ceilingOf(rangeBookingId))
                .as("control: the same master's RANGE service on the same day IS backfilled, so a "
                        + "backfill that did nothing at all cannot pass this test")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("V120 backfill leaves the ceiling NULL when the master's assignment carries a "
            + "priceOverride — the master fixed their own price, so there is no band to restore")
    void should_leaveNullCeiling_when_assignmentHasPriceOverride() throws Exception {
        String masterEmail = "bprc-override-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "bprc-override-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        // Both definitions are RANGE 300–500 and identical in every respect the backfill inspects.
        // ONLY master_services.price_override separates them — so this test can fail for exactly
        // one reason.
        UUID overriddenServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), new BigDecimal("400.00"));
        UUID plainServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        UUID overriddenBookingId = insertBooking(clientId, masterId, overriddenServiceId,
                ANCHOR, new BigDecimal("400.00"), null);
        UUID plainBookingId = insertBooking(clientId, masterId, plainServiceId,
                ANCHOR.plusMinutes(90), new BigDecimal("300.00"), null);
        alignServiceTimestamps(overriddenServiceId, overriddenBookingId, "-1 day");
        alignServiceTimestamps(plainServiceId, plainBookingId, "-1 day");

        jdbcTemplate.execute(v120BackfillStatement());

        assertThat(ceilingOf(overriddenBookingId))
                .as("an override means this master charges ONE price (400), not the definition's "
                        + "300–500 band — attaching 500 would invent a band the client never saw")
                .isNull();
        assertThat(ceilingOf(plainBookingId))
                .as("control: the same definition shape WITHOUT an override is backfilled")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("V120 backfill leaves the ceiling NULL when only the ASSIGNMENT was edited after "
            + "the booking — the ms.updated_at half of the freshness guard, which the existing "
            + "service-side test cannot reach")
    void should_leaveNullCeiling_when_assignmentWasEditedAfterBooking() throws Exception {
        String masterEmail = "bprc-msfresh-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "bprc-msfresh-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        UUID editedServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        UUID untouchedServiceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        UUID editedBookingId = insertBooking(clientId, masterId, editedServiceId,
                ANCHOR, new BigDecimal("300.00"), null);
        UUID untouchedBookingId = insertBooking(clientId, masterId, untouchedServiceId,
                ANCHOR.plusMinutes(90), new BigDecimal("300.00"), null);
        alignServiceTimestamps(editedServiceId, editedBookingId, "-1 day");
        alignServiceTimestamps(untouchedServiceId, untouchedBookingId, "-1 day");
        // The master re-points their own assignment AFTER the booking — dropping an override, or
        // re-activating the row — while the salon's definition is untouched. This is the ONLY way
        // to exercise `ms.updated_at <= b.created_at`: its sibling test bumps only
        // service_definitions.updated_at (touchServiceDefinitionAfterBooking), so deleting the
        // ms half of the guard left every existing test green.
        touchMasterServiceAfterBooking(editedServiceId, editedBookingId);

        jdbcTemplate.execute(v120BackfillStatement());

        assertThat(ceilingOf(editedBookingId))
                .as("the ASSIGNMENT moved after the booking, so the definition's current band is "
                        + "not provably the one the client agreed to — the row must stay NULL")
                .isNull();
        assertThat(ceilingOf(untouchedBookingId))
                .as("control: an assignment still older than its booking IS backfilled")
                .isEqualByComparingTo("500.00");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PROVIDER path — GET /bookings/me (BookingDetailResponse, entity hydrate)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER) — a booking with a frozen ceiling surfaces it, and "
            + "priceAtBooking stays the floor")
    void should_surfacePriceMax_when_providerBookingHasFrozenCeiling() throws Exception {
        String masterEmail = "bprc-provider-range-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("bprc-provider-range-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("300.00"), new BigDecimal("500.00"));

        JsonNode row = firstRow(callMyBookings(fixtures.tokenFor(masterEmail)));

        assertThat(row.path("priceAtBooking").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(row.path("priceMaxAtBooking").decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER) — a booking made against a RANGE service WITH a "
            + "priceOverride froze no ceiling, so the row reports null")
    void should_returnNullPriceMax_when_providerBookingIsRangeWithOverride() throws Exception {
        String masterEmail = "bprc-provider-override-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("bprc-provider-override-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), new BigDecimal("400.00"));
        insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("400.00"), null);

        JsonNode row = firstRow(callMyBookings(fixtures.tokenFor(masterEmail)));

        assertThat(row.path("priceAtBooking").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(row.path("priceMaxAtBooking").isNull())
                .as("priceOverride means the master fixed their own price — never a range")
                .isTrue();
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER) — a FIXED-service booking surfaces a null priceMaxAtBooking")
    void should_returnNullPriceMax_when_providerBookingIsFixed() throws Exception {
        String masterEmail = "bprc-provider-fixed-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("bprc-provider-fixed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("500.00"), null);

        JsonNode row = firstRow(callMyBookings(fixtures.tokenFor(masterEmail)));

        assertThat(row.path("priceMaxAtBooking").isNull()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIVERGENT FIXTURE — the only shape that can tell "reads the frozen column"
    //                     apart from "re-derives the rule live"
    // ══════════════════════════════════════════════════════════════════════════
    //
    // Every OTHER read-path test in this suite seeds a booking whose frozen ceiling happens to
    // EQUAL what live derivation would produce from the current service row (RANGE 300-500, no
    // override, frozen at 500). Against that fixture the two implementations are indistinguishable:
    // a read path that ignored the column and re-derived the rule would return the same number and
    // stay green. Mutation-proven during the Phase 26.9 QA audit — replacing
    // `b.priceMaxAtBooking` in hydrateClientBookingDetails with the live
    // `CASE WHEN sd.priceType = RANGE AND ms.priceOverride IS NULL THEN sd.priceMax END`
    // expression (i.e. reverting the entire phase on the CLIENT list path) left all sixteen tests
    // in this class PASSING.
    //
    // The fixtures below deliberately break that coincidence: the LIVE service says 999 while the
    // FROZEN column says 500 — the exact state a booking reaches when a provider widens their
    // service after the fact, which is the defect this phase exists to eliminate. Only a read path
    // that genuinely reads the stored column can answer 500.
    //
    // Both list paths and the detail endpoint get one, because they are THREE separate queries
    // (Specification ID page + hydrateClientBookingDetails for CLIENT, findAllByIdsWithGraph for
    // PROVIDER, findByIdWithFullGraph for detail) and a revert to live derivation in any one of
    // them is independently reachable.

    /**
     * Seeds a booking whose frozen band is NARROWER than the service's current one: live
     * derivation would answer 999.00, the frozen column answers 500.00.
     */
    private UUID insertBookingWithDivergentFrozenBand(UUID clientId, UUID masterId, BigDecimal frozenCeiling) {
        UUID serviceId = createRangeService(
                masterId, new BigDecimal("300.00"), new BigDecimal("999.00"), null);
        return insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("300.00"), frozenCeiling);
    }

    @Test
    @DisplayName("GET /me (CLIENT) reports the FROZEN ceiling, not the one the live service would "
            + "derive — the projection must read the column, never re-apply the rule")
    void should_readTheFrozenColumn_when_clientListDivergesFromLiveDerivation() throws Exception {
        String masterEmail = "bprc-diverge-client-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-diverge-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        insertBookingWithDivergentFrozenBand(clientId, masterId, new BigDecimal("500.00"));

        JsonNode row = firstRow(callMyBookings(fixtures.tokenFor(clientEmail)));

        assertThat(row.path("priceMaxAtBooking").decimalValue())
                .as("the live service now reads RANGE 300-999; the client agreed to 300-500 and "
                        + "must keep seeing 500")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("GET /me (CLIENT) keeps a null ceiling null even when the live service IS a range "
            + "— a band must never grow onto a booking agreed at a single price")
    void should_keepNullCeiling_when_clientListRowFrozeNoBandButServiceIsNowARange() throws Exception {
        String masterEmail = "bprc-diverge-null-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-diverge-null-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        insertBookingWithDivergentFrozenBand(clientId, masterId, null);

        JsonNode row = firstRow(callMyBookings(fixtures.tokenFor(clientEmail)));

        assertThat(row.path("priceMaxAtBooking").isNull())
                .as("live derivation would answer 999.00 here; the stored NULL is the truth")
                .isTrue();
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER) reports the FROZEN ceiling, not the live one — the "
            + "provider sees exactly what the client was charged against")
    void should_readTheFrozenColumn_when_providerListDivergesFromLiveDerivation() throws Exception {
        String masterEmail = "bprc-diverge-provider-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "bprc-diverge-provider-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        insertBookingWithDivergentFrozenBand(clientId, masterId, new BigDecimal("500.00"));

        JsonNode row = firstRow(callMyBookings(fixtures.tokenFor(masterEmail)));

        assertThat(row.path("priceMaxAtBooking").decimalValue())
                .as("a provider must not be shown a wider band than the one they agreed to honour")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("GET /bookings/{id} reports the FROZEN ceiling to BOTH actors even though the "
            + "live service now derives a wider one")
    void should_readTheFrozenColumn_when_detailDivergesFromLiveDerivation() throws Exception {
        String masterEmail = "bprc-diverge-detail-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-diverge-detail-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID bookingId = insertBookingWithDivergentFrozenBand(clientId, masterId, new BigDecimal("500.00"));

        JsonNode clientDetail = getDetail(fixtures.tokenFor(clientEmail), bookingId);
        JsonNode providerDetail = getDetail(fixtures.tokenFor(masterEmail), bookingId);

        assertThat(clientDetail.path("priceMaxAtBooking").decimalValue())
                .as("the detail endpoint runs a THIRD query (findByIdWithFullGraph) and can revert "
                        + "to live derivation independently of either list path")
                .isEqualByComparingTo("500.00");
        assertThat(providerDetail.path("priceMaxAtBooking").decimalValue())
                .isEqualByComparingTo("500.00");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SALON_OWNER scope — GET /bookings/me across SEVERAL salons
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The fourth list scope. {@code BookingService#listProviderBookings} has three ID-page
     * branches, and this suite pinned only two of them: {@code CLIENT} (its own projection query)
     * and {@code SALON_MASTER}/{@code INDEPENDENT_MASTER} (both
     * {@code findIdsByMasterIdFiltered}). {@code SALON_OWNER} takes a branch of its own —
     * {@code findIdsByOwnerIdAndIsActiveTrue} feeding {@code findIdsBySalonIdsFiltered}, an
     * {@code IN (…)} over every salon the owner holds — and no test reached it.
     *
     * <p><b>Two salons, not one.</b> A single-salon owner would exercise
     * {@code findIdsBySalonIdsFiltered} with a one-element {@code IN}, which cannot distinguish a
     * correct implementation from one that silently collapses to the first salon. The two salons
     * carry DELIBERATELY DIFFERENT bands (300–500 and 700–900) so a row-to-band mis-join shows up
     * as a wrong pairing rather than as a coincidentally-equal number — the same
     * divergent-fixture discipline the block above uses against live re-derivation.
     *
     * <p><b>Deliberately NOT given a statement-count gate</b>, unlike the three scopes at the
     * bottom of this class. Measured on this fixture: 4 statements / 13 entities
     * ({@code findIdsByOwnerIdAndIsActiveTrue} + the Specification ID page +
     * {@code findAllByIdsWithGraph} + {@code findReviewedBookingIds}; both locality-label
     * {@code IN} queries are skipped because this fixture stamps no locality). Pinning 4 would pin
     * a number produced by what this fixture OMITS rather than by the production path — the
     * textbook weak gate. Making it comparable to its siblings' 6 would mean rebuilding
     * {@link #seedSalonBookingsOnDistinctServices} + {@code stampSalonLocality} across two salons,
     * and the result would then pin the SAME six statements
     * {@link #SALON_MASTER_PAGE_STATEMENTS} already pins: everything after the ID page
     * ({@code findAllByIdsWithGraph}, the review batch, both label {@code IN}s) is shared code, and
     * the only branch-specific statements are {@code findIdsByOwnerIdAndIsActiveTrue} and the ID
     * page itself — both single, fixed, and independent of row AND salon count (2 salons still
     * resolve through one {@code IN (…)} page plus one graph hydrate, which the measurement above
     * confirms). This test therefore covers the scope's CORRECTNESS, which was the actual gap; its
     * performance shape is already covered by the salon-master gate it shares a pipeline with.
     */
    @Test
    @DisplayName("GET /me (SALON_OWNER) carries each booking's own frozen band across MULTIPLE "
            + "salons — the findIdsBySalonIdsFiltered scope no other test reaches")
    void should_carryFrozenBandPerRow_when_ownerListsBookingsAcrossSeveralSalons() throws Exception {
        String ownerEmail = "bprc-owner-multi-" + System.nanoTime() + "@beautica.test";
        var firstSalon = fixtures.createSalon(ownerEmail);
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, firstSalon.salonId());
        UUID secondSalonId = addSalonUnderOwner(ownerId);
        UUID secondMasterId = masterIdOfSalon(secondSalonId);
        UUID clientId = fixtures.createUser(
                "bprc-owner-multi-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        // Each booking is stamped with the salon it was made at, exactly as every production write
        // site does (BookingService:1794, AppointmentService:253/267). Phase 242: salonName is
        // resolved from bookings.salon_id, so a fixture that leaves the column NULL renders both
        // rows' salonName as null and the "different salons" premise below degrades to null == null.
        UUID serviceInFirst = createRangeService("SALON", firstSalon.salonId(), firstSalon.masterId(),
                new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        insertBooking(clientId, firstSalon.masterId(), serviceInFirst, ANCHOR,
                new BigDecimal("300.00"), new BigDecimal("500.00"), "CONFIRMED", firstSalon.salonId());
        UUID serviceInSecond = createRangeService("SALON", secondSalonId, secondMasterId,
                new BigDecimal("700.00"), new BigDecimal("900.00"), null);
        insertBooking(clientId, secondMasterId, serviceInSecond, ANCHOR.plusDays(1),
                new BigDecimal("700.00"), new BigDecimal("900.00"), "CONFIRMED", secondSalonId);

        var page = bookingService.getMyBookings(
                ownerId, authFor(Role.SALON_OWNER), null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.data())
                .as("premise — the owner must see BOTH salons' bookings, or the multi-salon IN "
                        + "clause this test exists for was never exercised")
                .hasSize(2);
        assertThat(page.data())
                .extracting(b -> b.salonName())
                .as("premise — the two rows must come from DIFFERENT salons")
                .doesNotHaveDuplicates();
        assertThat(page.data())
                .as("each row must carry ITS OWN frozen floor/ceiling pair; a mis-join across the "
                        + "salon IN clause would pair a booking with the other salon's band")
                .extracting(b -> b.priceAtBooking().stripTrailingZeros().toPlainString(),
                        b -> b.priceMaxAtBooking().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("300", "500"),
                        org.assertj.core.groups.Tuple.tuple("700", "900"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLIENT path — GET /bookings/me (ClientBookingDetailProjection JPQL hydrate)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (CLIENT) agrees with the PROVIDER view of the identical booking — both "
            + "read the same frozen column")
    void should_agreeWithProviderView_when_bookingHasFrozenCeiling() throws Exception {
        String masterEmail = "bprc-client-range-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-client-range-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("300.00"), new BigDecimal("500.00"));

        JsonNode clientRow = firstRow(callMyBookings(fixtures.tokenFor(clientEmail)));
        JsonNode providerRow = firstRow(callMyBookings(fixtures.tokenFor(masterEmail)));

        assertThat(clientRow.path("priceMaxAtBooking").decimalValue())
                .isEqualByComparingTo(providerRow.path("priceMaxAtBooking").decimalValue())
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("GET /me (CLIENT) — a booking that froze no ceiling surfaces null")
    void should_returnNullPriceMax_when_clientBookingFrozeNoCeiling() throws Exception {
        String masterEmail = "bprc-client-override-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-client-override-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), new BigDecimal("400.00"));
        insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("400.00"), null);

        JsonNode row = firstRow(callMyBookings(fixtures.tokenFor(clientEmail)));

        assertThat(row.path("priceMaxAtBooking").isNull()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Booking detail — GET /bookings/{id}
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /bookings/{id} — the client and the provider see the identical frozen ceiling")
    void should_agreeOnDetailEndpoint_when_bookingHasFrozenCeiling() throws Exception {
        String masterEmail = "bprc-detail-range-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-detail-range-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createRangeService(masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        UUID bookingId = insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("300.00"), new BigDecimal("500.00"));

        JsonNode clientDetail = getDetail(fixtures.tokenFor(clientEmail), bookingId);
        JsonNode providerDetail = getDetail(fixtures.tokenFor(masterEmail), bookingId);

        assertThat(clientDetail.path("priceMaxAtBooking").decimalValue())
                .isEqualByComparingTo(providerDetail.path("priceMaxAtBooking").decimalValue())
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("GET /bookings/{id} — a FIXED-service booking has a null priceMaxAtBooking for both actors")
    void should_returnNullPriceMaxOnDetailEndpoint_when_fixedBooking() throws Exception {
        String masterEmail = "bprc-detail-fixed-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "bprc-detail-fixed-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        UUID bookingId = insertBooking(clientId, masterId, serviceId, ANCHOR, new BigDecimal("500.00"), null);

        JsonNode clientDetail = getDetail(fixtures.tokenFor(clientEmail), bookingId);
        JsonNode providerDetail = getDetail(fixtures.tokenFor(masterEmail), bookingId);

        assertThat(clientDetail.path("priceMaxAtBooking").isNull()).isTrue();
        assertThat(providerDetail.path("priceMaxAtBooking").isNull()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // No N+1
    // ══════════════════════════════════════════════════════════════════════════
    //
    // These gates guard the JOIN FETCH / join contract on the two list paths. They are still
    // load-bearing after V119: the DTO factories no longer dereference serviceDefinition for
    // PRICING (the ceiling is a column on the booking row now), but they still dereference it for
    // serviceName and category, so dropping the fetch would reintroduce a per-row lazy SELECT.
    //
    // Two properties are asserted, in this order:
    //   1. ABSOLUTE statement count. This is what actually detects a regression. Each booking sits
    //      on its OWN master_services + service_definitions pair (see seedBookingsOnDistinctServices),
    //      so a missing fetch cannot be absorbed by Hibernate's L1 cache.
    //   2. The 1-row vs 5-row DELTA, as a secondary signal.
    //
    // Property 2 ALONE is inert, which is how the original version of these gates passed with the
    // fetch deleted: when all five bookings share one masterServiceId, five would-be proxy
    // initialisations collapse into a single SELECT via the L1 cache, so the 5-row count equals the
    // 1-row count either way. Never reduce these gates to the delta assertion again.
    //
    // Distinct services alone do NOT rescue the delta assertion either, because
    // hibernate.default_batch_fetch_size = 50 (application.yml) batches all five proxy
    // initialisations into ONE extra SELECT — so a deleted fetch join costs +1 statement whether
    // the page holds 1 row or 5, and the delta stays equal. Verified by mutation: deleting
    // `JOIN FETCH ms.serviceDefinition` from findAllByIdsWithGraph moves the provider count 6 -> 7,
    // which ONLY the absolute assertion catches. That +1 is the entire detection margin; do not
    // slacken it to a range or a "less than" bound.
    //
    // A THIRD gate (SALON_MASTER) mirrors the provider one on a salon-employed master. It is not
    // redundant: the other two seed through createIndependentMaster, which leaves m.salon NULL on
    // every row, so `LEFT JOIN FETCH m.salon s` fetches nothing and the LAZY Salon.owner proxy that
    // hangs off it does not exist to be initialised — meaning neither pinned count below can move
    // in response to a getOwner() dereference. The salon gate is the only one with the power to
    // catch that, and it is what backs the "no caller dereferences s.owner" claim on
    // findAllByIdsWithGraph's javadoc.
    //
    // The three constants are DERIVED FROM A RUN, never predicted — see their javadoc for the
    // per-statement arithmetic. The first two moved 5 -> 6 and 3 -> 4 when two opposite changes landed
    // together: deferring the ID page's COUNT(*) through PageableExecutionUtils removed one
    // statement from each, and stamping a real locality on the master's users row (so the
    // label-resolution seam stops resolving an all-null, all-empty id set) added two to each.
    //
    // The metric is getPrepareStatementCount(), NOT getQueryExecutionCount(): the latter only ticks
    // for HQL/Criteria/native query executions and never for lazy proxy initialisation, so it is
    // blind to exactly the N+1 being guarded. This matches the repo's other N+1 tests
    // (ClientBookingDetailProjectionTest, FavoriteListProjectionTest, ClientAggregationRepositoryTest).

    /**
     * Statement count for a 5-row provider page. Pinned — a rise means a lost fetch join.
     *
     * <p>6 = {@code masterRepository.findByUserId} (resolve the actor's Master row)
     * + the {@code Specification} ID page's content query
     * + {@code findAllByIdsWithGraph} (the graph hydrate)
     * + {@code reviewRepository.findReviewedBookingIds} (batched {@code canReview})
     * + the city-label {@code IN} query + the district-label {@code IN} query
     * ({@code DiscoveryLocationResolver.resolveLabels}, one per dimension, both non-empty now that
     * {@code seedBookingsOnDistinctServices} stamps a real locality on the master).
     *
     * <p><b>No {@code COUNT(*)}.</b> {@code BookingRepositoryCustomImpl.findIdPage} defers it
     * through {@code PageableExecutionUtils.getPage}, which short-circuits on a first page (offset
     * 0) whose content is shorter than the page size — 5 rows at {@code size=20}, exactly this
     * fixture. A regression that reinstates an unconditional count shows up here as 7.
     */
    private static final long PROVIDER_PAGE_STATEMENTS = 6L;

    /**
     * Statement count for a 5-row client page. Pinned — a rise means a lost join.
     *
     * <p>4 = the {@code Specification} ID page's content query
     * + {@code hydrateClientBookingDetails} (the projection hydrate)
     * + the city-label {@code IN} query + the district-label {@code IN} query. No
     * {@code findByUserId} (the client IS the actor) and no {@code findReviewedBookingIds} (the
     * projection carries {@code reviewExists} inline via its {@code LEFT JOIN Review}); no
     * {@code COUNT(*)}, for the same reason as {@link #PROVIDER_PAGE_STATEMENTS}.
     */
    private static final long CLIENT_PAGE_STATEMENTS = 4L;

    /**
     * Statement count for a 5-row SALON_MASTER page — the same provider code path as
     * {@link #PROVIDER_PAGE_STATEMENTS}, but with {@code m.salon} NON-NULL on every row.
     *
     * <p>Expected to equal {@link #PROVIDER_PAGE_STATEMENTS}: the role branch is shared
     * ({@code case SALON_MASTER, INDEPENDENT_MASTER} in {@code listProviderBookings}), so the six
     * statements are the identical six, and the extra {@code salons} row rides along on
     * {@code findAllByIdsWithGraph}'s {@code LEFT JOIN FETCH b.salon} (phase 242 — re-pointed from
     * {@code m.salon}; see that query's javadoc) rather than costing a statement of its own. Note
     * that the list path does NOT pay the rotated-case penalty the detail path does
     * ({@link #OWNER_DETAIL_STATEMENTS_ROTATED}): nothing on it walks
     * {@code master.getSalon()} — the per-row authorization is done upstream, on the ID page — so
     * the master's live {@code Salon} proxy is never opened even when it diverges. The two
     * constants are kept SEPARATE rather than collapsed into one,
     * because a future change that makes the salon path cost more must show up as a diff on THIS
     * line, not silently re-point a shared constant.
     *
     * <p><b>What only this gate can catch.</b> {@code Salon.owner} is a LAZY proxy that exists only
     * when a salon exists. On the independent path it is unreachable, so
     * {@link #PROVIDER_PAGE_STATEMENTS} is blind to a regression that dereferences it; here such a
     * regression initialises the proxy and the count goes 6 -&gt; 7 (one SELECT on {@code users},
     * collapsed to one because all five rows share a salon).
     *
     * <p><b>Verified by three mutations, and the negative results matter as much as the positive:</b>
     * <ol>
     *   <li>Dereferencing a NON-identifier property of the owner
     *       ({@code salon.getOwner().getEmail()}) in {@code listProviderBookings} moves THIS gate
     *       6 -&gt; 7 and leaves {@link #PROVIDER_PAGE_STATEMENTS} at 6 — the detection this gate
     *       exists for, and the proof the independent gate cannot substitute for it.</li>
     *   <li>Dereferencing the owner's ID ({@code salon.getOwner().getId()}) moves NOTHING. Hibernate
     *       serves an identifier off the uninitialised proxy, so no statement is issued. Do not
     *       "strengthen" this gate with an id-based mutation and conclude it is inert — it is the
     *       mutation, not the gate, that is inert. This is also exactly why
     *       {@code findActiveByClientIdAndIdempotencyKey} can drop its master-side fetches while
     *       still serving {@code getMaster().getId()}.</li>
     *   <li>Restoring {@code LEFT JOIN FETCH s.owner} to {@code findAllByIdsWithGraph} also moves
     *       nothing (a fetch join widens a join, it does not add a statement) — confirming the
     *       removed fetch was pure per-page cost with no statement-count benefit to lose.</li>
     * </ol>
     *
     * <p>DERIVED FROM A RUN, never predicted — same rule as the two constants above.
     */
    private static final long SALON_MASTER_PAGE_STATEMENTS = 6L;

    /**
     * Hydrated-entity count for the same 5-row SALON_MASTER page — the gate that catches what a
     * statement count structurally cannot.
     *
     * <p><b>Why a second metric is needed.</b> {@link #SALON_MASTER_PAGE_STATEMENTS} detects a
     * DEREFERENCE (a lazy proxy being initialised costs a SELECT). It is blind, by construction, to
     * the opposite regression: RE-ADDING a fetch join. A fetch join widens an existing join rather
     * than issuing a statement, so restoring {@code LEFT JOIN FETCH s.owner} to
     * {@code findAllByIdsWithGraph} leaves every pinned statement count at exactly its current
     * value — the "verified by mutation 3" note on {@link #SALON_MASTER_PAGE_STATEMENTS} says so
     * itself. Before this constant, the only thing standing between that dead fetch and production
     * was a javadoc paragraph, i.e. nothing a CI run can enforce. Hibernate DOES materialise the
     * extra {@code User} row, and {@code getEntityLoadCount()} counts it.
     *
     * <p>19 = 5 {@code Booking} + 5 {@code MasterServiceAssignment} + 5 {@code ServiceDefinition}
     * (one pair per row, per {@link #seedSalonBookingsOnDistinctServices}) + 1 {@code Master}
     * + 1 master {@code User} + 1 {@code Salon} + 1 client {@code User} (all five rows share these
     * four). Derived from a run, never predicted.
     *
     * <p><b>Mutation-verified (Phase 26.9 QA):</b> restoring {@code LEFT JOIN FETCH s.owner} to
     * {@code findAllByIdsWithGraph} moves this count 19 -&gt; 20 (the salon owner's {@code User},
     * {@code password_hash} included) while {@link #SALON_MASTER_PAGE_STATEMENTS} stays at 6 —
     * exactly the split of responsibilities described above.
     *
     * <p><b>Phase B2 (QA, 2026-08-06) — the divergent-salon seed, and why 19 is UNCHANGED.</b>
     * {@code BookingDetailResponse.from} now dereferences {@code booking.getSalon().getId()}. That
     * costs nothing today because Hibernate serves an identifier off an UNINITIALISED
     * {@code Salon} proxy — the same property mutation 2 above records for {@code Salon.owner}. But
     * this gate could not have proved it: {@link #insertBooking}'s original form never wrote
     * {@code bookings.salon_id}, so every row on this page had {@code booking.salon == null} and
     * there was no proxy to dereference at all. The gate was structurally blind, not passing.
     *
     * <p>The fixture books at a salon DISTINCT from {@code masters.salon_id}. Divergence is the
     * load-bearing part: with the two ids ALIGNED, both {@code b.salon} and {@code m.salon} resolve
     * to the same row and the gate cannot tell which of them the page actually materialised.
     *
     * <p><b>Phase 242 — 19 is UNCHANGED, but the ONE {@code Salon} among them swapped identity.</b>
     * The fetch join moved from {@code m.salon} to {@code b.salon}, so the hydrated salon is now
     * the BOOKED one and it is the master's LIVE salon that is left as an untouched proxy. The
     * count is the same because the page needs exactly one salon either way: nothing on the
     * provider list path walks {@code master.getSalon()} (per-row authorization happens upstream,
     * on the ID page), so the live-salon proxy is never opened. A rise to 20 means something
     * started walking it — or that {@code s.owner} was fetch-joined back on.
     *
     * <p><b>Mutation-verified (Phase B2 QA):</b> forcing a proxy open with
     * {@code Hibernate.initialize(...)} on the provider read path moves this count
     * 19 -&gt; 20 while {@link #SALON_MASTER_PAGE_STATEMENTS} moves 6 -&gt; 7. This gate is also the
     * one that would go red if {@code hibernate.jpa.compliance.proxy=true} were ever set: under JPA
     * proxy compliance Hibernate must initialise a proxy to answer {@code getId()}, so the
     * optimisation the remaining identifier-only reads rely on would silently stop applying and
     * land here as 20.
     */
    private static final long SALON_MASTER_PAGE_ENTITIES = 19L;

    private static Authentication authFor(Role role) {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    @Test
    @DisplayName("PROVIDER scope — a 5-row page costs a fixed, absolute number of JDBC statements, "
            + "each booking on its OWN service definition so a lost JOIN FETCH cannot hide in the L1 cache")
    void should_notScaleStatementCount_when_providerPageHasManyBookings() {
        String masterEmail = "bprc-qcount-provider-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        // BookingService#getMyBookings resolves the provider's Master row via
        // masterRepository.findByUserId(actorUserId) — the SERVICE takes the master's USER id,
        // never masters.id (which is what createIndependentMaster returns).
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID clientId = fixtures.createUser("bprc-qcount-provider-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        Pageable pageable = PageRequest.of(0, 20);
        Statistics statistics = statistics();

        seedBookingsOnDistinctServices(clientId, masterId, 1);
        statistics.clear();
        bookingService.getMyBookings(masterUserId, authFor(Role.INDEPENDENT_MASTER), null, null, null, null, pageable);
        long statementsForOneRow = statistics.getPrepareStatementCount();

        seedBookingsOnDistinctServices(clientId, masterId, 4);
        statistics.clear();
        var result = bookingService.getMyBookings(
                masterUserId, authFor(Role.INDEPENDENT_MASTER), null, null, null, null, pageable);
        long statementsForFiveRows = statistics.getPrepareStatementCount();

        assertThat(result.data()).hasSize(5);
        assertThat(statementsForFiveRows)
                .as("absolute JDBC statement count for a 5-row page (each on a distinct service "
                        + "definition). A rise means an association is no longer fetch-joined and is "
                        + "being lazily initialised per row.")
                .isEqualTo(PROVIDER_PAGE_STATEMENTS);
        assertThat(statementsForFiveRows)
                .as("secondary signal — statement count must not scale with row count; got %s for "
                        + "1 row, %s for 5 rows", statementsForOneRow, statementsForFiveRows)
                .isEqualTo(statementsForOneRow);
    }

    @Test
    @DisplayName("SALON_MASTER scope — a 5-row page of SALON-owned bookings, each booked at a "
            + "DIFFERENT salon from the master's live one, costs the same fixed statement count and "
            + "hydrates neither the Salon.owner proxy nor the booking's own Salon proxy")
    void should_notScaleStatementCount_when_salonMasterPageHasManyBookings() {
        var salon = fixtures.createSalon("bprc-qcount-salon-owner-" + System.nanoTime() + "@beautica.test");
        // As on the independent gate, the SERVICE takes the master's USER id, never masters.id.
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());
        UUID clientId = fixtures.createUser(
                "bprc-qcount-salon-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        // Phase B2: the salon each booking was made AT — deliberately NOT the master's live salon.
        // An aligned id would be served from the L1 cache entry `LEFT JOIN FETCH m.salon` already
        // materialised, so BookingDetailResponse.from's booking.getSalon().getId() would never
        // touch a proxy and the entity gate below would pass without testing anything.
        UUID salonOwnerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID bookedSalonId = insertBareSalonUnderOwner(
                salonOwnerId, "bprc-booked-salon-" + System.nanoTime());
        assertThat(bookedSalonId)
                .as("premise — the booking's salon and the master's live salon must be DIFFERENT "
                        + "rows, or booking.getSalon() resolves off the L1 cache and no proxy is "
                        + "dereferenced at all")
                .isNotEqualTo(salon.salonId());

        Pageable pageable = PageRequest.of(0, 20);
        Statistics statistics = statistics();

        seedSalonBookingsOnDistinctServices(clientId, salon.salonId(), salon.masterId(), 1, bookedSalonId);
        statistics.clear();
        bookingService.getMyBookings(masterUserId, authFor(Role.SALON_MASTER), null, null, null, null, pageable);
        long statementsForOneRow = statistics.getPrepareStatementCount();

        seedSalonBookingsOnDistinctServices(clientId, salon.salonId(), salon.masterId(), 4, bookedSalonId);
        statistics.clear();
        var result = bookingService.getMyBookings(
                masterUserId, authFor(Role.SALON_MASTER), null, null, null, null, pageable);
        long statementsForFiveRows = statistics.getPrepareStatementCount();
        long entitiesForFiveRows = statistics.getEntityLoadCount();

        assertThat(result.data()).hasSize(5);
        // Guards the premise of the gate itself: if the fixture ever stopped attaching a salon,
        // Salon.owner would vanish from the page and this test would silently degrade into a
        // duplicate of the independent-master gate while still passing.
        assertThat(result.data())
                .as("every row must carry a salon, or there is no Salon.owner proxy to guard")
                .allSatisfy(b -> assertThat(b.salonName()).isNotNull());
        // Phase B2 premise, and the second half of the one above: the entity count is only a gate
        // on booking.getSalon() if every row actually has a DIVERGENT booking.salon to dereference.
        // If a future fixture change stopped stamping bookings.salon_id (or realigned it with the
        // master's salon), this gate would silently revert to the blind state it was in before B2
        // while still passing at 19.
        assertThat(result.data())
                .as("every row's salonId must be the booking's OWN divergent salon snapshot, or "
                        + "there is no uninitialised Salon proxy for the entity count to guard")
                .allSatisfy(b -> assertThat(b.salonId()).isEqualTo(bookedSalonId));
        assertThat(result.data())
                .as("sanity — the divergence must survive the read path: salonId (booking snapshot) "
                        + "and the master's live salon must not have collapsed to one value")
                .allSatisfy(b -> assertThat(b.salonId()).isNotEqualTo(salon.salonId()));
        assertThat(statementsForFiveRows)
                .as("absolute JDBC statement count for a 5-row SALON-master page. A rise means an "
                        + "association is being lazily initialised per page — most pointedly "
                        + "Salon.owner, which only this gate can reach.")
                .isEqualTo(SALON_MASTER_PAGE_STATEMENTS);
        assertThat(entitiesForFiveRows)
                .as("absolute HYDRATED-ENTITY count for the same page. Complements the statement "
                        + "count in the OPPOSITE direction: a re-added fetch join costs no extra "
                        + "statement (it widens a join) and so is invisible above, but it does "
                        + "materialise an extra entity — see this gate's javadoc. A rise to %s here "
                        + "also means Phase B2's booking.getSalon().getId() stopped being served "
                        + "off the uninitialised proxy (a fetch join on b.salon, an accidental "
                        + "non-identifier dereference, or hibernate.jpa.compliance.proxy=true).",
                        SALON_MASTER_PAGE_ENTITIES + 1)
                .isEqualTo(SALON_MASTER_PAGE_ENTITIES);
        assertThat(statementsForFiveRows)
                .as("secondary signal — statement count must not scale with row count; got %s for "
                        + "1 row, %s for 5 rows", statementsForOneRow, statementsForFiveRows)
                .isEqualTo(statementsForOneRow);
    }

    /**
     * Hydrated-entity count for a SINGLE {@code GET /bookings/{id}} served to the salon OWNER —
     * the detail-path twin of {@link #SALON_MASTER_PAGE_ENTITIES}, and the gate that makes the
     * {@code s.owner} drop on {@code findByIdWithFullGraph} enforceable instead of merely asserted.
     *
     * <p><b>Why an entity count and not a statement count.</b> Restoring
     * {@code LEFT JOIN FETCH s.owner} widens an existing join rather than issuing a statement, so
     * every statement-based metric on this path is by construction blind to it — the same argument
     * that forced {@link #SALON_MASTER_PAGE_ENTITIES} into existence for the list path.
     * {@code getEntityLoadCount()} does see it: Hibernate materialises the owner's {@code User} row.
     *
     * <p><b>Why the OWNER is the actor.</b> This is the one role whose authorization actually walks
     * the association in question — {@code AuthorizationService#hasProviderAuthorityOverBooking}
     * evaluates {@code master.getSalon().getOwner().getId()}. Running the gate as the owner
     * therefore proves BOTH halves of the drop's rationale at once: that the ownership check still
     * succeeds against an UNINITIALISED proxy (an id is served without a statement, and without a
     * {@code LazyInitializationException} — the request completes inside the service transaction),
     * and that no {@code User} row is hydrated to answer it. A master-role actor would exercise
     * neither.
     *
     * <p>7 = 1 {@code Booking} + 1 {@code Master} + 1 master {@code User} + 1 {@code Salon}
     * + 1 client {@code User} + 1 {@code MasterServiceAssignment} + 1 {@code ServiceDefinition}.
     * The salon owner's {@code User} is conspicuously NOT among them.
     *
     * <p><b>Confirmed against a run, and mutation-verified (Phase 26.9 follow-up):</b> restoring
     * {@code LEFT JOIN FETCH s.owner} to {@code findByIdWithFullGraph} moves this count 7 -&gt; 8
     * (the salon owner's {@code User}, {@code password_hash} included) while every statement-count
     * gate in this class stays exactly where it is — the observed split this gate was added for.
     * Do not adjust the constant to make a failing run pass without first establishing which
     * association the extra entity belongs to.
     *
     * <p><b>Phase 242 — this gate SPLIT into two, and the constants below are why.</b> B2 read only
     * {@code booking.getSalon().getId()}, an identifier served off an uninitialised proxy for free.
     * Phase 242 re-pointed the whole display block onto that same snapshot and reads real
     * properties off it ({@code getName()}, {@code getStreet()}, {@code getLocationNote()}), which
     * INITIALISE the proxy — so {@code findByIdWithFullGraph} now fetch-joins {@code b.salon}
     * instead of {@code m.salon}. The two cases genuinely diverge from here:
     * <ul>
     *   <li><b>Aligned</b> ({@link #OWNER_DETAIL_ENTITIES_ALIGNED} = 7) — the production-normal
     *       shape: {@code bookings.salon_id == masters.salon_id}, so the ONE fetched {@code Salon}
     *       answers both the display block and {@code AuthorizationService}'s
     *       {@code master.getSalon().getOwner().getId()} walk (the persistence context resolves
     *       {@code m.salon} to the already-materialised row rather than minting a proxy). 7 is
     *       therefore unchanged from before the re-point, and this is the count that matters for
     *       real traffic.</li>
     *   <li><b>Rotated</b> ({@link #OWNER_DETAIL_ENTITIES_ROTATED} = 8) — the deliberately
     *       divergent fixture: the request now genuinely needs TWO salon rows, the booking's (to
     *       display) and the master's live one (for the owner authorization check), and no fetch
     *       strategy can serve both from one row. The extra entity is a {@code Salon}, NOT the
     *       owner's {@code User} — the {@code s.owner} drop this gate was created for still holds,
     *       which is exactly why the two constants sit one apart and not two.</li>
     * </ul>
     * Keeping both is the point: a single constant would have had to pick one shape and go blind
     * to the other, and collapsing the pair is how a real {@code s.owner} re-fetch would later hide
     * inside a number that had already been bumped once.
     *
     * <p>7 = 1 {@code Booking} + 1 {@code Master} + 1 master {@code User} + 1 {@code Salon}
     * + 1 client {@code User} + 1 {@code MasterServiceAssignment} + 1 {@code ServiceDefinition}.
     * The salon owner's {@code User} is conspicuously NOT among them, in either case.
     */
    private static final long OWNER_DETAIL_ENTITIES_ALIGNED = 7L;

    /**
     * Statement counts for the same two shapes — {@code findByIdWithFullGraph} plus the two
     * {@code DiscoveryLocationResolver} label {@code IN} queries, and in the rotated case one
     * further {@code salons} SELECT. Both DERIVED FROM A RUN, never predicted.
     *
     * <p>Phase 242 before/after: the ALIGNED count is unchanged at 3 — the production-normal shape
     * pays nothing for the re-point. The ROTATED count moved 3 -&gt; 4, because a booking whose
     * salon differs from its master's live salon genuinely needs both rows (one to display, one to
     * authorize) and no fetch strategy can serve both from one. Restoring
     * {@code LEFT JOIN FETCH m.salon} ALONGSIDE {@code b.salon} would trade that SELECT for a
     * second {@code salons} join on every read, aligned or not, and still hydrate two entities in
     * the rotated case — strictly worse. Do not "fix" it that way.
     */
    private static final long OWNER_DETAIL_STATEMENTS_ALIGNED = 3L;

    /** See {@link #OWNER_DETAIL_STATEMENTS_ALIGNED}. */
    private static final long OWNER_DETAIL_STATEMENTS_ROTATED = 4L;

    /** See {@link #OWNER_DETAIL_ENTITIES_ALIGNED} — the post-rotation shape. Derived from a run. */
    private static final long OWNER_DETAIL_ENTITIES_ROTATED = 8L;

    @Test
    @DisplayName("GET /bookings/{id} for a booking whose master has since gone INDEPENDENT still "
            + "serves the booked salon's name, and fetches it in the main query rather than "
            + "lazy-loading it — the one shape that pins WHICH salon findByIdWithFullGraph fetches")
    void should_notLazyLoadTheBookedSalon_when_theMasterHasSinceGoneIndependent() {
        var salon = fixtures.createSalon("bprc-detail-gone-indep-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser(
                "bprc-detail-gone-indep-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = createRangeService("SALON", salon.salonId(), salon.masterId(),
                new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        stampSalonLocality(salon.salonId());
        UUID bookingId = insertBooking(clientId, salon.masterId(), serviceId, ANCHOR,
                new BigDecimal("300.00"), new BigDecimal("500.00"), "CONFIRMED", salon.salonId());

        // The master leaves the salon and goes solo AFTER the booking. masters.salon_id becomes
        // NULL; bookings.salon_id is a snapshot and keeps pointing at the salon the client booked.
        jdbcTemplate.update(
                "UPDATE masters SET salon_id = NULL, master_type = 'INDEPENDENT_MASTER' WHERE id = ?",
                salon.masterId());
        jdbcTemplate.update(
                "UPDATE users SET salon_id = NULL WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                salon.masterId());

        // Read as the master's OWN user: isAuthorizedToManageBooking's INDEPENDENT_MASTER branch
        // settles it in memory, so this measures the read itself and not a SecurityContext walk.
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());

        Statistics statistics = statistics();
        statistics.clear();
        var detail = bookingService.getBooking(masterUserId, bookingId);
        long statements = statistics.getPrepareStatementCount();

        assertThat(detail.salonName())
                .as("correctness — the visit WAS at a salon, so its name must still be served; a "
                        + "master going solo does not retroactively turn a past salon visit into a "
                        + "home-studio one")
                .isNotNull();
        assertThat(statements)
                .as("this is the ONLY shape that can tell findByIdWithFullGraph's LEFT JOIN FETCH "
                        + "b.salon apart from the m.salon it replaced. In both the aligned and the "
                        + "rotated fixture the two graphs happen to cost the same (aligned: one "
                        + "row serves both; rotated: one is fetched and the other lazy-loaded, "
                        + "either way 1 + 1). Here m.salon is NULL, so a graph still fetching it "
                        + "materialises nothing and the mapper's booking.getSalon() property reads "
                        + "lazy-load the booked salon — %s instead of %s.",
                        OWNER_DETAIL_STATEMENTS_ALIGNED + 1, OWNER_DETAIL_STATEMENTS_ALIGNED)
                .isEqualTo(OWNER_DETAIL_STATEMENTS_ALIGNED);
    }

    @Test
    @DisplayName("GET /bookings/{id} as the salon OWNER — production-normal shape (the booking's "
            + "salon IS the master's live salon): ONE Salon row serves both the address block and "
            + "the ownership walk, and the owner's User is still never hydrated")
    void should_hydrateExactlyOneSalon_when_bookingSalonMatchesTheMastersLiveSalon() {
        var salon = fixtures.createSalon("bprc-detail-aligned-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID clientId = fixtures.createUser(
                "bprc-detail-aligned-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = createRangeService("SALON", salon.salonId(), salon.masterId(),
                new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        stampSalonLocality(salon.salonId());
        UUID bookingId = insertBooking(clientId, salon.masterId(), serviceId, ANCHOR,
                new BigDecimal("300.00"), new BigDecimal("500.00"), "CONFIRMED", salon.salonId());

        Statistics statistics = statistics();
        statistics.clear();
        var detail = bookingService.getBooking(ownerId, bookingId);
        long entities = statistics.getEntityLoadCount();
        long statements = statistics.getPrepareStatementCount();

        assertThat(statements)
                .as("absolute JDBC statement count for the production-normal detail read: the "
                        + "single findByIdWithFullGraph + the two DiscoveryLocationResolver label "
                        + "IN queries. A rise means an association the mapper reads stopped being "
                        + "fetch-joined and is being lazily initialised.")
                .isEqualTo(OWNER_DETAIL_STATEMENTS_ALIGNED);
        assertThat(detail.salonId())
                .as("premise — this gate is the ALIGNED case; the booking's snapshot and the "
                        + "master's live salon must be the SAME row or it measures the other case")
                .isEqualTo(salon.salonId());
        assertThat(detail.salonName())
                .as("premise — a real salon must be on the row, or there is no Salon.owner proxy")
                .isNotNull();
        assertThat(entities)
                .as("absolute HYDRATED-ENTITY count for the production-normal detail read. This is "
                        + "the number that must not move when the salon fetch is re-pointed: one "
                        + "Salon row answers the address block AND the ownership walk. A rise to %s "
                        + "means s.owner (or another association) was fetch-joined back on.",
                        OWNER_DETAIL_ENTITIES_ALIGNED + 1)
                .isEqualTo(OWNER_DETAIL_ENTITIES_ALIGNED);
    }

    @Test
    @DisplayName("GET /bookings/{id} as the salon OWNER authorizes off the uninitialised Salon.owner "
            + "proxy and hydrates no User row for it — with a DIVERGENT booking salon the request "
            + "needs both salon rows and no more")
    void should_notHydrateTheSalonOwner_when_loadingABookingDetail() {
        var salon = fixtures.createSalon("bprc-detail-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID clientId = fixtures.createUser(
                "bprc-detail-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = createRangeService("SALON", salon.salonId(), salon.masterId(),
                new BigDecimal("300.00"), new BigDecimal("500.00"), null);
        // Phase B2/242: booked at a DIFFERENT salon from the master's live one — the post-rotation
        // shape. booking.getSalon() is the fetched row; master.getSalon() is a real uninitialised
        // proxy that AuthorizationService's getOwner() walk must open.
        UUID bookedSalonId = insertBareSalonUnderOwner(
                ownerId, "bprc-detail-booked-salon-" + System.nanoTime());
        assertThat(bookedSalonId)
                .as("premise — divergent from the master's live salon, or this is the aligned case")
                .isNotEqualTo(salon.salonId());
        UUID bookingId = insertBooking(clientId, salon.masterId(), serviceId, ANCHOR,
                new BigDecimal("300.00"), new BigDecimal("500.00"), "CONFIRMED", bookedSalonId);

        Statistics statistics = statistics();
        statistics.clear();
        var detail = bookingService.getBooking(ownerId, bookingId);
        long entities = statistics.getEntityLoadCount();
        long statements = statistics.getPrepareStatementCount();

        assertThat(statements)
                .as("absolute JDBC statement count for a POST-ROTATION detail read — exactly one "
                        + "more than the aligned case (%s), and that one is the master's live Salon "
                        + "row, opened by AuthorizationService's getSalon().getOwner() walk. It is "
                        + "irreducible: the request needs the booking's salon to display and the "
                        + "master's live salon to authorize, and they are different rows. A rise "
                        + "beyond this is a real regression.", OWNER_DETAIL_STATEMENTS_ALIGNED)
                .isEqualTo(OWNER_DETAIL_STATEMENTS_ROTATED);

        // The authorization walk itself is the premise: reaching a response at all means
        // getOwner().getId() resolved off the proxy rather than throwing.
        assertThat(detail.priceMaxAtBooking())
                .as("premise — the owner must actually be authorized to read this booking")
                .isEqualByComparingTo("500.00");
        assertThat(detail.salonName())
                .as("premise — the row must carry a real salon, or there is no Salon.owner proxy "
                        + "for this gate to be about")
                .isNotNull();
        assertThat(detail.salonId())
                .as("premise — the booking's own salon snapshot must be the DIVERGENT row, or "
                        + "there is no second Salon for this gate to account for either")
                .isEqualTo(bookedSalonId)
                .isNotEqualTo(salon.salonId());
        assertThat(entities)
                .as("absolute HYDRATED-ENTITY count for one owner-served booking detail whose "
                        + "salon DIVERGES from the master's live one: the booking's Salon (fetched) "
                        + "plus the master's live Salon (opened by the ownership walk), and nothing "
                        + "else. A rise to %s means s.owner — or another association — was "
                        + "fetch-joined back onto findByIdWithFullGraph, invisible to any statement "
                        + "count, which is precisely why this gate counts entities.",
                        OWNER_DETAIL_ENTITIES_ROTATED + 1)
                .isEqualTo(OWNER_DETAIL_ENTITIES_ROTATED);
    }

    @Test
    @DisplayName("CLIENT scope — a 5-row page costs a fixed, absolute number of JDBC statements, "
            + "each booking on its OWN service definition so a lost join cannot hide in the L1 cache")
    void should_notScaleStatementCount_when_clientPageHasManyBookings() {
        String masterEmail = "bprc-qcount-client-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("bprc-qcount-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        Pageable pageable = PageRequest.of(0, 20);
        Statistics statistics = statistics();

        seedBookingsOnDistinctServices(clientId, masterId, 1);
        statistics.clear();
        bookingService.getMyBookings(clientId, authFor(Role.CLIENT), null, null, null, null, pageable);
        long statementsForOneRow = statistics.getPrepareStatementCount();

        seedBookingsOnDistinctServices(clientId, masterId, 4);
        statistics.clear();
        var result = bookingService.getMyBookings(clientId, authFor(Role.CLIENT), null, null, null, null, pageable);
        long statementsForFiveRows = statistics.getPrepareStatementCount();

        assertThat(result.data()).hasSize(5);
        assertThat(statementsForFiveRows)
                .as("absolute JDBC statement count for a 5-row page (each on a distinct service "
                        + "definition). A rise means an association is no longer joined and is being "
                        + "lazily initialised per row.")
                .isEqualTo(CLIENT_PAGE_STATEMENTS);
        assertThat(statementsForFiveRows)
                .as("secondary signal — statement count must not scale with row count; got %s for "
                        + "1 row, %s for 5 rows", statementsForOneRow, statementsForFiveRows)
                .isEqualTo(statementsForOneRow);
    }

    /**
     * Seeds {@code count} bookings, each against a FRESHLY created {@code service_definitions} +
     * {@code master_services} pair. Distinct services are the whole point: with a single shared
     * assignment, Hibernate's first-level cache resolves every row's association from one SELECT,
     * so a deleted {@code JOIN FETCH} costs exactly one extra statement no matter how many rows
     * are on the page — and a count-based gate sees nothing.
     *
     * <p>Slots are spaced 90 minutes apart from a per-call offset so repeated calls never collide
     * on the {@code bookings} overlap constraints.
     *
     * <p><b>A real locality is stamped on the master's {@code users} row</b> so the label-resolution
     * seam is actually exercised. {@code BookingTestFixtures.createUser} leaves
     * {@code city_id}/{@code district_id} NULL, and {@code BookingService}'s
     * {@code resolveProjectionLabels}/{@code resolveBookingLabels} pre-filter nulls before calling
     * {@code DiscoveryLocationResolver.resolveLabels} — so with an all-null page BOTH id sets came
     * out empty, both {@code IN} queries were skipped, and the two gates below counted zero
     * statements for a seam production pays up to two for. A regression that turned
     * {@code resolveLabels} into a per-row lookup would have stayed invisible (still zero). Both
     * ids are stamped, not just the city, so both dimensions of the seam are counted.
     *
     * <p><b>Why one locality and not a distinct one per booking</b> (which would make a per-row
     * regression scale with page size, not merely appear): both gates scope their page to a SINGLE
     * independent master, and the discovery locality of a booking is
     * {@code master.salon != null ? salon.cityId : master.user.cityId} — for a salon-less master,
     * one value for every row on the page, by construction. Distinct-per-booking is unreachable
     * here without seeding distinct masters, which would put the bookings outside the very
     * master-scoped page these gates measure. What the absolute count still catches: a regression
     * that calls {@code resolveLabels} once PER ROW (5 calls x 1 city query = 5 statements, caught
     * by both the absolute bound and the 1-row/5-row delta), and any new unbatched lookup on this
     * path. What it cannot catch: a per-id loop INSIDE {@code resolveLabels}, which a one-element
     * id set collapses to a single query regardless.
     */
    private void seedBookingsOnDistinctServices(UUID clientId, UUID masterId, int count) {
        stampMasterLocality(masterId);
        long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ?", Long.class, masterId);
        for (int i = 0; i < count; i++) {
            UUID serviceId = createRangeService(
                    masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
            insertBooking(clientId, masterId, serviceId,
                    ANCHOR.plusMinutes(90L * (existing + i)),
                    new BigDecimal("300.00"), new BigDecimal("500.00"));
        }
    }

    /**
     * Adds a SECOND active salon (plus its own {@code SALON_MASTER}) under an EXISTING owner, so a
     * {@code SALON_OWNER} page spans more than one salon.
     *
     * <p>Needed because {@code BookingTestFixtures.createSalon} mints a fresh owner per call, which
     * can only ever produce single-salon owners. {@code BookingService#listProviderBookings} routes
     * {@code SALON_OWNER} through {@code salonRepository.findIdsByOwnerIdAndIsActiveTrue} into
     * {@code findIdsBySalonIdsFiltered} — an {@code IN (…)} over every salon the owner holds — and
     * with one salon that query is indistinguishable from the single-id
     * {@code findIdsByMasterIdFiltered} the other gates cover.
     */
    private UUID addSalonUnderOwner(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);
        UUID masterUserId = fixtures.createUser(
                "bprc-owner-salon-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), masterUserId, salonId);
        return salonId;
    }

    private UUID masterIdOfSalon(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM masters WHERE salon_id = ? ORDER BY created_at LIMIT 1", UUID.class, salonId);
    }

    /**
     * SALON-master twin of {@link #seedBookingsOnDistinctServices}. Same distinct-service-per-row
     * discipline (so no lost join can hide in the L1 cache), but the master belongs to a salon:
     * {@code masters.salon_id} is non-null, the service definitions are {@code owner_type = 'SALON'},
     * and the locality lives on the SALON row.
     *
     * <p><b>Why this variant exists.</b> Every other gate here seeds through
     * {@code fixtures.createIndependentMaster}, which leaves {@code m.salon} NULL on every row. With
     * a NULL salon the {@code LEFT JOIN FETCH m.salon s} produces nothing to hold a
     * {@code Salon.owner} proxy, so a lazy {@code getOwner()} dereference on the provider list path
     * literally CANNOT move a statement count — which made those gates unable to justify dropping
     * the {@code s.owner} fetch from {@code findAllByIdsWithGraph}, even though the drop's javadoc
     * cites exactly that path. This seed is the one that puts a real, initialisable
     * {@code Salon.owner} proxy on the page, so the pinned count below actually has the power to
     * catch a regression that starts walking {@code salon.getOwner()}.
     *
     * <p><b>{@code bookedSalonId} (Phase B2 QA)</b> is what each booking's own
     * {@code bookings.salon_id} snapshot is set to — deliberately a DIFFERENT salon from
     * {@code salonId} (the master's live affiliation, which drives {@code m.salon} and the
     * locality stamp). See {@link #SALON_MASTER_PAGE_ENTITIES} for why the divergence is
     * load-bearing rather than decorative.
     */
    private void seedSalonBookingsOnDistinctServices(UUID clientId, UUID salonId, UUID masterId,
                                                     int count, UUID bookedSalonId) {
        stampSalonLocality(salonId);
        long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ?", Long.class, masterId);
        for (int i = 0; i < count; i++) {
            UUID serviceId = createRangeService(
                    "SALON", salonId, masterId, new BigDecimal("300.00"), new BigDecimal("500.00"), null);
            insertBooking(clientId, masterId, serviceId,
                    ANCHOR.plusMinutes(90L * (existing + i)),
                    new BigDecimal("300.00"), new BigDecimal("500.00"), "CONFIRMED", bookedSalonId);
        }
    }

    /**
     * Pins BOTH {@code updated_at} columns the backfill inspects ({@code service_definitions} and
     * {@code master_services}) to a fixed offset from the booking's own {@code created_at}.
     * Necessary because {@link #createRangeService} and {@link #insertBooking} both stamp
     * {@code NOW()}, leaving their relative order undefined at microsecond resolution — the guard
     * under test would then be decided by a race rather than by the scenario.
     */
    private void alignServiceTimestamps(UUID masterServiceId, UUID bookingId, String interval) {
        jdbcTemplate.update(
                "UPDATE service_definitions SET updated_at = "
                        + "(SELECT created_at FROM bookings WHERE id = ?) + ?::interval "
                        + "WHERE id = (SELECT service_def_id FROM master_services WHERE id = ?)",
                bookingId, interval, masterServiceId);
        jdbcTemplate.update(
                "UPDATE master_services SET updated_at = "
                        + "(SELECT created_at FROM bookings WHERE id = ?) + ?::interval WHERE id = ?",
                bookingId, interval, masterServiceId);
    }

    /**
     * Bumps ONLY {@code service_definitions.updated_at} past the booking, leaving
     * {@code master_services.updated_at} in the past — so a failure isolates the
     * {@code sd.updated_at <= b.created_at} half of the guard rather than passing on the other.
     */
    private void touchServiceDefinitionAfterBooking(UUID masterServiceId, UUID bookingId) {
        jdbcTemplate.update(
                "UPDATE service_definitions SET updated_at = "
                        + "(SELECT created_at FROM bookings WHERE id = ?) + interval '1 second' "
                        + "WHERE id = (SELECT service_def_id FROM master_services WHERE id = ?)",
                bookingId, masterServiceId);
    }

    /**
     * Bumps ONLY {@code master_services.updated_at} past the booking, leaving
     * {@code service_definitions.updated_at} in the past — the exact mirror of
     * {@link #touchServiceDefinitionAfterBooking}, so a failure isolates the
     * {@code ms.updated_at <= b.created_at} half of the freshness guard.
     *
     * <p>Both halves need their own fixture because the two columns move independently in
     * production: editing the salon's definition (price, mode, duration) touches only {@code sd},
     * while a master editing their own assignment (setting or dropping a {@code price_override},
     * re-activating the row) touches only {@code ms}. With only the {@code sd} fixture in the
     * suite, deleting the {@code ms} conjunct from V120 changed no test's outcome.
     */
    private void touchMasterServiceAfterBooking(UUID masterServiceId, UUID bookingId) {
        jdbcTemplate.update(
                "UPDATE master_services SET updated_at = "
                        + "(SELECT created_at FROM bookings WHERE id = ?) + interval '1 second' "
                        + "WHERE id = ?",
                bookingId, masterServiceId);
    }

    /**
     * FIXED-priced twin of {@link #createRangeService}, for the backfill's {@code price_type =
     * 'RANGE'} negative case. {@code price_max} is left NULL because
     * {@code chk_service_def_price_mode} (V67:41) requires exactly that for a FIXED row — which is
     * also why the FIXED case cannot falsify the {@code price_type} conjunct independently of the
     * {@code price_max IS NOT NULL} one; see the note on
     * {@code should_leaveNullCeiling_when_serviceIsFixed}.
     */
    private UUID createFixedService(UUID masterId, BigDecimal basePrice) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, price_max, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Fixed Service', ?, 60, 'FIXED', ?, NULL, 0, "
                        + "true, NOW(), NOW())",
                serviceDefId, userId,
                fixtures.resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId), basePrice);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, NULL, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private BigDecimal ceilingOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT price_max_at_booking FROM bookings WHERE id = ?", BigDecimal.class, bookingId);
    }

    /**
     * Points the master's {@code users} row at a real {@code city_districts} row and its parent
     * city (both columns carry FKs — V54:53 / V54 {@code fk_users_city_id} — so invented UUIDs
     * would be rejected), making {@code discoveryCityId}/{@code discoveryDistrictId} non-null for
     * every booking seeded against this master. Idempotent: repeated calls rewrite the same pair.
     */
    /**
     * Salon-side twin of {@link #stampMasterLocality}. For a salon-employed master the discovery
     * locality is read off the SALON row, not the master's {@code users} row
     * ({@code BookingService#discoveryCityId}: {@code salon != null ? salon.cityId :
     * masterUser.cityId}), so the salon gate must stamp here or both label {@code IN} queries are
     * skipped and the gate silently stops counting the seam — the exact hole
     * {@link #stampMasterLocality} was added to close on the independent path. Idempotent.
     */
    private void stampSalonLocality(UUID salonId) {
        UUID[] districtAndCity = jdbcTemplate.queryForObject(
                "SELECT id, city_id FROM city_districts ORDER BY id LIMIT 1",
                (rs, n) -> new UUID[]{(UUID) rs.getObject(1), (UUID) rs.getObject(2)});
        jdbcTemplate.update(
                "UPDATE salons SET district_id = ?, city_id = ? WHERE id = ?",
                districtAndCity[0], districtAndCity[1], salonId);
    }

    private void stampMasterLocality(UUID masterId) {
        UUID[] districtAndCity = jdbcTemplate.queryForObject(
                "SELECT id, city_id FROM city_districts ORDER BY id LIMIT 1",
                (rs, n) -> new UUID[]{(UUID) rs.getObject(1), (UUID) rs.getObject(2)});
        jdbcTemplate.update(
                "UPDATE users SET district_id = ?, city_id = ? "
                        + "WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                districtAndCity[0], districtAndCity[1], masterId);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * Creates a RANGE-priced {@code service_definitions} row (+ {@code master_services}
     * assignment, optionally with {@code priceOverride}) for an INDEPENDENT_MASTER. Deliberately
     * NOT added to the shared {@link BookingTestFixtures} — its {@code createIndependentMasterService}
     * is hardcoded to a FIXED price, and widening its signature would touch every existing caller;
     * this suite is the only one that needs RANGE pricing, so the helper stays local (mirrors the
     * established convention documented on {@code BookingTestFixtures}).
     */
    private UUID createRangeService(UUID masterId, BigDecimal basePrice, BigDecimal priceMax, BigDecimal priceOverride) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        return createRangeService("INDEPENDENT_MASTER", userId, masterId, basePrice, priceMax, priceOverride);
    }

    /**
     * Owner-parameterised form, so the SALON-master gate can seed a {@code owner_type = 'SALON'}
     * definition without duplicating the insert. {@code ownerId} must match {@code ownerType}:
     * the master's USER id for {@code INDEPENDENT_MASTER}, the SALON id for {@code SALON} —
     * mirroring {@code BookingTestFixtures.createSalonService}.
     */
    private UUID createRangeService(String ownerType, UUID ownerId, UUID masterId,
                                    BigDecimal basePrice, BigDecimal priceMax, BigDecimal priceOverride) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, price_max, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Range Service', ?, 60, 'RANGE', ?, ?, 0, true, NOW(), NOW())",
                serviceDefId, ownerType, ownerId,
                fixtures.resolveUnusedServiceTypeId(ownerType, ownerId), basePrice, priceMax);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId, priceOverride);
        return masterServiceId;
    }

    /**
     * Inserts a CONFIRMED booking with an explicit frozen price floor AND ceiling, mirroring what
     * the create paths persist (see {@code BookingPriceRange}). Read-side tests seed via SQL rather
     * than {@code POST /bookings} because the point under test is what the read paths surface; the
     * freeze itself is covered separately, through the real create endpoints.
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                               OffsetDateTime startsAt, BigDecimal priceAtBooking,
                               BigDecimal priceMaxAtBooking) {
        return insertBooking(clientId, masterId, masterServiceId, startsAt,
                priceAtBooking, priceMaxAtBooking, "CONFIRMED");
    }

    /**
     * Status-parameterised form. Exists so the freeze can be asserted on a {@code COMPLETED}
     * booking — the phase's headline framing is "even a COMPLETED booking keeps its agreed band",
     * yet every other freeze test in this class runs on {@code CONFIRMED}. The band is structurally
     * status-independent ({@code BookingPriceRange} never reads the status, and the read paths
     * select a stored column), so this pins a claim the suite made but never exercised rather than
     * covering a distinct code path.
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                               OffsetDateTime startsAt, BigDecimal priceAtBooking,
                               BigDecimal priceMaxAtBooking, String status) {
        return insertBooking(clientId, masterId, masterServiceId, startsAt,
                priceAtBooking, priceMaxAtBooking, status, null);
    }

    /**
     * {@code salon_id}-carrying form (Phase B2 QA). Every other overload leaves
     * {@code bookings.salon_id} NULL, which made all four statement/entity gates in this class
     * structurally blind to {@code BookingDetailResponse.from}'s
     * {@code booking.getSalon() != null ? booking.getSalon().getId() : null} dereference: with a
     * NULL FK there is no proxy to dereference at all, so the gates could not have caught it
     * becoming a statement.
     *
     * <p>The value must be a salon row DISTINCT from the master's own {@code masters.salon_id} for
     * the gate to bite. When the two agree, Hibernate resolves {@code booking.salon} off the
     * first-level cache entry already materialised by {@code LEFT JOIN FETCH m.salon} and hands
     * back the real entity — no proxy is ever created and the id read is trivially free. Only a
     * divergent id (the post-rotation shape B2's whole design is about) produces a genuine
     * uninitialised {@code Salon} proxy on the page.
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                               OffsetDateTime startsAt, BigDecimal priceAtBooking,
                               BigDecimal priceMaxAtBooking, String status, UUID salonId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, price_max_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status, startsAt,
                startsAt.plusMinutes(60), priceAtBooking, priceMaxAtBooking);
        return bookingId;
    }

    /**
     * A second {@code salons} row under an EXISTING owner — the "salon the booking was made at"
     * for the divergent-salon gates. Deliberately leaner than {@link #addSalonUnderOwner}: no
     * master is attached, because nothing must ever put this salon on the page under test via the
     * master graph. It exists as the target of {@code bookings.salon_id} alone.
     *
     * <p><b>Phase 242 — it is stamped with a locality, and that is load-bearing.</b> Since the
     * display block (including {@code cityLabel}/{@code districtLabel}) is resolved from
     * {@code booking.getSalon()}, a locality-less booked salon makes
     * {@code DiscoveryLocationResolver.resolveLabels} short-circuit on two empty id sets and the
     * page silently drops from six statements to four. The gates would then be pinning a number
     * produced by what the fixture OMITS rather than by the production path — and would go blind
     * to a regression in the label queries. Both salons carry the SAME locality on purpose: the
     * divergence that matters to these gates is the salon ROW identity (which decides whether a
     * proxy exists at all), not the locality values.
     */
    private UUID insertBareSalonUnderOwner(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name);
        stampSalonLocality(salonId);
        return salonId;
    }

    /**
     * Mirrors the V72 schedule shape: ONE open-ended {@code weekly_schedules} row plus SEVEN
     * {@code working_intervals} rows (ISO day_of_week 1..7), so slot availability is not
     * weekday-dependent and the create-path tests can book any near-future day.
     */
    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '08:00', '20:00')",
                    UUID.randomUUID(), scheduleId, day);
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private UUID createBookingViaApi(String clientToken, UUID masterId, UUID masterServiceId) throws Exception {
        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"masterId":"%s","masterServiceId":"%s","startsAt":"%s"}
                """.formatted(masterId, masterServiceId, startsAt.toOffsetDateTime());
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode())
                .as("booking creation must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(resp.getBody()).path("data").path("id").asText());
    }

    private ResponseEntity<String> callMyBookings(String token) {
        return restTemplate.exchange(
                BOOKINGS_URL + "/me?size=20", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private JsonNode getDetail(String token, UUID bookingId) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private JsonNode firstRow(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        JsonNode data = root.path("data").path("data");
        assertThat(data).as("expected exactly one booking row").hasSize(1);
        return data.get(0);
    }
}
