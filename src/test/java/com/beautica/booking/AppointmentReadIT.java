package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack smoke suite for BE-5's read model of multi-service visits:
 *
 * <ol>
 *   <li><b>Additive {@code appointmentId}</b> on the booking read model — {@code GET /bookings/me}
 *       and {@code GET /bookings/{id}} both surface the populated {@code appointmentId} for a visit
 *       child, and {@code null} for a legacy single-service booking. The row shape is otherwise
 *       unchanged (grouping N item rows into one card stays the mobile client's job, MO-5).</li>
 *   <li><b>New {@code GET /api/v1/appointments/{id}}</b> — returns the full enriched visit for the
 *       owning CLIENT and for the owning provider (the visit's master), and a uniform {@code 403}
 *       for a foreign client / foreign provider (no existence oracle, mirroring
 *       {@code GET /bookings/{id}}).</li>
 * </ol>
 *
 * <p>Reuses {@link BookingTestFixtures} + the local {@code addWorkingHoursForEveryDay}/{@code
 * postVisit} helpers established by {@link AppointmentCreateIT} (the BE-3 create smoke) rather than
 * re-seeding by hand.
 */
@Import(TestSecurityConfig.class)
@DisplayName("BE-5 read model — appointmentId on bookings + GET /appointments/{id} over real Postgres")
class AppointmentReadIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String BOOKINGS_URL = "/api/v1/bookings";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory emf;

    private BookingTestFixtures fixtures;

    /**
     * Absolute JDBC statement count for ONE {@code GET /appointments/{id}} served over the full
     * HTTP stack, read by the OWNING CLIENT. Pins the phase-242 choice of {@code first.getSalon()}
     * (item snapshot) over {@code appointment.getSalon()}: the items query already fetch-joins
     * {@code b.salon}, so the address block costs nothing, whereas the appointment header is loaded
     * by a plain {@code findById} and reading its salon would lazy-load one more row per visit. A
     * rise here means the salon fetch was re-pointed away from {@code b.salon}, or the header's own
     * salon is being dereferenced. DERIVED FROM A RUN, never predicted.
     *
     * <p><b>Phase-242 audit, finding 1 — this constant DROPPED 5 &rarr; 4, and that is the fix
     * working, not a regression. Do not "restore" it to 5.</b> Before that fix
     * {@code AuthorizationService#enforceCanViewBooking} called
     * {@code isAuthorizedToManageBooking} unconditionally, ahead of its role branches, and that
     * predicate walks {@code master.getSalon().getOwner()} — a PROPERTY read that INITIALISES the
     * {@code Salon} proxy. Since phase 242 stopped fetching {@code m.salon}, an owning CLIENT
     * reading a ROTATED visit paid one standalone {@code SELECT ... FROM salons} to load the
     * master's LIVE salon: a row the client's response never renders. The client fast path now runs
     * first, so that statement is gone for the highest-volume viewer class.
     *
     * <p>Shared by BOTH shape gates below ({@code masterHasSinceRotated} and
     * {@code masterHasSinceGoneIndependent}) because the two genuinely cost the same for a CLIENT
     * viewer — see {@link #should_notLazyLoadTheBookedSalon_when_theVisitsMasterHasSinceGoneIndependent}
     * for why only the second of them can tell {@code b.salon} apart from {@code m.salon}.
     */
    private static final long VISIT_DETAIL_STATEMENTS = 4L;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. appointmentId is additive on the booking read model
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /bookings/me and GET /bookings/{id} both carry the populated appointmentId for a "
            + "visit child; a legacy single-service booking returns appointmentId = null")
    void should_exposeAppointmentId_when_bookingIsVisitChild_andNull_forLegacyBooking() throws Exception {
        String masterEmail = "be5-read-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "be5-read-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        // A two-service visit → one appointment, two chained bookings.
        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        JsonNode visit = objectMapper.readTree(
                postVisit(clientToken, masterId, startsAt, serviceA, serviceB).getBody()).path("data");
        UUID appointmentId = UUID.fromString(visit.path("id").asText());
        UUID childBookingId = UUID.fromString(visit.path("items").get(0).path("bookingId").asText());

        // A legacy single-service booking for the same client — appointment_id stays NULL.
        UUID legacyBookingId = insertLegacyBooking(clientId, masterId, serviceA);

        // GET /bookings/{id} — the visit child carries the appointmentId; the legacy booking is null.
        JsonNode childDetail = getJson(BOOKINGS_URL + "/" + childBookingId, clientToken).path("data");
        assertThat(childDetail.path("appointmentId").asText())
                .as("a visit child's GET /bookings/{id} must surface its appointment_id")
                .isEqualTo(appointmentId.toString());

        JsonNode legacyDetail = getJson(BOOKINGS_URL + "/" + legacyBookingId, clientToken).path("data");
        assertThat(legacyDetail.path("appointmentId").isNull())
                .as("a legacy single-service booking has no appointment — appointmentId must be null")
                .isTrue();

        // GET /bookings/me — the same populated/null split holds on the CLIENT projection path.
        JsonNode meRows = getJson(BOOKINGS_URL + "/me?size=50", clientToken).path("data").path("data");
        JsonNode childRow = findRow(meRows, childBookingId);
        JsonNode legacyRow = findRow(meRows, legacyBookingId);

        assertThat(childRow).as("the visit child must appear on the client's GET /bookings/me").isNotNull();
        assertThat(childRow.path("appointmentId").asText())
                .as("GET /bookings/me must surface the visit child's appointment_id (CLIENT projection)")
                .isEqualTo(appointmentId.toString());
        assertThat(legacyRow).as("the legacy booking must appear on GET /bookings/me").isNotNull();
        assertThat(legacyRow.path("appointmentId").isNull())
                .as("the legacy booking's appointmentId must be null on GET /bookings/me too")
                .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. GET /appointments/{id} — owning client + owning provider 200; foreign 403
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /appointments/{id} returns the full enriched visit for the owning CLIENT and the "
            + "owning provider, and 403 for a foreign client and a foreign provider (uniform, no oracle)")
    void should_returnVisitForOwnerAndProvider_andDenyForeignViewers() throws Exception {
        String masterEmail = "be5-appt-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "be5-appt-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(9).withMinute(0).withSecond(0).withNano(0);
        JsonNode visit = objectMapper.readTree(
                postVisit(clientToken, masterId, startsAt, serviceA, serviceB).getBody()).path("data");
        UUID appointmentId = UUID.fromString(visit.path("id").asText());

        // Owning CLIENT → 200 with the full visit (two items) and the BE-5 enrichment fields present.
        ResponseEntity<String> ownerResp = getRaw(APPOINTMENTS_URL + "/" + appointmentId, clientToken);
        assertThat(ownerResp.getStatusCode())
                .as("the owning client must read their own visit — body: %s", ownerResp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode ownerData = objectMapper.readTree(ownerResp.getBody()).path("data");
        assertThat(ownerData.path("id").asText()).isEqualTo(appointmentId.toString());
        assertThat(ownerData.path("items")).as("the visit returns its ordered items").hasSize(2);
        // BE-5 enrichment: the mutually-visible note fields exist (null on a CONFIRMED visit) and the
        // locality fields exist — proving AppointmentDetailResponse gained them.
        assertThat(ownerData.has("providerComment")
                && ownerData.has("clientCancellationNote")
                && ownerData.has("clientComment")
                && ownerData.has("cityLabel") && ownerData.has("districtLabel")
                && ownerData.has("street") && ownerData.has("buildingNo")
                && ownerData.has("locationNote"))
                .as("the enriched visit-detail fields must be present on the response")
                .isTrue();

        // Owning provider (the visit's master) → 200.
        String masterToken = fixtures.tokenFor(masterEmail);
        assertThat(getRaw(APPOINTMENTS_URL + "/" + appointmentId, masterToken).getStatusCode())
                .as("the visit's own master must be able to read the visit")
                .isEqualTo(HttpStatus.OK);

        // Foreign CLIENT → 403.
        String foreignClientEmail = "be5-appt-foreign-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignClientEmail, "CLIENT", null);
        assertThat(getRaw(APPOINTMENTS_URL + "/" + appointmentId, fixtures.tokenFor(foreignClientEmail))
                .getStatusCode())
                .as("a different client must never read another client's visit")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Foreign provider (a different independent master) → 403.
        String foreignMasterEmail = "be5-appt-foreign-master-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMaster(foreignMasterEmail);
        assertThat(getRaw(APPOINTMENTS_URL + "/" + appointmentId, fixtures.tokenFor(foreignMasterEmail))
                .getStatusCode())
                .as("a master who does not own the visit must be denied")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. GET /appointments/{id} — enriched payload: ordered items, frozen totals,
    //    and mutually-visible provider note after a DECLINE (client sees it)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /appointments/{id} carries the ordered contiguous items and the frozen visit "
            + "totals; after a provider DECLINE the owning CLIENT sees the header providerComment "
            + "(mutually-visible notes rule)")
    void should_exposeOrderedItemsTotalsAndDeclineNote_toOwningClient() throws Exception {
        String masterEmail = "be5-note-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "be5-note-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(4).withHour(11).withMinute(0).withSecond(0).withNano(0);
        JsonNode visit = objectMapper.readTree(
                postVisit(clientToken, masterId, startsAt, serviceA, serviceB).getBody()).path("data");
        UUID appointmentId = UUID.fromString(visit.path("id").asText());

        // Enriched read while CONFIRMED: ordered, contiguous items + frozen totals.
        JsonNode confirmed = getJson(APPOINTMENTS_URL + "/" + appointmentId, clientToken).path("data");
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");

        JsonNode items = confirmed.path("items");
        assertThat(items).as("a two-service visit returns two ordered item lines").hasSize(2);
        ZonedDateTime item0End = ZonedDateTime.parse(items.get(0).path("endsAt").asText());
        ZonedDateTime item1Start = ZonedDateTime.parse(items.get(1).path("startsAt").asText());
        assertThat(item0End.isEqual(item1Start))
                .as("items must be ordered and contiguous — item[1].startsAt == item[0].endsAt")
                .isTrue();
        assertThat(items.get(0).path("serviceName").asText()).isNotBlank();
        assertThat(items.get(0).path("bookingId").isNull())
                .as("each item line carries its child bookingId").isFalse();

        // Frozen totals: two 500.00 base-price, 60-min, zero-buffer services → 1000.00 / 120 min,
        // single price (no range) → totalPriceMax null. Never re-derived on read.
        assertThat(new BigDecimal(confirmed.path("totalPrice").asText()))
                .as("totalPrice = Σ item price floors (2 × 500.00)")
                .isEqualByComparingTo("1000.00");
        assertThat(confirmed.path("totalPriceMax").isNull())
                .as("no service was a genuine range → totalPriceMax must be null")
                .isTrue();
        assertThat(confirmed.path("totalDurationMinutes").asInt())
                .as("totalDurationMinutes = endsAt − startsAt = 2 × (60 + 0)")
                .isEqualTo(120);
        assertThat(confirmed.path("providerComment").isNull())
                .as("a CONFIRMED visit has no provider note yet").isTrue();

        // Provider (the independent master) declines the whole visit with a note.
        String masterToken = fixtures.tokenFor(masterEmail);
        String note = "Майстер захворів — переносимо візит";
        ResponseEntity<Void> declineResp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{\"providerComment\":\"" + note + "\"}", jsonHeaders(masterToken)),
                Void.class);
        assertThat(declineResp.getStatusCode())
                .as("the visit's own master must be able to decline it").isEqualTo(HttpStatus.NO_CONTENT);

        // The mutually-visible header note is surfaced to the OWNING CLIENT on the DECLINED visit —
        // by the locked "all notes visible for all sides" decision (mirrors BookingNoteVisibilityIT).
        JsonNode declined = getJson(APPOINTMENTS_URL + "/" + appointmentId, clientToken).path("data");
        assertThat(declined.path("status").asText()).isEqualTo("DECLINED");
        assertThat(declined.path("providerComment").asText())
                .as("the owning client must see the provider's decline note on the visit header")
                .isEqualTo(note);
    }

    @Test
    @DisplayName("GET /appointments/{id} for an UNKNOWN id returns 403 (not 404) — uniform with "
            + "GET /bookings/{id}, no existence oracle")
    void should_return403_when_appointmentIdIsUnknown() throws Exception {
        String clientEmail = "be5-missing-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        assertThat(getRaw(APPOINTMENTS_URL + "/" + UUID.randomUUID(), clientToken).getStatusCode())
                .as("a non-existent appointment id must be a uniform 403, never a 404 that reveals "
                        + "existence")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. PII / address masking — a salon visit surfaces the SALON's address and
    //    NEVER the salon-employed master's PERSONAL street/door code (leak class)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /appointments/{id} for a SALON visit returns the SALON address and NEVER the "
            + "salon-employed master's personal street / building / door code (no PII leak)")
    void should_returnSalonAddress_andNeverMasterPersonalDoorCode_forSalonVisit() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("be5-salon-owner-" + System.nanoTime() + "@beautica.test");

        // The salon's own arrival address — what a client MUST see.
        String salonStreet = "вул. Салонна-" + System.nanoTime();
        String salonBuildingNo = "10-САЛОН";
        String salonNote = "2-й поверх, вхід з вулиці";
        jdbcTemplate.update(
                "UPDATE salons SET street = ?, building_no = ?, location_note = ? WHERE id = ?",
                salonStreet, salonBuildingNo, salonNote, salon.salonId());

        // The salon-employed master's DISTINCT personal home address — must NEVER surface on a salon
        // visit (this is the door-code leak class the booking-detail path was pinned against).
        String masterPersonalStreet = "вул-Приватна-СЕКРЕТ-" + System.nanoTime();
        String masterPersonalBuildingNo = "99-ПРИВАТ";
        String masterPersonalNote = "приватний код дверей 4321 — не показувати";
        jdbcTemplate.update(
                "UPDATE users SET street = ?, building_no = ?, location_note = ? "
                        + "WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                masterPersonalStreet, masterPersonalBuildingNo, masterPersonalNote, salon.masterId());

        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        String clientEmail = "be5-salon-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(5).withHour(12).withMinute(0).withSecond(0).withNano(0);
        JsonNode visit = objectMapper.readTree(
                postVisit(clientToken, salon.masterId(), startsAt, serviceA).getBody()).path("data");
        UUID appointmentId = UUID.fromString(visit.path("id").asText());

        ResponseEntity<String> resp = getRaw(APPOINTMENTS_URL + "/" + appointmentId, clientToken);
        assertThat(resp.getStatusCode())
                .as("the owning client must read their salon visit — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");

        // The SALON's address wins outright (salon-employed → salon locality, never the master's own).
        assertThat(data.path("street").asText())
                .as("a salon visit must arrive at the SALON's street").isEqualTo(salonStreet);
        assertThat(data.path("buildingNo").asText()).isEqualTo(salonBuildingNo);
        assertThat(data.path("locationNote").asText()).isEqualTo(salonNote);

        // THE CRITICAL non-leak assertion: the master's PERSONAL address never appears ANYWHERE in the
        // response — not in the address fields, not smuggled into any other field.
        String rawBody = resp.getBody();
        assertThat(rawBody)
                .as("the salon-master's personal street must NEVER leak onto a salon visit")
                .doesNotContain(masterPersonalStreet);
        assertThat(rawBody)
                .as("the salon-master's personal building number must NEVER leak onto a salon visit")
                .doesNotContain(masterPersonalBuildingNo);
        assertThat(rawBody)
                .as("the salon-master's personal door code must NEVER leak onto a salon visit")
                .doesNotContain(masterPersonalNote);
    }

    /**
     * Phase 242, appointment surface — the visit's address block follows the VISIT's own salon
     * snapshot, not the master's live affiliation.
     *
     * <p>The twin of {@code BookingDetailContractIT#should_serveTheBookedSalonsAddress_when_
     * theMasterHasSinceRotatedToAnotherSalon}, for {@code GET /appointments/{id}}. Same leak, same
     * sharp edge: {@code locationNote} holds door codes, so serving a rotated master's CURRENT
     * salon's note hands a client premises access for somewhere they never booked.
     *
     * <p>Both salons carry DISTINCT, NON-NULL street / buildingNo / locationNote. A null on either
     * side would make the {@code isNotEqualTo} assertions and the raw-body scan pass vacuously.
     */
    @Test
    @DisplayName("GET /appointments/{id} keeps serving the BOOKED salon's address and door code "
            + "after the master rotates to another salon — the new salon's note never reaches the client")
    void should_returnBookedSalonAddress_when_masterHasSinceRotatedToAnotherSalon() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("be5-rot-owner-" + System.nanoTime() + "@beautica.test");

        // Distinct localities, so cityLabel/districtLabel — resolved by AppointmentService#enrich
        // through a DIFFERENT code path from the address block in AppointmentDetailResponse#from —
        // are covered too. Without them, re-pointing only the DTO would still pass.
        Locality localityA = resolveLocality(0);
        // Phase-242 QA audit, finding 4: selected so BOTH labels differ. District names recur
        // across Ukrainian cities, so an offset-based second pick can hand back a pair whose
        // districtLabel coincides and defang the districtLabel assertion below.
        Locality localityB = resolveLocalityWithBothLabelsDifferentFrom(localityA);
        assertThat(localityB.cityLabel())
                .as("premise — the two seeded localities must differ, or the label assertion below "
                        + "compares a value against itself")
                .isNotEqualTo(localityA.cityLabel());
        assertThat(localityB.districtLabel())
                .as("premise — same, for the district half")
                .isNotEqualTo(localityA.districtLabel());

        // Salon A — where the visit is booked.
        String salonAStreet = "вул. Заброньована-A-" + System.nanoTime();
        String salonABuildingNo = "11-A";
        String salonANote = "A: 3-й поверх, код 1234";
        jdbcTemplate.update(
                "UPDATE salons SET name = ?, street = ?, building_no = ?, location_note = ?, "
                        + "city_id = ?, district_id = ? WHERE id = ?",
                "Booked Salon A", salonAStreet, salonABuildingNo, salonANote,
                localityA.cityId(), localityA.districtId(), salon.salonId());

        // The master's personal row stays distinct too — the pre-existing COALESCE-fallthrough
        // guard must survive this change untouched.
        String masterPersonalNote = "приватний код дверей 4321 — не показувати";
        jdbcTemplate.update(
                "UPDATE users SET street = ?, building_no = ?, location_note = ? "
                        + "WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                "вул-Приватна-СЕКРЕТ-" + System.nanoTime(), "99-ПРИВАТ",
                masterPersonalNote, salon.masterId());

        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        String clientEmail = "be5-rot-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(6).withHour(12).withMinute(0).withSecond(0).withNano(0);
        JsonNode visit = objectMapper.readTree(
                postVisit(clientToken, salon.masterId(), startsAt, serviceA).getBody()).path("data");
        UUID appointmentId = UUID.fromString(visit.path("id").asText());

        // Salon B — same owner (rotateMasterSalon permits same-owner moves only), created and
        // rotated to AFTER the visit exists. appointments.salon_id / bookings.salon_id are
        // snapshots and do not follow; masters.salon_id does.
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        String salonBStreet = "вул. Поточна-B-" + System.nanoTime();
        String salonBBuildingNo = "22-B";
        String salonBNote = "B: 5-й поверх, код 9999";
        UUID salonBId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, street, building_no, location_note, "
                        + "city_id, district_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonBId, ownerId, "Current Salon B", salonBStreet, salonBBuildingNo, salonBNote,
                localityB.cityId(), localityB.districtId());
        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", salonBId, salon.masterId());
        jdbcTemplate.update(
                "UPDATE users SET salon_id = ? WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                salonBId, salon.masterId());

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        ResponseEntity<String> resp = getRaw(APPOINTMENTS_URL + "/" + appointmentId, clientToken);
        long statements = statistics.getPrepareStatementCount();
        assertThat(resp.getStatusCode())
                .as("the owning client must still read their visit — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");

        assertThat(statements)
                .as("the address block must be served off the items query's LEFT JOIN FETCH "
                        + "b.salon — a rise means the booked salon is being lazy-loaded, one extra "
                        + "SELECT per visit read")
                .isEqualTo(VISIT_DETAIL_STATEMENTS);
        assertThat(data.path("salonName").asText())
                .as("salonName is the visit's own salon snapshot, not the master's current one")
                .isEqualTo("Booked Salon A").isNotEqualTo("Current Salon B");
        assertThat(data.path("street").asText()).isEqualTo(salonAStreet).isNotEqualTo(salonBStreet);
        assertThat(data.path("buildingNo").asText())
                .isEqualTo(salonABuildingNo).isNotEqualTo(salonBBuildingNo);
        assertThat(data.path("cityLabel").asText())
                .as("cityLabel is resolved by AppointmentService#enrich, a separate site from the "
                        + "address block — it must describe the SAME premises as street, or the "
                        + "client is sent to salon A's street in salon B's city")
                .isEqualTo(localityA.cityLabel()).isNotEqualTo(localityB.cityLabel());
        assertThat(data.path("districtLabel").asText())
                .as("Phase-242 QA audit, finding 4 — districtLabel rides AppointmentService#enrich's "
                        + "districtId, a SEPARATE re-pointed line from cityId, and NOTHING asserted "
                        + "its value anywhere in the suite: reverting just that line to "
                        + "master.getSalon().getDistrictId() was a green mutation. There is no "
                        + "AppointmentServiceTest and no AppointmentDetailResponseTest to catch it "
                        + "either, so this assertion is the only gate on that site.")
                .isEqualTo(localityA.districtLabel()).isNotEqualTo(localityB.districtLabel());
        // THE security assertion — negative stated explicitly, so it cannot pass on two fixtures
        // that happen to share a note.
        assertThat(data.path("locationNote").asText())
                .as("the client booked at salon A — they must get A's door code and NEVER B's")
                .isEqualTo(salonANote)
                .isNotEqualTo(salonBNote)
                .isNotEqualTo(masterPersonalNote);

        assertThat(resp.getBody())
                .as("nothing about the rotated-to salon, nor the master's personal address, may "
                        + "appear anywhere in the visit response")
                .doesNotContain(salonBNote)
                .doesNotContain(salonBStreet)
                .doesNotContain(salonBBuildingNo)
                .doesNotContain("Current Salon B")
                .doesNotContain(masterPersonalNote);
    }

    /**
     * Phase-242 audit, finding 4 — the ONE visit fixture that can tell
     * {@code findByAppointmentIdWithGraph}'s {@code LEFT JOIN FETCH b.salon} apart from the
     * {@code m.salon} it replaced.
     *
     * <p><b>Why the rotated gate above cannot.</b> There both salons exist, so either graph
     * materialises exactly one {@code Salon} and leaves the other a proxy — the counts coincide and
     * the statement assertion is blind to which one was fetched. Only the value assertions catch a
     * re-point there, and only for the DTO, not for the query. Here {@code masters.salon_id} is
     * {@code NULL} while {@code bookings.salon_id} still points at the booked salon, so a graph
     * still fetching {@code m.salon} materialises NOTHING and
     * {@code AppointmentDetailResponse#from}'s real-property reads off {@code first.getSalon()}
     * ({@code getName()} / {@code getStreet()} / {@code getLocationNote()}) lazy-load the booked
     * salon — one extra {@code SELECT}. This is the visit-side twin of
     * {@code BookingPriceRangeContractIT#should_notLazyLoadTheBookedSalon_when_theMasterHasSinceGoneIndependent},
     * which closed exactly this hole on the booking surface.
     *
     * <p>The correctness half is load-bearing too: a master going solo must not retroactively turn
     * a past SALON visit into a home-studio one. Reverting the DTO's salon source to the pre-242
     * {@code master.getSalon()} makes it {@code NULL} here, which the {@code salonName} assertion
     * catches, and re-exposes the fallthrough to the master's PERSONAL address that the
     * {@code locationNote} assertion pins.
     */
    @Test
    @DisplayName("GET /appointments/{id} for a visit whose master has since gone INDEPENDENT still "
            + "serves the BOOKED salon's address, and fetches it in the items query rather than "
            + "lazy-loading it — the one shape that pins WHICH salon the visit graph fetches")
    void should_notLazyLoadTheBookedSalon_when_theVisitsMasterHasSinceGoneIndependent() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("be5-gone-indep-owner-" + System.nanoTime() + "@beautica.test");
        Locality locality = resolveLocality(0);

        String salonStreet = "вул. Залишкова-" + System.nanoTime();
        String salonBuildingNo = "77-C";
        String salonNote = "C: 2-й поверх, код 5678";
        jdbcTemplate.update(
                "UPDATE salons SET name = ?, street = ?, building_no = ?, location_note = ?, "
                        + "city_id = ?, district_id = ? WHERE id = ?",
                "Booked Salon C", salonStreet, salonBuildingNo, salonNote,
                locality.cityId(), locality.districtId(), salon.salonId());

        // Distinct personal row on the master's user: once masters.salon_id is NULL, a mapper that
        // resolved the block off master.getSalon() would fall straight through to THESE values.
        String masterPersonalNote = "домашній код 8765 — не показувати";
        String masterPersonalStreet = "вул-Домашня-СЕКРЕТ-" + System.nanoTime();
        jdbcTemplate.update(
                "UPDATE users SET street = ?, building_no = ?, location_note = ? "
                        + "WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                masterPersonalStreet, "88-ДІМ", masterPersonalNote, salon.masterId());

        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        String clientEmail = "be5-gone-indep-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(8).withHour(11).withMinute(0).withSecond(0).withNano(0);
        JsonNode visit = objectMapper.readTree(
                postVisit(clientToken, salon.masterId(), startsAt, serviceA).getBody()).path("data");
        UUID appointmentId = UUID.fromString(visit.path("id").asText());

        // The master leaves the salon and goes solo AFTER the visit. masters.salon_id becomes NULL;
        // bookings.salon_id / appointments.salon_id are snapshots and keep pointing at the salon
        // the client actually booked at.
        jdbcTemplate.update(
                "UPDATE masters SET salon_id = NULL, master_type = 'INDEPENDENT_MASTER' WHERE id = ?",
                salon.masterId());
        jdbcTemplate.update(
                "UPDATE users SET salon_id = NULL WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                salon.masterId());

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        ResponseEntity<String> resp = getRaw(APPOINTMENTS_URL + "/" + appointmentId, clientToken);
        long statements = statistics.getPrepareStatementCount();

        assertThat(resp.getStatusCode())
                .as("the owning client must still read their visit — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");

        assertThat(data.path("salonName").asText(null))
                .as("correctness — the visit WAS at a salon, so its name must still be served; a "
                        + "master going solo does not retroactively turn a past salon visit into a "
                        + "home-studio one. NULL here means the address block was resolved off "
                        + "master.getSalon() (the pre-242 source) instead of the item snapshot.")
                .isEqualTo("Booked Salon C");
        assertThat(data.path("street").asText())
                .as("the booked salon's street, never the now-solo master's personal one")
                .isEqualTo(salonStreet).isNotEqualTo(masterPersonalStreet);
        assertThat(data.path("locationNote").asText())
                .as("THE security assertion — a master going independent must not hand every past "
                        + "client of theirs the door code to their HOME")
                .isEqualTo(salonNote).isNotEqualTo(masterPersonalNote);
        assertThat(resp.getBody())
                .as("nothing from the master's personal address row may appear anywhere in the "
                        + "visit response")
                .doesNotContain(masterPersonalNote)
                .doesNotContain(masterPersonalStreet);

        assertThat(statements)
                .as("this is the ONLY visit shape that can tell findByAppointmentIdWithGraph's "
                        + "LEFT JOIN FETCH b.salon apart from the m.salon it replaced. In the "
                        + "rotated fixture both graphs cost the same (one salon fetched, the other "
                        + "left a proxy — either way 1 + 1). Here m.salon is NULL, so a graph still "
                        + "fetching it materialises nothing and AppointmentDetailResponse#from's "
                        + "property reads lazy-load the booked salon — %s instead of %s.",
                        VISIT_DETAIL_STATEMENTS + 1, VISIT_DETAIL_STATEMENTS)
                .isEqualTo(VISIT_DETAIL_STATEMENTS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. Partially-declined visit — per-item status surfaced, totals exclude the
    //    declined line, header time window unchanged
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /appointments/{id} on a PARTIALLY-declined visit surfaces the per-item status "
            + "(one DECLINED, one CONFIRMED), EXCLUDES the declined line from totalPrice/duration, and "
            + "leaves the header time window spanning ALL items")
    void should_exposePerItemStatusAndExcludeDeclinedLineFromTotals_whenVisitPartiallyDeclined() throws Exception {
        String masterEmail = "be5-partial-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "be5-partial-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        String masterToken = fixtures.tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(7).withHour(15).withMinute(0).withSecond(0).withNano(0);
        JsonNode visit = objectMapper.readTree(
                postVisit(clientToken, masterId, startsAt, serviceA, serviceB).getBody()).path("data");
        UUID appointmentId = UUID.fromString(visit.path("id").asText());

        // Baseline read while fully CONFIRMED — capture the header window + full totals.
        JsonNode before = getJson(APPOINTMENTS_URL + "/" + appointmentId, clientToken).path("data");
        String windowStart = before.path("startsAt").asText();
        String windowEnd = before.path("endsAt").asText();
        assertThat(new BigDecimal(before.path("totalPrice").asText()))
                .as("baseline total = 2 × 500.00").isEqualByComparingTo("1000.00");
        assertThat(before.path("totalDurationMinutes").asInt())
                .as("baseline duration = 2 × 60").isEqualTo(120);
        // item[1] (11:00) is the one we will decline — capture its child booking id.
        UUID declinedChild = UUID.fromString(before.path("items").get(1).path("bookingId").asText());

        // Provider declines ONE service line.
        String note = "Другу послугу того дня не робимо";
        ResponseEntity<String> declineResp = patchServiceDecline(masterToken, appointmentId, declinedChild,
                "{\"providerComment\":\"" + note + "\"}");
        assertThat(declineResp.getStatusCode())
                .as("per-service decline must succeed — body: %s", declineResp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Read the partially-declined visit.
        JsonNode after = getJson(APPOINTMENTS_URL + "/" + appointmentId, clientToken).path("data");

        // Header stays CONFIRMED (a sibling survives).
        assertThat(after.path("status").asText())
                .as("the header stays CONFIRMED while one service remains").isEqualTo("CONFIRMED");

        // Per-item status is surfaced: item[0] CONFIRMED, item[1] DECLINED with its own note+reason.
        JsonNode items = after.path("items");
        assertThat(items).as("both service lines are still returned").hasSize(2);
        assertThat(items.get(0).path("status").asText())
                .as("the surviving service line stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(items.get(1).path("status").asText())
                .as("the declined service line reads DECLINED per-item").isEqualTo("DECLINED");
        assertThat(items.get(1).path("cancellationReason").asText())
                .as("the declined line carries its own PROVIDER_UNAVAILABLE reason").isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(items.get(1).path("providerComment").asText())
                .as("the per-service note rides on the declined item line (mutually visible)").isEqualTo(note);
        assertThat(items.get(0).path("providerComment").isNull())
                .as("the surviving line has no note").isTrue();

        // Totals EXCLUDE the declined line.
        assertThat(new BigDecimal(after.path("totalPrice").asText()))
                .as("the declined service is dropped from the owed total — client owes for one 500.00 service")
                .isEqualByComparingTo("500.00");
        assertThat(after.path("totalPriceMax").isNull())
                .as("no genuine range remains → totalPriceMax null").isTrue();
        assertThat(after.path("totalDurationMinutes").asInt())
                .as("the declined service's 60 min is dropped from the total duration").isEqualTo(60);

        // The header time window is UNCHANGED — it still spans all items (declined lines included).
        assertThat(after.path("startsAt").asText())
                .as("the header window start must NOT shrink when a line is declined").isEqualTo(windowStart);
        assertThat(after.path("endsAt").asText())
                .as("the header window end must still span the declined last item").isEqualTo(windowEnd);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** PATCH the per-service decline route {@code /appointments/{id}/services/{bookingId}/decline}. */
    private ResponseEntity<String> patchServiceDecline(
            String token, UUID appointmentId, UUID bookingId, String body) {
        HttpEntity<String> entity = body == null
                ? new HttpEntity<>(jsonHeaders(token)) : new HttpEntity<>(body, jsonHeaders(token));
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, entity, String.class);
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> postVisit(
            String clientToken, UUID masterId, ZonedDateTime startsAt, UUID... serviceIds) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < serviceIds.length; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append('"').append(serviceIds[i]).append('"');
        }
        String body = """
                {"masterId":"%s","masterServiceIds":[%s],"startsAt":"%s"}
                """.formatted(masterId, ids, startsAt.toOffsetDateTime());
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode())
                .as("visit creation must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return resp;
    }

    /**
     * A real seeded district + its city, plus the Ukrainian labels the
     * {@code DiscoveryLocationResolver} resolves them to. Distinct indices yield localities in
     * DIFFERENT cities, so {@code cityLabel} genuinely differs between them.
     */
    private Locality resolveLocality(int index) {
        return jdbcTemplate.queryForObject(
                "SELECT DISTINCT ON (c.id) d.id, c.id, c.name_uk, d.name_uk "
                        + "FROM city_districts d JOIN cities c ON c.id = d.city_id "
                        + "ORDER BY c.id, d.id OFFSET ? LIMIT 1",
                (rs, n) -> new Locality((UUID) rs.getObject(1), (UUID) rs.getObject(2),
                        rs.getString(3), rs.getString(4)),
                index);
    }

    /**
     * A locality whose city label AND district label both differ from {@code other} —
     * deterministic, rather than hoping two offsets land on distinct district names (they recur
     * across Ukrainian cities). See {@code BookingDetailContractIT}'s twin.
     */
    private Locality resolveLocalityWithBothLabelsDifferentFrom(Locality other) {
        return jdbcTemplate.queryForObject(
                "SELECT d.id, c.id, c.name_uk, d.name_uk "
                        + "FROM city_districts d JOIN cities c ON c.id = d.city_id "
                        + "WHERE c.name_uk <> ? AND d.name_uk <> ? "
                        + "ORDER BY c.id, d.id LIMIT 1",
                (rs, n) -> new Locality((UUID) rs.getObject(1), (UUID) rs.getObject(2),
                        rs.getString(3), rs.getString(4)),
                other.cityLabel(), other.districtLabel());
    }

    private record Locality(UUID districtId, UUID cityId, String cityLabel, String districtLabel) {
    }

    private ResponseEntity<String> getRaw(String url, String token) {
        return restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private JsonNode getJson(String url, String token) throws Exception {
        ResponseEntity<String> resp = getRaw(url, token);
        assertThat(resp.getStatusCode()).as("GET %s must succeed — body: %s", url, resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private static JsonNode findRow(JsonNode rows, UUID bookingId) {
        for (JsonNode row : rows) {
            if (row.path("id").asText().equals(bookingId.toString())) {
                return row;
            }
        }
        return null;
    }

    /** A legacy single-service CONFIRMED booking (appointment_id stays NULL) for the given client. */
    private UUID insertLegacyBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime start = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(6).withHour(14).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "booking_source, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', 'APP', ?, ?, 500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, start, start.plusMinutes(60));
        return bookingId;
    }

    /**
     * Open-ended weekly schedule with all seven ISO weekdays 08:00–20:00 so a near-future visit can be
     * booked on any day — mirrors {@link AppointmentCreateIT}'s local helper of the same name.
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
}
