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
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private MasterRepository masterRepository;

    @Autowired
    private TestEntityManager em;

    private User clientUser;
    private Master master;
    private Booking completedBooking;

    @BeforeEach
    void setUp() {
        clientUser = new User(
                "client-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.CLIENT,
                "Client",
                "User",
                "+380501111111"
        );
        em.persist(clientUser);

        User masterUser = new User(
                "master-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
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
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
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
    @DisplayName("should_findReviewByBookingId_when_reviewExists")
    void should_findReviewByBookingId_when_reviewExists() {
        Review review = Review.builder()
                .booking(completedBooking)
                .client(clientUser)
                .master(master)
                .rating((short) 5)
                .comment("Excellent service!")
                .build();
        em.persist(review);
        em.flush();

        Optional<Review> result = reviewRepository.findByBookingId(completedBooking.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(review.getId());
        assertThat(result.get().getRating()).isEqualTo((short) 5);
    }

    @Test
    @DisplayName("should_returnEmpty_when_noReviewForBooking")
    void should_returnEmpty_when_noReviewForBooking() {
        Optional<Review> result = reviewRepository.findByBookingId(completedBooking.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should_recalculateMasterRating_when_twoReviewsExist")
    void should_recalculateMasterRating_when_twoReviewsExist() {
        User masterUser2 = new User(
                "master2-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
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
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(45)
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
