package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.security.core.context.SecurityContextHolder;
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
    void seedFixtures() {
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
    // 2b — Phase 319: repeatable status + serviceId, the SERVER-side predicates
    //      that replace mobile's truncation-prone client-side narrowing
    // ══════════════════════════════════════════════════════════════════════════
    //
    // The salon «Записи» board used to fetch a day whole and filter in the client. That is sound
    // only while the day fits one page, and spring.data.web.pageable.max-page-size is 100 — so a
    // salon day with >100 bookings narrowed a TRUNCATED set and the filter silently lied. These
    // tests exercise the real predicates over real Postgres, not a mocked repository.

    @Test
    @DisplayName("a REPEATED ?status=A&status=B narrows to the union of both statuses — the "
            + "multi-select the pre-319 single ?status= could not express")
    void should_filterByMultipleStatuses_when_statusRepeated() throws Exception {
        String ownerEmail = "bsb-multistatus-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID clientId = fixtures.createUser("bsb-multistatus-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID confirmed =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 9, 1, 10, 0));
        UUID completed =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 9, 1, 12, 0));
        UUID cancelled =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 9, 1, 14, 0));
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", completed);
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", cancelled);

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null,
                List.of("CONFIRMED", "COMPLETED"), null, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root))
                .as("both requested statuses must be present and the third excluded — proving the "
                        + "predicate is an IN over the whole list, not the last value winning")
                .containsExactlyInAnyOrder(confirmed, completed);
    }

    @Test
    @DisplayName("?serviceId narrows the salon-wide list to bookings placed against that "
            + "MasterService, across masters")
    void should_filterByServiceId_when_serviceIdProvided() throws Exception {
        String ownerEmail = "bsb-serviceid-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID clientId = fixtures.createUser("bsb-serviceid-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceC = fixtures.createSalonService(salon.salonId(), secondMasterId);

        UUID onA = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(), kyiv(2031, 9, 2, 10, 0));
        insertBooking(clientId, salon.masterId(), serviceB, salon.salonId(), kyiv(2031, 9, 2, 12, 0));
        UUID onC = insertBooking(clientId, secondMasterId, serviceC, salon.salonId(), kyiv(2031, 9, 2, 14, 0));

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null,
                null, null, null, List.of(serviceA, serviceC));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root))
                .as("a salon-wide serviceId filter must span masters — this is why the option "
                        + "universe is the SALON catalogue, not one master's")
                .containsExactlyInAnyOrder(onA, onC);
    }

    @Test
    @DisplayName("status and serviceId compose as AND, not OR — a booking must satisfy both")
    void should_andStatusWithServiceId_when_bothProvided() throws Exception {
        String ownerEmail = "bsb-and-filters-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID clientId = fixtures.createUser("bsb-and-filters-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), salon.masterId());

        UUID wanted = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(), kyiv(2031, 9, 3, 10, 0));
        UUID rightServiceWrongStatus =
                insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(), kyiv(2031, 9, 3, 12, 0));
        insertBooking(clientId, salon.masterId(), serviceB, salon.salonId(), kyiv(2031, 9, 3, 14, 0));
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", rightServiceWrongStatus);

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null,
                List.of("CONFIRMED"), null, null, List.of(serviceA));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactly(wanted);
    }

    @Test
    @DisplayName("a serviceId belonging to ANOTHER salon matches nothing rather than leaking a "
            + "cross-salon existence oracle (locked Phase 26.4 convention, unchanged here)")
    void should_returnEmpty_when_serviceIdBelongsToAnotherSalon() throws Exception {
        String ownerEmail = "bsb-foreign-service-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        BookingTestFixtures.SalonFixture otherSalon =
                fixtures.createSalon("bsb-foreign-service-other-" + System.nanoTime() + "@beautica.test");
        UUID foreignService = fixtures.createSalonService(otherSalon.salonId(), otherSalon.masterId());
        UUID clientId = fixtures.createUser("bsb-foreign-service-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 9, 4, 10, 0));

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null,
                null, null, null, List.of(foreignService));

        assertThat(resp.getStatusCode())
                .as("never a 404 — that would confirm the foreign MasterService id exists")
                .isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2c — Phase 319: GET /bookings/salon/{salonId}/booked-days
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("booked-days returns the salon's DISTINCT, ascending local (Kyiv) days inside the "
            + "range — the day-rail dots the salon board could not draw before")
    void should_returnDistinctAscendingDays_when_ownerRequestsSalonBookedDays() throws Exception {
        String ownerEmail = "bsb-days-owner-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID clientId = fixtures.createUser("bsb-days-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), secondMasterId);

        // Two bookings on the same day (must collapse to ONE dot), one on a later day, one by a
        // DIFFERENT master (the salon-wide scope, not the caller's own), plus one outside the range.
        insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(), kyiv(2031, 10, 5, 10, 0));
        insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(), kyiv(2031, 10, 5, 15, 0));
        insertBooking(clientId, secondMasterId, serviceB, salon.salonId(), kyiv(2031, 10, 9, 11, 0));
        insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(), kyiv(2031, 11, 20, 11, 0));

        ResponseEntity<String> resp = callSalonBookedDays(fixtures.tokenFor(ownerEmail), salon.salonId(),
                LocalDate.of(2031, 10, 1), LocalDate.of(2031, 10, 31));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(toDays(data))
                .as("two bookings on 2031-10-05 must collapse to one dot; the other salon master's "
                        + "day must be included; the out-of-range day must not be")
                .containsExactly(LocalDate.of(2031, 10, 5), LocalDate.of(2031, 10, 9));
    }

    @Test
    @DisplayName("booked-days is FILTER-INDEPENDENT — a CANCELLED-only day is still dotted, because "
            + "an unfiltered GET /bookings/salon/{salonId} would list it (rail and list must agree)")
    void should_dotCancelledOnlyDay_when_salonBookedDaysRequested() throws Exception {
        String ownerEmail = "bsb-days-cancelled-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID clientId = fixtures.createUser("bsb-days-cancelled-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID cancelled =
                insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 10, 14, 10, 0));
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", cancelled);

        ResponseEntity<String> daysResp = callSalonBookedDays(fixtures.tokenFor(ownerEmail), salon.salonId(),
                LocalDate.of(2031, 10, 14), LocalDate.of(2031, 10, 14));
        ResponseEntity<String> listResp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null,
                null, LocalDate.of(2031, 10, 14), LocalDate.of(2031, 10, 14), null);

        assertThat(daysResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toDays(objectMapper.readTree(daysResp.getBody()).path("data")))
                .containsExactly(LocalDate.of(2031, 10, 14));
        assertThat(fixtures.extractIds(objectMapper.readTree(listResp.getBody())))
                .as("the invariant: a dot must never point at a day the UNFILTERED list renders empty")
                .containsExactly(cancelled);
    }

    @Test
    @DisplayName("booked-days — the salon's ASSIGNED ADMIN gets 200: GET /bookings/me/booked-days "
            + "hard-rejects SALON_ADMIN, which is why this route exists at all")
    void should_return200_when_assignedAdminRequestsSalonBookedDays() throws Exception {
        String ownerEmail = "bsb-days-admin-owner-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        String adminEmail = "bsb-days-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID clientId = fixtures.createUser("bsb-days-admin-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(), kyiv(2031, 10, 21, 10, 0));

        ResponseEntity<String> resp = callSalonBookedDays(fixtures.tokenFor(adminEmail), salon.salonId(),
                LocalDate.of(2031, 10, 1), LocalDate.of(2031, 10, 31));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toDays(objectMapper.readTree(resp.getBody()).path("data")))
                .containsExactly(LocalDate.of(2031, 10, 21));
    }

    @Test
    @DisplayName("booked-days — an owner of a DIFFERENT salon gets 403: the role gate alone would "
            + "hand them a competitor's activity calendar")
    void should_return403_when_foreignOwnerRequestsSalonBookedDays() throws Exception {
        BookingTestFixtures.SalonFixture targetSalon =
                fixtures.createSalon("bsb-days-foreign-target-" + System.nanoTime() + "@beautica.test");
        String strangerEmail = "bsb-days-foreign-owner-" + System.nanoTime() + "@beautica.test";
        fixtures.createSalon(strangerEmail);

        ResponseEntity<String> resp = callSalonBookedDays(fixtures.tokenFor(strangerEmail), targetSalon.salonId(),
                LocalDate.of(2031, 10, 1), LocalDate.of(2031, 10, 31));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("booked-days — an admin assigned to a DIFFERENT salon gets 403")
    void should_return403_when_foreignAdminRequestsSalonBookedDays() throws Exception {
        BookingTestFixtures.SalonFixture targetSalon =
                fixtures.createSalon("bsb-days-fadmin-target-" + System.nanoTime() + "@beautica.test");
        BookingTestFixtures.SalonFixture otherSalon =
                fixtures.createSalon("bsb-days-fadmin-other-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "bsb-days-fadmin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", otherSalon.salonId());

        ResponseEntity<String> resp = callSalonBookedDays(fixtures.tokenFor(adminEmail), targetSalon.salonId(),
                LocalDate.of(2031, 10, 1), LocalDate.of(2031, 10, 31));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("booked-days — a CLIENT gets 403 (role gate), and no bearer token gets 401")
    void should_denyClientAndAnonymous_when_requestingSalonBookedDays() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsb-days-deny-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "bsb-days-deny-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> clientResp = callSalonBookedDays(fixtures.tokenFor(clientEmail), salon.salonId(),
                LocalDate.of(2031, 10, 1), LocalDate.of(2031, 10, 31));
        URI anonUri = URI.create("http://localhost:" + port + BOOKINGS_URL + "/salon/" + salon.salonId()
                + "/booked-days?from=2031-10-01&to=2031-10-31");
        ResponseEntity<String> anonResp =
                restTemplate.exchange(anonUri, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(clientResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(anonResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("booked-days — a non-existent salonId gets 403, never 404/500: no existence oracle")
    void should_return403_when_salonBookedDaysSalonIdDoesNotExist() throws Exception {
        String ownerEmail = "bsb-days-ghost-" + System.nanoTime() + "@beautica.test";
        fixtures.createSalon(ownerEmail);

        ResponseEntity<String> resp = callSalonBookedDays(fixtures.tokenFor(ownerEmail), UUID.randomUUID(),
                LocalDate.of(2031, 10, 1), LocalDate.of(2031, 10, 31));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("booked-days — 400 when 'from' or 'to' is missing: the range is REQUIRED, because "
            + "an unbounded default would scan the salon's whole booking history")
    void should_return400_when_salonBookedDaysRangeIncomplete() throws Exception {
        String ownerEmail = "bsb-days-range-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        String token = fixtures.tokenFor(ownerEmail);
        String base = "http://localhost:" + port + BOOKINGS_URL + "/salon/" + salon.salonId() + "/booked-days";

        ResponseEntity<String> noFrom = restTemplate.exchange(URI.create(base + "?to=2031-10-31"),
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        ResponseEntity<String> noTo = restTemplate.exchange(URI.create(base + "?from=2031-10-01"),
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        ResponseEntity<String> inverted = restTemplate.exchange(
                URI.create(base + "?from=2031-10-31&to=2031-10-01"),
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(noFrom.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noTo.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inverted.getStatusCode())
                .as("'from' after 'to' must be a clean 400, never a 500 or an empty 200")
                .isEqualTo(HttpStatus.BAD_REQUEST);
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
    // 6 — providerCanReviewClient on the salon board (Phase 320: performer-only)
    // ══════════════════════════════════════════════════════════════════════════
    //
    // WHAT THIS SECTION USED TO PIN, and why it inverted. getSalonBookings resolved
    // providerCanReviewClient through the PAGE-BATCHED
    // AuthorizationService#filterBookingIdsWithProviderAuthorityForCurrentActor, whose salon arm
    // answered SALON_ADMIN through manageableSalonIds' assigned-salon leg. Section 6 proved that
    // arm GRANTED; section 6b proved it NARROWED to the master's LIVE salon.
    //
    // Phase 320 deleted the arm, the batched method and manageableSalonIds' role in this flag
    // outright. Locked product decision: "salon owner or salon admin can complete the booking, and
    // after it only salon master can leave the feedback". The flag is now
    // `master.user_id == actor && master.is_active`, resolved in memory off the fetch-joined graph,
    // so neither an owner nor an admin can read TRUE on a booking one of their STAFF performed —
    // and the live-salon-vs-snapshot question 6b existed for no longer participates in the answer
    // at all. Both tests below were rewritten onto what the endpoint now decides.
    //
    // The one shape that still reads TRUE for a caller of this endpoint is the OWNER-AS-MASTER row
    // (MasterService#createMasterForOwner → master_type = 'SALON_OWNER', user_id = the owner). That
    // is the separation section 6b now pins, and it is load-bearing rather than incidental: it is
    // the only fixture on this board that can tell "performer-only" apart from "nobody, ever".

    @Test
    @DisplayName("an assigned SALON_ADMIN listing a COMPLETED booking gets providerCanReviewClient "
            + "FALSE — they may complete the booking, but the client review belongs to the master "
            + "who performed it (phase 320); the page itself must still resolve cleanly, never 500")
    void should_returnProviderCanReviewClientFalse_when_adminListsCompletedBooking() throws Exception {
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
                .as("premise — an admin's salon board must resolve cleanly; body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        JsonNode row = root.path("data").path("data").get(0);
        assertThat(row.path("id").asText())
                .as("premise — the completed booking must actually be on the page")
                .isEqualTo(bookingId.toString());
        assertThat(row.path("providerCanReviewClient").asBoolean())
                .as("every review precondition is satisfied — COMPLETED, registered client, no "
                        + "existing review — so this false is the phase-320 authority term and "
                        + "nothing else. The controller also no longer names SALON_ADMIN in its "
                        + "hasAnyRole for POST /client-reviews, so the CTA this flag gates would "
                        + "403 if it were ever shown.")
                .isFalse();
    }

    // ── 6b — the ONE shape that still reads TRUE on this board ────────────────
    //
    // Without this test, "performer-only" and "hardcoded false on the salon board" are
    // indistinguishable here: the endpoint admits only SALON_OWNER and SALON_ADMIN, and neither is
    // normally a masters.user_id. The owner-as-master row is the exception that separates them, and
    // it is a real production shape (MasterService#createMasterForOwner). Both rows sit on the SAME
    // page, for the SAME owner, differing only in which master performed them — so a mutant that
    // collapses the flag to a constant, in either direction, fails one half or the other.

    @Test
    @DisplayName("on ONE owner's salon page, the booking the OWNER personally performed (an "
            + "owner-as-master row) carries providerCanReviewClient=TRUE while the booking their "
            + "STAFF master performed carries FALSE — the phase-320 performer term, end to end")
    void should_separateOwnerAsMasterFromStaffMaster_when_ownerListsTheSalonBoard() throws Exception {
        String suffix = "-" + System.nanoTime() + "@beautica.test";
        String ownerEmail = "bsb-320-owner" + suffix;
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID ownerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, ownerEmail);
        UUID ownerMasterId = createOwnerAsMaster(salon.salonId(), ownerUserId);
        UUID clientId = fixtures.createUser("bsb-320-client" + suffix, "CLIENT", null);

        UUID ownerPerformedId =
                insertCompletedBooking(clientId, salon, ownerMasterId, kyiv(2020, 6, 10, 10, 0));
        UUID staffPerformedId =
                insertCompletedBooking(clientId, salon, salon.masterId(), kyiv(2020, 6, 10, 12, 0));

        ResponseEntity<String> resp = callSalonBookings(fixtures.tokenFor(ownerEmail), salon.salonId(), null, null);

        assertThat(resp.getStatusCode())
                .as("premise — the owner's own salon page must resolve; body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root))
                .as("premise — BOTH completed rows must be on the page, or neither half is exercised")
                .containsExactlyInAnyOrder(ownerPerformedId, staffPerformedId);
        assertThat(providerCanReviewClient(root, ownerPerformedId))
                .as("the owner IS this booking's masters.user_id (master_type = 'SALON_OWNER'), so "
                        + "the performer term admits them. A mutant that hard-denies every "
                        + "owner/admin caller fails HERE.")
                .isTrue();
        assertThat(providerCanReviewClient(root, staffPerformedId))
                .as("the same owner, the same page, the same salon — but a booking their STAFF "
                        + "master performed. A mutant that re-adds the salon-ownership arm flips "
                        + "this to true and fails HERE. Both halves are needed.")
                .isFalse();
    }

    /**
     * An OWNER-AS-MASTER row — the shape {@code MasterService#createMasterForOwner} persists when a
     * salon owner also performs services: {@code master_type = 'SALON_OWNER'} with {@code user_id}
     * pointing at the OWNER's own {@code users} row (not a separate staff account, which is the
     * whole difference from {@link #createExtraSalonMaster}).
     */
    private UUID createOwnerAsMaster(UUID salonId, UUID ownerUserId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, NOW(), NOW())",
                masterId, ownerUserId, salonId);
        return masterId;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7 — backend-perf MEDIUM: statement-count regression gate for providerCanReviewClient
    //     authority resolution on the salon board
    // ══════════════════════════════════════════════════════════════════════════
    //
    // WHEN THIS GATE LANDED, getSalonBookings resolved providerCanReviewClient PER ROW via
    // AuthorizationService#hasProviderAuthorityOverBooking(UUID, Booking), whose
    // SALON_OWNER/SALON_ADMIN arm reads master.getSalon() — the master's LIVE salon proxy,
    // deliberately NOT the booking's own frozen salon findAllByIdsWithGraph fetch-joins. The Phase
    // 319 audit replaced that with the page-batched
    // filterBookingIdsWithProviderAuthorityForCurrentActor, whose pass one reads the owner off
    // booking.getSalon() instead. BOTH shapes are free for exactly the same reason and BOTH are
    // pinned by exactly the same numbers, which is why this gate survived the fix unchanged and why
    // its assertions are left as written: the identity-map / fetch-join coincidence below is what
    // the counts measure, not which method dereferences it. Today that costs NO extra SELECT only
    // because
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
     * <p>5 = the ID page ({@code findIdsBySalonIdFiltered}) + the graph hydrate ({@code
     * findAllByIdsWithGraph}, which fetch-joins {@code b.salon}) + the client-&gt;provider review
     * batch ({@code reviewRepository.findReviewedBookingIds}, always run) + the
     * provider-&gt;client review batch ({@code clientReviewRepository.findReviewedBookingIds}, run
     * because every seeded row is review-eligible) + the discovery-label city batch
     * ({@code TaxonomyDiscoveryLocationResolver#resolveCityLabels}, run because {@code
     * resolveBookingLabels}' {@code cityIds} set is non-empty — every seeded master shares one
     * salon, so this is ONE query for the whole page, not one per row; the sibling district batch
     * stays skipped since no fixture in this test sets a {@code districtId}). Notably NOT 6: the
     * per-row {@code hasProviderAuthorityOverBooking} call costs ZERO extra statements for THIS
     * actor (the salon OWNER) specifically because {@code AuthorizationService:716}'s in-memory
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
     *
     * <p><b>Baseline moved 4 -&gt; 5 (V150, "a salon must always have a city").</b> Every salon
     * test fixture now sets a real, non-null {@code cityId} (previously this fixture left it
     * {@code NULL}, so {@code resolveBookingLabels}' {@code cityIds} set was empty and the city
     * batch above was skipped entirely). This is the fixture becoming more representative of
     * production — post-V150 every real salon has a city, so this query is now ALWAYS exercised
     * in production too — not a new N+1: the mutation above still adds its own independent +1 on
     * top of this new baseline (identity-map miss vs. city-label resolution are unrelated code
     * paths), so the gate's discriminating power against that specific regression is unaffected.
     */
    private static final long SALON_OWNER_COMPLETED_PAGE_STATEMENTS = 5L;

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
        bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null, null, pageable);
        long statementsForOneRow = statistics.getPrepareStatementCount();

        seedCompletedBookingsAcrossDistinctMasters(clientId, salon, 4);
        statistics.clear();
        var result = bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null, null, pageable);
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
     * Statement count for a ONE-row SALON_OWNER page of COMPLETED bookings whose master has SINCE
     * ROTATED to a DIFFERENT salon, and for the same page grown to FIVE such rows.
     *
     * <p><b>Why this gate exists (QA, 2026-09-16 — the hole
     * {@link #SALON_OWNER_COMPLETED_PAGE_STATEMENTS}'s own javadoc admits at its "or a salon with
     * many masters who HAVE since rotated" clause, and never pinned).</b> That gate seeds only
     * masters still assigned to the salon they were booked at, so
     * {@code master.getSalon()} resolves against the identity-map entry {@code LEFT JOIN FETCH
     * b.salon} already loaded and the per-row authority call is free. A ROTATED master breaks both
     * halves of that coincidence at once: {@code master.getSalon()} points at a salon the fetch
     * join never loaded (so the proxy had to be hydrated), and the in-memory
     * {@code salon.getOwner().getId().equals(actorId)} short-circuit then FAILED (the rotated salon
     * has a different owner), dropping through to the NON-memoised three-arg
     * {@code hasManagementAccess} → {@code SalonRepository#existsByIdAndOwnerId}, once PER ROW.
     * Each rotated master is sent to its OWN distinct destination salon precisely so no two rows
     * can share either resolution — which is also why memoising the existing
     * {@code (salon, actor)} fact would NOT have fixed this: every row keys a different salon.
     *
     * <p><b>THE FIX LANDED — these constants were re-derived on 2026-09-16.</b> They previously
     * read {@code 7} and {@code 11}: a MARGINAL cost of exactly one statement per additional
     * rotated row, against the non-rotated gate's marginal ZERO. They now read {@code 6} and
     * {@code 6} — marginal ZERO, and one BELOW the old one-row figure. Both movements are
     * accounted for:
     * <ul>
     *   <li><b>11 → 6 at five rows</b> is the N+1 itself going away.
     *       {@code BookingService#resolveSalonPageProviderAuthority} now resolves the whole page
     *       through {@code AuthorizationService#filterBookingIdsWithProviderAuthorityForCurrentActor},
     *       which de-duplicates the page's live-salon ids and answers every one of them in a
     *       SINGLE {@code SalonRepository#findIdsByIdInAndOwnerId}, instead of one
     *       {@code existsByIdAndOwnerId} per row.</li>
     *   <li><b>7 → 6 at one row</b> is the {@code Salon} proxy hydration going away too. The
     *       batched form never calls {@code getOwner()} on a rotated master's live salon at all:
     *       it compares the master's live salon id to the booking's own — both identifier-only
     *       reads — and only reads an owner when they MATCH, off the {@code b.salon} instance the
     *       fetch join already loaded.</li>
     * </ul>
     *
     * <p>So the marginal figure is now ZERO on BOTH gates, and the rotated page costs one flat
     * statement more than the non-rotated one regardless of how many distinct foreign salons it
     * spans. Keep asserting the pair: a regression to per-row resolution would show up here as the
     * five-row figure climbing away from the one-row figure, exactly as it did before.
     * DERIVED FROM A RUN, never predicted — same rule as every sibling constant here and in
     * {@code BookingPriceRangeContractIT}.
     *
     * <p><b>PHASE 320 — RE-DERIVED AGAIN, 2026-09-16: both moved 6 &rarr; 5.</b> The locked
     * product decision ("salon owner or salon admin can complete the booking, and after it only
     * salon master can leave the feedback") reduced {@code providerCanReviewClient} to
     * {@code AuthorizationService#isPerformingMasterOfBooking}, which reads {@code masters.user_id}
     * and {@code masters.is_active} — both already materialised by {@code findAllByIdsWithGraph}'s
     * {@code JOIN FETCH b.master m} + {@code LEFT JOIN FETCH m.user}. The batched salon-ownership
     * lookup that used to cost the rotated page its one flat extra statement
     * ({@code SalonRepository#findIdsByIdInAndOwnerId}, inside the now-DELETED
     * {@code AuthorizationService#filterBookingIdsWithProviderAuthorityForCurrentActor}) is gone
     * with the salon arm it resolved. The rotated page therefore now costs exactly what the
     * non-rotated page costs: authority resolution issues NOTHING, on either shape.
     *
     * <p>The MARGINAL figure is unchanged at ZERO — the fix removed a fixed prelude statement, not
     * a per-row one — which is precisely why the pair and the per-row delta are asserted
     * separately. A rise in the delta is still the per-row N+1 coming back.
     */
    private static final long ROTATED_MASTER_ONE_ROW_STATEMENTS = 5L;

    /** Five-row counterpart of {@link #ROTATED_MASTER_ONE_ROW_STATEMENTS}. DERIVED FROM A RUN. */
    private static final long ROTATED_MASTER_FIVE_ROW_STATEMENTS = 5L;

    /**
     * The marginal JDBC cost of each additional rotated-master row, derived as
     * {@code (FIVE_ROW - ONE_ROW) / 4}. <b>Now ZERO</b>, matching the non-rotated gate — it was
     * {@code 1} before the Phase 319 audit fix, and that non-zero value WAS the finding. Asserted
     * separately from the absolute counts so that a change which moves the fixed prelude (a new
     * page-wide batch, as V150's city batch once did) is distinguishable at a glance from one that
     * regresses the PER-ROW behaviour this gate is about.
     */
    private static final long ROTATED_MASTER_PER_ROW_STATEMENTS =
            (ROTATED_MASTER_FIVE_ROW_STATEMENTS - ROTATED_MASTER_ONE_ROW_STATEMENTS) / 4;

    @Test
    @DisplayName("SALON_OWNER scope, COMPLETED page of bookings by masters who have SINCE ROTATED "
            + "to other salons — authority resolution stays FLAT at 0 extra statements per extra "
            + "rotated row, pinning the N+1 the non-rotated gate cannot cover and which the Phase "
            + "319 audit fix removed (backend-perf MEDIUM)")
    void should_pinPerRowStatementCost_when_ownerPageHasBookingsByRotatedMasters() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsb-qcount-rotated-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID clientId = fixtures.createUser(
                "bsb-qcount-rotated-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        Pageable pageable = PageRequest.of(0, 20);
        Statistics statistics = statistics();

        long statementsForOneRow;
        long statementsForFiveRows;
        com.beautica.common.PageResponse<com.beautica.booking.dto.BookingDetailResponse> result;
        // The SecurityContext used to be REQUIRED here: the batched authority filter fell through
        // to roleFromCurrentAuthentication() once the in-memory owner short-circuit failed, and
        // without a context the call 403'd instead of querying. Phase 320 deleted that filter, so
        // this read path no longer touches the thread-local context at all. The context is kept
        // deliberately — installing one cannot change the measurement, and removing it would make
        // this gate silently depend on that decoupling holding, which is a property no assertion
        // here states.
        try {
            SecurityContextHolder.getContext().setAuthentication(ownerAuthentication(ownerId));

            seedCompletedBookingsAcrossRotatedMasters(clientId, salon, 1);
            statistics.clear();
            bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null, null, pageable);
            statementsForOneRow = statistics.getPrepareStatementCount();

            seedCompletedBookingsAcrossRotatedMasters(clientId, salon, 4);
            statistics.clear();
            result = bookingService.getSalonBookings(
                    ownerId, salon.salonId(), null, null, null, null, null, pageable);
            statementsForFiveRows = statistics.getPrepareStatementCount();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(result.data())
                .as("premise — the rotation must not drop rows from the salon's own list; "
                        + "bookings.salon_id is the predicate, not the master's live salon_id")
                .hasSize(5);
        assertThat(result.data())
                .as("premise — every seeded row must be review-eligible, or the per-row authority "
                        + "call this gate measures is never invoked at all")
                .allSatisfy(b -> assertThat(b.status()).isEqualTo(BookingStatus.COMPLETED));
        assertThat(result.data())
                .as("premise — the owner reads FALSE here BY RULE since phase 320, not as an "
                        + "artefact of rotation: only the performing master may review the client, "
                        + "and these rows were performed by staff masters. (Before 320 this false "
                        + "was an unendorsed QA finding about the authority arm following the "
                        + "master's LIVE salon; that arm no longer exists.) It is a premise rather "
                        + "than the claim — what this gate measures is the statement count.")
                .allSatisfy(b -> assertThat(b.providerCanReviewClient()).isFalse());

        assertThat(List.of(statementsForOneRow, statementsForFiveRows))
                .as("absolute JDBC statement counts for a ONE-row and a FIVE-row rotated-master "
                        + "page, asserted as a pair so one failure reports the whole shape rather "
                        + "than only whichever end moved first")
                .containsExactly(ROTATED_MASTER_ONE_ROW_STATEMENTS, ROTATED_MASTER_FIVE_ROW_STATEMENTS);
        assertThat(statementsForFiveRows - statementsForOneRow)
                .as("%s extra statements per additional rotated-master row. This was THE FINDING "
                        + "at 1/row; the Phase 319 audit fix took it to 0/row, matching the "
                        + "non-rotated gate. A RISE here is the per-row N+1 coming back.",
                        ROTATED_MASTER_PER_ROW_STATEMENTS)
                .isEqualTo(4 * ROTATED_MASTER_PER_ROW_STATEMENTS);
    }

    /**
     * ADDS {@code newRowCount} more COMPLETED bookings to {@code salon}, each by a NEW, DISTINCT
     * master who was created in {@code salon}, booked there, and has SINCE ROTATED to its OWN
     * brand-new destination salon. Cumulative, exactly like
     * {@link #seedCompletedBookingsAcrossDistinctMasters}.
     *
     * <p>A separate destination salon per master is load-bearing: a shared destination would be
     * hydrated once and then served from the identity map for rows 2..N, which is the very
     * coincidence this gate exists to strip away.
     */
    private void seedCompletedBookingsAcrossRotatedMasters(
            UUID clientId, BookingTestFixtures.SalonFixture salon, int newRowCount) {
        for (int i = 0; i < newRowCount; i++) {
            UUID masterId = createExtraSalonMaster(salon.salonId());
            UUID serviceId = fixtures.createSalonService(salon.salonId(), masterId);
            UUID bookingId = insertBooking(clientId, masterId, serviceId, salon.salonId(),
                    kyiv(2020, 9, 1, 10, 0).plusMinutes(90L * i));
            jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);

            BookingTestFixtures.SalonFixture destination = fixtures.createSalon(
                    "bsb-qcount-rotated-dest-" + System.nanoTime() + "-" + i + "@beautica.test");
            jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?",
                    destination.salonId(), masterId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8 — Phase 319: the semantics the queued (salon_id, master_service_id,
    //     starts_at DESC) composite index must NOT change
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a serviceId-filtered salon list paginates over the EXACT ordered ground truth — "
            + "every page correct, ordered startsAt DESC, no duplicate and no dropped row across "
            + "page boundaries. This is the behavioural contract the queued composite index "
            + "(salon_id, master_service_id, starts_at DESC) must preserve: an index swap changes "
            + "the PLAN, and this pins that it cannot change the ANSWER.")
    void should_paginateServiceFilteredListOverExactOrderedGroundTruth() throws Exception {
        String ownerEmail = "bsb-svc-paging-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID clientId = fixtures.createUser("bsb-svc-paging-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID target = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID otherSameMaster = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID otherOtherMaster = fixtures.createSalonService(salon.salonId(), secondMasterId);

        // Five matching rows at strictly increasing times, INTERLEAVED with non-matching rows on
        // both a sibling service of the same master and a service of a different master — so an
        // index that returned rows in the wrong order, or leaked a neighbouring service's row,
        // cannot hide behind a homogeneous fixture.
        //
        // Rows are spaced 3h apart per master because insertBooking writes a fixed 60-minute
        // duration and the `no_overlapping_bookings` exclusion constraint is keyed on
        // (master_id, [starts_at, ends_at)) — same-master rows closer than an hour are rejected by
        // Postgres, not by anything this test is about.
        List<UUID> ascending = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ascending.add(insertBooking(clientId, salon.masterId(), target, salon.salonId(),
                    kyiv(2032, 3, 1, 9, 0).plusMinutes(180L * i)));
            insertBooking(clientId, salon.masterId(), otherSameMaster, salon.salonId(),
                    kyiv(2032, 3, 1, 10, 0).plusMinutes(180L * i));
            insertBooking(clientId, secondMasterId, otherOtherMaster, salon.salonId(),
                    kyiv(2032, 3, 1, 9, 30).plusMinutes(180L * i));
        }
        List<UUID> expectedDescending = new ArrayList<>(ascending);
        java.util.Collections.reverse(expectedDescending);

        String token = fixtures.tokenFor(ownerEmail);
        List<UUID> concatenated = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            ResponseEntity<String> resp = callSalonBookingsPaged(token, salon.salonId(), target, page, 2);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode root = objectMapper.readTree(resp.getBody());
            assertThat(root.path("data").path("totalElements").asLong())
                    .as("totalElements must count the FILTERED set, never the salon-wide one — "
                            + "page %s", page)
                    .isEqualTo(5L);
            assertThat(root.path("data").path("totalPages").asInt())
                    .as("5 filtered rows at size 2 = 3 pages — page %s", page)
                    .isEqualTo(3);
            concatenated.addAll(fixtures.extractIds(root));
        }

        assertThat(concatenated)
                .as("the three pages concatenated must equal the ordered ground truth EXACTLY — "
                        + "same rows, same startsAt DESC order, no duplicate across a boundary and "
                        + "no dropped row at one. A plan flip onto a different index must leave "
                        + "this identical.")
                .containsExactlyElementsOf(expectedDescending);
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

    /**
     * The Authentication shape {@code JwtAuthenticationFilter} installs: the user id lives in
     * {@code details} (what {@code AuthenticationUtils#userId} reads) and the single {@code ROLE_*}
     * authority carries the role (what {@code AuthenticationUtils#role} reads). Built here rather
     * than borrowed from a test support class so this gate states, in one place, exactly what the
     * rotated authority branch depends on.
     */
    private static org.springframework.security.core.Authentication ownerAuthentication(UUID ownerId) {
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ownerId.toString(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SALON_OWNER")));
        token.setDetails(ownerId);
        return token;
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

    /**
     * A COMPLETED booking in {@code salon} by {@code masterId}, on a service definition created for
     * that master. COMPLETED is the only status that makes {@code providerCanReviewClient}
     * meaningful, so the authority arm under test is never even consulted without it.
     */
    private UUID insertCompletedBooking(UUID clientId, BookingTestFixtures.SalonFixture salon, UUID masterId,
                                         OffsetDateTime startsAt) {
        UUID serviceId = fixtures.createSalonService(salon.salonId(), masterId);
        UUID bookingId = insertBooking(clientId, masterId, serviceId, salon.salonId(), startsAt);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        return bookingId;
    }

    /**
     * {@code providerCanReviewClient} of the row carrying {@code bookingId}. Looked up BY ID rather
     * than by page index so the assertion cannot silently follow a sort change onto the other row.
     */
    private static boolean providerCanReviewClient(JsonNode root, UUID bookingId) {
        for (JsonNode row : root.path("data").path("data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return row.path("providerCanReviewClient").asBoolean();
            }
        }
        throw new AssertionError("booking " + bookingId + " is not on the page at all");
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private ResponseEntity<String> callSalonBookings(String token, UUID salonId, UUID masterId, String status) {
        return callSalonBookings(token, salonId, masterId, status, null, null);
    }

    private ResponseEntity<String> callSalonBookings(String token, UUID salonId, UUID masterId, String status,
                                                       LocalDate from, LocalDate to) {
        return callSalonBookings(token, salonId, masterId,
                status == null ? null : List.of(status), from, to, null);
    }

    /**
     * Phase 319 overload — {@code statuses} and {@code serviceIds} are emitted as REPEATED query
     * params ({@code ?status=A&status=B}), never as one comma-joined value, because that repeated
     * form is exactly what Spring must bind to the widened {@code List} parameters. A comma-joined
     * spelling would exercise a different binder path and would not prove the wire contract mobile
     * actually sends.
     */
    private ResponseEntity<String> callSalonBookings(String token, UUID salonId, UUID masterId,
                                                       List<String> statuses, LocalDate from, LocalDate to,
                                                       List<UUID> serviceIds) {
        List<String> parts = new ArrayList<>();
        if (masterId != null) {
            parts.add("masterId=" + masterId);
        }
        if (statuses != null) {
            statuses.forEach(st -> parts.add("status=" + st));
        }
        if (from != null) {
            parts.add("from=" + from);
        }
        if (to != null) {
            parts.add("to=" + to);
        }
        if (serviceIds != null) {
            serviceIds.forEach(id -> parts.add("serviceId=" + id));
        }
        String pathAndQuery = BOOKINGS_URL + "/salon/" + salonId + (parts.isEmpty() ? "" : "?" + String.join("&", parts));
        URI uri = URI.create("http://localhost:" + port + pathAndQuery);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    /**
     * Phase 319 — a serviceId-filtered salon list at an explicit {@code page}/{@code size}. Sort is
     * deliberately NOT passed: the point is to exercise the endpoint's own
     * {@code @PageableDefault(sort = "startsAt", direction = DESC)}, which is the order the queued
     * composite index is shaped to serve and therefore the order that must not change under it.
     */
    private ResponseEntity<String> callSalonBookingsPaged(String token, UUID salonId, UUID serviceId,
                                                            int page, int size) {
        URI uri = URI.create("http://localhost:" + port + BOOKINGS_URL + "/salon/" + salonId
                + "?serviceId=" + serviceId + "&page=" + page + "&size=" + size);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> callSalonBookedDays(String token, UUID salonId, LocalDate from, LocalDate to) {
        URI uri = URI.create("http://localhost:" + port + BOOKINGS_URL + "/salon/" + salonId
                + "/booked-days?from=" + from + "&to=" + to);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private static List<LocalDate> toDays(JsonNode dataArray) {
        List<LocalDate> days = new ArrayList<>();
        dataArray.forEach(node -> days.add(LocalDate.parse(node.asText())));
        return days;
    }
}
