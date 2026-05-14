package com.beautica.review.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.entity.Review;
import com.beautica.review.event.ReviewCreatedEvent;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ReviewService — unit")
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReviewService reviewService;

    private static final UUID CLIENT_ID  = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID MASTER_ID  = UUID.randomUUID();
    private static final UUID REVIEW_ID  = UUID.randomUUID();

    // ── createReview ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("creates review and publishes event when booking is COMPLETED and actor is the booking client")
    void should_createReview_when_completedBookingAndOwnerClient() {
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);

        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(BOOKING_ID);
        when(booking.getStatus()).thenReturn(BookingStatus.COMPLETED);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getSalon()).thenReturn(null);

        User savedClient = mock(User.class);
        when(savedClient.getFirstName()).thenReturn("Anna");
        when(savedClient.getLastName()).thenReturn("Koval");

        Review saved = mock(Review.class);
        when(saved.getId()).thenReturn(REVIEW_ID);
        when(saved.getClient()).thenReturn(savedClient);
        when(saved.getMaster()).thenReturn(master);
        when(saved.getRating()).thenReturn((short) 5);
        when(saved.getComment()).thenReturn("Great service");
        when(saved.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 5, "Great service");

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(BOOKING_ID)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(Review.class))).thenReturn(saved);

        ReviewResponse response = reviewService.createReview(CLIENT_ID, request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(REVIEW_ID);
        assertThat(response.masterId()).isEqualTo(MASTER_ID);
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Great service");
        assertThat(response.clientDisplayName()).isEqualTo("Anna K.");
        verify(reviewRepository).saveAndFlush(any(Review.class));
        verify(eventPublisher).publishEvent(new ReviewCreatedEvent(MASTER_ID));
    }

    @ParameterizedTest
    @DisplayName("throws 400 BusinessException when booking status is not COMPLETED (covers all non-terminal states)")
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED", "DECLINED", "CANCELLED", "NOT_COMPLETED"})
    void should_throw400_when_bookingStatusIsNotCompleted(BookingStatus status) {
        // Ownership check runs first (fix for IDOR oracle); stub client so it passes through to status check.
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(client);
        when(booking.getStatus()).thenReturn(status);

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 4, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwForbidden_when_actorIsNotBookingClient")
    void should_throwForbidden_when_actorIsNotBookingClient() {
        UUID differentClientId = UUID.randomUUID();

        // Ownership check runs first — getStatus() is never reached, so do not stub it.
        User actualClient = mock(User.class);
        when(actualClient.getId()).thenReturn(CLIENT_ID);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(actualClient);

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 3, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reviewService.createReview(differentClientId, request))
                .isInstanceOf(ForbiddenException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throw409_when_reviewAlreadyExistsForBooking")
    void should_throw409_when_reviewAlreadyExistsForBooking() {
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(BOOKING_ID);
        when(booking.getStatus()).thenReturn(BookingStatus.COMPLETED);
        when(booking.getClient()).thenReturn(client);

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 5, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(BOOKING_ID)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throw404_when_bookingNotFound")
    void should_throw404_when_bookingNotFound() {
        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 5, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(NotFoundException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_notCallRecalculate_when_saveThrowsDataIntegrityViolation")
    void should_notCallRecalculate_when_saveThrowsDataIntegrityViolation() {
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Master master = mock(Master.class);
        // master.getId() is NOT stubbed — recalculateMasterRating must never be reached

        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(BOOKING_ID);
        when(booking.getStatus()).thenReturn(BookingStatus.COMPLETED);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getSalon()).thenReturn(null);

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 5, "Great service");

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(BOOKING_ID)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(Review.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── getReviewsForMaster ───────────────────────────────────────────────────

    // @Cacheable on getReviewsForMaster is not exercised here (MockitoExtension bypasses AOP);
    // cache hit/miss behaviour is covered by ReviewIntegrationTest.

    @Test
    @DisplayName("should_returnPagedReviews_when_masterHasReviews")
    void should_returnPagedReviews_when_masterHasReviews() {
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Anna");
        when(client.getLastName()).thenReturn("Koval");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);

        Review review = mock(Review.class);
        when(review.getId()).thenReturn(REVIEW_ID);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 4);
        when(review.getComment()).thenReturn(null);
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        // The service strips caller sort before hitting the repository, so stub with any(Pageable.class).
        Pageable pageable = PageRequest.of(0, 20);
        Page<UUID> idPage = new PageImpl<>(List.of(REVIEW_ID), pageable, 1);

        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(eq(MASTER_ID), any(Pageable.class)))
                .thenReturn(idPage);
        when(reviewRepository.findByIdsWithGraph(List.of(REVIEW_ID)))
                .thenReturn(List.of(review));

        Page<ReviewResponse> result = reviewService.getReviewsForMaster(MASTER_ID, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).masterId()).isEqualTo(MASTER_ID);
    }

    @Test
    @DisplayName("should_returnEmptyPage_when_masterHasNoReviews")
    void should_returnEmptyPage_when_masterHasNoReviews() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<UUID> emptyIdPage = Page.empty(pageable);

        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(eq(MASTER_ID), any(Pageable.class)))
                .thenReturn(emptyIdPage);

        Page<ReviewResponse> result = reviewService.getReviewsForMaster(MASTER_ID, pageable);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        verify(reviewRepository, never()).findByIdsWithGraph(any());
    }

    @Test
    @DisplayName("should_returnEmptyPage_when_masterIdDoesNotExist")
    void should_returnEmptyPage_when_masterIdDoesNotExist() {
        Pageable pageable = PageRequest.of(0, 20);
        UUID nonExistentMasterId = UUID.randomUUID();
        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(eq(nonExistentMasterId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        Page<ReviewResponse> result = reviewService.getReviewsForMaster(nonExistentMasterId, pageable);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        verify(reviewRepository, never()).findByIdsWithGraph(any());
    }

    @Test
    @DisplayName("should_ignoreCallerSort_when_gettingReviewsForMaster")
    void should_ignoreCallerSort_when_gettingReviewsForMaster() {
        // Arrange — caller supplies a sort that must not reach the repository
        Pageable callerPageable = PageRequest.of(0, 20, Sort.by("comment"));
        Page<UUID> emptyIdPage = Page.empty(PageRequest.of(0, 20));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(eq(MASTER_ID), any(Pageable.class)))
                .thenReturn(emptyIdPage);

        // Act
        reviewService.getReviewsForMaster(MASTER_ID, callerPageable);

        // Assert — repository received an unsorted Pageable
        verify(reviewRepository).findIdsByMasterIdOrderByCreatedAtDesc(eq(MASTER_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort())
                .as("repository must receive Sort.unsorted() regardless of caller-supplied sort")
                .isEqualTo(Sort.unsorted());
    }

    // ── getReview ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnReview_when_reviewIdExists")
    void should_returnReview_when_reviewIdExists() {
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Ivan");
        when(client.getLastName()).thenReturn("Petrenko");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);

        Review review = mock(Review.class);
        when(review.getId()).thenReturn(REVIEW_ID);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 5);
        when(review.getComment()).thenReturn("Excellent");
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        when(reviewRepository.findByIdWithAssociations(REVIEW_ID)).thenReturn(Optional.of(review));

        ReviewResponse response = reviewService.getReview(REVIEW_ID);

        assertThat(response.id()).isEqualTo(REVIEW_ID);
        assertThat(response.masterId()).isEqualTo(MASTER_ID);
        assertThat(response.clientDisplayName()).isEqualTo("Ivan P.");
    }

    @Test
    @DisplayName("should_returnAnonymous_when_clientHasNoName")
    void should_returnAnonymous_when_clientHasNoName() {
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn(null);
        when(client.getLastName()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);

        Review review = mock(Review.class);
        when(review.getId()).thenReturn(REVIEW_ID);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 3);
        when(review.getComment()).thenReturn(null);
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        when(reviewRepository.findByIdWithAssociations(REVIEW_ID)).thenReturn(Optional.of(review));

        ReviewResponse response = reviewService.getReview(REVIEW_ID);

        assertThat(response.clientDisplayName())
                .as("clientDisplayName must be 'Anonymous' when both firstName and lastName are null")
                .isEqualTo("Anonymous");
    }

    @Test
    @DisplayName("should_returnFirstNameOnly_when_clientHasNoLastName")
    void should_returnFirstNameOnly_when_clientHasNoLastName() {
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Anna");
        when(client.getLastName()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);

        Review review = mock(Review.class);
        when(review.getId()).thenReturn(REVIEW_ID);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 4);
        when(review.getComment()).thenReturn(null);
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        when(reviewRepository.findByIdWithAssociations(REVIEW_ID)).thenReturn(Optional.of(review));

        ReviewResponse response = reviewService.getReview(REVIEW_ID);

        assertThat(response.clientDisplayName())
                .as("clientDisplayName must be firstName only when lastName is null")
                .isEqualTo("Anna");
    }

    @Test
    @DisplayName("should_throwNotFound_when_reviewByIdNotFound")
    void should_throwNotFound_when_reviewByIdNotFound() {
        when(reviewRepository.findByIdWithAssociations(REVIEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getReview(REVIEW_ID))
                .isInstanceOf(NotFoundException.class);
    }

    // ── getReviewsForMaster — timing oracle (FIX 5) ──────────────────────────

    @Test
    @DisplayName("should_return_empty_page_when_masterId_does_not_exist")
    void should_return_empty_page_when_masterId_does_not_exist() {
        // Arrange — unknown master: repository returns empty page (no rows)
        UUID unknownMasterId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);

        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(unknownMasterId, PageRequest.of(0, 20)))
                .thenReturn(Page.empty(pageable));

        // Act — must not throw NotFoundException; same shape as a master with zero reviews
        Page<ReviewResponse> result = reviewService.getReviewsForMaster(unknownMasterId, pageable);

        // Assert — empty page returned; timing oracle is not present
        assertThat(result.isEmpty())
                .as("unknown master must produce an empty page, not an exception")
                .isTrue();
        assertThat(result.getTotalElements())
                .as("total elements must be 0 for unknown master")
                .isZero();
    }
}
