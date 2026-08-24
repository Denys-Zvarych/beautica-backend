package com.beautica.review;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.dto.FavoriteSalonResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.service.FavoriteService;
import com.beautica.master.event.SalonStaffChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mobile Phase 111 — end-to-end wiring for the SECOND salon-rating recalc trigger, against a real
 * PostgreSQL.
 *
 * <p><b>Why this class exists and a unit test cannot replace it.</b> Every existing test of this
 * feature stops at a seam:
 * <ul>
 *   <li>{@code MasterServiceTest} / {@code MasterServiceRotateTest} verify a MOCK
 *       {@code ApplicationEventPublisher} was called — they prove the publish argument, never that
 *       anything listens.</li>
 *   <li>{@code SalonStaffRatingListenerTest} calls {@code onSalonStaffChanged} DIRECTLY on a
 *       hand-built instance — it proves the method body, never that Spring registers the bean, nor
 *       that {@code @TransactionalEventListener(AFTER_COMMIT)} binds to
 *       {@code SalonStaffChangedEvent}.</li>
 *   <li>{@code ReviewRepositoryTest} calls {@code recalculateSalonRating} directly in a
 *       {@code @DataJpaTest} slice — it proves the SQL, never the trigger.</li>
 * </ul>
 * Delete {@code @Component} from the listener, or narrow its parameter type, and all three stay
 * green while a salon's rating silently freezes forever. This class goes red.
 *
 * <p>It additionally pins the read side the mobile screen actually consumes:
 * {@code FavoriteRepository#findFavoriteSalonRows} stopped computing
 * {@code AVG(reviews.rating)} live and now projects the PERSISTED {@code salons.avg_rating}
 * column, so the favourites list is only ever as correct as this recalc. That coupling — and the
 * {@code CASE WHEN review_count = 0 THEN NULL} branch guarding it — exists nowhere except in
 * native SQL, which no mock can evaluate.
 *
 * <p>ASCII-only seed data; cleanup via {@link AbstractIntegrationTest#cleanDb()}.
 */
@DisplayName("SalonStaffChangedEvent -> SalonStaffRatingListener — full wiring (Phase 111)")
class SalonStaffRatingIT extends AbstractIntegrationTest {

    /**
     * A value the recalc can never produce, written to {@code salons} before the event is
     * published. Every assertion below is therefore a two-sided one: the number must be RIGHT, and
     * distinguishable from "the listener never ran".
     */
    private static final BigDecimal POISON_RATING = new BigDecimal("9.99");
    private static final int POISON_COUNT = 999;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FavoriteService favoriteService;

    // ── the wiring itself ────────────────────────────────────────────────────────

    /**
     * The load-bearing test. A salon whose two masters hold 5 / 1,1,1 must land on the
     * equal-weighted <b>3.00</b>:
     * <ul>
     *   <li>{@code 9.99} → the listener never ran (unregistered bean, wrong event type,
     *       {@code AFTER_COMMIT} never fired);</li>
     *   <li>{@code 2.00} → it ran the pre-Phase-111 flat {@code AVG(reviews.rating)};</li>
     *   <li>{@code 3.00} → the shipped formula, reached through the real publish/listen path.</li>
     * </ul>
     */
    @Test
    @DisplayName("publishing SalonStaffChangedEvent recomputes salons.avg_rating after commit")
    void should_recalculateSalonRating_when_staffChangedEventCommits() {
        UUID clientId = insertClient("staff-event-client@beautica.test");
        UUID salonId = insertSalon("staff-event-owner@beautica.test", "Staff Event Salon");
        UUID high = insertSalonMaster(salonId, "staff-event-high@beautica.test");
        UUID low = insertSalonMaster(salonId, "staff-event-low@beautica.test");
        insertReview(salonId, high, clientId, 5);
        insertReview(salonId, low, clientId, 1);
        insertReview(salonId, low, clientId, 1);
        insertReview(salonId, low, clientId, 1);
        poison(salonId);

        publishStaffChangedInCommittedTransaction(salonId);

        assertThat(readAvgRating(salonId))
                .as("equal-weighted mean of (5.00, 1.00); 2.00 would mean the OLD per-review "
                        + "formula ran, 9.99 that no listener is wired to the event at all")
                .isEqualByComparingTo(new BigDecimal("3.00"));
        assertThat(readReviewCount(salonId))
                .as("the count of underlying reviews that fed the average, not of masters")
                .isEqualTo(4);
    }

    /**
     * {@code AFTER_COMMIT}, not {@code BEFORE_COMMIT} and not inline. The aggregate filters on
     * {@code masters.salon_id} / {@code masters.is_active}, so running it while the staff mutation
     * is still uncommitted would read the pre-change staff set and persist a stale number that
     * nothing later corrects.
     */
    @Test
    @DisplayName("the recalc does not run before the publishing transaction commits")
    void should_notRecalculateUntilCommit_when_staffChangedEventPublished() {
        UUID clientId = insertClient("phase-client@beautica.test");
        UUID salonId = insertSalon("phase-owner@beautica.test", "Phase Salon");
        UUID master = insertSalonMaster(salonId, "phase-master@beautica.test");
        insertReview(salonId, master, clientId, 4);
        poison(salonId);

        BigDecimal seenInsideTransaction = transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new SalonStaffChangedEvent(salonId));
            return readAvgRating(salonId);
        });

        assertThat(seenInsideTransaction)
                .as("still the poison value — an AFTER_COMMIT listener must not have run yet")
                .isEqualByComparingTo(POISON_RATING);
        assertThat(readAvgRating(salonId))
                .as("and it HAS run by the time the transaction is committed")
                .isEqualByComparingTo(new BigDecimal("4.00"));
    }

    /**
     * Per-salon, never global. The recalc is keyed by the event's salon id, so an unrelated salon
     * must keep its own (here: deliberately wrong) number — a {@code WHERE} clause slip that
     * rewrote every salon would otherwise look like a passing test.
     */
    @Test
    @DisplayName("only the event's own salon is recalculated — a sibling salon is untouched")
    void should_recalculateOnlyTheTargetSalon_when_staffChangedEventCommits() {
        UUID clientId = insertClient("scoped-client@beautica.test");
        UUID target = insertSalon("scoped-target-owner@beautica.test", "Target Salon");
        UUID bystander = insertSalon("scoped-bystander-owner@beautica.test", "Bystander Salon");
        UUID targetMaster = insertSalonMaster(target, "scoped-target-master@beautica.test");
        insertReview(target, targetMaster, clientId, 4);
        poison(target);
        poison(bystander);

        publishStaffChangedInCommittedTransaction(target);

        assertThat(readAvgRating(target)).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(readAvgRating(bystander))
                .as("no event named this salon — its row must not have been rewritten")
                .isEqualByComparingTo(POISON_RATING);
        assertThat(readReviewCount(bystander)).isEqualTo(POISON_COUNT);
    }

    // ── the read side the Favourites screen consumes ─────────────────────────────

    /**
     * {@code findFavoriteSalonRows} no longer aggregates {@code reviews} itself — it projects the
     * {@code salons.avg_rating} column this listener maintains. Nothing else asserts that
     * hand-off, and it is the reason a missing publish is user-visible: the favourites card would
     * print an average that never moves again.
     */
    @Test
    @DisplayName("listSalonFavorites surfaces the recalculated rating off the persisted column")
    void should_surfaceRecalculatedRating_when_listingSalonFavorites() {
        UUID clientId = insertClient("fav-rating-client@beautica.test");
        UUID salonId = insertSalon("fav-rating-owner@beautica.test", "Fav Rating Salon");
        UUID high = insertSalonMaster(salonId, "fav-rating-high@beautica.test");
        UUID low = insertSalonMaster(salonId, "fav-rating-low@beautica.test");
        insertReview(salonId, high, clientId, 5);
        insertReview(salonId, low, clientId, 1);
        insertReview(salonId, low, clientId, 1);
        insertReview(salonId, low, clientId, 1);
        poison(salonId);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        publishStaffChangedInCommittedTransaction(salonId);

        List<FavoriteSalonResponse> favorites =
                favoriteService.listSalonFavorites(clientId, Pageable.unpaged()).getContent();
        assertThat(favorites).singleElement()
                .extracting(FavoriteSalonResponse::salonId, FavoriteSalonResponse::avgRating)
                .as("the card reads salons.avg_rating; 2.0 would be the old per-review formula, "
                        + "9.99 a projection that still trusts a never-recalculated column")
                .containsExactly(salonId, 3.0);
    }

    /**
     * The three-in-one regression net: a salon's only reviewed master leaves, so
     * <ol>
     *   <li>the staff change alone (no review written) must move the number —</li>
     *   <li>the aggregate must drop an {@code is_active = false} master's scores, leaving zero
     *       contributors and a persisted {@code 0.00 / 0} —</li>
     *   <li>and the favourites projection's {@code CASE WHEN review_count = 0 THEN NULL} must turn
     *       that into a <b>null</b> rating, not a fabricated {@code 0.0} star.</li>
     * </ol>
     * The pre-state is asserted first so the null is provably a CHANGE, not a fixture that was
     * never populated.
     */
    @Test
    @DisplayName("a favorited salon's rating goes null once its only reviewed master is deactivated")
    void should_returnNullFavoriteRating_when_theOnlyReviewedMasterIsDeactivated() {
        UUID clientId = insertClient("null-rating-client@beautica.test");
        UUID salonId = insertSalon("null-rating-owner@beautica.test", "Null Rating Salon");
        UUID master = insertSalonMaster(salonId, "null-rating-master@beautica.test");
        insertReview(salonId, master, clientId, 5);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);
        publishStaffChangedInCommittedTransaction(salonId);

        assertThat(readFavoriteRating(clientId))
                .as("pre-state: the rating IS populated, so the null below is a real transition")
                .isEqualTo(5.0);

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", master);
        publishStaffChangedInCommittedTransaction(salonId);

        assertThat(readReviewCount(salonId))
                .as("no contributors left — COALESCE writes 0, not NULL, to the column")
                .isZero();
        assertThat(readFavoriteRating(clientId))
                .as("review_count = 0 must project as a NULL rating; a 0.0 would render as a "
                        + "zero-star salon on the Favourites card")
                .isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Publishes inside a real transaction and lets it COMMIT, which is the only way an
     * {@code AFTER_COMMIT} listener fires. {@code TransactionTemplate} rather than a
     * {@code @Transactional} test method: this class must not be transactional itself, or the
     * outer test rollback would swallow the very commit under test.
     */
    private void publishStaffChangedInCommittedTransaction(UUID salonId) {
        transactionTemplate.executeWithoutResult(
                status -> eventPublisher.publishEvent(new SalonStaffChangedEvent(salonId)));
    }

    private void poison(UUID salonId) {
        jdbcTemplate.update("UPDATE salons SET avg_rating = ?, review_count = ? WHERE id = ?",
                POISON_RATING, POISON_COUNT, salonId);
    }

    private BigDecimal readAvgRating(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM salons WHERE id = ?", BigDecimal.class, salonId);
    }

    private int readReviewCount(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT review_count FROM salons WHERE id = ?", Integer.class, salonId);
    }

    private Double readFavoriteRating(UUID clientId) {
        return favoriteService.listSalonFavorites(clientId, Pageable.unpaged())
                .getContent().get(0).avgRating();
    }

    private UUID insertClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                id, email);
        return id;
    }

    private UUID insertSalon(String ownerEmail, String name) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)",
                ownerId, ownerEmail);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 0.00, 0, true, NOW(), NOW())",
                salonId, ownerId, name);
        return salonId;
    }

    /**
     * A salon-employed master plus the ONE salon-owned service definition and assignment its
     * bookings hang off. One definition per master, never per review: V121's partial UNIQUE on
     * {@code (owner_type, owner_id, service_type_id) WHERE is_active} would reject a second ACTIVE
     * definition for the same salon and service type, and the seeded selectable types are finite.
     */
    private UUID insertSalonMaster(UUID salonId, String email) {
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

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Rating Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId));
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId);
        return masterId;
    }

    /**
     * One COMPLETED booking and its review. A fresh booking per review is mandatory —
     * {@code reviews} carries a UNIQUE on {@code booking_id} (the locked "review unit is the
     * BOOKING" decision) — while the master's single {@code master_services} row is reused, which
     * carries no uniqueness.
     */
    private void insertReview(UUID salonId, UUID masterId, UUID clientId, int rating) {
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId, rating);
    }
}
