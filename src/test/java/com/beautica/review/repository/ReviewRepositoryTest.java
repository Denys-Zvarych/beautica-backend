package com.beautica.review.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.review.entity.Review;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.beautica.review.dto.MyReviewResponse;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import jakarta.persistence.PersistenceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReviewRepository")
class ReviewRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private MasterRepository masterRepository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private EntityManagerFactory emf;

    private User clientUser;
    private Master master;
    private Booking completedBooking;

    @BeforeEach
    void setUp() {
        clientUser = new User(
                "client-" + UUID.randomUUID() + "@test.com",
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                Role.CLIENT,
                "Client",
                "User",
                "+380501111111"
        );
        em.persist(clientUser);

        User masterUser = new User(
                "master-" + UUID.randomUUID() + "@test.com",
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                Role.INDEPENDENT_MASTER,
                "Master",
                "User",
                "+380502222222"
        );
        em.persist(masterUser);

        master = Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master);

        ServiceType defaultServiceType = persistServiceType();

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(serviceDefinition);

        MasterServiceAssignment masterService = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(masterService);

        completedBooking = Booking.builder()
                .client(clientUser)
                .master(master)
                .masterService(masterService)
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 6, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(completedBooking);

        em.flush();
    }

    private static final java.util.concurrent.atomic.AtomicInteger SORT_ORDER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(90_000);

    /**
     * Persists a CatalogCategory + ServiceType so fixture ServiceDefinitions satisfy the
     * NOT NULL service_type_id FK. sortOrder is unique per call (uq_service_categories_sort_order) —
     * some tests call this helper more than once per transaction (e.g. a second master's
     * ServiceDefinition), so a fixed sortOrder would collide.
     */
    private ServiceType persistServiceType() {
        CatalogCategory category = CatalogCategory.builder()
                .nameUk("Нігті")
                .nameEn("Nails")
                .sortOrder(SORT_ORDER_SEQ.getAndIncrement())
                .build();
        em.persist(category);

        ServiceType serviceType = ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build();
        em.persist(serviceType);
        return serviceType;
    }

    @Test
    @DisplayName("should_returnTrue_when_reviewExistsForBooking")
    void should_returnTrue_when_reviewExistsForBooking() {
        Review review = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 5)
                .comment("Excellent service!")
                .build();
        em.persist(review);
        em.flush();

        boolean exists = reviewRepository.existsByBookingId(completedBooking.getId());

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("should_returnFalse_when_noReviewForBooking")
    void should_returnFalse_when_noReviewForBooking() {
        boolean exists = reviewRepository.existsByBookingId(completedBooking.getId());

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("recalculates master rating to 0.00 / count 0 when the master has no reviews (COALESCE path)")
    void should_recalculateMasterRating_when_noReviewsExist() {
        // Arrange — master was seeded with avgRating=0, reviewCount=0 but no reviews in DB.
        // Calling recalculate on a master with zero reviews must leave the row at 0.00 / 0
        // (not NULL — the COALESCE in the native query handles the empty-aggregation edge case).

        // Act
        reviewRepository.recalculateMasterRating(master.getId());

        // Assert
        Master reloaded = masterRepository.findById(master.getId())
                .orElseThrow(() -> new AssertionError("Master not found after recalculation"));

        assertThat(reloaded.getAvgRating())
                .as("avgRating must be 0.00 when no reviews exist (COALESCE AVG NULL → 0)")
                .isEqualByComparingTo(new java.math.BigDecimal("0.00"));
        assertThat(reloaded.getReviewCount())
                .as("reviewCount must be 0 when no reviews exist (COALESCE COUNT NULL → 0)")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("should_recalculateMasterRating_when_twoReviewsExist")
    void should_recalculateMasterRating_when_twoReviewsExist() {
        User masterUser2 = new User(
                "master2-" + UUID.randomUUID() + "@test.com",
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                Role.INDEPENDENT_MASTER,
                "Master2",
                "User",
                "+380503333333"
        );
        em.persist(masterUser2);

        Master master2 = Master.builder()
                .user(masterUser2)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master2);

        ServiceDefinition sd2 = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master2.getId())
                .name("Pedicure")
                .category("MANICURE")
                .baseDurationMinutes(45)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .serviceType(persistServiceType())
                .isActive(true)
                .build();
        em.persist(sd2);

        MasterServiceAssignment msa2 = MasterServiceAssignment.builder()
                .master(master2)
                .serviceDefinition(sd2)
                .isActive(true)
                .build();
        em.persist(msa2);

        Booking booking2 = Booking.builder()
                .client(clientUser)
                .master(master2)
                .masterService(msa2)
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 7, 1, 10, 45, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("300.00"))
                .durationMinutesAtBooking(45)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(booking2);

        Booking booking3 = Booking.builder()
                .client(clientUser)
                .master(master2)
                .masterService(msa2)
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 7, 2, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 7, 2, 10, 45, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("300.00"))
                .durationMinutesAtBooking(45)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(booking3);

        em.flush();

        Review review1 = Review.builder()
                .booking(booking2)
                .client(clientUser)
                .master(master2)
                .rating((short) 4)
                .build();
        em.persist(review1);

        Review review2 = Review.builder()
                .booking(booking3)
                .client(clientUser)
                .master(master2)
                .rating((short) 2)
                .build();
        em.persist(review2);

        // flushAutomatically = true on recalculateMasterRating ensures the INSERT is
        // flushed before the UPDATE's subqueries run, so AVG and COUNT include both reviews.
        reviewRepository.recalculateMasterRating(master2.getId());

        Master reloaded = masterRepository.findById(master2.getId())
                .orElseThrow(() -> new AssertionError("Master not found after recalculation"));

        assertThat(reloaded.getAvgRating()).isEqualByComparingTo(new BigDecimal("3.00"));
        assertThat(reloaded.getReviewCount()).isEqualTo(2);

        // Cross-master isolation: recalculating master2 must not change master1's stats.
        Master master1Reloaded = masterRepository.findById(master.getId())
                .orElseThrow(() -> new AssertionError("master1 not found after recalculation"));
        assertThat(master1Reloaded.getAvgRating())
                .as("recalculating master2 must not alter master1's avgRating")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(master1Reloaded.getReviewCount())
                .as("recalculating master2 must not alter master1's reviewCount")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("should_orderByCreatedAtDesc_when_findingMasterReviews")
    void should_orderByCreatedAtDesc_when_findingMasterReviews() {
        Booking booking2 = Booking.builder()
                .client(clientUser)
                .master(master)
                .masterService(completedBooking.getMasterService())
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 8, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(booking2);
        em.flush();

        Review olderReview = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 3)
                .comment("Average")
                .build();
        em.persist(olderReview);
        em.flush();

        Review newerReview = Review.builder()
                .booking(booking2)
                .client(clientUser)
                .master(master)
                .rating((short) 5)
                .comment("Excellent")
                .build();
        em.persist(newerReview);
        em.flush();

        em.clear();

        // Two-query pattern (Fix 5 — HHH90003004): paginate IDs, then hydrate graph.
        Page<UUID> idPage = reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(
                master.getId(), PageRequest.of(0, 10));
        List<Review> hydrated = reviewRepository.findByIdsWithGraph(idPage.getContent());
        Map<UUID, Review> byId = hydrated.stream()
                .collect(java.util.stream.Collectors.toMap(Review::getId, r -> r));
        List<Review> ordered = idPage.getContent().stream()
                .map(byId::get)
                .toList();

        assertThat(idPage.getTotalElements()).isEqualTo(2);
        assertThat(ordered.get(0).getId()).isEqualTo(newerReview.getId());
        assertThat(ordered.get(1).getId()).isEqualTo(olderReview.getId());

        // Regression guard: findByIdsWithGraph was widened with a
        // booking.masterService.serviceDefinition JOIN FETCH chain so ReviewResponse.serviceName
        // can be populated without a lazy N+1. em.clear() above already detached the persistence
        // context, so any of the two new JOIN FETCH hops being silently dropped would surface
        // here as a LazyInitializationException (open-in-view=false in production) rather than
        // passing on the ID-only assertions above.
        assertThat(hydrated.get(0).getBooking().getMasterService().getServiceDefinition().getName())
                .as("booking.masterService.serviceDefinition must be hydrated after session detachment — "
                        + "proves both new JOIN FETCH hops are present, not just the pre-existing client/master/booking graph")
                .isEqualTo("Manicure");
    }

    @Test
    @DisplayName("should_enforceUniqueBookingConstraint_when_duplicateReviewInserted")
    void should_enforceUniqueBookingConstraint_when_duplicateReviewInserted() {
        Review firstReview = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 4)
                .build();
        em.persist(firstReview);
        em.flush();

        Review duplicateReview = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 2)
                .build();
        em.persist(duplicateReview);

        assertThatThrownBy(() -> em.flush())
                .isInstanceOf(PersistenceException.class)
                .cause()
                .isInstanceOf(java.sql.BatchUpdateException.class);
    }

    // ── findMyReviews (Phase 19.4 — GET /reviews/me) ──────────────────────────

    @Test
    @DisplayName("findMyReviews — populates master first/last name, service name and bookingId from the projection joins")
    void should_populateProjectionFields_when_findingMyReviews() {
        Review review = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 5)
                .comment("Loved the manicure")
                .build();
        em.persist(review);
        em.flush();
        em.clear();

        Page<MyReviewResponse> page = reviewRepository.findMyReviews(
                clientUser.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        MyReviewResponse row = page.getContent().get(0);
        assertThat(row)
                .extracting(
                        MyReviewResponse::id, MyReviewResponse::masterId,
                        MyReviewResponse::masterFirstName, MyReviewResponse::masterLastName,
                        MyReviewResponse::serviceName, MyReviewResponse::rating,
                        MyReviewResponse::comment, MyReviewResponse::bookingId)
                .containsExactly(
                        review.getId(), master.getId(),
                        "Master", "User",
                        "Manicure", 5,
                        "Loved the manicure", completedBooking.getId());
        assertThat(row.createdAt())
                .as("createdAt must be carried through from the review audit timestamp")
                .isNotNull();
    }

    @Test
    @DisplayName("findMyReviews — returns ONLY the requested client's reviews (cross-client isolation)")
    void should_returnOnlyRequestedClientReviews_when_findingMyReviews() {
        // Review authored by the seeded clientUser.
        Review myReview = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 5)
                .comment("Mine")
                .build();
        em.persist(myReview);

        // A second client with their own booking + review against the same master.
        User otherClient = new User(
                "other-" + UUID.randomUUID() + "@test.com",
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                Role.CLIENT,
                "Other",
                "Client",
                "+380509999999"
        );
        em.persist(otherClient);

        Booking otherBooking = Booking.builder()
                .client(otherClient)
                .master(master)
                .masterService(completedBooking.getMasterService())
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 9, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(otherBooking);

        Review otherReview = Review.builder()
                .booking(otherBooking)
                .client(otherClient)
                .master(master)
                .rating((short) 1)
                .comment("Theirs")
                .build();
        em.persist(otherReview);
        em.flush();
        em.clear();

        Page<MyReviewResponse> page = reviewRepository.findMyReviews(
                clientUser.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements())
                .as("only the requested client's single review must be returned")
                .isEqualTo(1);
        assertThat(page.getContent())
                .extracting(MyReviewResponse::id, MyReviewResponse::comment)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(myReview.getId(), "Mine"));
        assertThat(page.getContent())
                .as("the other client's review must never appear")
                .noneMatch(r -> r.id().equals(otherReview.getId()));
    }

    @Test
    @DisplayName("findMyReviews — orders the client's reviews by createdAt DESC")
    void should_orderByCreatedAtDesc_when_findingMyReviews() {
        Booking secondBooking = Booking.builder()
                .client(clientUser)
                .master(master)
                .masterService(completedBooking.getMasterService())
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 10, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 10, 1, 11, 0, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(secondBooking);
        em.flush();

        Review olderReview = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 3)
                .comment("Older")
                .build();
        em.persist(olderReview);
        em.flush();

        Review newerReview = Review.builder()
                .booking(secondBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 5)
                .comment("Newer")
                .build();
        em.persist(newerReview);
        em.flush();
        em.clear();

        Page<MyReviewResponse> page = reviewRepository.findMyReviews(
                clientUser.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(MyReviewResponse::id)
                .as("newest review must come first (createdAt DESC)")
                .containsExactly(newerReview.getId(), olderReview.getId());
    }

    @Test
    @DisplayName("findMyReviews — runs a bounded, single-statement query regardless of row count (no N+1)")
    void should_runBoundedQuery_when_findingMyReviews() {
        // Seed three reviews for the same client across three distinct bookings.
        int rows = 3;
        for (int i = 0; i < rows; i++) {
            Booking booking = Booking.builder()
                    .client(clientUser)
                    .master(master)
                    .masterService(completedBooking.getMasterService())
                    .status(BookingStatus.COMPLETED)
                    .startsAt(OffsetDateTime.of(2026, 11, 1 + i, 10, 0, 0, 0, ZoneOffset.UTC))
                    .endsAt(OffsetDateTime.of(2026, 11, 1 + i, 11, 0, 0, 0, ZoneOffset.UTC))
                    .priceAtBooking(new BigDecimal("450.00"))
                    .durationMinutesAtBooking(60)
                    .bufferMinutesAtBooking(0)
                    .build();
            em.persist(booking);
            Review review = Review.builder()
                    .booking(booking)
                    .client(clientUser)
                    .master(master)
                    .rating((short) 4)
                    .comment("Comment " + i)
                    .build();
            em.persist(review);
        }
        em.flush();
        em.clear();

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Page<MyReviewResponse> page = reviewRepository.findMyReviews(
                clientUser.getId(), PageRequest.of(0, 20));

        // Constructor-expression projection builds the whole row in one SELECT — no lazy
        // traversal at mapping time. Page adds a single COUNT statement. Total: 2, independent
        // of row count. A per-row (N+1) hydration would scale the count with `rows`.
        assertThat(page.getContent()).hasSize(rows);
        assertThat(statistics.getPrepareStatementCount())
                .as("findMyReviews must issue a bounded statement count (SELECT + COUNT), not one per row")
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("should_persistReview_when_salonIsNull")
    void should_persistReview_when_salonIsNull() {
        Review review = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .salon(null)
                .rating((short) 5)
                .comment("Great independent master")
                .build();
        em.persist(review);
        em.flush();
        em.clear();

        Review reloaded = reviewRepository.findById(review.getId())
                .orElseThrow(() -> new AssertionError("Review not found after persist"));

        assertThat(reloaded.getSalon()).isNull();
        assertThat(reloaded.getRating()).isEqualTo((short) 5);
    }

    // ── recalculateSalonRating — equal-weighted mean of the salon's ACTIVE masters ─────
    //
    // Mobile Phase 111 replaced the flat AVG(reviews.rating) WHERE salon_id = X with a two-level
    // aggregate. Fixtures are chosen so per-review and per-master averages DIVERGE — one where
    // they coincide passes under both formulas and pins nothing.
    //
    // Verified by mutation, not by inspection. Reverting recalculateSalonRating to the old flat
    // formula turns these RED:
    //   * should_weightEachMasterEqually_...            (3.00 -> 2.00)
    //   * should_dropDepartedMasterScores_...           (old ignores m.is_active)
    //   * should_excludeMasterAttachedToAnotherSalon_...(old ignores m.salon_id)
    //   * both countBySalonIdGroupByRating parity tests (old histogram carried no master join)
    // and leaves these GREEN, because they guard a DIFFERENT mutation — say so rather than
    // implying blanket discrimination:
    //   * should_ignoreReviewsEarnedAtAnotherSalon_...  — the old formula was ALSO scoped by
    //     r.salon_id, so it agrees at 5.00. What this pins is the forward-looking mistake of
    //     feeding the LIFETIME masters.avg_rating column into the outer average.
    //   * should_recalculateSalonRatingToZero_...       — a COALESCE branch, 0.00 under both.
    //   * should_ignoreUnreviewedActiveMaster_...       — pins the masters-LEFT-JOIN-reviews
    //     rewrite (5.00 -> 2.50); it is the only test in this class that catches that shape.

    /**
     * The defining case for equal weighting. Master A: one 5. Master B: three 1s.
     * <ul>
     *   <li>OLD (per-review) → (5+1+1+1)/4 = <b>2.00</b></li>
     *   <li>NEW (per-master, equal weight) → mean(5.00, 1.00) = <b>3.00</b></li>
     * </ul>
     * {@code review_count} stays 4 — the number of underlying REVIEWS, not of contributing
     * masters (which would be 2). See the method javadoc for why.
     */
    @Test
    @DisplayName("should_weightEachMasterEqually_when_recalculatingSalonRating")
    void should_weightEachMasterEqually_when_recalculatingSalonRating() {
        Salon salon = persistSalon("Equal Weight Salon");
        Master high = persistSalonMaster(salon, "high");
        Master low = persistSalonMaster(salon, "low");

        persistSalonReview(salon, high, (short) 5);
        persistSalonReview(salon, low, (short) 1);
        persistSalonReview(salon, low, (short) 1);
        persistSalonReview(salon, low, (short) 1);

        reviewRepository.recalculateSalonRating(salon.getId());

        Salon reloaded = em.find(Salon.class, salon.getId());
        assertThat(reloaded.getAvgRating())
                .as("mean of the per-master means (5.00, 1.00), NOT the per-review mean of 2.00")
                .isEqualByComparingTo(new BigDecimal("3.00"));
        assertThat(reloaded.getReviewCount())
                .as("the count of underlying reviews, not of contributing masters")
                .isEqualTo(4);
    }

    /**
     * The reason {@code masters.avg_rating} must not be reused as the input: it is a LIFETIME
     * figure. This master holds a 1 earned elsewhere (a {@code salon_id} of another salon) and a
     * 5 earned here. Only the 5 may reach this salon's rating.
     */
    @Test
    @DisplayName("should_ignoreReviewsEarnedAtAnotherSalon_when_recalculatingSalonRating")
    void should_ignoreReviewsEarnedAtAnotherSalon_when_recalculatingSalonRating() {
        Salon here = persistSalon("Current Employer");
        Salon elsewhere = persistSalon("Previous Employer");
        Master mover = persistSalonMaster(here, "mover");

        persistSalonReview(here, mover, (short) 5);
        // Same master_id, different salon_id — a review carried over from a previous employer.
        persistSalonReview(elsewhere, mover, (short) 1);

        reviewRepository.recalculateSalonRating(here.getId());

        Salon reloaded = em.find(Salon.class, here.getId());
        assertThat(reloaded.getAvgRating())
                .as("salon-scoped: the 1 earned at the previous employer must not be imported")
                .isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(reloaded.getReviewCount()).isEqualTo(1);
    }

    /**
     * The staff-set dependency that forced a second recalc trigger
     * ({@code SalonStaffChangedEvent}): with no review written, deactivating a master changes the
     * salon's rating.
     */
    @Test
    @DisplayName("should_dropDepartedMasterScores_when_recalculatingSalonRatingAfterDeactivation")
    void should_dropDepartedMasterScores_when_recalculatingSalonRatingAfterDeactivation() {
        Salon salon = persistSalon("Staff Change Salon");
        Master stays = persistSalonMaster(salon, "stays");
        Master leaves = persistSalonMaster(salon, "leaves");

        persistSalonReview(salon, stays, (short) 4);
        persistSalonReview(salon, leaves, (short) 2);

        UUID leavesId = leaves.getId();
        reviewRepository.recalculateSalonRating(salon.getId());
        assertThat(em.find(Salon.class, salon.getId()).getAvgRating())
                .isEqualByComparingTo(new BigDecimal("3.00"));

        // Re-find rather than mutating `leaves` directly: recalculateSalonRating declares
        // clearAutomatically = true, so the first call DETACHED every entity above and a
        // setter on the stale reference would flush nothing (the test would then pass for
        // the wrong reason — asserting the unchanged 3.00 that a no-op produces).
        em.find(Master.class, leavesId).setActive(false);
        em.flush();

        reviewRepository.recalculateSalonRating(salon.getId());

        Salon reloaded = em.find(Salon.class, salon.getId());
        assertThat(reloaded.getAvgRating())
                .as("a deactivated master stops contributing — no review was written")
                .isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(reloaded.getReviewCount())
                .as("their reviews leave the count too, keeping it consistent with the average")
                .isEqualTo(1);
    }

    /**
     * A master attached to ANOTHER salon whose review nonetheless carries this salon_id must not
     * contribute — the {@code m.salon_id = :salonId} join term, distinct from the
     * {@code r.salon_id = :salonId} filter.
     */
    @Test
    @DisplayName("should_excludeMasterAttachedToAnotherSalon_when_recalculatingSalonRating")
    void should_excludeMasterAttachedToAnotherSalon_when_recalculatingSalonRating() {
        Salon salon = persistSalon("Host Salon");
        Salon other = persistSalon("Other Salon");
        Master ours = persistSalonMaster(salon, "ours");
        Master theirs = persistSalonMaster(other, "theirs");

        persistSalonReview(salon, ours, (short) 4);
        // Stale row: review tagged with our salon, master now belongs to another salon.
        persistSalonReview(salon, theirs, (short) 1);

        reviewRepository.recalculateSalonRating(salon.getId());

        Salon reloaded = em.find(Salon.class, salon.getId());
        assertThat(reloaded.getAvgRating()).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(reloaded.getReviewCount()).isEqualTo(1);
    }

    /** COALESCE path — an AVG over no contributors is NULL and must land as 0.00 / 0, not NULL. */
    @Test
    @DisplayName("should_recalculateSalonRatingToZero_when_noContributingMasters")
    void should_recalculateSalonRatingToZero_when_noContributingMasters() {
        Salon salon = persistSalon("Empty Salon");
        persistSalonMaster(salon, "unreviewed");

        reviewRepository.recalculateSalonRating(salon.getId());

        Salon reloaded = em.find(Salon.class, salon.getId());
        assertThat(reloaded.getAvgRating()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(reloaded.getReviewCount()).isZero();
    }

    /**
     * Knock-on consistency: the histogram must sum to the {@code review_count} printed beside it.
     * The departed master's 2 disappears from BOTH.
     */
    @Test
    @DisplayName("should_matchRecalculatedReviewCount_when_countingSalonRatingDistribution")
    void should_matchRecalculatedReviewCount_when_countingSalonRatingDistribution() {
        Salon salon = persistSalon("Histogram Salon");
        Master stays = persistSalonMaster(salon, "hist-stays");
        Master leaves = persistSalonMaster(salon, "hist-leaves");

        persistSalonReview(salon, stays, (short) 4);
        persistSalonReview(salon, stays, (short) 4);
        persistSalonReview(salon, leaves, (short) 2);
        leaves.setActive(false);
        em.flush();

        reviewRepository.recalculateSalonRating(salon.getId());
        List<RatingCountProjection> buckets = reviewRepository.countBySalonIdGroupByRating(salon.getId());

        long histogramTotal = buckets.stream().mapToLong(RatingCountProjection::getCount).sum();
        assertThat(histogramTotal)
                .as("the bars must sum to the reviewCount the headline average is derived from")
                .isEqualTo(em.find(Salon.class, salon.getId()).getReviewCount())
                .isEqualTo(2L);
        assertThat(buckets).extracting(RatingCountProjection::getRating)
                .as("the departed master's 2-star bucket is gone entirely")
                .containsExactly(4);
    }

    /**
     * The dilution trap. An ACTIVE master who has simply never been reviewed at this salon must be
     * absent from the aggregate, not present as a zero.
     *
     * <p>The shipped query is safe because its inner aggregate starts from {@code reviews} and
     * joins masters in — an unreviewed master produces no group. The obvious "improvement" of
     * starting from {@code masters} and {@code LEFT JOIN}ing reviews (the shape you reach for when
     * asked to "include every active master") turns this salon's 5.00 into 2.50 and every
     * new hire silently halves their employer's rating. Nothing else in the suite would notice.
     */
    @Test
    @DisplayName("should_ignoreUnreviewedActiveMaster_when_recalculatingSalonRating")
    void should_ignoreUnreviewedActiveMaster_when_recalculatingSalonRating() {
        Salon salon = persistSalon("Dilution Salon");
        Master reviewed = persistSalonMaster(salon, "reviewed");
        persistSalonMaster(salon, "never-reviewed");

        persistSalonReview(salon, reviewed, (short) 5);

        reviewRepository.recalculateSalonRating(salon.getId());

        Salon reloaded = em.find(Salon.class, salon.getId());
        assertThat(reloaded.getAvgRating())
                .as("an unreviewed master contributes no group at all — counting them as a 0 "
                        + "would give 2.50")
                .isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(reloaded.getReviewCount()).isEqualTo(1);
    }

    /**
     * Histogram parity with the {@code m.salon_id = :salonId} join term, the sibling of the
     * {@code m.is_active} case already covered above. {@code recalculateSalonRating} and
     * {@code countBySalonIdGroupByRating} carry the SAME contributor predicate by contract; a
     * predicate added to one and forgotten in the other leaves the bars refusing to sum to the
     * number printed beside them.
     */
    @Test
    @DisplayName("should_excludeMasterOfAnotherSalon_when_countingSalonRatingDistribution")
    void should_excludeMasterOfAnotherSalon_when_countingSalonRatingDistribution() {
        Salon salon = persistSalon("Histogram Host Salon");
        Salon other = persistSalon("Histogram Other Salon");
        Master ours = persistSalonMaster(salon, "hist-ours");
        Master theirs = persistSalonMaster(other, "hist-theirs");

        persistSalonReview(salon, ours, (short) 5);
        // Review tagged with OUR salon, but its master is attached to another one.
        persistSalonReview(salon, theirs, (short) 1);

        reviewRepository.recalculateSalonRating(salon.getId());
        List<RatingCountProjection> buckets = reviewRepository.countBySalonIdGroupByRating(salon.getId());

        assertThat(buckets).extracting(RatingCountProjection::getRating)
                .as("the outside master's 1-star bucket must not appear")
                .containsExactly(5);
        assertThat(buckets.stream().mapToLong(RatingCountProjection::getCount).sum())
                .as("and the bars still sum to the recalculated review_count")
                .isEqualTo(em.find(Salon.class, salon.getId()).getReviewCount())
                .isEqualTo(1L);
    }

    // ── salon-rating fixtures ────────────────────────────────────────────────────

    private Salon persistSalon(String name) {
        User owner = new User(
                "salon-owner-" + UUID.randomUUID() + "@test.com",
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                Role.SALON_OWNER, "Owner", "User", "+380504444444");
        em.persist(owner);

        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name(name)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(salon);
        return salon;
    }

    private Master persistSalonMaster(Salon salon, String label) {
        User masterUser = new User(
                label + "-" + UUID.randomUUID() + "@test.com",
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                Role.SALON_MASTER, "Salon", "Master", "+380505555555");
        em.persist(masterUser);

        Master salonMaster = Master.builder()
                .user(masterUser)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(salonMaster);
        return salonMaster;
    }

    /**
     * One COMPLETED booking + its review, tagged with {@code salonId}. A fresh booking per review
     * is required — {@code reviews} carries a UNIQUE on {@code booking_id} (one review per
     * booking, the locked "review unit is the BOOKING" decision).
     */
    private void persistSalonReview(Salon salon, Master reviewedMaster, short rating) {
        ServiceDefinition sd = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salon.getId())
                .name("Salon Service")
                .category("MANICURE")
                .baseDurationMinutes(30)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("200.00"))
                .serviceType(persistServiceType())
                .isActive(true)
                .build();
        em.persist(sd);

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .master(reviewedMaster)
                .serviceDefinition(sd)
                .isActive(true)
                .build();
        em.persist(msa);

        Booking booking = Booking.builder()
                .client(clientUser)
                .master(reviewedMaster)
                .masterService(msa)
                .status(BookingStatus.COMPLETED)
                .startsAt(OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC))
                .endsAt(OffsetDateTime.of(2026, 5, 1, 9, 30, 0, 0, ZoneOffset.UTC))
                .priceAtBooking(new BigDecimal("200.00"))
                .durationMinutesAtBooking(30)
                .bufferMinutesAtBooking(0)
                .build();
        em.persist(booking);

        Review review = Review.builder()
                .booking(booking)
                .client(clientUser)
                .master(reviewedMaster)
                .salon(salon)
                .rating(rating)
                .build();
        em.persist(review);
        em.flush();
    }
}
