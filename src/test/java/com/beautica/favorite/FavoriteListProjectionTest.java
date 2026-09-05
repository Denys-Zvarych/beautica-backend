package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.service.FavoriteService;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No-N+1 statement-count guard for the two paginated favorites lists the controller
 * uses ({@link FavoriteService#listMasterFavorites(UUID, org.springframework.data.domain.Pageable)}
 * and {@link FavoriteService#listSalonFavorites(UUID, org.springframework.data.domain.Pageable)}).
 *
 * <p>Mirrors the reference pattern in
 * {@code com.beautica.booking.repository.ClientBookingDetailProjectionTest}: the test
 * asserts the Hibernate {@link Statistics#getPrepareStatementCount()} for a page is
 * BOUNDED and INDEPENDENT of the number of favorited rows. Because the service composes
 * a JPA/native projection page (content + count) with a single batched
 * {@code DiscoveryLocationResolver.resolveLabels} call and a single batched category
 * derivation ({@code FavoriteCategoryResolver}), the prepared-statement count must be a small
 * constant regardless of N favorited masters/salons. A per-row "latest booking" lookup or a
 * per-row label query would make the count scale with N — this guard fails the build if that
 * regresses.
 *
 * <p>The two list guards below therefore measure at TWO different N and assert the counts match
 * before asserting the value, so "batched" is proven rather than inferred from one magic number.
 *
 * <p>Runs the real service + resolver against the full Testcontainers context
 * ({@link AbstractIntegrationTest}) because {@code FavoriteService} is not a
 * {@code @DataJpaTest} bean. ASCII-only seed data throughout.
 */
@DisplayName("Favorites — paginated list no-N+1 statement-count guard (Testcontainers)")
class FavoriteListProjectionTest extends AbstractIntegrationTest {

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private EntityManagerFactory emf;

    private Statistics statistics() {
        SessionFactory sessionFactory = emf.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

    // ── master favorites — bounded statement count, independent of N ──────────────

    /**
     * <b>Two statements per page, and — the part that actually matters — the SAME two at any N.</b>
     *
     * <p>Measuring one fixed N can only pin a magic number; it cannot distinguish "batched" from
     * "happens to be 2 when N is 5". So this runs the list at two different N and asserts the
     * counts are EQUAL to each other before asserting the value. A per-row regression fails the
     * equality assertion with a diagnostic that names both N, rather than failing an opaque
     * constant.
     *
     * <p><b>Why the exact value moved from 1 to 2.</b> The favourites screen gained a category
     * FILTER axis ({@code categories}), derived from every distinct platform category each
     * provider actually OFFERS (an active service in an active assignment) — reversed from an
     * earlier design that derived it from the client's most recent booking with each provider;
     * see {@code FavoriteCategoryResolver}'s class javadoc for the full rationale. That
     * derivation is a SECOND statement, issued once per page against the ids the projection
     * already returned — deliberately not a term in the list query itself. The obvious
     * alternative, a correlated {@code LATERAL} inside the projection, is exactly what the
     * deleted {@code lastServiceName} did: it cost 9.11 ms a page, and removing it took the page
     * to 0.089 ms. Keeping the derivation in its own page-bounded statement preserves that.
     *
     * <p>The label half of the axis adds NOTHING here: it resolves off the already-{@code
     * @Cacheable} {@code platform-category-order} list, so it issues no statement at all. If this
     * ledger ever reads 3, a cached read has become a query.
     *
     * <p>Still EXACT, not a ceiling: at {@code <= 3} a 2 -> 3 regression (an added
     * {@code JOIN FETCH}, or the category label going to the database) would pass unnoticed.
     */
    @Test
    @DisplayName("listMasterFavorites runs the same bounded statement count at any number of favorited masters")
    void should_runBoundedStatementCount_when_listingManyMasterFavorites() {
        long atTwo = countMasterListStatements("small", 2);
        long atFive = countMasterListStatements("large", 5);

        assertThat(atTwo)
                .as("the statement count must not grow with the page's row count — got %s for 2 "
                        + "favorited masters and %s for 5, which is the signature of a per-row "
                        + "lookup rather than a batched one", atTwo, atFive)
                .isEqualTo(atFive);
        assertThat(atFive)
                .as("EXACTLY two: the one content query (the count is skipped on a short page, and "
                        + "the locality label resolve is batched away when no row carries one) plus "
                        + "the one batched category derivation; got %s", atFive)
                .isEqualTo(2);
    }

    /**
     * Seeds {@code n} favorited independent masters for a fresh client — each with an active
     * service, so the category derivation has a real offering to resolve and cannot report a
     * flattering count by finding nothing to do — then measures one page. A COMPLETED booking is
     * also seeded per master; it is no longer what the category derivation reads (that reversed
     * to the offering, not the booking), but it is kept so this fixture still exercises a
     * favourited master with real booking history too.
     */
    private long countMasterListStatements(String tag, int n) {
        UUID clientId = createClient("fav-masters-" + tag + "@beautica.test");
        for (int i = 0; i < n; i++) {
            UUID master = createIndependentMaster("fav-master-" + tag + "-" + i + "@beautica.test");
            UUID ms = createIndependentMasterService(master);
            createCompletedBooking(clientId, master, ms);
            favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, master);
        }

        Statistics stats = statistics();
        stats.clear();
        long rows = favoriteService.listMasterFavorites(clientId, PageRequest.of(0, 20))
                .getContent().size();
        long statementCount = stats.getPrepareStatementCount();

        assertThat(rows)
                .as("the measurement is only meaningful if the page actually returned %s rows", n)
                .isEqualTo(n);
        return statementCount;
    }

    // ── salon favorites — bounded statement count, independent of N ───────────────

    /**
     * Salon counterpart of the master ledger above — same two-N method, same "moved from 1 to 2"
     * category-derivation history. See that test's javadoc for both.
     *
     * <p><b>A further, salon-only bump: 2 -&gt; 3 (V150, "a salon must always have a city").</b>
     * The master ledger's "locality label resolve is batched away when no row carries one" stays
     * true for masters — {@code createIndependentMaster} still leaves the master-user's personal
     * {@code cityId} unset, and {@code users.city_id} is untouched by V150. It is no longer true
     * for salons: every salon test fixture (including {@link #createSalon}) now sets a real,
     * non-null {@code cityId} — required for persistence at all, since {@code salons.city_id} is
     * DB-level {@code NOT NULL} as of V150 — so the locality label batch is now ALWAYS exercised
     * here, exactly as it will be in production for every real salon. Still EXACT, not a ceiling:
     * a 3 -&gt; 4 regression would pass unnoticed at {@code <= 4}.
     */
    @Test
    @DisplayName("listSalonFavorites runs the same bounded statement count at any number of favorited salons")
    void should_runBoundedStatementCount_when_listingManySalonFavorites() {
        long atTwo = countSalonListStatements("small", 2);
        long atFive = countSalonListStatements("large", 5);

        assertThat(atTwo)
                .as("got %s statements for 2 favorited salons and %s for 5 — a count that tracks "
                        + "the row count is a per-row lookup", atTwo, atFive)
                .isEqualTo(atFive);
        assertThat(atFive)
                .as("EXACTLY three: the content query, the locality label resolve (every salon has "
                        + "a real cityId as of V150, so this is no longer batched away), and the "
                        + "one batched category derivation; got %s", atFive)
                .isEqualTo(3);
    }

    private long countSalonListStatements(String tag, int n) {
        UUID clientId = createClient("fav-salons-" + tag + "@beautica.test");
        for (int i = 0; i < n; i++) {
            UUID salon = createSalon("fav-salon-owner-" + tag + "-" + i + "@beautica.test");
            favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salon);
        }

        Statistics stats = statistics();
        stats.clear();
        long rows = favoriteService.listSalonFavorites(clientId, PageRequest.of(0, 20))
                .getContent().size();
        long statementCount = stats.getPrepareStatementCount();

        assertThat(rows).isEqualTo(n);
        return statementCount;
    }

    // ── wish list (SERVICE favorites) — statement count AND entity-load shape ─────
    //
    // GET /favorites/services was measured at 1 statement for a short page (the count query is
    // skipped when the page is not full) and 2 when paged, with ZERO Salon and ZERO User entity
    // loads: FavoriteRepository#findFavoriteServiceRows projects the master's identity as SCALARS
    // rather than hydrating a credential-bearing users row (§I), and never touches the salon.
    // Those properties were unpinned — a future `JOIN FETCH m.salon` or `m.user` added to that
    // query (as was just done to the master-scoped booking finders) would regress them silently.
    // The seed deliberately uses a SALON_MASTER's service, which IS a valid wish-list target, so
    // a salon fetch would actually have a salon to load and the assertion can catch it.

    @Test
    @DisplayName("listServiceFavorites runs a bounded statement count and hydrates no Salon or User rows")
    void should_runBoundedStatementCountAndLoadNoSalonOrUser_when_listingWishList() {
        UUID clientId = createClient("fav-services-client@beautica.test");

        int n = 5;
        for (int i = 0; i < n; i++) {
            UUID salon = createSalon("fav-svc-owner-" + i + "@beautica.test");
            UUID master = createSalonMaster("fav-svc-master-" + i + "@beautica.test", salon);
            UUID ms = createSalonMasterService(master, salon);
            favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, ms);
        }

        Statistics stats = statistics();
        stats.clear();
        long rows = favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 20))
                .getContent().size();
        long statementCount = stats.getPrepareStatementCount();

        assertThat(rows).isEqualTo(n);
        assertThat(statementCount)
                .as("listServiceFavorites must run EXACTLY the one content query on a short page; "
                        + "got %s for %s favorited services. Exact, not a ceiling — a <= 4 bound "
                        + "tolerates the very 1 -> 3 regression the comment above warns about",
                        statementCount, n)
                .isEqualTo(1);
        assertThat(stats.getEntityStatistics(Salon.class.getName()).getLoadCount())
                .as("the wish list must not hydrate Salon rows — adding JOIN FETCH m.salon to "
                        + "findFavoriteServiceRows would regress the measured 0")
                .isZero();
        assertThat(stats.getEntityStatistics(User.class.getName()).getLoadCount())
                .as("the wish list projects the master's name/avatar as scalars; hydrating a "
                        + "credential-bearing User row would regress the measured 0 (§I)")
                .isZero();
    }

    @Test
    @DisplayName("listServiceFavorites applies the LIMIT in SQL — a full page costs one extra count query, no more")
    void should_applyLimitInSql_when_wishListIsPaged() {
        UUID clientId = createClient("fav-services-paged@beautica.test");

        int n = 5;
        for (int i = 0; i < n; i++) {
            UUID salon = createSalon("fav-paged-owner-" + i + "@beautica.test");
            UUID master = createSalonMaster("fav-paged-master-" + i + "@beautica.test", salon);
            UUID ms = createSalonMasterService(master, salon);
            favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, ms);
        }

        Statistics stats = statistics();
        stats.clear();
        // A page SMALLER than the total forces the count query and proves the window is applied by
        // the database, not by trimming a fully-materialised list in memory.
        var page = favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 2));
        long statementCount = stats.getPrepareStatementCount();

        assertThat(page.getContent())
                .as("the LIMIT must be applied in SQL — a larger result means the page was sliced "
                        + "in memory after loading every wish-listed row")
                .hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(n);
        assertThat(statementCount)
                .as("a paged wish list costs EXACTLY the content query plus the count query; got %s",
                        statementCount)
                .isEqualTo(2);
        assertThat(stats.getEntityStatistics(Salon.class.getName()).getLoadCount()).isZero();
        assertThat(stats.getEntityStatistics(User.class.getName()).getLoadCount()).isZero();
    }

    // ── seed helpers (ASCII data) ────────────────────────────────────────────────

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                id, email);
        return id;
    }

    private UUID createSalon(String ownerEmail) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)",
                ownerId, ownerEmail);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, 'Test Salon', true, NOW(), NOW(), ?)",
                salonId, ownerId, testCityId());
        return salonId;
    }

    /**
     * A SALON-employed master (salon_id NOT NULL) — deliberately not an independent one, so the
     * wish-list guard's {@code Salon} load-count assertion has a salon it COULD hydrate.
     */
    private UUID createSalonMaster(String email, UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    /** A SALON-owned service definition assigned to {@code masterId}. */
    private UUID createSalonMasterService(UUID masterId, UUID salonId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private void createCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        jdbcTemplate.update(
                "INSERT INTO bookings "
                        + "(id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', "
                        + "NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId);
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }
}
