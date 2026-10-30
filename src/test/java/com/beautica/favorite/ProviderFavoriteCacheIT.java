package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.favorite.dto.AddFavoriteRequest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.favorite.service.ProviderFavoriteDecorator;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, against a REAL Postgres and the REAL Caffeine caches, that the per-client
 * {@code isFavorite} on the master profile, salon profile and search cards never leaks between
 * callers, is never itself written into a shared cache, and costs one favourites query per search
 * page regardless of page size (no N+1).
 *
 * <p>Every leak assertion warms the shared cache as the favouriting client FIRST and then reads
 * as a second client / an anonymous caller with no cache clear in between — moving the decoration
 * inside a {@code @Cacheable} method turns those assertions RED.
 */
@Import(TestSecurityConfig.class)
@DisplayName("isFavorite on master/salon profiles + search — cache isolation and one-query batching")
class ProviderFavoriteCacheIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ProviderFavoriteDecorator providerFavoriteDecorator;

    @Autowired
    private FavoriteRepository favoriteRepository;

    // ── master profile ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{id}: A (favourited) sees true; B and anonymous on the SAME warm cache "
            + "see false; the cached entry holds false")
    void should_notLeakMasterFavorite_when_otherCallersReadWarmCache() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Profilemaster");
        UUID clientA = createClient("pf-master-a@beautica.test");
        UUID clientB = createClient("pf-master-b@beautica.test");
        favorite(clientA, "MASTER", masterId);
        String tokenA = login("pf-master-a@beautica.test");
        String tokenB = login("pf-master-b@beautica.test");

        boolean asA = getData("/api/v1/masters/" + masterId, tokenA).path("isFavorite").asBoolean();
        boolean asB = getData("/api/v1/masters/" + masterId, tokenB).path("isFavorite").asBoolean();
        JsonNode anonymous = getData("/api/v1/masters/" + masterId, null);

        assertThat(asA).isTrue();
        assertThat(asB).as("B favourited nothing — A's flag must not leak via the cache").isFalse();
        assertThat(anonymous.path("isFavorite").isBoolean()).isTrue();
        assertThat(anonymous.path("isFavorite").asBoolean()).isFalse();
        MasterDetailResponse cached = (MasterDetailResponse) cacheManager.getCache("master-detail")
                .get(masterId).get();
        assertThat(cached.isFavorite()).as("the shared cache entry never carries a per-client flag").isFalse();
    }

    // ── salon profile ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /salons/{id}: A (favourited) sees true; B and anonymous on the SAME warm cache "
            + "see false; the cached entry holds false")
    void should_notLeakSalonFavorite_when_otherCallersReadWarmCache() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Profile Salon");
        UUID clientA = createClient("pf-salon-a@beautica.test");
        createClient("pf-salon-b@beautica.test");
        favorite(clientA, "SALON", salonId);
        String tokenA = login("pf-salon-a@beautica.test");
        String tokenB = login("pf-salon-b@beautica.test");

        boolean asA = getData("/api/v1/salons/" + salonId, tokenA).path("isFavorite").asBoolean();
        boolean asB = getData("/api/v1/salons/" + salonId, tokenB).path("isFavorite").asBoolean();
        boolean anonymous = getData("/api/v1/salons/" + salonId, null).path("isFavorite").asBoolean();

        assertThat(asA).isTrue();
        assertThat(asB).isFalse();
        assertThat(anonymous).isFalse();
        PublicSalonResponse cached = (PublicSalonResponse) cacheManager.getCache("salon-detail")
                .get(salonId).get();
        assertThat(cached.isFavorite()).isFalse();
    }

    // ── search cards ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /search/masters + /search/salons: A's cards flagged, B's and anonymous cards on "
            + "the SAME warm cache all false; every cached search page holds false")
    void should_notLeakSearchFavorites_when_otherCallersReadWarmCache() throws Exception {
        List<UUID> masters = seedBookableMasters("Zirkaleak", 3);
        UUID salon = BookableMasterSeeder.insertSalon(jdbcTemplate, "Zirkaleak Studio");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon);
        UUID clientA = createClient("pf-search-a@beautica.test");
        createClient("pf-search-b@beautica.test");
        favorite(clientA, "MASTER", masters.get(1));
        favorite(clientA, "SALON", salon);
        String tokenA = login("pf-search-a@beautica.test");
        String tokenB = login("pf-search-b@beautica.test");

        Map<String, Boolean> mastersAsA = flags("/api/v1/search/masters?q=Zirkaleak", "masterId", tokenA);
        Map<String, Boolean> mastersAsB = flags("/api/v1/search/masters?q=Zirkaleak", "masterId", tokenB);
        Map<String, Boolean> mastersAnon = flags("/api/v1/search/masters?q=Zirkaleak", "masterId", null);
        Map<String, Boolean> salonsAsA = flags("/api/v1/search/salons?q=Zirkaleak", "salonId", tokenA);
        Map<String, Boolean> salonsAsB = flags("/api/v1/search/salons?q=Zirkaleak", "salonId", tokenB);

        assertThat(mastersAsA).containsOnlyKeys(masters.stream().map(UUID::toString).toList());
        assertThat(mastersAsA).containsEntry(masters.get(1).toString(), true)
                .containsEntry(masters.get(0).toString(), false)
                .containsEntry(masters.get(2).toString(), false);
        assertThat(mastersAsB.values()).hasSize(3).containsOnly(false);
        assertThat(mastersAnon.values()).hasSize(3).containsOnly(false);
        assertThat(salonsAsA).containsEntry(salon.toString(), true);
        assertThat(salonsAsB.values()).isNotEmpty().containsOnly(false);
        assertThat(cachedSearchCards(SearchCacheNames.MASTERS_QUERY)).isNotEmpty()
                .allSatisfy(card -> assertThat(((MasterSearchResult) card).isFavorite()).isFalse());
        assertThat(cachedSearchCards(SearchCacheNames.SALONS_QUERY)).isNotEmpty()
                .allSatisfy(card -> assertThat(((SalonSearchResult) card).isFavorite()).isFalse());
    }

    // ── favourite lifecycle through the REAL favourites API ─────────────────────

    @Test
    @DisplayName("favourited via POST /favorites in an EARLIER session (caches already warm with false): "
            + "a fresh login sees isFavorite=true on master profile, salon profile and both search cards")
    void should_showFavoriteOnAllFourRoutes_when_favoritedInEarlierSession() throws Exception {
        Lifecycle fixture = seedLifecycle("Zirkasession", "pf-session@beautica.test");
        // Warm every shared cache with the caller-agnostic (false) value BEFORE favouriting.
        assertThat(readAllFour(fixture, null).values()).as("anonymous warm-up").hasSize(4).containsOnly(false);
        String earlierSession = login(fixture.email());
        postFavorite(earlierSession, FavoriteTargetType.MASTER, fixture.masterId());
        postFavorite(earlierSession, FavoriteTargetType.SALON, fixture.salonId());

        String laterSession = login(fixture.email());
        Map<String, Boolean> flags = readAllFour(fixture, laterSession);

        assertThat(flags).as("isFavorite per route for the favouriting client, actual=%s", flags)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "masterProfile", true, "salonProfile", true,
                        "masterCard", true, "salonCard", true));
    }

    @Test
    @DisplayName("after DELETE /favorites the very next read shows isFavorite=false on all four routes — "
            + "no stale per-client value survives the warm true read")
    void should_showNotFavoriteOnAllFourRoutes_when_unfavoritedAfterWarmRead() throws Exception {
        Lifecycle fixture = seedLifecycle("Zirkaunfav", "pf-unfav@beautica.test");
        String token = login(fixture.email());
        postFavorite(token, FavoriteTargetType.MASTER, fixture.masterId());
        postFavorite(token, FavoriteTargetType.SALON, fixture.salonId());
        assertThat(readAllFour(fixture, token).values()).as("precondition: favourited on every route")
                .hasSize(4).containsOnly(true);

        deleteFavorite(token, FavoriteTargetType.MASTER, fixture.masterId());
        deleteFavorite(token, FavoriteTargetType.SALON, fixture.salonId());
        Map<String, Boolean> flags = readAllFour(fixture, token);

        assertThat(flags).as("isFavorite per route after unfavourite, actual=%s", flags)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "masterProfile", false, "salonProfile", false,
                        "masterCard", false, "salonCard", false));
    }

    @Test
    @DisplayName("no N+1: a warm 4-card search page costs a CLIENT the same statements as a warm 1-card "
            + "page, and decorating a page issues exactly ONE favourites query")
    void should_issueOneFavoritesQueryPerPage_when_clientSearches() throws Exception {
        List<UUID> many = seedBookableMasters("Zirkamany", 4);
        UUID single = seedBookableMasters("Zirkaone", 1).get(0);
        UUID clientId = createClient("pf-nplus1@beautica.test");
        favorite(clientId, "MASTER", many.get(0));
        favorite(clientId, "MASTER", single);
        String token = login("pf-nplus1@beautica.test");
        // Warm both search caches so only the per-request work is measured below.
        flags("/api/v1/search/masters?q=Zirkamany", "masterId", token);
        flags("/api/v1/search/masters?q=Zirkaone", "masterId", token);
        Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);

        long beforeMany = statistics.getPrepareStatementCount();
        Map<String, Boolean> manyFlags = flags("/api/v1/search/masters?q=Zirkamany", "masterId", token);
        long manyStatements = statistics.getPrepareStatementCount() - beforeMany;
        long beforeOne = statistics.getPrepareStatementCount();
        Map<String, Boolean> oneFlags = flags("/api/v1/search/masters?q=Zirkaone", "masterId", token);
        long oneStatements = statistics.getPrepareStatementCount() - beforeOne;
        long beforeDirect = statistics.getPrepareStatementCount();
        List<MasterSearchResult> decorated = providerFavoriteDecorator.decorateMasters(
                cards(many), clientAuthentication(clientId));
        long directStatements = statistics.getPrepareStatementCount() - beforeDirect;

        assertThat(manyFlags).hasSize(4).containsEntry(many.get(0).toString(), true);
        assertThat(oneFlags).containsExactlyEntriesOf(Map.of(single.toString(), true));
        assertThat(manyStatements)
                .as("statements for a 4-card page must equal a 1-card page — per-card lookups would scale")
                .isEqualTo(oneStatements);
        assertThat(directStatements).as("one batched IN-query for the whole page").isEqualTo(1L);
        assertThat(decorated).extracting(MasterSearchResult::isFavorite).containsExactly(true, false, false, false);
    }

    // ── response headers — per-viewer bodies must never be stored by a shared cache ──

    @Test
    @DisplayName("all four per-viewer routes answer Cache-Control: no-store + Vary: Authorization through "
            + "the REAL security filter chain, for anonymous and CLIENT callers alike")
    void should_sendNoStoreAndVaryAuthorization_when_readingPerViewerRoutes() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Headermaster");
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Header Salon");
        createClient("pf-headers@beautica.test");
        String token = login("pf-headers@beautica.test");
        List<String> urls = List.of(
                "/api/v1/masters/" + masterId,
                "/api/v1/salons/" + salonId,
                "/api/v1/search/masters?q=Headermaster",
                "/api/v1/search/salons?q=Header");

        for (String url : urls) {
            for (String caller : new String[]{null, token}) {
                HttpHeaders headers = get(url, caller).getHeaders();

                assertThat(headers.getCacheControl()).as("Cache-Control on %s", url).isEqualTo("no-store");
                // Header field names in Vary are case-insensitive; the merged CORS value is lower-cased.
                assertThat(String.join(",", headers.getVary()))
                        .as("Vary on %s", url).containsIgnoringCase("Authorization");
            }
        }
    }

    // ── profile EXISTS probe — correct and index-only ───────────────────────────

    @Test
    @DisplayName("isFavorited: true only for the caller's own row of the SAME target type and id")
    void should_matchOnlyOwnRowOfSameType_when_probingFavorite() {
        UUID clientA = createClient("pf-exists-a@beautica.test");
        UUID clientB = createClient("pf-exists-b@beautica.test");
        UUID target = UUID.randomUUID();
        favorite(clientA, "MASTER", target);

        assertThat(favoriteRepository.isFavorited(clientA, FavoriteTargetType.MASTER, target)).isTrue();
        assertThat(favoriteRepository.isFavorited(clientB, FavoriteTargetType.MASTER, target)).isFalse();
        assertThat(favoriteRepository.isFavorited(clientA, FavoriteTargetType.SALON, target))
                .as("target_id is polymorphic — the type predicate is load-bearing").isFalse();
        assertThat(favoriteRepository.isFavorited(clientA, FavoriteTargetType.MASTER, UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("the profile EXISTS probe is answerable from uq_favorite alone (Index Only Scan)")
    void should_planIndexOnlyScanOnUqFavorite_when_probingFavorite() {
        String explainSql = "EXPLAIN " + FavoriteRepository.FAVORITE_EXISTS_SQL
                .replace(":clientId", "?").replace(":targetType", "?").replace(":targetId", "?");

        String plan = jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                hideCompetingFavoriteIndexes(connection);
                try (PreparedStatement explain = connection.prepareStatement(explainSql)) {
                    explain.setObject(1, UUID.randomUUID());
                    explain.setString(2, FavoriteTargetType.MASTER.name());
                    explain.setObject(3, UUID.randomUUID());
                    return readPlan(explain);
                }
            } finally {
                // Postgres DDL is transactional: the dropped indexes come back here.
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });

        // A form that selects a non-indexed column (the derived existsBy… renders `SELECT f.id`)
        // plans as a plain "Index Scan" here, so this pins "every column is in uq_favorite".
        assertThat(plan).contains("Index Only Scan using uq_favorite");
    }

    /**
     * Inside the caller's transaction: forbids seq/bitmap scans (tiny test tables make them
     * cheapest) and drops every other {@code favorites} index, so the plan shows whether
     * {@code uq_favorite} ALONE can answer the probe — not which index the planner happens to
     * prefer on an un-analysed test table.
     */
    private static void hideCompetingFavoriteIndexes(java.sql.Connection connection) throws java.sql.SQLException {
        List<String> others = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'favorites' "
                     + "AND indexname NOT IN ('uq_favorite', 'favorites_pkey')")) {
            while (rows.next()) {
                others.add(rows.getString(1));
            }
        }
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("SET LOCAL enable_seqscan = off");
            ddl.execute("SET LOCAL enable_bitmapscan = off");
            for (String index : others) {
                ddl.execute("DROP INDEX \"" + index + "\"");
            }
        }
    }

    private static String readPlan(PreparedStatement explain) throws java.sql.SQLException {
        StringBuilder lines = new StringBuilder();
        try (ResultSet rows = explain.executeQuery()) {
            while (rows.next()) {
                lines.append(rows.getString(1)).append('\n');
            }
        }
        return lines.toString();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private record Lifecycle(UUID masterId, UUID salonId, String searchTerm, String email) {
    }

    /** A bookable independent master + an active salon with a bookable master, both searchable by
     * {@code term}, and a CLIENT that has favourited nothing yet. */
    private Lifecycle seedLifecycle(String term, String email) {
        UUID masterId = seedBookableMasters(term, 1).get(0);
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, term + " Studio");
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salonId);
        createClient(email);
        return new Lifecycle(masterId, salonId, term, email);
    }

    /** isFavorite for the fixture's master/salon on each of the four per-viewer routes. */
    private Map<String, Boolean> readAllFour(Lifecycle fixture, String tokenOrNull) throws Exception {
        Map<String, Boolean> masterCards = flags(
                "/api/v1/search/masters?q=" + fixture.searchTerm(), "masterId", tokenOrNull);
        Map<String, Boolean> salonCards = flags(
                "/api/v1/search/salons?q=" + fixture.searchTerm(), "salonId", tokenOrNull);
        assertThat(masterCards).as("master card on the search page").containsKey(fixture.masterId().toString());
        assertThat(salonCards).as("salon card on the search page").containsKey(fixture.salonId().toString());
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("masterProfile", profileFlag("/api/v1/masters/" + fixture.masterId(), tokenOrNull));
        flags.put("salonProfile", profileFlag("/api/v1/salons/" + fixture.salonId(), tokenOrNull));
        flags.put("masterCard", masterCards.get(fixture.masterId().toString()));
        flags.put("salonCard", salonCards.get(fixture.salonId().toString()));
        return flags;
    }

    private boolean profileFlag(String url, String tokenOrNull) throws Exception {
        JsonNode isFavorite = getData(url, tokenOrNull).path("isFavorite");
        assertThat(isFavorite.isBoolean()).as("isFavorite always present on %s", url).isTrue();
        return isFavorite.asBoolean();
    }

    private void postFavorite(String token, FavoriteTargetType type, UUID targetId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = objectMapper.writeValueAsString(new AddFavoriteRequest(type, targetId));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/favorites", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).as("POST /favorites %s %s, body=%s", type, targetId, resp.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private void deleteFavorite(String token, FavoriteTargetType type, UUID targetId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/favorites?targetType={type}&targetId={id}", HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class, type.name(), targetId);
        assertThat(resp.getStatusCode()).as("DELETE /favorites %s %s", type, targetId)
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private List<UUID> seedBookableMasters(String name, int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            UUID id = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, name);
            BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, id);
            return id;
        }).toList();
    }

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD));
        return id;
    }

    private void favorite(UUID clientId, String targetType, UUID targetId) {
        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, ?, ?)",
                clientId, targetType, targetId);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<AuthResponse>>() {}).data().accessToken();
    }

    private JsonNode getData(String url, String tokenOrNull) throws Exception {
        return objectMapper.readTree(get(url, tokenOrNull).getBody()).path("data");
    }

    private ResponseEntity<String> get(String url, String tokenOrNull) {
        HttpHeaders headers = new HttpHeaders();
        if (tokenOrNull != null) {
            headers.setBearerAuth(tokenOrNull);
        }
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).as("GET %s", url).isEqualTo(HttpStatus.OK);
        return resp;
    }

    /** id → isFavorite for every card on the page. */
    private Map<String, Boolean> flags(String url, String idField, String tokenOrNull) throws Exception {
        Map<String, Boolean> flags = new HashMap<>();
        for (JsonNode card : getData(url, tokenOrNull).path("data")) {
            assertThat(card.path("isFavorite").isBoolean()).as("isFavorite always present").isTrue();
            flags.put(card.path(idField).asText(), card.path("isFavorite").asBoolean());
        }
        return flags;
    }

    private List<Object> cachedSearchCards(String cacheName) {
        CaffeineCache cache = (CaffeineCache) cacheManager.getCache(cacheName);
        assertThat(cache).as("%s must be a registered Caffeine cache", cacheName).isNotNull();
        List<Object> cards = new ArrayList<>();
        for (Object value : cache.getNativeCache().asMap().values()) {
            if (value instanceof Page<?> page) {
                cards.addAll(page.getContent());
            } else if (value instanceof Collection<?> list) {
                cards.addAll(list);
            }
        }
        return cards;
    }

    private static List<MasterSearchResult> cards(List<UUID> masterIds) {
        return masterIds.stream()
                .map(id -> new MasterSearchResult(id, "Z", "Z", null, null, null, 0, null,
                        BigDecimal.ONE, BigDecimal.ONE, List.of(), null, null, null, List.of()))
                .toList();
    }

    private static UsernamePasswordAuthenticationToken clientAuthentication(UUID clientId) {
        var token = new UsernamePasswordAuthenticationToken(
                "client", null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
        token.setDetails(clientId);
        return token;
    }
}
