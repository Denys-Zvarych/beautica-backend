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
 * {@code DiscoveryLocationResolver.resolveLabels} call, the prepared-statement count must
 * be a small constant regardless of N favorited masters/salons. A per-row "latest booking"
 * lookup or a per-row label query would make the count scale with N — this guard fails the
 * build if that regresses.
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

    @Test
    @DisplayName("listMasterFavorites runs a bounded statement count independent of the number of favorited masters")
    void should_runBoundedStatementCount_when_listingManyMasterFavorites() {
        UUID clientId = createClient("fav-masters-client@beautica.test");

        // N >= 3 favorited independent masters, each with a completed booking for this
        // client so the per-row "latest booking service name" LATERAL is exercised.
        int n = 5;
        for (int i = 0; i < n; i++) {
            UUID master = createIndependentMaster("fav-master-" + i + "@beautica.test");
            UUID ms = createIndependentMasterService(master);
            createCompletedBooking(clientId, master, ms);
            favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, master);
        }

        Statistics stats = statistics();
        stats.clear();
        long rows = favoriteService.listMasterFavorites(clientId, PageRequest.of(0, 20))
                .getContent().size();
        long statementCount = stats.getPrepareStatementCount();

        assertThat(rows).isEqualTo(n);
        assertThat(statementCount)
                .as("listMasterFavorites must run EXACTLY the one content query (the count is "
                        + "skipped on a short page, and the label resolve is batched away when no row "
                        + "carries a locality); got %s for %s favorited masters. An exact bound, not a "
                        + "ceiling: at <= 4 a 1 -> 3 regression such as an added JOIN FETCH or a "
                        + "per-row lookup would still pass", statementCount, n)
                .isEqualTo(1);
    }

    // ── salon favorites — bounded statement count, independent of N ───────────────

    @Test
    @DisplayName("listSalonFavorites runs a bounded statement count independent of the number of favorited salons")
    void should_runBoundedStatementCount_when_listingManySalonFavorites() {
        UUID clientId = createClient("fav-salons-client@beautica.test");

        // N >= 3 favorited salons.
        int n = 5;
        for (int i = 0; i < n; i++) {
            UUID salon = createSalon("fav-salon-owner-" + i + "@beautica.test");
            favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salon);
        }

        Statistics stats = statistics();
        stats.clear();
        long rows = favoriteService.listSalonFavorites(clientId, PageRequest.of(0, 20))
                .getContent().size();
        long statementCount = stats.getPrepareStatementCount();

        assertThat(rows).isEqualTo(n);
        assertThat(statementCount)
                .as("listSalonFavorites must run EXACTLY the one content query (count skipped on a "
                        + "short page, label resolve batched away); got %s for %s favorited salons. "
                        + "Exact, not a ceiling — see listMasterFavorites", statementCount, n)
                .isEqualTo(1);
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
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'Test Salon', true, NOW(), NOW())",
                salonId, ownerId);
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
