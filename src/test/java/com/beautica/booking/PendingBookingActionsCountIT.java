package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.beautica.support.HibernateStatistics;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 357 — {@code GET /bookings/me/pending-actions/count} and {@code GET
 * /bookings/salon/{salonId}/pending-actions/count}, full HTTP stack over real Postgres, fixed
 * {@link Clock}. Pins each leg (toClose / toRateClient), the role/scope matrix, the mutation
 * round-trips, and parity with the archive list's own per-row flags.
 *
 * <p>Salon-source rule applied (Scope-2): {@code BookingCompletionAccess} projects the MASTER's
 * salon ({@code bm.salon.id}), so the salon scope is {@code b.salon = S AND b.master.salon = S};
 * case 12 pins a rotated master dropping out.
 */
@Import({TestSecurityConfig.class, PendingBookingActionsCountIT.FrozenClockConfig.class})
@DisplayName("GET .../pending-actions/count — Phase 357, full HTTP stack over real Postgres, fixed clock")
class PendingBookingActionsCountIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String ME_URL = BOOKINGS_URL + "/me/pending-actions/count";
    private static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final long ME_STATEMENTS = 3L;
    private static final long SALON_STATEMENTS = 3L;
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

    @TestConfiguration
    static class FrozenClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EntityManagerFactory emf;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── 1 ─ 2 ─ 14: legs and boundaries ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("#1 toClose counts only CONFIRMED rows whose end has passed (future and in-slot excluded)")
    void should_countOnlyEndedConfirmed_when_toCloseLeg() throws Exception {
        Solo solo = solo("p357-1");
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "CONFIRMED", "APP", NOW.minusHours(3), NOW.minusHours(2));
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "CONFIRMED", "APP", NOW.plusHours(2), NOW.plusHours(3));
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "CONFIRMED", "APP", NOW.minusMinutes(30), NOW.plusMinutes(30));

        JsonNode counts = me(solo.token, "");

        assertThat(counts.path("toClose").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("#2 toRateClient counts only COMPLETED + registered client + no review; count == sum")
    void should_countOnlyRateableCompleted_when_toRateLeg() throws Exception {
        Solo solo = solo("p357-2");
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "COMPLETED", "APP", d(1), d(1).plusHours(1));
        UUID reviewed = insert(solo.clientId, solo.masterId, solo.serviceId, null, "COMPLETED", "APP", d(2), d(2).plusHours(1));
        insert(null, solo.masterId, solo.serviceId, null, "COMPLETED", "LINK", d(3), d(3).plusHours(1));
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "NOT_COMPLETED", "APP", d(4), d(4).plusHours(1));
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "CANCELLED", "APP", d(5), d(5).plusHours(1));
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "DECLINED", "APP", d(6), d(6).plusHours(1));
        assertThat(rate(reviewed, solo.token)).isEqualTo(HttpStatus.CREATED);
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "CONFIRMED", "APP", d(7), d(7).plusHours(1));

        JsonNode counts = me(solo.token, "");

        assertThat(counts.path("toRateClient").asLong()).isEqualTo(1L);
        assertThat(counts.path("toClose").asLong()).isEqualTo(1L);
        assertThat(counts.path("count").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("#14 ends_at == now is not counted (strict <)")
    void should_notCount_when_endsAtEqualsNow() throws Exception {
        Solo solo = solo("p357-14");
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "CONFIRMED", "APP", NOW.minusHours(1), NOW);

        assertThat(me(solo.token, "").path("count").asLong()).isZero();
    }

    // ── 3 ─ 4 ─ 6: mutation round trips ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("#3 POST /client-reviews drops toRateClient to 0")
    void should_dropToRate_when_clientReviewPosted() throws Exception {
        Solo solo = solo("p357-3");
        UUID id = insert(solo.clientId, solo.masterId, solo.serviceId, null, "COMPLETED", "APP", d(1), d(1).plusHours(1));
        assertThat(me(solo.token, "").path("toRateClient").asLong()).isEqualTo(1L);

        assertThat(rate(id, solo.token)).isEqualTo(HttpStatus.CREATED);

        assertThat(me(solo.token, "").path("toRateClient").asLong()).isZero();
    }

    @Test
    @DisplayName("#4 PATCH /complete moves a row from toClose to toRateClient")
    void should_moveToRate_when_rowCompleted() throws Exception {
        Solo solo = solo("p357-4");
        UUID id = insert(solo.clientId, solo.masterId, solo.serviceId, null, "CONFIRMED", "APP", d(1), d(1).plusHours(1));

        assertThat(patch(BOOKINGS_URL + "/" + id + "/complete", solo.token)).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode counts = me(solo.token, "");
        assertThat(counts.path("toClose").asLong()).isZero();
        assertThat(counts.path("toRateClient").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("#6 staff walk-in (guest name only): counted in toClose, never in toRateClient after complete")
    void should_notRate_when_walkInWithoutClient() throws Exception {
        Solo solo = solo("p357-6");
        UUID id = insert(null, solo.masterId, solo.serviceId, null, "CONFIRMED", "STAFF", d(1), d(1).plusHours(1));
        assertThat(me(solo.token, "").path("toClose").asLong()).isEqualTo(1L);

        assertThat(patch(BOOKINGS_URL + "/" + id + "/complete", solo.token)).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode counts = me(solo.token, "");
        assertThat(counts.path("toClose").asLong()).isZero();
        assertThat(counts.path("toRateClient").asLong()).isZero();
    }

    @Test
    @DisplayName("#5 multi-service visit: 2 ended children count 2; completing one leaves 1")
    void should_countChildrenIndividually_when_multiServiceVisit() throws Exception {
        Solo solo = solo("p357-5");
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'CONFIRMED', 'APP', NOW(), NOW())", appointmentId, solo.clientId);
        UUID first = insertVisitChild(solo, appointmentId, d(1));
        insertVisitChild(solo, appointmentId, d(1).plusHours(1));
        assertThat(me(solo.token, "").path("toClose").asLong()).isEqualTo(2L);

        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + first + "/complete",
                HttpMethod.PATCH, new HttpEntity<>(fixtures.bearerHeaders(solo.token)), String.class);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(me(solo.token, "").path("toClose").asLong()).isEqualTo(1L);
    }

    // ── 7 ─ 8 ─ 9 ─ 10: scope and authorization ──────────────────────────────────────────────────

    @Test
    @DisplayName("#7 owner asMaster=true counts only owner-performed rows; #8 salon endpoint counts all, "
            + "identically for owner and assigned admin")
    void should_scopeOwnerMasterAndSalon_when_ownerAndAdminRead() throws Exception {
        SalonSetup s = salonSetup("p357-7");
        seedOwnerAndMasterRows(s);

        JsonNode asMaster = me(s.ownerToken, "?asMaster=true");
        JsonNode salonOwner = salon(s.salonId, s.ownerToken);
        JsonNode salonAdmin = salon(s.salonId, s.adminToken);

        assertThat(asMaster.path("toClose").asLong()).isEqualTo(2L);
        assertThat(asMaster.path("toRateClient").asLong()).isEqualTo(1L);
        assertThat(salonOwner.path("toClose").asLong()).isEqualTo(5L);
        assertThat(salonOwner.path("toRateClient").asLong()).isEqualTo(2L);
        assertThat(salonAdmin).isEqualTo(salonOwner);
    }

    @Test
    @DisplayName("#9 admin of another salon, and an admin whose assignment was revoked: 403")
    void should_return403_when_adminOfOtherSalonOrRevoked() throws Exception {
        SalonSetup a = salonSetup("p357-9a");
        SalonSetup b = salonSetup("p357-9b");

        assertThat(status(salonUrl(b.salonId), a.adminToken)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(salonUrl(a.salonId), a.adminToken)).isEqualTo(HttpStatus.OK);

        jdbcTemplate.update("UPDATE users SET salon_id = NULL WHERE email = ?", a.adminEmail);

        assertThat(status(salonUrl(a.salonId), a.adminToken)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("#9b salon master and client are refused the salon endpoint; foreign owner too")
    void should_return403_when_salonMasterClientOrForeignOwner() throws Exception {
        SalonSetup a = salonSetup("p357-9c");
        SalonSetup b = salonSetup("p357-9d");
        String clientEmail = "p357-9c-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        assertThat(status(salonUrl(a.salonId), fixtures.tokenFor(a.salon.masterEmail()))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(salonUrl(a.salonId), fixtures.tokenFor(clientEmail))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(salonUrl(a.salonId), b.ownerToken)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("#10 /me is 403 for SALON_MASTER, SALON_ADMIN, CLIENT and SALON_OWNER without asMaster")
    void should_return403_when_meCalledByNonScopedRole() throws Exception {
        SalonSetup s = salonSetup("p357-10");
        String clientEmail = "p357-10-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        assertThat(status(ME_URL, fixtures.tokenFor(s.salon.masterEmail()))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(ME_URL, s.adminToken)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(ME_URL, fixtures.tokenFor(clientEmail))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(ME_URL, s.ownerToken)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(ME_URL + "?asMaster=false", s.ownerToken)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("401 when unauthenticated")
    void should_return401_when_unauthenticated() {
        ResponseEntity<String> resp = restTemplate.exchange(ME_URL, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 11 ─ 12: liveness and rotation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("#11 inactive independent master: toRateClient 0, toClose still counted")
    void should_zeroRateButKeepClose_when_independentMasterInactive() throws Exception {
        Solo solo = solo("p357-11");
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "COMPLETED", "APP", d(1), d(1).plusHours(1));
        insert(solo.clientId, solo.masterId, solo.serviceId, null, "CONFIRMED", "APP", d(2), d(2).plusHours(1));
        assertThat(me(solo.token, "").path("toRateClient").asLong()).isEqualTo(1L);

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", solo.masterId);

        JsonNode counts = me(solo.token, "");
        assertThat(counts.path("toRateClient").asLong()).isZero();
        assertThat(counts.path("toClose").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("#11b inactive independent master parity: the archive list stays 200 (no liveness gate on "
            + "the independent role) with providerCanReviewClient=false, so count == flagged rows == toClose only")
    void should_matchArchiveList_when_independentMasterInactive() throws Exception {
        Solo solo = solo("p357-11b");
        seedMixed(solo.clientId, solo.masterId, solo.serviceId, null, 30, 0, solo.token);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", solo.masterId);

        JsonNode counts = me(solo.token, "");
        long flagged = listFlagged(BOOKINGS_URL + "/me?partition=HISTORY", solo.token);

        assertThat(counts.path("toRateClient").asLong()).as("inactive independent master cannot rate").isZero();
        assertThat(counts.path("toClose").asLong()).isPositive();
        assertParity(counts, flagged);
    }

    @Test
    @DisplayName("#12 rule: salon scope = b.salon = S AND b.master.salon = S — a master rotated to another "
            + "salon drops out of the old salon's count (the authority kernel reads the master's live salon)")
    void should_dropRotatedMaster_when_masterMovedToAnotherSalon() throws Exception {
        SalonSetup a = salonSetup("p357-12a");
        SalonSetup b = salonSetup("p357-12b");
        UUID clientId = fixtures.createUser("p357-12-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID service = fixtures.createSalonService(a.salonId, a.salon.masterId());
        insert(clientId, a.salon.masterId(), service, a.salonId, "CONFIRMED", "APP", d(1), d(1).plusHours(1));
        assertThat(salon(a.salonId, a.ownerToken).path("toClose").asLong()).isEqualTo(1L);

        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", b.salonId, a.salon.masterId());

        assertThat(salon(a.salonId, a.ownerToken).path("toClose").asLong()).isZero();
        assertThat(salon(b.salonId, b.ownerToken).path("toClose").asLong()).isZero();
    }

    // ── 13: parity with the archive list ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("#13 parity: count == archive rows flagged awaitingClosure || providerCanReviewClient "
            + "(independent, owner asMaster, salon owner, salon admin)")
    void should_matchArchiveListFlags_when_mixedSetSpansPages() throws Exception {
        Solo solo = solo("p357-13a");
        seedMixed(solo.clientId, solo.masterId, solo.serviceId, null, 30, 0, solo.token);
        assertParity(me(solo.token, ""), listFlagged(BOOKINGS_URL + "/me?partition=HISTORY", solo.token));

        SalonSetup s = salonSetup("p357-13b");
        UUID clientId = fixtures.createUser("p357-13-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID ownerMasterId = fixtures.createOwnerAsMaster(s.salonId, ownerUserId(s));
        UUID ownerService = fixtures.createSalonService(s.salonId, ownerMasterId);
        UUID masterService = fixtures.createSalonService(s.salonId, s.salon.masterId());
        seedMixed(clientId, ownerMasterId, ownerService, s.salonId, 26, 0, s.ownerToken);
        // 100-day offset: both masters share ONE client, and a client may not hold overlapping slots,
        // so the two series must occupy disjoint day ranges (26 rows < 100).
        seedMixed(clientId, s.salon.masterId(), masterService, s.salonId, 14, 100, s.ownerToken);

        assertParity(me(s.ownerToken, "?asMaster=true"),
                listFlagged(BOOKINGS_URL + "/me?asMaster=true&partition=HISTORY", s.ownerToken));
        String salonList = BOOKINGS_URL + "/salon/" + s.salonId + "?partition=HISTORY";
        assertParity(salon(s.salonId, s.ownerToken), listFlagged(salonList, s.ownerToken));
        assertParity(salon(s.salonId, s.adminToken), listFlagged(salonList, s.adminToken));
    }

    // ── QA additions (phase 357 audit) ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("scope isolation: independent master A never counts independent master B's rows")
    void should_notCountOtherMastersRows_when_twoIndependentMastersHavePending() throws Exception {
        Solo a = solo("p357-iso-a");
        Solo b = solo("p357-iso-b");
        insert(b.clientId, b.masterId, b.serviceId, null, "CONFIRMED", "APP", d(1), d(1).plusHours(1));
        insert(b.clientId, b.masterId, b.serviceId, null, "COMPLETED", "APP", d(2), d(2).plusHours(1));

        JsonNode counts = me(a.token, "");

        assertThat(counts.path("count").asLong()).isZero();
        assertThat(me(b.token, "").path("count").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("#12b rotated master: a COMPLETED un-reviewed row also drops out of the old salon's toRateClient")
    void should_dropRotatedMasterRateLeg_when_masterMovedToAnotherSalon() throws Exception {
        SalonSetup a = salonSetup("p357-12c");
        SalonSetup b = salonSetup("p357-12d");
        UUID clientId = fixtures.createUser("p357-12c-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID service = fixtures.createSalonService(a.salonId, a.salon.masterId());
        insert(clientId, a.salon.masterId(), service, a.salonId, "COMPLETED", "APP", d(1), d(1).plusHours(1));
        assertThat(salon(a.salonId, a.ownerToken).path("toRateClient").asLong()).isEqualTo(1L);

        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", b.salonId, a.salon.masterId());

        assertThat(salon(a.salonId, a.ownerToken).path("toRateClient").asLong()).isZero();
        assertThat(salon(b.salonId, b.ownerToken).path("toRateClient").asLong()).isZero();
    }

    @Test
    @DisplayName("salon endpoint: ends_at == now and future rows are not counted in toClose")
    void should_notCountBoundaryRows_when_salonScope() throws Exception {
        SalonSetup s = salonSetup("p357-sb");
        UUID clientId = fixtures.createUser("p357-sb-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID service = fixtures.createSalonService(s.salonId, s.salon.masterId());
        insert(clientId, s.salon.masterId(), service, s.salonId, "CONFIRMED", "APP", NOW.minusHours(1), NOW);
        insert(clientId, s.salon.masterId(), service, s.salonId, "CONFIRMED", "APP", NOW.plusHours(2), NOW.plusHours(3));
        insert(clientId, s.salon.masterId(), service, s.salonId, "CONFIRMED", "APP", NOW.minusHours(3), NOW.minusHours(2));

        assertThat(salon(s.salonId, s.ownerToken).path("toClose").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("salon endpoint: guest-COMPLETED and already-reviewed rows are excluded from toRateClient")
    void should_excludeGuestAndReviewed_when_salonScopeRateLeg() throws Exception {
        SalonSetup s = salonSetup("p357-sr");
        UUID clientId = fixtures.createUser("p357-sr-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID service = fixtures.createSalonService(s.salonId, s.salon.masterId());
        insert(clientId, s.salon.masterId(), service, s.salonId, "COMPLETED", "APP", d(1), d(1).plusHours(1));
        insert(null, s.salon.masterId(), service, s.salonId, "COMPLETED", "LINK", d(2), d(2).plusHours(1));
        UUID reviewed = insert(clientId, s.salon.masterId(), service, s.salonId, "COMPLETED", "APP", d(3), d(3).plusHours(1));
        assertThat(rate(reviewed, s.ownerToken)).isEqualTo(HttpStatus.CREATED);

        assertThat(salon(s.salonId, s.ownerToken).path("toRateClient").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("#10b asMaster=true does not unlock /me for SALON_MASTER, SALON_ADMIN or CLIENT (still 403)")
    void should_return403_when_nonScopedRoleSendsAsMasterTrue() throws Exception {
        SalonSetup s = salonSetup("p357-10b");
        String clientEmail = "p357-10b-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        assertThat(status(ME_URL + "?asMaster=true", fixtures.tokenFor(s.salon.masterEmail()))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(ME_URL + "?asMaster=true", s.adminToken)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(ME_URL + "?asMaster=true", fixtures.tokenFor(clientEmail))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("owner asMaster=true with a deactivated owner-master row: 403, not a zero count")
    void should_return403_when_ownerMasterRowInactive() throws Exception {
        SalonSetup s = salonSetup("p357-oi");
        UUID ownerMasterId = fixtures.createOwnerAsMaster(s.salonId, ownerUserId(s));
        assertThat(status(ME_URL + "?asMaster=true", s.ownerToken)).isEqualTo(HttpStatus.OK);

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", ownerMasterId);

        assertThat(status(ME_URL + "?asMaster=true", s.ownerToken)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("salon endpoint for an unknown salonId is 403 for an owner (no existence oracle)")
    void should_return403_when_salonDoesNotExist() throws Exception {
        SalonSetup s = salonSetup("p357-unk");

        assertThat(status(salonUrl(UUID.randomUUID()), s.ownerToken)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("absolute statement ceiling: /me and salon endpoints each add exactly 2 COUNTs, no row loading")
    void should_issueFixedStatementCounts_when_countingMeAndSalon() throws Exception {
        Solo solo = solo("p357-abs");
        seedMixed(solo.clientId, solo.masterId, solo.serviceId, null, 12, 0, solo.token);
        SalonSetup s = salonSetup("p357-abs-s");
        UUID clientId = fixtures.createUser("p357-abs-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID service = fixtures.createSalonService(s.salonId, s.salon.masterId());
        for (int i = 1; i <= 12; i++) {
            insert(clientId, s.salon.masterId(), service, s.salonId, i % 2 == 0 ? "CONFIRMED" : "COMPLETED", "APP", d(i), d(i).plusHours(1));
        }
        Statistics statistics = HibernateStatistics.enabledOn(emf);
        me(solo.token, "");
        salon(s.salonId, s.ownerToken);

        statistics.clear();
        me(solo.token, "");
        long meStatements = statistics.getPrepareStatementCount();
        long meLoads = statistics.getEntityLoadCount();
        statistics.clear();
        salon(s.salonId, s.ownerToken);
        long salonStatements = statistics.getPrepareStatementCount();
        long salonLoads = statistics.getEntityLoadCount();

        assertThat(meStatements).as("/me: master lookup + 2 COUNTs (+auth)").isEqualTo(ME_STATEMENTS);
        assertThat(salonStatements).as("salon: authz lookups + 2 COUNTs").isEqualTo(SALON_STATEMENTS);
        assertThat(meLoads).as("no booking entities hydrated").isLessThanOrEqualTo(3L);
        assertThat(salonLoads).as("no booking entities hydrated").isLessThanOrEqualTo(4L);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private record Solo(UUID masterId, UUID clientId, UUID serviceId, String token) {}

    private record SalonSetup(BookingTestFixtures.SalonFixture salon, UUID salonId, String ownerToken,
                              String adminEmail, String adminToken) {}

    private Solo solo(String tag) throws Exception {
        String email = tag + "-m-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        UUID clientId = fixtures.createUser(tag + "-c-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        return new Solo(masterId, clientId, serviceId, fixtures.tokenFor(email));
    }

    private SalonSetup salonSetup(String tag) throws Exception {
        var salon = fixtures.createSalon(tag + "-o-" + System.nanoTime() + "@beautica.test");
        String adminEmail = tag + "-a-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        return new SalonSetup(salon, salon.salonId(), fixtures.tokenFor(salon.ownerEmail()),
                adminEmail, fixtures.tokenFor(adminEmail));
    }

    private UUID ownerUserId(SalonSetup s) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, s.salon.ownerEmail());
    }

    /** Owner-performed: 2 ended CONFIRMED + 1 rateable COMPLETED. Salon master: 3 ended CONFIRMED + 1 rateable COMPLETED. */
    private void seedOwnerAndMasterRows(SalonSetup s) {
        UUID clientId = fixtures.createUser("p357-seed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID ownerMasterId = fixtures.createOwnerAsMaster(s.salonId, ownerUserId(s));
        UUID ownerService = fixtures.createSalonService(s.salonId, ownerMasterId);
        UUID masterService = fixtures.createSalonService(s.salonId, s.salon.masterId());
        for (int i = 1; i <= 2; i++) {
            insert(clientId, ownerMasterId, ownerService, s.salonId, "CONFIRMED", "APP", d(i), d(i).plusHours(1));
        }
        insert(clientId, ownerMasterId, ownerService, s.salonId, "COMPLETED", "APP", d(3), d(3).plusHours(1));
        for (int i = 4; i <= 6; i++) {
            insert(clientId, s.salon.masterId(), masterService, s.salonId, "CONFIRMED", "APP", d(i), d(i).plusHours(1));
        }
        insert(clientId, s.salon.masterId(), masterService, s.salonId, "COMPLETED", "APP", d(7), d(7).plusHours(1));
    }

    /** n rows cycling through every status/shape; some completed rows are really reviewed via the API. */
    private void seedMixed(UUID clientId, UUID masterId, UUID serviceId, UUID salonId, int n, int dayOffset,
                           String reviewerToken) throws Exception {
        String[] shapes = {"CONFIRMED_ENDED", "CONFIRMED_FUTURE", "COMPLETED", "COMPLETED_GUEST",
                "NOT_COMPLETED", "CANCELLED", "COMPLETED_REVIEWED", "DECLINED"};
        for (int i = 0; i < n; i++) {
            int day = dayOffset + 20 + i;
            OffsetDateTime past = d(day);
            switch (shapes[i % shapes.length]) {
                case "CONFIRMED_ENDED" -> insert(clientId, masterId, serviceId, salonId, "CONFIRMED", "APP", past, past.plusHours(1));
                case "CONFIRMED_FUTURE" -> insert(clientId, masterId, serviceId, salonId, "CONFIRMED", "APP", NOW.plusDays(day), NOW.plusDays(day).plusHours(1));
                case "COMPLETED" -> insert(clientId, masterId, serviceId, salonId, "COMPLETED", "APP", past, past.plusHours(1));
                case "COMPLETED_GUEST" -> insert(null, masterId, serviceId, salonId, "COMPLETED", "LINK", past, past.plusHours(1));
                case "NOT_COMPLETED" -> insert(clientId, masterId, serviceId, salonId, "NOT_COMPLETED", "APP", past, past.plusHours(1));
                case "CANCELLED" -> insert(clientId, masterId, serviceId, salonId, "CANCELLED", "APP", past, past.plusHours(1));
                case "DECLINED" -> insert(clientId, masterId, serviceId, salonId, "DECLINED", "APP", past, past.plusHours(1));
                default -> {
                    UUID id = insert(clientId, masterId, serviceId, salonId, "COMPLETED", "APP", past, past.plusHours(1));
                    assertThat(rate(id, reviewerToken)).isEqualTo(HttpStatus.CREATED);
                }
            }
        }
    }

    private UUID insertVisitChild(Solo solo, UUID appointmentId, OffsetDateTime start) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "appointment_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                id, solo.clientId, solo.masterId, solo.serviceId, start, start.plusHours(1), appointmentId);
        return id;
    }

    /** A day-aligned instant {@code days} in the past, offset so ends never touch NOW. */
    private static OffsetDateTime d(int days) {
        return NOW.minusDays(days).minusHours(5);
    }

    private UUID insert(UUID clientId, UUID masterId, UUID serviceId, UUID salonId, String status, String source,
                        OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID id = UUID.randomUUID();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        boolean guest = clientId == null;
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, ?, ?, ?, ?, NOW(), NOW())",
                id, clientId, masterId, serviceId, salonId, status, startsAt, endsAt, minutes, source,
                guest ? "Guest" : null, guest && "STAFF".equals(source) ? "Walkin" : null,
                guest ? "+380501234567" : null);
        return id;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────────────────────

    private String salonUrl(UUID salonId) {
        return BOOKINGS_URL + "/salon/" + salonId + "/pending-actions/count";
    }

    private JsonNode me(String token, String query) throws Exception {
        return body(ME_URL + query, token);
    }

    private JsonNode salon(UUID salonId, String token) throws Exception {
        return body(salonUrl(salonId), token);
    }

    private JsonNode body(String url, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("GET %s body=%s", url, resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private HttpStatus status(String url, String token) {
        return HttpStatus.valueOf(restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class)
                .getStatusCode().value());
    }

    private HttpStatus patch(String url, String token) {
        return HttpStatus.valueOf(restTemplate.exchange(
                url, HttpMethod.PATCH, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class)
                .getStatusCode().value());
    }

    private HttpStatus rate(UUID bookingId, String token) throws Exception {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = objectMapper.writeValueAsString(
                java.util.Map.of("bookingId", bookingId.toString(), "rating", 5));
        return HttpStatus.valueOf(restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST, new HttpEntity<>(json, headers), String.class)
                .getStatusCode().value());
    }

    /** Pages through the archive list (size 10) and counts rows offering a provider action. */
    private long listFlagged(String url, String token) throws Exception {
        long flagged = 0;
        long total = -1;
        int seen = 0;
        int pageCount = 0;
        for (int page = 0; total < 0 || seen < total; page++) {
            String sep = url.contains("?") ? "&" : "?";
            ResponseEntity<String> resp = restTemplate.exchange(
                    url + sep + "page=" + page + "&size=10", HttpMethod.GET,
                    new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
            assertThat(resp.getStatusCode()).as("list %s body=%s", url, resp.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode pageNode = objectMapper.readTree(resp.getBody()).path("data");
            total = pageNode.path("totalElements").asLong();
            for (JsonNode row : pageNode.path("data")) {
                seen++;
                if (row.path("awaitingClosure").asBoolean() || row.path("providerCanReviewClient").asBoolean()) {
                    flagged++;
                }
            }
            pageCount++;
            if (pageNode.path("data").isEmpty()) {
                break;
            }
        }
        assertThat(pageCount).as("the archive must span more than one page").isGreaterThan(1);
        return flagged;
    }

    private void assertParity(JsonNode counts, long flagged) {
        assertThat(counts.path("count").asLong()).as("count vs archive-list flags").isEqualTo(flagged);
        assertThat(counts.path("count").asLong()).isPositive();
        assertThat(counts.path("count").asLong())
                .isEqualTo(counts.path("toClose").asLong() + counts.path("toRateClient").asLong());
    }
}
