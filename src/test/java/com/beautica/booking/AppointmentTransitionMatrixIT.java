package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack QA authority matrix for the per-service visit decline
 * ({@code PATCH /appointments/{id}/services/{bookingId}/decline}): allowed actors (independent master,
 * salon owner, assigned salon admin), denied actors (read-only salon master, client, foreign master,
 * foreign salon owner), and the no-existence-oracle 403 for a missing visit.
 *
 * <p><b>Phase 475.</b> This suite used to be the QA matrix for the whole-visit
 * {@code cancel}/{@code decline}/{@code complete}/{@code not-complete} routes (lockstep, auth, illegal
 * transitions, elapsed guard). Those routes were removed, so those sections were deleted; the per-item
 * equivalents live in {@code AppointmentItemCancelIT}, {@code AppointmentItemCompleteIT} and
 * {@link AppointmentTransitionIT}.
 *
 * <p><b>Visits are seeded directly via SQL</b> (header + N chained CONFIRMED items) rather than through
 * {@code POST /appointments}: deterministic, no working-hours/slot plumbing.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{id}/services/{bookingId}/decline — QA authority matrix")
class AppointmentTransitionMatrixIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Point 3 — authorization: no existence oracle on the per-service decline
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Authorization matrix")
    class Authorization {

        @Test
        @DisplayName("decline-service — a valid provider declining a service of a NONEXISTENT appointment "
                + "id must be denied 403, NOT 404 — the missing-visit case collapses to 403 BEFORE the "
                + "bookingId-not-a-child 404, so no existence oracle")
        void should_return403_not404_when_providerDeclinesServiceOfNonexistentVisit() throws Exception {
            BookingTestFixtures.SalonFixture salon =
                    fixtures.createSalon("appt-mx-psd-oracle-owner-" + System.nanoTime() + "@beautica.test");
            UUID nonexistentAppointmentId = UUID.randomUUID();
            UUID anyBookingId = UUID.randomUUID();

            ResponseEntity<String> resp = patchServiceDecline(fixtures.tokenFor(salon.ownerEmail()),
                    nonexistentAppointmentId, anyBookingId, "{\"providerComment\":\"немає такого візиту\"}");

            assertThat(resp.getStatusCode())
                    .as("a nonexistent appointment id must be indistinguishable from a foreign one — both "
                            + "403; the missing-visit case must collapse to 403 BEFORE the child-not-found 404")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Per-service (per-child) decline authority matrix —
    // PATCH /appointments/{id}/services/{bookingId}/decline
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Per-service decline — authority matrix")
    class PerServiceDeclineAuthorization {

        @Test
        @DisplayName("allow — the INDEPENDENT_MASTER who owns the visit declines one child; that child → "
                + "DECLINED, the sibling stays CONFIRMED, the header stays CONFIRMED, one STATUS_CHANGED")
        void should_declineChild_when_owningIndependentMaster() throws Exception {
            IndependentVisit v = seedIndependentVisit("psd-im-owner", 2, futureStart());
            UUID child0 = firstChildOf(v.id());
            UUID child1 = secondChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(v.masterToken(), v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("the visit's own independent master may decline one of its services — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(child0)).isEqualTo("DECLINED");
            assertThat(childStatus(child1)).as("the sibling stays CONFIRMED").isEqualTo("CONFIRMED");
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("allow — the SALON_OWNER of the visit's master declines one child of a salon visit; "
                + "that child → DECLINED, the sibling stays CONFIRMED, the header stays CONFIRMED")
        void should_declineChild_when_salonOwnerOfTheMaster() throws Exception {
            SalonVisit v = seedSalonVisit("psd-owner", 2, futureStart());
            UUID child0 = firstChildOf(v.id());
            UUID child1 = secondChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(v.ownerToken(), v.id(), child0,
                    "{\"providerComment\":\"Цю послугу того дня не робимо\"}");

            assertThat(resp.getStatusCode())
                    .as("the salon owner may decline one service of their salon-master's visit — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(child0)).isEqualTo("DECLINED");
            assertThat(childStatus(child1)).isEqualTo("CONFIRMED");
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("allow — a SALON_ADMIN assigned to the visit's salon declines one child; that child "
                + "→ DECLINED, the sibling stays CONFIRMED (SALON_ADMIN shares the provider authority)")
        void should_declineChild_when_salonAdminAssignedToTheSalon() throws Exception {
            SalonVisit v = seedSalonVisit("psd-admin", 2, futureStart());
            String adminEmail = "appt-mx-psd-admin-" + System.nanoTime() + "@beautica.test";
            fixtures.createUser(adminEmail, "SALON_ADMIN", v.salonId()); // assigned to THIS salon
            String adminToken = fixtures.tokenFor(adminEmail);
            UUID child0 = firstChildOf(v.id());
            UUID child1 = secondChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(adminToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("a salon admin assigned to the salon may decline one service — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(childStatus(child0)).isEqualTo("DECLINED");
            assertThat(childStatus(child1)).isEqualTo("CONFIRMED");
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(1L);
        }

        @Test
        @DisplayName("deny 403 — the read-only SALON_MASTER declining a service of their OWN visit is "
                + "rejected by the role gate; both children stay CONFIRMED, no notification")
        void should_return403_when_salonMasterDeclinesChild() throws Exception {
            SalonVisit v = seedSalonVisit("psd-master-ro", 2, futureStart());
            String salonMasterToken = fixtures.tokenFor(v.masterEmail());
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(salonMasterToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("SALON_MASTER is read-only — excluded from the provider role gate → 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("deny 403 — a CLIENT (even the visit's OWN client) hitting the provider-only "
                + "per-service decline endpoint is rejected by the role gate; nothing changes")
        void should_return403_when_clientDeclinesChild() throws Exception {
            IndependentVisit v = seedIndependentVisit("psd-client", 2, futureStart());
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(v.clientToken(), v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("per-service decline is provider-only — a CLIENT must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertVisitStatus(v.id(), "CONFIRMED");
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("deny 403 — a FOREIGN independent master (no authority over the visit's master) "
                + "declining a child is rejected by the ownership guard; nothing changes")
        void should_return403_when_foreignIndependentMasterDeclinesChild() throws Exception {
            IndependentVisit v = seedIndependentVisit("psd-foreign-im", 2, futureStart());
            String foreignEmail = "appt-mx-psd-foreign-im-" + System.nanoTime() + "@beautica.test";
            fixtures.createIndependentMaster(foreignEmail);
            String foreignToken = fixtures.tokenFor(foreignEmail);
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(foreignToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("a provider with no authority over the visit's master must be forbidden")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }

        @Test
        @DisplayName("deny 403 — a FOREIGN salon owner (owns a different salon) declining a child of "
                + "this salon's visit is rejected by the ownership guard; nothing changes")
        void should_return403_when_foreignSalonOwnerDeclinesChild() throws Exception {
            SalonVisit v = seedSalonVisit("psd-foreign-owner", 2, futureStart());
            BookingTestFixtures.SalonFixture other =
                    fixtures.createSalon("appt-mx-psd-foreign-owner-" + System.nanoTime() + "@beautica.test");
            String foreignOwnerToken = fixtures.tokenFor(other.ownerEmail());
            UUID child0 = firstChildOf(v.id());

            ResponseEntity<String> resp = patchServiceDecline(foreignOwnerToken, v.id(), child0, "{}");

            assertThat(resp.getStatusCode())
                    .as("a salon owner with no authority over the visit's salon must get 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertAllItemsStatus(v.id(), "CONFIRMED", 2);
            assertThat(statusChangedCount(v.id())).isEqualTo(0L);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /** A seeded independent-master CONFIRMED visit plus the owning client + master tokens. */
    private record IndependentVisit(UUID id, String clientToken, String masterToken) {}

    /** A seeded salon-master CONFIRMED visit plus the salon owner + salon-master identities. */
    private record SalonVisit(UUID id, UUID salonId, String ownerToken, String masterEmail) {}

    /**
     * Seeds a CONFIRMED independent-master visit directly: one appointment header (salon_id NULL) plus
     * {@code itemCount} contiguous one-hour CONFIRMED bookings, each a distinct master-service, chained
     * from {@code firstStart}. Returns the header id and both actors' tokens.
     */
    private IndependentVisit seedIndependentVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        String masterEmail = "appt-mx-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-mx-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createIndependentMasterService(masterId);
        }
        UUID appointmentId = seedVisitRows(clientId, masterId, null, serviceIds, firstStart);
        return new IndependentVisit(appointmentId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

    /**
     * Seeds a CONFIRMED salon-master visit: a full salon graph (owner + salon + SALON_MASTER master),
     * an appointment header with salon_id set, and {@code itemCount} contiguous CONFIRMED salon-service
     * items — each also carrying salon_id (mirrors AppointmentService, which stamps master.getSalon()
     * onto both header and items).
     */
    private SalonVisit seedSalonVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("appt-mx-" + tag + "-owner-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "appt-mx-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createSalonService(salon.salonId(), salon.masterId());
        }
        UUID appointmentId = seedVisitRows(clientId, salon.masterId(), salon.salonId(), serviceIds, firstStart);
        return new SalonVisit(appointmentId, salon.salonId(),
                fixtures.tokenFor(salon.ownerEmail()), salon.masterEmail());
    }

    /**
     * Inserts the {@code appointments} header (APP, CONFIRMED) and one CONFIRMED {@code bookings} row
     * per service, chained back-to-back in one-hour blocks from {@code firstStart}. {@code salonId}
     * (nullable) is stamped on the header and every item. Contiguous, non-overlapping windows satisfy
     * the {@code no_overlapping_bookings} EXCLUDE constraint.
     */
    private UUID seedVisitRows(UUID clientId, UUID masterId, UUID salonId, UUID[] serviceIds, OffsetDateTime firstStart) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId, salonId);
        OffsetDateTime cursor = firstStart;
        for (UUID serviceId : serviceIds) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, appointment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                    UUID.randomUUID(), clientId, masterId, serviceId, salonId,
                    cursor, cursor.plusHours(1), appointmentId);
            cursor = cursor.plusHours(1);
        }
        return appointmentId;
    }

    /** A clearly-future anchor (2 days out, 10:00 UTC) so no visit is accidentally elapsed. */
    private static OffsetDateTime futureStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    /** PATCH the per-service decline route {@code /appointments/{id}/services/{bookingId}/decline}. */
    private ResponseEntity<String> patchServiceDecline(String token, UUID appointmentId, UUID bookingId, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, entity, String.class);
    }

    private UUID firstChildOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1",
                UUID.class, appointmentId);
    }

    private UUID secondChildOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at OFFSET 1 LIMIT 1",
                UUID.class, appointmentId);
    }

    private String childStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private void assertVisitStatus(UUID appointmentId, String expected) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
        assertThat(status).as("appointment header status").isEqualTo(expected);
    }

    private void assertAllItemsStatus(UUID appointmentId, String expected, int expectedCount) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                String.class, appointmentId);
        assertThat(statuses)
                .as("every chained item must move in lockstep with the header")
                .hasSize(expectedCount)
                .allSatisfy(s -> assertThat(s).isEqualTo(expected));
    }

    private Long statusChangedCount(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId);
    }
}
