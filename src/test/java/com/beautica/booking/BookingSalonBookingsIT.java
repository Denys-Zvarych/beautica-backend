package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored suite for Phase 23.4 (backend-qa, 2026-08-27): {@code GET
 * /bookings/salon/{salonId}}, exercised over the FULL HTTP stack — real Spring Security, a real
 * {@link com.beautica.booking.service.BookingService}, and a real Postgres Testcontainers
 * instance.
 *
 * <p><b>Why this suite exists.</b> {@code BookingServiceTest} proves the service's own filter
 * wiring against a mocked repository; nothing there exercises the real {@code @PreAuthorize}
 * SpEL gate (role AND {@code @authz.canManageSalon}) or the real
 * {@code BookingSpecifications#bookingSalonIdEquals} predicate against Postgres. Covers:
 * <ol>
 *   <li><b>Authorization</b> — the salon's own OWNER and its assigned ADMIN both get 200; an
 *       owner of a DIFFERENT salon, an admin assigned to a DIFFERENT salon, and a CLIENT all get
 *       403.</li>
 *   <li><b>masterId filter</b> — narrows the salon-wide list to one master's bookings.</li>
 *   <li><b>status filter</b> — narrows to a single status.</li>
 *   <li><b>Scope is {@code booking.salon_id}, not the master's LIVE {@code salon_id}</b> — a
 *       booking made while the master belonged to salon A must still appear in salon A's list
 *       after the master is reassigned to salon B, proving {@code
 *       BookingSpecifications#bookingSalonIdEquals} (the booking's own snapshot column) is the
 *       predicate actually wired, not {@code #salonIdIn}'s master-join shape.</li>
 * </ol>
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/salon/{salonId} — Phase 23.4, full HTTP stack over real Postgres")
class BookingSalonBookingsIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";

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

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1 — authorization: owner/admin OF THIS salon only
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the salon's own OWNER gets 200 and sees the salon's booking")
    void should_return200_when_salonOwnerListsOwnSalon() throws Exception {
        String ownerEmail = "bsb-owner-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID clientId = fixtures.createUser("bsb-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID bookingId = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 7, 10, 12, 0));

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactly(bookingId);
    }

    @Test
    @DisplayName("the salon's own ASSIGNED ADMIN gets 200 — this is the path GET /bookings/me "
            + "hard-rejects for SALON_ADMIN, and the whole reason this endpoint exists")
    void should_return200_when_salonAdminListsOwnSalon() throws Exception {
        String ownerEmail = "bsb-admin-owner-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        String adminEmail = "bsb-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID clientId = fixtures.createUser("bsb-admin-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID bookingId = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 7, 11, 12, 0));

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(adminEmail), salon.salonId(), null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactly(bookingId);
    }

    @Test
    @DisplayName("an owner of a DIFFERENT salon gets 403 — hasAnyRole('SALON_OWNER','SALON_ADMIN') "
            + "alone would wrongly admit this; only @authz.canManageSalon(#salonId) catches it")
    void should_return403_when_ownerOfDifferentSalon() throws Exception {
        BookingTestFixtures.SalonFixture targetSalon =
                fixtures.createSalon("bsb-foreign-target-" + System.nanoTime() + "@beautica.test");
        String strangerEmail = "bsb-foreign-owner-" + System.nanoTime() + "@beautica.test";
        fixtures.createSalon(strangerEmail);

        ResponseEntity<String> resp =
                callSalonBookings(fixtures.tokenFor(strangerEmail), targetSalon.salonId(), null, null);

        assertThat(resp.getStatusCode())
                .as("an owner must never see another salon's bookings just by role")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an admin assigned to a DIFFERENT salon gets 403")
    void should_return403_when_adminOfDifferentSalon() throws Exception {
        BookingTestFixtures.SalonFixture targetSalon =
                fixtures.createSalon("bsb-foreign-admin-target-" + System.nanoTime() + "@beautica.test");
        BookingTestFixtures.SalonFixture otherSalon =
                fixtures.createSalon("bsb-foreign-admin-other-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "bsb-foreign-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", otherSalon.salonId());

        ResponseEntity<String> resp =
                callSalonBookings(fixtures.tokenFor(adminEmail), targetSalon.salonId(), null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a CLIENT gets 403 — the role gate alone rejects it before @authz.canManageSalon runs")
    void should_return403_when_callerIsClient() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsb-client-target-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "bsb-client-caller-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(clientEmail), salon.salonId(), null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 — masterId / status filters
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("masterId filter narrows the salon-wide list to one master's bookings")
    void should_filterByMasterId_when_masterIdProvided() throws Exception {
        String ownerEmail = "bsb-master-filter-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID clientId = fixtures.createUser("bsb-master-filter-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID service1 = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID service2 = fixtures.createSalonService(salon.salonId(), secondMasterId);

        UUID firstMasterBooking =
                insertBooking(clientId, salon.masterId(), service1, salon.salonId(), kyiv(2031, 7, 12, 10, 0));
        insertBooking(clientId, secondMasterId, service2, salon.salonId(), kyiv(2031, 7, 12, 11, 0));

        ResponseEntity<String> resp =
                callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), salon.masterId(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactly(firstMasterBooking);
    }

    @Test
    @DisplayName("a masterId belonging to a DIFFERENT salon matches nothing rather than leaking a "
            + "cross-salon existence oracle (locked convention — see GET /bookings/me's serviceId "
            + "filter contract)")
    void should_returnEmpty_when_masterIdBelongsToAnotherSalon() throws Exception {
        String ownerEmail = "bsb-foreign-master-owner-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        BookingTestFixtures.SalonFixture otherSalon =
                fixtures.createSalon("bsb-foreign-master-other-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsb-foreign-master-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 7, 13, 10, 0));

        ResponseEntity<String> resp = callSalonBookings(
                fixtures.tokenFor(ownerEmail), salon.salonId(), otherSalon.masterId(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).isEmpty();
    }

    @Test
    @DisplayName("status filter narrows the salon-wide list to a single status")
    void should_filterByStatus_when_statusProvided() throws Exception {
        String ownerEmail = "bsb-status-filter-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID clientId = fixtures.createUser("bsb-status-filter-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID confirmedBooking =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 7, 14, 10, 0));
        UUID cancelledBooking =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 7, 14, 12, 0));
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", cancelledBooking);

        ResponseEntity<String> resp = callSalonBookings(
                fixtures.tokenFor(ownerEmail), salon.salonId(), null, "CONFIRMED");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactly(confirmedBooking);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3 — scope is booking.salon_id, not the master's LIVE salon_id
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a booking made while its master belonged to salon A still appears in salon A's "
            + "list after the master is reassigned to salon B — proves bookingSalonIdEquals "
            + "(the booking's OWN salon snapshot) is the wired predicate, never a master-join scope")
    void should_stillReturnBooking_when_masterHasSinceRotatedToAnotherSalon() throws Exception {
        String ownerAEmail = "bsb-rotate-owner-a-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salonA = fixtures.createSalon(ownerAEmail);
        BookingTestFixtures.SalonFixture salonB =
                fixtures.createSalon("bsb-rotate-owner-b-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsb-rotate-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salonA.salonId(), salonA.masterId());
        UUID bookingId =
                insertBooking(clientId, salonA.masterId(), serviceId, salonA.salonId(), kyiv(2031, 7, 15, 10, 0));

        // The master rotates to salon B — masters.salon_id changes, but bookings.salon_id (the
        // booking's own snapshot column) is untouched.
        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", salonB.salonId(), salonA.masterId());

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerAEmail), salonA.salonId(), null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root))
                .as("salon A's historical booking list must not silently lose a booking just "
                        + "because its master has since moved to another salon")
                .containsExactly(bookingId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4 — from/to date-range filter, including the 'to' day-boundary
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("from/to narrows the salon list to the requested window, and 'to' is INCLUSIVE of "
            + "the whole final local day — a booking at 22:00 Kyiv on 'to' must still appear, one "
            + "the day before and one the day after must not")
    void should_filterByDateRange_when_fromAndToProvided() throws Exception {
        String ownerEmail = "bsb-daterange-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID clientId = fixtures.createUser("bsb-daterange-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());

        insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 8, 9, 23, 0));
        UUID lateOnLastDay =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 8, 12, 22, 0));
        insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 8, 13, 0, 30));

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null, null,
                LocalDate.of(2031, 8, 10), LocalDate.of(2031, 8, 12));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root))
                .as("'to' must include the WHOLE final calendar day (22:00 Kyiv still counts), and "
                        + "the day before 'from' / the day after 'to' must both be excluded")
                .containsExactly(lateOnLastDay);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5 — coverage gaps: empty salon, ghost salonId, unauthenticated
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a salon with zero bookings returns 200 and an EMPTY page, not an error")
    void should_returnEmptyPage_when_salonHasNoBookings() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsb-empty-salon-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(salon.ownerEmail()), salon.salonId(), null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).isEmpty();
        assertThat(root.path("data").path("totalElements").asLong())
                .as("an empty salon must report zero total elements, not merely an empty content array")
                .isZero();
    }

    @Test
    @DisplayName("a non-existent salonId gets 403, never 404/500 — canManageSalon denies a random "
            + "UUID with no existence oracle, same as any other salon the caller does not manage")
    void should_return403_when_salonIdDoesNotExist() throws Exception {
        String ownerEmail = "bsb-ghost-salon-" + System.nanoTime() + "@beautica.test";
        fixtures.createSalon(ownerEmail);
        UUID ghostSalonId = UUID.randomUUID();

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), ghostSalonId, null, null);

        assertThat(resp.getStatusCode())
                .as("a salon id that matches no row must be indistinguishable from a real salon the "
                        + "caller does not manage — never a 404/500 that would leak existence")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("no bearer token gets 401 before @authz.canManageSalon ever runs")
    void should_return401_when_noToken() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsb-noauth-" + System.nanoTime() + "@beautica.test");
        URI uri = URI.create("http://localhost:" + port + BOOKINGS_URL + "/salon/" + salon.salonId());

        ResponseEntity<String> resp = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6 — providerCanReviewClient wiring for SALON_ADMIN over a COMPLETED booking
    // ══════════════════════════════════════════════════════════════════════════
    //
    // BookingService#getSalonBookings' own javadoc explains WHY this endpoint computes
    // providerCanReviewClient per-row via the entity-based hasProviderAuthorityOverBooking(UUID,
    // Booking) overload rather than loadProviderReviewBatch's page-batched
    // filterBookingIdsWithProviderAuthority kernel: that batched kernel explicitly THROWS
    // IllegalArgumentException for SALON_ADMIN. Nothing anywhere else in this suite (or in
    // BookingServiceTest, whose mocked `authz` never runs real hasManagementAccess SQL) proves an
    // assigned SALON_ADMIN can actually list a COMPLETED, review-eligible booking without a 500 —
    // this test exists to close exactly that gap.

    @Test
    @DisplayName("an assigned SALON_ADMIN listing a COMPLETED booking gets providerCanReviewClient "
            + "computed correctly (true) — proves the per-row entity-based authority check resolves "
            + "ADMIN authority via canManageSalon rather than throwing the way the batched "
            + "loadProviderReviewBatch kernel would for this role")
    void should_computeProviderCanReviewClient_when_adminListsCompletedBooking() throws Exception {
        String ownerEmail = "bsb-admin-review-owner-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        String adminEmail = "bsb-admin-review-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID clientId = fixtures.createUser("bsb-admin-review-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID bookingId =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2020, 7, 20, 12, 0));
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(adminEmail), salon.salonId(), null, null);

        assertThat(resp.getStatusCode())
                .as("premise — must resolve cleanly, never a 500 from the batched-kernel's "
                        + "IllegalArgumentException; body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        JsonNode row = root.path("data").path("data").get(0);
        assertThat(row.path("id").asText())
                .as("premise — the completed booking must actually be on the page")
                .isEqualTo(bookingId.toString());
        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("an assigned SALON_ADMIN over a COMPLETED booking with a registered client and no "
                        + "existing provider review must be authorized to leave one")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7 — backend-perf MEDIUM: statement-count regression gate for the per-row
    //     hasProviderAuthorityOverBooking(UUID, Booking) call
    // ══════════════════════════════════════════════════════════════════════════
    //
    // BookingService#getSalonBookings' javadoc documents that providerCanReviewClient authority is
    // computed PER ROW via AuthorizationService#hasProviderAuthorityOverBooking(UUID, Booking) —
    // unlike GET /bookings/me's listProviderBookings, which resolves authority upstream on the
    // batched ID page and never walks master.getSalon() at all (see
    // BookingPriceRangeContractIT#SALON_MASTER_PAGE_STATEMENTS' javadoc for that contrast). The
    // entity overload's SALON_OWNER/SALON_ADMIN arm (AuthorizationService:707-721) reads
    // master.getSalon() — the master's LIVE salon proxy, deliberately NOT the booking's own frozen
    // salon findAllByIdsWithGraph fetch-joins. Today that costs NO extra SELECT only because
    // Hibernate's persistence-context identity map resolves master.getSalon()'s proxy against the
    // SAME (Salon, id) key the `LEFT JOIN FETCH b.salon` in findAllByIdsWithGraph already loaded —
    // true whenever a master has not rotated away from the salon its booking was made at. That
    // coincidence was completely unpinned before this gate (backend-perf MEDIUM, Phase 23.4 QA
    // audit, 2026-08-27): a future fetch-graph change (dropping `LEFT JOIN FETCH b.salon`, or a
    // salon with many masters who HAVE since rotated to distinct other salons) would silently
    // reintroduce a real per-row SELECT with nothing here going red.
    //
    // Follows BookingPriceRangeContractIT's SALON_MASTER_PAGE_STATEMENTS mechanism verbatim
    // (Hibernate Statistics#getPrepareStatementCount over the SAME EntityManagerFactory, measured
    // at TWO row counts so a per-row regression cannot hide behind a single number) rather than
    // inventing a parallel one — see that constant's javadoc for the underlying technique.

    /**
     * Statement count for a 5-row SALON_OWNER page of COMPLETED (review-eligible) bookings, each
     * booked by a DIFFERENT master — {@link #createExtraSalonMaster} four more times — all still
     * assigned to the SAME salon they were booked at (the common, non-rotated case), each booking
     * on its OWN service definition so a lost masterService/serviceDefinition fetch join cannot
     * hide behind the L1 cache either (mirrors {@code BookingPriceRangeContractIT}'s sibling
     * gates). Multiple DISTINCT masters, not one repeated master, is deliberate: with a single
     * master the SAME already-managed {@code Master} Java object would serve every row regardless
     * of whether {@code b.salon} is fetch-joined, making the gate blind to the fetch-join actually
     * doing anything; five independent masters force five independent
     * {@code master.getSalon()} proxy resolutions, each of which must hit the SAME identity-map
     * entry to stay free.
     *
     * <p>4 = the ID page ({@code findIdsBySalonIdFiltered}) + the graph hydrate ({@code
     * findAllByIdsWithGraph}, which fetch-joins {@code b.salon}) + the client-&gt;provider review
     * batch ({@code reviewRepository.findReviewedBookingIds}, always run) + the
     * provider-&gt;client review batch ({@code clientReviewRepository.findReviewedBookingIds}, run
     * because every seeded row is review-eligible). Notably NOT 5: the per-row {@code
     * hasProviderAuthorityOverBooking} call costs ZERO extra statements for THIS actor (the salon
     * OWNER) specifically because {@code AuthorizationService:716}'s in-memory
     * {@code salon.getOwner().getId().equals(actorId)} short-circuits before {@code
     * hasManagementAccess} — but reaching that free comparison at all still requires {@code
     * salon.getOwner()} (a non-identifier property read on the {@code Salon} the {@code
     * master.getSalon()} proxy resolves to) to be answerable without a query, which is exactly the
     * identity-map coincidence this gate exists to pin: {@code master.getSalon()}'s target row is
     * the SAME managed {@code Salon} entity {@code LEFT JOIN FETCH b.salon} already loaded. DERIVED
     * FROM A RUN, never predicted — same rule as every sibling constant in {@code
     * BookingPriceRangeContractIT}.
     *
     * <p><b>Mutation-verified (QA, 2026-08-27).</b> Temporarily deleting {@code LEFT JOIN FETCH
     * b.salon} from {@code BookingRepository#findAllByIdsWithGraph} moves this gate's 5-row count
     * 4 -&gt; <b>5</b> — {@code master.getSalon()} can no longer resolve to the already-managed
     * {@code Salon} entity and must issue its own {@code SELECT} to read {@code salon.getOwner()}.
     * Confirmed RED, then the fetch join was restored and the gate re-confirmed GREEN at 4. This is
     * exactly the regression backend-perf's MEDIUM finding describes as currently uncaught.
     */
    private static final long SALON_OWNER_COMPLETED_PAGE_STATEMENTS = 4L;

    @Test
    @DisplayName("SALON_OWNER scope, COMPLETED (review-eligible) page — the per-row "
            + "hasProviderAuthorityOverBooking(UUID, Booking) call costs a FIXED, absolute number "
            + "of JDBC statements across FIVE different masters at the owner's own salon, pinning "
            + "the identity-map coincidence that keeps it N+1-free today (backend-perf MEDIUM)")
    void should_notScaleStatementCount_when_ownerPageHasManyCompletedBookingsAcrossMasters() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsb-qcount-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID clientId = fixtures.createUser(
                "bsb-qcount-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        Pageable pageable = PageRequest.of(0, 20);
        Statistics statistics = statistics();

        seedCompletedBookingsAcrossDistinctMasters(clientId, salon, 1);
        statistics.clear();
        bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null, pageable);
        long statementsForOneRow = statistics.getPrepareStatementCount();

        seedCompletedBookingsAcrossDistinctMasters(clientId, salon, 4);
        statistics.clear();
        var result = bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null, pageable);
        long statementsForFiveRows = statistics.getPrepareStatementCount();

        assertThat(result.data()).hasSize(5);
        assertThat(result.data())
                .as("premise — every seeded row must actually be review-eligible, or the per-row "
                        + "authz call this gate exists to pin is never invoked at all")
                .allSatisfy(b -> assertThat(b.status()).isEqualTo(BookingStatus.COMPLETED));
        assertThat(statementsForFiveRows)
                .as("absolute JDBC statement count for a 5-row page across FIVE different masters, "
                        + "all still assigned to the salon they were booked at. A rise means "
                        + "master.getSalon() is no longer being served off the identity-map entry "
                        + "LEFT JOIN FETCH b.salon already loaded — see this gate's javadoc.")
                .isEqualTo(SALON_OWNER_COMPLETED_PAGE_STATEMENTS);
        assertThat(statementsForFiveRows)
                .as("secondary signal — statement count must not scale with row count; got %s for "
                        + "1 row (1 master), %s for 5 rows (5 masters)", statementsForOneRow, statementsForFiveRows)
                .isEqualTo(statementsForOneRow);
    }

    /**
     * ADDS {@code newRowCount} more COMPLETED bookings to {@code salon} on top of whatever this
     * method has already seeded for it, each by a NEW, DISTINCT master ({@link
     * #createExtraSalonMaster}) still assigned to {@code salon} (the non-rotated case), each on
     * its own service definition. Cumulative by design — mirrors {@code
     * BookingPriceRangeContractIT#seedSalonBookingsOnDistinctServices}'s "call once for 1, call
     * again for 4 more -&gt; 5 total" convention exactly, so two calls (1, then 4) produce a 5-row
     * page, not two independent 1-row and 4-row pages.
     */
    private void seedCompletedBookingsAcrossDistinctMasters(
            UUID clientId, BookingTestFixtures.SalonFixture salon, int newRowCount) {
        for (int i = 0; i < newRowCount; i++) {
            UUID masterId = createExtraSalonMaster(salon.salonId());
            UUID serviceId = fixtures.createSalonService(salon.salonId(), masterId);
            UUID bookingId = insertBooking(clientId, masterId, serviceId, salon.salonId(),
                    kyiv(2020, 8, 1, 10, 0).plusMinutes(90L * i));
            jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        }
    }

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static OffsetDateTime kyiv(int year, int month, int day, int hour, int minute) {
        return java.time.LocalDate.of(year, month, day).atTime(hour, minute).atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    /** Second SALON_MASTER in the given salon, for the masterId-filter tests. */
    private UUID createExtraSalonMaster(UUID salonId) {
        String masterEmail = "bsb-extra-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = fixtures.createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return masterId;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId,
                startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private ResponseEntity<String> callSalonBookings(String token, UUID salonId, UUID masterId, String status) {
        return callSalonBookings(token, salonId, masterId, status, null, null);
    }

    private ResponseEntity<String> callSalonBookings(String token, UUID salonId, UUID masterId, String status,
                                                       LocalDate from, LocalDate to) {
        List<String> parts = new ArrayList<>();
        if (masterId != null) {
            parts.add("masterId=" + masterId);
        }
        if (status != null) {
            parts.add("status=" + status);
        }
        if (from != null) {
            parts.add("from=" + from);
        }
        if (to != null) {
            parts.add("to=" + to);
        }
        String pathAndQuery = BOOKINGS_URL + "/salon/" + salonId + (parts.isEmpty() ? "" : "?" + String.join("&", parts));
        URI uri = URI.create("http://localhost:" + port + pathAndQuery);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }
}
