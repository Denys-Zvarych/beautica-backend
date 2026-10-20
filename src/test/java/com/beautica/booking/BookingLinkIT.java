package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.booking.service.BookingLinkService;
import com.beautica.config.BookingLinksProperties;
import com.beautica.service.BookabilityHttp;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.ThreadJdbcFootprint;
import com.beautica.support.ThreadJdbcFootprint.Footprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 375 — {@code GET /api/v1/booking-links/mine} end to end (real Postgres, real security chain).
 *
 * <p>Fixtures come from {@link BookableMasterSeeder} — the same seeder the search bookable-gate ITs
 * use — and every master starts as a LEGACY row ({@code booking_slug NULL}), the state V86 left
 * pre-existing masters in. {@code bookable} is cross-checked against discovery search for the same
 * fixture, proving the link flag and search read one predicate.
 *
 * <p>Database-footprint assertions use {@link ThreadJdbcFootprint} (QA 2026-10-20): counters scoped
 * to the calling thread, so a scheduled job or async listener running concurrently on this context
 * cannot move them. The former SessionFactory-wide Hibernate {@code Statistics} assertions were a
 * timing-dependent flake.
 */
@Import(ThreadJdbcFootprint.Config.class)
@DisplayName("GET /booking-links/mine — role matrix, lazy permanent slug, bookable, kill-switch (Phase 375)")
class BookingLinkIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/booking-links/mine";
    private static final String SLUG_CHECK = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private BookingLinksProperties bookingLinksProperties;
    @Autowired private BookingLinkService bookingLinkService;

    private BookabilityHttp http;

    @BeforeEach
    void setUp() {
        http = new BookabilityHttp(restTemplate, objectMapper);
        bookingLinksProperties.setShareEnabled(true);
    }

    @AfterEach
    void restoreSwitch() {
        // The properties bean is a context singleton shared with every IT on this cached context.
        bookingLinksProperties.setShareEnabled(true);
    }

    // ── fixture helpers ──────────────────────────────────────────────────────────────────────

    private UUID userIdOfMaster(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private String slugOf(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM masters WHERE id = ?", String.class, masterId);
    }

    private String tokenFor(UUID userId, Role role) {
        return jwtTokenProvider.generateAccessToken(userId, "user-" + userId + "@beautica.test", role);
    }

    private String masterToken(UUID masterId, Role role) {
        return tokenFor(userIdOfMaster(masterId), role);
    }

    /** Salon owner's own master row (the owner is always a master), legacy NULL slug. */
    private UUID insertOwnerMaster(UUID salonId) {
        UUID ownerUserId = jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
        jdbcTemplate.update("UPDATE users SET first_name = 'Ostap', last_name = 'Owner' WHERE id = ?", ownerUserId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, NOW(), NOW())",
                masterId, ownerUserId, salonId);
        return masterId;
    }

    private UUID insertUser(Role role, UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                userId, "user-" + userId + "@beautica.test", PASSWORD_HASH, role.name(), salonId);
        return userId;
    }

    private JsonNode mine(String token) throws Exception {
        return http.get(URL, token);
    }

    private ResponseEntity<String> rawMine(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(URL, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    // ── role matrix (D2) ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("independent master (legacy NULL slug) → one MASTER link; slug issued, matches the V86 CHECK, url absolute")
    void should_issueSlugAndReturnMasterLink_when_independentMasterWithLegacyNullSlug() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Kalynka");
        assertThat(slugOf(masterId)).isNull();

        JsonNode data = mine(masterToken(masterId, Role.INDEPENDENT_MASTER));

        String stored = slugOf(masterId);
        assertThat(stored).matches(SLUG_CHECK).startsWith("kalynka-kalynka-");
        assertThat(data.path("shareEnabled").asBoolean()).isTrue();
        assertThat(data.path("links")).hasSize(1);
        JsonNode link = data.path("links").get(0);
        assertThat(link.path("kind").asText()).isEqualTo("MASTER");
        assertThat(link.path("slug").asText()).isEqualTo(stored);
        assertThat(link.path("url").asText()).isEqualTo("https://beautica.app/book/" + stored);
        assertThat(link.path("displayName").asText()).isEqualTo("Kalynka Kalynka");
        assertThat(link.path("salonId").isNull()).isTrue();
    }

    @Test
    @DisplayName("salon owner → own MASTER link (owner is always a master)")
    void should_returnOwnMasterLink_when_salonOwner() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Ostap Salon");
        UUID ownerMasterId = insertOwnerMaster(salonId);

        JsonNode data = mine(masterToken(ownerMasterId, Role.SALON_OWNER));

        assertThat(data.path("links")).hasSize(1);
        assertThat(data.path("links").get(0).path("kind").asText()).isEqualTo("MASTER");
        assertThat(data.path("links").get(0).path("slug").asText()).isEqualTo(slugOf(ownerMasterId));
        assertThat(data.path("links").get(0).path("displayName").asText()).isEqualTo("Ostap Owner");
    }

    @Test
    @DisplayName("salon admin → links: [] (salon link is phase 376)")
    void should_returnNoLinks_when_salonAdmin() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Admin Salon");
        UUID adminUserId = insertUser(Role.SALON_ADMIN, salonId);

        JsonNode data = mine(tokenFor(adminUserId, Role.SALON_ADMIN));

        assertThat(data.path("shareEnabled").asBoolean()).isTrue();
        assertThat(data.path("links")).isEmpty();
    }

    @Test
    @DisplayName("salon master → links: [] and its slug column is left untouched")
    void should_returnNoLinksAndLeaveSlugNull_when_salonMaster() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Master Salon");
        UUID salonMasterId = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId);

        JsonNode data = mine(masterToken(salonMasterId, Role.SALON_MASTER));

        assertThat(data.path("links")).isEmpty();
        assertThat(slugOf(salonMasterId)).isNull();
    }

    @Test
    @DisplayName("client → 403")
    void should_return403_when_client() {
        UUID clientUserId = insertUser(Role.CLIENT, null);

        ResponseEntity<String> response = rawMine(tokenFor(clientUserId, Role.CLIENT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("anonymous → 401")
    void should_return401_when_unauthenticated() {
        ResponseEntity<String> response = rawMine(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("inactive master row → link omitted, no slug issued")
    void should_omitLink_when_masterInactive() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Sonna");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterId);

        JsonNode data = mine(masterToken(masterId, Role.INDEPENDENT_MASTER));

        assertThat(data.path("links")).isEmpty();
        assertThat(slugOf(masterId)).isNull();
    }

    // ── permanent slug (D3/D4) ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("second call returns the same slug; renaming the master never changes it")
    void should_keepSlugStable_when_calledAgainAndAfterRename() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Stabilna");
        String token = masterToken(masterId, Role.INDEPENDENT_MASTER);
        String first = mine(token).path("links").get(0).path("slug").asText();

        String second = mine(token).path("links").get(0).path("slug").asText();
        jdbcTemplate.update("UPDATE users SET first_name = 'Inna', last_name = 'Inshe' WHERE id = ?",
                userIdOfMaster(masterId));
        JsonNode afterRename = mine(token).path("links").get(0);

        assertThat(second).isEqualTo(first);
        assertThat(afterRename.path("slug").asText()).isEqualTo(first);
        assertThat(afterRename.path("displayName").asText()).isEqualTo("Inna Inshe");
        assertThat(slugOf(masterId)).isEqualTo(first);
    }

    @Test
    @DisplayName("parallel first calls for one legacy master → every response carries the one stored slug")
    void should_issueExactlyOneSlug_when_firstCallsRaceInParallel() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Paralel");
        String token = masterToken(masterId, Role.INDEPENDENT_MASTER);
        int callers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mine(token).path("links").get(0).path("slug").asText();
                }));
            }

            start.countDown();
            Set<String> slugs = new HashSet<>();
            for (Future<String> future : futures) {
                slugs.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(slugs).containsExactly(slugOf(masterId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the issued slug resolves on the public page GET /book/{slug}/info (200)")
    void should_resolveOnPublicInfo_when_slugIssued() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Publichna");
        String slug = mine(masterToken(masterId, Role.INDEPENDENT_MASTER)).path("links").get(0).path("slug").asText();

        ResponseEntity<String> info = restTemplate.getForEntity("/api/v1/book/" + slug + "/info", String.class);

        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── bookable (D7/D8) — one predicate shared with discovery search ────────────────────────

    @Test
    @DisplayName("no services and no schedule → bookable=false, and search hides the master")
    void should_markNotBookable_when_noServicesNoSchedule() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Porozhnia");

        boolean bookable = mine(masterToken(masterId, Role.INDEPENDENT_MASTER)).path("links").get(0).path("bookable").asBoolean();

        assertThat(bookable).isFalse();
        assertThat(ids(http.get("/api/v1/search/masters?q=Porozhnia"), "masterId")).isEmpty();
    }

    @Test
    @DisplayName("service but no schedule → bookable=false, and search hides the master")
    void should_markNotBookable_when_serviceButNoSchedule() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Bezgrafika");
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);

        boolean bookable = mine(masterToken(masterId, Role.INDEPENDENT_MASTER)).path("links").get(0).path("bookable").asBoolean();

        assertThat(bookable).isFalse();
        assertThat(ids(http.get("/api/v1/search/masters?q=Bezgrafika"), "masterId")).isEmpty();
    }

    @Test
    @DisplayName("schedule but no service → bookable=false")
    void should_markNotBookable_when_scheduleButNoService() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Bezposlug");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);

        boolean bookable = mine(masterToken(masterId, Role.INDEPENDENT_MASTER)).path("links").get(0).path("bookable").asBoolean();

        assertThat(bookable).isFalse();
    }

    @Test
    @DisplayName("service AND schedule → bookable=true, and search lists the master")
    void should_markBookable_when_serviceAndSchedule() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Gotova");
        BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, masterId);

        boolean bookable = mine(masterToken(masterId, Role.INDEPENDENT_MASTER)).path("links").get(0).path("bookable").asBoolean();

        assertThat(bookable).isTrue();
        assertThat(ids(http.get("/api/v1/search/masters?q=Gotova"), "masterId")).containsExactly(masterId.toString());
    }

    @Test
    @DisplayName("salon owner with a salon service and schedule → bookable=true")
    void should_markOwnerBookable_when_ownerHasSalonServiceAndSchedule() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Bookable Salon");
        UUID ownerMasterId = insertOwnerMaster(salonId);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, ownerMasterId);

        boolean bookable = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links").get(0).path("bookable").asBoolean();

        assertThat(bookable).isTrue();
    }

    // ── kill-switch (D6) ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("switch off → shareEnabled=false, links: [], and no slug is written")
    void should_returnDisabledAndWriteNoSlug_when_shareSwitchedOff() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Vymknena");
        bookingLinksProperties.setShareEnabled(false);

        JsonNode data = mine(masterToken(masterId, Role.INDEPENDENT_MASTER));

        assertThat(data.path("shareEnabled").asBoolean()).isFalse();
        assertThat(data.path("links")).isEmpty();
        assertThat(slugOf(masterId)).isNull();
    }

    // ── closed salon / pre-issued slug (QA 2026-10-20) ──────────────────────────────────────

    @Test
    @DisplayName("salon owner whose salon is closed → link still returned (slug permanent) but bookable=false, page 404s")
    void should_returnLinkMarkedNotBookable_when_ownersSalonClosed() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Zakryta Salon");
        UUID ownerMasterId = insertOwnerMaster(salonId);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, ownerMasterId);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        JsonNode links = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links");

        assertThat(links).as("closed salon must not drop the owner's link, links=%s", links).hasSize(1);
        JsonNode link = links.get(0);
        assertThat(link.path("bookable").asBoolean())
                .as("a closed salon fails MasterBookability even with service + schedule").isFalse();
        assertThat(link.path("slug").asText()).isEqualTo(slugOf(ownerMasterId));
        assertThat(restTemplate.getForEntity("/api/v1/book/" + link.path("slug").asText() + "/info", String.class)
                .getStatusCode())
                .as("the public page agrees: a closed salon's master slug does not resolve")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("master that already holds a slug (non-legacy) → that exact slug returned, column never rewritten")
    void should_returnStoredSlugVerbatim_when_slugAlreadyIssued() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Mayie");
        String preIssued = "pre-issued-" + UUID.randomUUID().toString().substring(0, 4);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", preIssued, masterId);

        JsonNode link = mine(masterToken(masterId, Role.INDEPENDENT_MASTER)).path("links").get(0);

        assertThat(link.path("slug").asText()).isEqualTo(preIssued);
        assertThat(link.path("url").asText()).isEqualTo("https://beautica.app/book/" + preIssued);
        assertThat(slugOf(masterId)).isEqualTo(preIssued);
    }

    // ── 375 audit F1/F2 + perf INFO — DB footprint of getMyLinks, measured on the calling thread ─
    // ThreadJdbcFootprint counts only this thread's JDBC work, so a concurrent @Scheduled job or
    // async listener cannot move the counters (the previous global Hibernate Statistics could). The
    // legacy-issue test proves the probe is not vacuous: its counters must move. The service is
    // called directly so the window holds only getMyLinks, not the security filter chain.

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INDEPENDENT_MASTER", "SALON_OWNER", "SALON_ADMIN", "SALON_MASTER"})
    @DisplayName("switch off → zero connections, statements and transactions for every provider role")
    void should_touchNoDatabase_when_shareSwitchedOff(Role role) {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Bezbazy");
        UUID userId = userIdOfMaster(masterId);
        bookingLinksProperties.setShareEnabled(false);

        Footprint footprint = ThreadJdbcFootprint.footprintOf(() -> bookingLinkService.getMyLinks(userId, role));

        assertThat(footprint.connections()).as("%s", footprint).isZero();
        assertThat(footprint.statements()).as("%s", footprint).isZero();
        assertThat(footprint.transactions()).as("%s", footprint).isZero();
        assertThat(slugOf(masterId)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_ADMIN", "SALON_MASTER"})
    @DisplayName("switch on, role with no master link → zero connections, statements and transactions")
    void should_touchNoDatabase_when_roleOwnsNoMasterLink(Role role) {
        UUID userId = UUID.randomUUID();

        Footprint footprint = ThreadJdbcFootprint.footprintOf(() -> bookingLinkService.getMyLinks(userId, role));

        assertThat(footprint.connections()).as("%s", footprint).isZero();
        assertThat(footprint.statements()).as("%s", footprint).isZero();
        assertThat(footprint.transactions()).as("%s", footprint).isZero();
    }

    @Test
    @DisplayName("probe isolation: JDBC work on another thread during the window is NOT counted (the old global-counter flake)")
    void should_ignoreOtherThreadsJdbc_when_measuringFootprint() throws Exception {
        UUID userId = UUID.randomUUID();
        ExecutorService background = Executors.newSingleThreadExecutor();
        try {
            Footprint footprint = ThreadJdbcFootprint.footprintOf(() -> {
                try {
                    // Stands in for a @Scheduled job / async listener hitting the pool mid-measurement.
                    background.submit(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM masters", Long.class))
                            .get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                bookingLinkService.getMyLinks(userId, Role.SALON_MASTER);
            });

            assertThat(footprint.connections()).as("%s", footprint).isZero();
            assertThat(footprint.statements()).as("%s", footprint).isZero();
        } finally {
            background.shutdownNow();
        }
    }

    @Test
    @DisplayName("legacy master → exactly one (issue) transaction, and the probe sees real work (non-vacuous)")
    void should_openOnlyTheIssueTransaction_when_legacyMasterFirstCall() {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Odnatranz");
        UUID userId = userIdOfMaster(masterId);

        Footprint footprint = ThreadJdbcFootprint.footprintOf(
                () -> bookingLinkService.getMyLinks(userId, Role.INDEPENDENT_MASTER));

        assertThat(slugOf(masterId)).isNotNull();
        assertThat(footprint.transactions()).as("%s", footprint).isEqualTo(1);
        assertThat(footprint.connections()).as("%s", footprint).isPositive();
        assertThat(footprint.statements()).as("%s", footprint).isPositive();
    }

    @Test
    @DisplayName("steady state (slug already stored) → at most 2 connection checkouts, no transaction, slug untouched")
    void should_checkOutAtMostTwoConnectionsWithoutTransaction_when_slugAlreadyStored() {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Stalyi");
        BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, masterId);
        UUID userId = userIdOfMaster(masterId);
        String slug = bookingLinkService.getMyLinks(userId, Role.INDEPENDENT_MASTER).links().get(0).slug();

        ThreadJdbcFootprint.Measured<BookingLinksResponse> steady = ThreadJdbcFootprint.measure(
                () -> bookingLinkService.getMyLinks(userId, Role.INDEPENDENT_MASTER));

        Footprint footprint = steady.footprint();
        assertThat(footprint.connections())
                .as("BookingLinkService:85 master read + :124 bookability read only — %s", footprint)
                .isLessThanOrEqualTo(2)
                .isPositive();
        assertThat(footprint.transactions()).as("%s", footprint).isZero();
        assertThat(steady.result().links()).extracting(BookingLinkResponse::slug, BookingLinkResponse::bookable)
                .containsExactly(tuple(slug, true));
        assertThat(slugOf(masterId)).isEqualTo(slug);
    }
}
