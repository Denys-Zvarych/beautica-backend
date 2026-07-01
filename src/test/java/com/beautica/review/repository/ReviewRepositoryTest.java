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
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
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

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
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
}
