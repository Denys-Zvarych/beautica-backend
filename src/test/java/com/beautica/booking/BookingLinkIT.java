package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.booking.service.BookingLinkService;
import com.beautica.config.BookingLinksProperties;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.service.BookabilityHttp;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.HeldDbLock;
import com.beautica.support.RepositorySpies;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
 *
 * <p>Phase 376 adds SALON links: owner → one per ACTIVE owned salon (and, since 2026-10-20, NO MASTER
 * link), admin → its bound salon, salon master → none. Salon fixtures are legacy rows too ({@code salons.booking_slug NULL}, V200's state
 * for pre-existing salons).
 */
@Import(ThreadJdbcFootprint.Config.class)
@DisplayName("GET /booking-links/mine — role matrix, lazy permanent slug, bookable, kill-switch (Phase 375 + 376 SALON links)")
class BookingLinkIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/booking-links/mine";
    private static final String SLUG_CHECK = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private BookingLinksProperties bookingLinksProperties;
    @Autowired private BookingLinkService bookingLinkService;
    @Autowired private DataSource dataSource;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SalonService salonService;
    /** Pass-through spy (reset after each test); stubbed only by the lock-timeout / deleted-salon tests. */
    @MockitoSpyBean private SalonRepository salonRepository;

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

    private String salonSlugOf(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
    }

    private UUID ownerOf(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
    }

    /** A further active salon of the same owner (legacy NULL slug). */
    private UUID insertSiblingSalon(UUID ownerUserId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerUserId, name, testCityId());
        return salonId;
    }

    private static List<String> kinds(JsonNode links) {
        List<String> kinds = new ArrayList<>();
        links.forEach(link -> kinds.add(link.path("kind").asText()));
        return kinds;
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
    @DisplayName("salon owner → ONLY its SALON link, no personal MASTER link, owner's master slug never issued (2026-10-20)")
    void should_returnSalonLinkOnly_when_salonOwner() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Ostap Salon");
        UUID ownerMasterId = insertOwnerMaster(salonId);

        JsonNode data = mine(masterToken(ownerMasterId, Role.SALON_OWNER));

        JsonNode links = data.path("links");
        assertThat(kinds(links)).containsExactly("SALON");
        assertThat(slugOf(ownerMasterId)).as("the owner's master slug is not issued on this call").isNull();
        String salonSlug = salonSlugOf(salonId);
        assertThat(salonSlug).matches(SLUG_CHECK).startsWith("ostap-salon-");
        assertThat(links.get(0).path("slug").asText()).isEqualTo(salonSlug);
        assertThat(links.get(0).path("url").asText()).isEqualTo("https://beautica.app/book/s/" + salonSlug);
        assertThat(links.get(0).path("displayName").asText()).isEqualTo("Ostap Salon");
        assertThat(links.get(0).path("salonId").asText()).isEqualTo(salonId.toString());
    }

    @Test
    @DisplayName("owner and its admin get the SAME salon link (2026-10-20); owner's stored master slug is never surfaced")
    void should_returnSameSalonLinkAsAdmin_when_salonOwner() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Spilnyi Salon");
        UUID ownerMasterId = insertOwnerMaster(salonId);
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'owner-own-ab12' WHERE id = ?", ownerMasterId);
        UUID adminUserId = insertUser(Role.SALON_ADMIN, salonId);

        JsonNode ownerLinks = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links");
        JsonNode adminLinks = mine(tokenFor(adminUserId, Role.SALON_ADMIN)).path("links");

        assertThat(ownerLinks).isEqualTo(adminLinks);
        assertThat(kinds(ownerLinks)).containsExactly("SALON");
        assertThat(ownerLinks.toString()).doesNotContain("owner-own-ab12");
    }

    @Test
    @DisplayName("owner with 2 active salons → one SALON link per salon (no MASTER), ordered by salon name, each slug issued")
    void should_returnOneSalonLinkPerActiveSalon_when_ownerHasTwoSalons() throws Exception {
        UUID zetaSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Zeta Studio");
        UUID ownerMasterId = insertOwnerMaster(zetaSalon);
        UUID alfaSalon = insertSiblingSalon(ownerOf(zetaSalon), "Alfa Studio");

        JsonNode links = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links");

        assertThat(kinds(links)).containsExactly("SALON", "SALON");
        assertThat(links.get(0).path("salonId").asText()).isEqualTo(alfaSalon.toString());
        assertThat(links.get(0).path("slug").asText()).isEqualTo(salonSlugOf(alfaSalon)).startsWith("alfa-studio-");
        assertThat(links.get(1).path("salonId").asText()).isEqualTo(zetaSalon.toString());
        assertThat(links.get(1).path("slug").asText()).isEqualTo(salonSlugOf(zetaSalon)).startsWith("zeta-studio-");
    }

    @Test
    @DisplayName("salon admin → only the SALON link of the salon it is bound to, never the owner's other salon")
    void should_returnBoundSalonLinkOnly_when_salonAdmin() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Admin Salon");
        UUID otherSalon = insertSiblingSalon(ownerOf(salonId), "Other Salon");
        UUID adminUserId = insertUser(Role.SALON_ADMIN, salonId);

        JsonNode data = mine(tokenFor(adminUserId, Role.SALON_ADMIN));

        assertThat(data.path("shareEnabled").asBoolean()).isTrue();
        assertThat(data.path("links")).hasSize(1);
        JsonNode link = data.path("links").get(0);
        assertThat(link.path("kind").asText()).isEqualTo("SALON");
        assertThat(link.path("salonId").asText()).isEqualTo(salonId.toString());
        assertThat(link.path("slug").asText()).isEqualTo(salonSlugOf(salonId));
        assertThat(salonSlugOf(otherSalon)).as("the sibling salon is not the admin's; no slug issued").isNull();
    }

    @Test
    @DisplayName("salon admin whose salon is closed → links: [], no slug issued")
    void should_returnNoLinks_when_adminsSalonInactive() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Closed Admin Salon");
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);
        UUID adminUserId = insertUser(Role.SALON_ADMIN, salonId);

        JsonNode data = mine(tokenFor(adminUserId, Role.SALON_ADMIN));

        assertThat(data.path("links")).isEmpty();
        assertThat(salonSlugOf(salonId)).isNull();
    }

    @Test
    @DisplayName("salon master → links: [], neither its master slug nor its salon's slug is written")
    void should_returnNoLinksAndLeaveSlugNull_when_salonMaster() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Master Salon");
        UUID salonMasterId = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId);

        JsonNode data = mine(masterToken(salonMasterId, Role.SALON_MASTER));

        assertThat(data.path("links")).isEmpty();
        assertThat(slugOf(salonMasterId)).isNull();
        assertThat(salonSlugOf(salonId)).isNull();
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
    @DisplayName("salon owner whose own master row has a salon service and schedule → its SALON link is bookable=true")
    void should_markOwnerSalonBookable_when_ownerHasSalonServiceAndSchedule() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Bookable Salon");
        UUID ownerMasterId = insertOwnerMaster(salonId);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, ownerMasterId);

        JsonNode link = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links").get(0);

        assertThat(link.path("kind").asText()).isEqualTo("SALON");
        assertThat(link.path("bookable").asBoolean()).isTrue();
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
    @DisplayName("salon owner whose only salon is closed → links: [] (no MASTER fallback), neither slug issued")
    void should_returnNoLinks_when_ownersOnlySalonClosed() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Zakryta Salon");
        UUID ownerMasterId = insertOwnerMaster(salonId);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, ownerMasterId);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        JsonNode links = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links");

        assertThat(links).as("closed salon yields no SALON link and the owner has no MASTER link").isEmpty();
        assertThat(slugOf(ownerMasterId)).isNull();
        assertThat(salonSlugOf(salonId)).isNull();
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
    @EnumSource(value = Role.class, names = {"SALON_MASTER", "CLIENT"})
    @DisplayName("switch on, role that owns no link (376: SALON_ADMIN now owns a salon link) → zero connections, statements and transactions")
    void should_touchNoDatabase_when_roleOwnsNoLink(Role role) {
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

    // ── phase 376 — SALON links: closed salon, permanence, bookable, kill-switch, race, footprint ─

    @Test
    @DisplayName("owner with one active + one closed salon → closed salon's link omitted and its slug never issued (D8)")
    void should_omitClosedSalon_when_ownerHasActiveAndClosedSalon() throws Exception {
        UUID openSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Vidkryta Salon");
        UUID ownerMasterId = insertOwnerMaster(openSalon);
        UUID closedSalon = insertSiblingSalon(ownerOf(openSalon), "Zachynena Salon");
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", closedSalon);

        JsonNode links = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links");

        assertThat(kinds(links)).containsExactly("SALON");
        assertThat(links.get(0).path("salonId").asText()).isEqualTo(openSalon.toString());
        assertThat(salonSlugOf(closedSalon)).isNull();
    }

    @Test
    @DisplayName("salon slug is permanent: second call and a salon rename never change it (D5)")
    void should_keepSalonSlug_when_calledAgainAndAfterSalonRename() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Persha Nazva");
        UUID adminUserId = insertUser(Role.SALON_ADMIN, salonId);
        String token = tokenFor(adminUserId, Role.SALON_ADMIN);
        String first = mine(token).path("links").get(0).path("slug").asText();

        String second = mine(token).path("links").get(0).path("slug").asText();
        jdbcTemplate.update("UPDATE salons SET name = 'Druha Nazva' WHERE id = ?", salonId);
        JsonNode afterRename = mine(token).path("links").get(0);

        assertThat(first).startsWith("persha-nazva-");
        assertThat(second).isEqualTo(first);
        assertThat(afterRename.path("slug").asText()).isEqualTo(first);
        assertThat(afterRename.path("displayName").asText()).isEqualTo("Druha Nazva");
        assertThat(salonSlugOf(salonId)).isEqualTo(first);
    }

    @Test
    @DisplayName("salon already holding a slug → that exact slug returned, column never rewritten")
    void should_returnStoredSalonSlugVerbatim_when_salonSlugAlreadyIssued() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Mayie Salon");
        jdbcTemplate.update("UPDATE salons SET booking_slug = 'pre-salon-ab12' WHERE id = ?", salonId);
        UUID adminUserId = insertUser(Role.SALON_ADMIN, salonId);

        JsonNode link = mine(tokenFor(adminUserId, Role.SALON_ADMIN)).path("links").get(0);

        assertThat(link.path("slug").asText()).isEqualTo("pre-salon-ab12");
        assertThat(link.path("url").asText()).isEqualTo("https://beautica.app/book/s/pre-salon-ab12");
        assertThat(salonSlugOf(salonId)).isEqualTo("pre-salon-ab12");
    }

    @Test
    @DisplayName("salon without a bookable master → bookable=false; one bookable master → bookable=true (shared BOOKABLE_SALON_S)")
    void should_flipSalonBookable_when_aMasterBecomesBookable() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Gotovyi Salon");
        UUID masterId = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId);
        String token = tokenFor(insertUser(Role.SALON_ADMIN, salonId), Role.SALON_ADMIN);

        boolean before = mine(token).path("links").get(0).path("bookable").asBoolean();
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, masterId);
        boolean after = mine(token).path("links").get(0).path("bookable").asBoolean();

        assertThat(before).as("salon master has no service and no schedule").isFalse();
        assertThat(after).as("service + schedule on an active master of the salon").isTrue();
    }

    @Test
    @DisplayName("owner's 2 salons: bookability is per salon, batched — only the salon with a bookable master is bookable")
    void should_markEachSalonBookableIndependently_when_ownerHasTwoSalons() throws Exception {
        UUID bookableSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "A Bookable");
        UUID ownerMasterId = insertOwnerMaster(bookableSalon);
        BookableMasterSeeder.makeBookable(jdbcTemplate, bookableSalon, ownerMasterId);
        UUID emptySalon = insertSiblingSalon(ownerOf(bookableSalon), "B Empty");

        JsonNode links = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links");

        assertThat(kinds(links)).containsExactly("SALON", "SALON");
        assertThat(links.get(0).path("salonId").asText()).isEqualTo(bookableSalon.toString());
        assertThat(links.get(0).path("bookable").asBoolean()).isTrue();
        assertThat(links.get(1).path("salonId").asText()).isEqualTo(emptySalon.toString());
        assertThat(links.get(1).path("bookable").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("switch off → no salon slug issued for owner/admin")
    void should_issueNoSalonSlug_when_shareSwitchedOff(Role role) throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Vymknenyi Salon");
        UUID userId = role == Role.SALON_OWNER ? ownerOf(salonId) : insertUser(Role.SALON_ADMIN, salonId);
        bookingLinksProperties.setShareEnabled(false);

        JsonNode data = mine(tokenFor(userId, role));

        assertThat(data.path("shareEnabled").asBoolean()).isFalse();
        assertThat(data.path("links")).isEmpty();
        assertThat(salonSlugOf(salonId)).isNull();
    }

    @Test
    @DisplayName("parallel first calls for one legacy salon (owner + admins) → every response carries the one stored slug")
    void should_issueExactlyOneSalonSlug_when_firstCallsRaceInParallel() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Paralelnyi Salon");
        List<String> tokens = new ArrayList<>();
        tokens.add(tokenFor(ownerOf(salonId), Role.SALON_OWNER));
        for (int i = 0; i < 5; i++) {
            tokens.add(tokenFor(insertUser(Role.SALON_ADMIN, salonId), Role.SALON_ADMIN));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    start.await();
                    JsonNode links = mine(token).path("links");
                    return links.get(links.size() - 1).path("slug").asText();
                }));
            }

            start.countDown();
            Set<String> slugs = new HashSet<>();
            for (Future<String> future : futures) {
                slugs.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(slugs).containsExactly(salonSlugOf(salonId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("owner, 2 legacy salons + legacy master → exactly 1 short issue transaction (ONE salon batch); master slug NOT issued (2026-10-20)")
    void should_openOnlyOneSalonBatchTransaction_when_ownerFirstCall() {
        UUID salonA = BookableMasterSeeder.insertSalon(jdbcTemplate, "Legacy A");
        UUID ownerMasterId = insertOwnerMaster(salonA);
        UUID salonB = insertSiblingSalon(ownerOf(salonA), "Legacy B");
        UUID userId = userIdOfMaster(ownerMasterId);

        Footprint footprint = ThreadJdbcFootprint.footprintOf(() -> bookingLinkService.getMyLinks(userId, Role.SALON_OWNER));

        assertThat(slugOf(ownerMasterId)).as("owner gets no MASTER link → no lazy master-slug write").isNull();
        assertThat(salonSlugOf(salonA)).isNotNull();
        assertThat(salonSlugOf(salonB)).isNotNull();
        assertThat(footprint.transactions()).as("%s", footprint).isEqualTo(1);
    }

    /** Owner whose master slug is already stored, with {@code legacySalons} slug-less active salons. */
    private UUID ownerWithLegacySalons(String prefix, int legacySalons) {
        UUID firstSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, prefix + " 0");
        UUID ownerMasterId = insertOwnerMaster(firstSalon);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?",
                prefix.toLowerCase(java.util.Locale.ROOT) + "-own-" + ownerMasterId.toString().substring(0, 4), ownerMasterId);
        for (int i = 1; i < legacySalons; i++) {
            insertSiblingSalon(ownerOf(firstSalon), prefix + " " + i);
        }
        return userIdOfMaster(ownerMasterId);
    }

    @Test
    @DisplayName("376 audit: owner with 3 slug-less salons (master slug stored) → exactly ONE transaction issues all 3, statements constant in the salon count")
    void should_issueAllSalonSlugsInOneTransaction_when_ownerHasThreeLegacySalons() {
        UUID oneSalonOwner = ownerWithLegacySalons("Odyn", 1);
        UUID threeSalonOwner = ownerWithLegacySalons("Try", 3);

        Footprint one = ThreadJdbcFootprint.footprintOf(() -> bookingLinkService.getMyLinks(oneSalonOwner, Role.SALON_OWNER));
        ThreadJdbcFootprint.Measured<BookingLinksResponse> three = ThreadJdbcFootprint.measure(
                () -> bookingLinkService.getMyLinks(threeSalonOwner, Role.SALON_OWNER));

        List<BookingLinkResponse> salonLinks = three.result().links().stream()
                .filter(link -> link.kind() == com.beautica.booking.dto.BookingLinkKind.SALON).toList();
        assertThat(salonLinks).hasSize(3);
        for (BookingLinkResponse link : salonLinks) {
            assertThat(link.slug()).matches(SLUG_CHECK).isEqualTo(salonSlugOf(link.salonId()));
        }
        assertThat(salonLinks).extracting(BookingLinkResponse::slug).doesNotHaveDuplicates();
        assertThat(three.footprint().transactions()).as("%s", three.footprint()).isEqualTo(1);
        assertThat(three.footprint().statements())
                .as("batched issue: 3 legacy salons cost the same statements as 1 — one=%s three=%s", one, three.footprint())
                .isEqualTo(one.statements());
    }

    @Test
    @DisplayName("owner steady state, 3 salons → no transaction, 2 reads: salons (ONE) + salon bookability (ONE batch); no master read (2026-10-20)")
    void should_readSalonsAndBookabilityOnceEach_when_ownerSteadyStateWithTwoSalons() {
        UUID salonA = BookableMasterSeeder.insertSalon(jdbcTemplate, "Stalyi A");
        UUID ownerMasterId = insertOwnerMaster(salonA);
        insertSiblingSalon(ownerOf(salonA), "Stalyi B");
        insertSiblingSalon(ownerOf(salonA), "Stalyi C");
        UUID userId = userIdOfMaster(ownerMasterId);
        bookingLinkService.getMyLinks(userId, Role.SALON_OWNER);

        ThreadJdbcFootprint.Measured<BookingLinksResponse> steady = ThreadJdbcFootprint.measure(
                () -> bookingLinkService.getMyLinks(userId, Role.SALON_OWNER));

        Footprint footprint = steady.footprint();
        assertThat(steady.result().links()).extracting(BookingLinkResponse::kind)
                .containsOnly(com.beautica.booking.dto.BookingLinkKind.SALON).hasSize(3);
        assertThat(footprint.transactions()).as("%s", footprint).isZero();
        assertThat(footprint.statements())
                .as("constant in the number of salons (3 salons here), no master/master-bookability read — %s", footprint)
                .isEqualTo(2);
        assertThat(footprint.connections()).as("%s", footprint).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("admin steady state → no transaction, 2 reads: bound salon (ONE) + salon bookability (ONE)")
    void should_readTwice_when_adminSteadyState() {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Admin Stalyi");
        UUID adminUserId = insertUser(Role.SALON_ADMIN, salonId);
        bookingLinkService.getMyLinks(adminUserId, Role.SALON_ADMIN);

        Footprint footprint = ThreadJdbcFootprint.footprintOf(
                () -> bookingLinkService.getMyLinks(adminUserId, Role.SALON_ADMIN));

        assertThat(footprint.transactions()).as("%s", footprint).isZero();
        assertThat(footprint.statements()).as("%s", footprint).isEqualTo(2);
    }

    // ── phase 376 QA — creation path agrees with the link; stale-token admin ────────────────

    @Test
    @DisplayName("salon created through SalonService → /mine returns the creation-time slug verbatim, no lazy salon issue")
    void should_returnCreationSlug_when_salonCreatedThroughService() throws Exception {
        UUID ownerUserId = insertUser(Role.SALON_OWNER, null);
        jdbcTemplate.update("UPDATE users SET first_name = 'Nova', last_name = 'Vlasnytsia' WHERE id = ?", ownerUserId);
        UUID salonId = salonService.createSalon(ownerUserId, new CreateSalonRequest(
                "Novyi Salon", null, null, null, null, null, null, testCityId(), null,
                "вул. Хрещатик", "1", null)).id();
        String created = salonSlugOf(salonId);

        JsonNode links = mine(tokenFor(ownerUserId, Role.SALON_OWNER)).path("links");

        assertThat(created).as("D4: issued at creation").matches(SLUG_CHECK).startsWith("novyi-salon-");
        assertThat(kinds(links)).containsExactly("SALON");
        assertThat(links.get(0).path("slug").asText()).isEqualTo(created);
        assertThat(links.get(0).path("url").asText()).isEqualTo("https://beautica.app/book/s/" + created);
        assertThat(salonSlugOf(salonId)).isEqualTo(created);
        verify(salonRepository, never()).lockSalonIdsWithoutBookingSlug(anyCollection());
    }

    @Test
    @DisplayName("stale SALON_ADMIN token for a user now stored as SALON_MASTER (still bound to the salon) → links: [], no salon slug issued")
    void should_returnNoSalonLink_when_adminTokenStaleAndStoredRoleIsSalonMaster() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Zastarilyi Token");
        UUID demotedUserId = insertUser(Role.SALON_MASTER, salonId);

        JsonNode data = mine(tokenFor(demotedUserId, Role.SALON_ADMIN));

        assertThat(data.path("links")).as("the stored role, not the token role, grants the salon binding").isEmpty();
        assertThat(salonSlugOf(salonId)).isNull();
    }

    @Test
    @DisplayName("SALON_OWNER token never yields another owner's salon, even when bound to it via users.salon_id")
    void should_returnOnlyOwnedSalons_when_ownerIsBoundToForeignSalon() throws Exception {
        UUID ownSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Vlasnyi Salon");
        UUID ownerMasterId = insertOwnerMaster(ownSalon);
        UUID foreignSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Chuzhyi Salon");
        jdbcTemplate.update("UPDATE users SET salon_id = ? WHERE id = ?", foreignSalon, ownerOf(ownSalon));

        JsonNode links = mine(masterToken(ownerMasterId, Role.SALON_OWNER)).path("links");

        assertThat(kinds(links)).containsExactly("SALON");
        assertThat(links.get(0).path("salonId").asText()).isEqualTo(ownSalon.toString());
        assertThat(salonSlugOf(foreignSalon)).isNull();
    }

    // ── 376 audit cycle 2 — bounded lock waits, deleted salon ────────────────────────────────

    @Test
    @DisplayName("376 audit: another transaction holds the legacy salon's row lock past the 3s lock_timeout → 409, not a hang; slug not issued")
    void should_return409_when_salonRowLockHeldBeyondTimeout() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Zablokovanyi Ryadok");
        String token = tokenFor(insertUser(Role.SALON_ADMIN, salonId), Role.SALON_ADMIN);

        ResponseEntity<String> response;
        try (HeldDbLock ignored = HeldDbLock.salonRow(dataSource, salonId)) {
            response = rawMine(token);
        }

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(salonSlugOf(salonId)).isNull();
    }

    @Test
    @DisplayName("perf L3: another transaction holds the legacy master's row lock past the 3s lock_timeout → 409, not a hang; slug not issued")
    void should_return409_when_masterRowLockHeldBeyondTimeout() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Zablokovana");
        String token = masterToken(masterId, Role.INDEPENDENT_MASTER);

        ResponseEntity<String> response;
        try (HeldDbLock ignored = HeldDbLock.masterRow(dataSource, masterId)) {
            response = rawMine(token);
        }

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(slugOf(masterId)).isNull();
    }

    @Test
    @DisplayName("376 audit: another writer holds the drawn slug candidate's advisory lock past the 3s lock_timeout → 409, not a hang; slug not issued")
    void should_return409_when_slugCandidateLockHeldBeyondTimeout() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Zainiatyi Kandydat");
        String token = tokenFor(insertUser(Role.SALON_ADMIN, salonId), Role.SALON_ADMIN);
        List<HeldDbLock> holders = new CopyOnWriteArrayList<>();
        // The candidate is random: take its lock on another connection just before the real lock call.
        // Stubbed inside a transaction: the lock method is MANDATORY and the stubbing call passes its interceptor.
        transactionTemplate.executeWithoutResult(status -> doAnswer(invocation -> {
            holders.add(HeldDbLock.salonSlugCandidates(dataSource, invocation.getArgument(0)));
            return RepositorySpies.passThrough(salonRepository, invocation);
        }).when(salonRepository).lockBookingSlugCandidates(any()));

        ResponseEntity<String> response;
        try {
            response = rawMine(token);
        } finally {
            for (HeldDbLock holder : holders) {
                holder.close();
            }
        }

        assertThat(holders).as("the candidate lock was contended").isNotEmpty();
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(salonSlugOf(salonId)).isNull();
    }

    @Test
    @DisplayName("376 audit: a legacy salon deleted between the caller's read and the issue → its link omitted (200), the other salon issued")
    void should_omitSalonLink_when_salonDeletedBeforeSlugIssue() throws Exception {
        UUID keptSalon = BookableMasterSeeder.insertSalon(jdbcTemplate, "A Zalyshenyi");
        UUID ownerMasterId = insertOwnerMaster(keptSalon);
        UUID deletedSalon = insertSiblingSalon(ownerOf(keptSalon), "B Vydalenyi");
        // Stubbed inside a transaction: the lock method is MANDATORY and the stubbing call passes its interceptor.
        transactionTemplate.executeWithoutResult(status -> doAnswer(invocation -> {
            // Committed on another connection: the delete lands after the read, before the row lock.
            CompletableFuture.runAsync(() -> jdbcTemplate.update("DELETE FROM salons WHERE id = ?", deletedSalon))
                    .get(30, TimeUnit.SECONDS);
            return RepositorySpies.passThrough(salonRepository, invocation);
        }).when(salonRepository).lockSalonIdsWithoutBookingSlug(anyCollection()));

        ResponseEntity<String> response = rawMine(masterToken(ownerMasterId, Role.SALON_OWNER));

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode links = objectMapper.readTree(response.getBody()).path("data").path("links");
        assertThat(kinds(links)).containsExactly("SALON");
        assertThat(links.get(0).path("salonId").asText()).isEqualTo(keptSalon.toString());
        assertThat(links.get(0).path("slug").asText()).isEqualTo(salonSlugOf(keptSalon));
    }
}
