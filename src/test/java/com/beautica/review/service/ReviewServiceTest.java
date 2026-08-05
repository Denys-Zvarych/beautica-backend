package com.beautica.review.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.RatingBucket;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.common.PageResponse;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.MyReviewResponse;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.dto.SalonReviewSummaryResponse;
import com.beautica.review.entity.Review;
import com.beautica.review.event.ReviewCreatedEvent;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    private SalonRepository salonRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Clock clock;

    @InjectMocks
    private ReviewService reviewService;

    private static final UUID CLIENT_ID  = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID MASTER_ID  = UUID.randomUUID();
    // users.id of the reviewed master — key of the userId-keyed "master-detail-by-user" cache
    // (Phase 240 re-audit, Finding 1). Deliberately distinct from MASTER_ID: the two caches live
    // in separate key spaces, so a test that reused one UUID for both could not catch a swap.
    private static final UUID MASTER_USER_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID  = UUID.randomUUID();
    // Fixed "now" for the review-eligibility (BookingClosureRule#isReviewEligible) checks —
    // lenient because most tests below never reach a branch that reads the clock at all (e.g.
    // COMPLETED-status tests short-circuit BEFORE isAwaitingClosure ever evaluates endsAt), so
    // MockitoExtension's strict-stubs would otherwise flag this as an unnecessary stub for them.
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-15T12:00:00Z");

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(NOW.toInstant());
    }

    // review.booking is NOT NULL (unique FK) and Booking.masterService is NOT NULL, so
    // ReviewResponse.from never null-checks the chain — every mock that reaches
    // ReviewResponse.from must stub getBooking(), or the mock's default `null` return NPEs.
    private static Booking mockBookingWithServiceName(String serviceName) {
        var serviceDefinition = mock(ServiceDefinition.class);
        when(serviceDefinition.getName()).thenReturn(serviceName);

        var masterService = mock(MasterServiceAssignment.class);
        when(masterService.getServiceDefinition()).thenReturn(serviceDefinition);

        var booking = mock(Booking.class);
        when(booking.getMasterService()).thenReturn(masterService);
        return booking;
    }

    // ── createReview ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("creates review and publishes event when booking is COMPLETED and actor is the booking client")
    void should_createReview_when_completedBookingAndOwnerClient() {
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        // findByIdWithFullGraph JOIN FETCHes m.user, so createReview reads the master's userId
        // off a hydrated entity to populate ReviewCreatedEvent.masterUserId.
        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(MASTER_USER_ID);
        when(master.getUser()).thenReturn(masterUser);

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
        Booking savedBooking = mockBookingWithServiceName("Manicure");
        when(saved.getBooking()).thenReturn(savedBooking);
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
        assertThat(response.serviceName()).isEqualTo("Manicure");
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Great service");
        assertThat(response.clientDisplayName()).isEqualTo("Anna K.");
        verify(reviewRepository).saveAndFlush(any(Review.class));
        verify(eventPublisher).publishEvent(new ReviewCreatedEvent(MASTER_ID, MASTER_USER_ID, null));
    }

    @Test
    @DisplayName("publishes a non-null salonId when the booking belongs to a salon-affiliated master")
    void should_publishSalonId_when_bookingHasSalon() {
        UUID salonId = UUID.randomUUID();

        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        // findByIdWithFullGraph JOIN FETCHes m.user, so createReview reads the master's userId
        // off a hydrated entity to populate ReviewCreatedEvent.masterUserId.
        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(MASTER_USER_ID);
        when(master.getUser()).thenReturn(masterUser);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(BOOKING_ID);
        when(booking.getStatus()).thenReturn(BookingStatus.COMPLETED);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getSalon()).thenReturn(salon);

        User savedClient = mock(User.class);
        when(savedClient.getFirstName()).thenReturn("Anna");
        when(savedClient.getLastName()).thenReturn("Koval");

        Review saved = mock(Review.class);
        when(saved.getId()).thenReturn(REVIEW_ID);
        when(saved.getClient()).thenReturn(savedClient);
        when(saved.getMaster()).thenReturn(master);
        Booking savedBooking = mockBookingWithServiceName("Manicure");
        when(saved.getBooking()).thenReturn(savedBooking);
        when(saved.getRating()).thenReturn((short) 5);
        when(saved.getComment()).thenReturn("Great service");
        when(saved.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 5, "Great service");

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(BOOKING_ID)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(Review.class))).thenReturn(saved);

        reviewService.createReview(CLIENT_ID, request);

        verify(eventPublisher).publishEvent(new ReviewCreatedEvent(MASTER_ID, MASTER_USER_ID, salonId));
    }

    @ParameterizedTest
    @DisplayName("throws 400 BusinessException for a terminal-non-reviewable status, REGARDLESS of "
            + "endsAt (DECLINED/CANCELLED/NOT_COMPLETED never become reviewable by elapsed time — "
            + "unlike CONFIRMED, covered separately below)")
    @EnumSource(value = BookingStatus.class, names = {"DECLINED", "CANCELLED", "NOT_COMPLETED"})
    void should_throw400_when_bookingStatusIsTerminalNonReviewable(BookingStatus status) {
        // Ownership check runs first (fix for IDOR oracle); stub client so it passes through to status check.
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(client);
        when(booking.getStatus()).thenReturn(status);
        // endsAt deliberately NOT stubbed (stays null): isReviewEligible's isAwaitingClosure leg
        // short-circuits on `status == CONFIRMED` before ever dereferencing endsAt, so a
        // DECLINED/CANCELLED/NOT_COMPLETED booking must reject cleanly even with no endsAt at all —
        // proving these three are unreviewable independent of time, not just "elapsed or not".

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 4, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws 400 BusinessException when the booking is CONFIRMED and its endsAt has NOT "
            + "yet elapsed — the important assertion this fix must preserve: a still-open booking "
            + "stays unreviewable")
    void should_throw400_when_bookingIsConfirmedAndNotYetElapsed() {
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(client);
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(booking.getEndsAt()).thenReturn(NOW.plusHours(1));

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 4, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("creates review when the booking is CONFIRMED but its endsAt has already elapsed — "
            + "the bug fix: a booking the provider never closed but that aged into Past by time "
            + "must be reviewable")
    void should_createReview_when_bookingIsConfirmedButElapsed() {
        User client = mock(User.class);
        when(client.getId()).thenReturn(CLIENT_ID);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        // findByIdWithFullGraph JOIN FETCHes m.user, so createReview reads the master's userId
        // off a hydrated entity to populate ReviewCreatedEvent.masterUserId.
        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(MASTER_USER_ID);
        when(master.getUser()).thenReturn(masterUser);

        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(BOOKING_ID);
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(booking.getEndsAt()).thenReturn(NOW.minusHours(1));
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
        Booking savedBooking = mockBookingWithServiceName("Manicure");
        when(saved.getBooking()).thenReturn(savedBooking);
        when(saved.getRating()).thenReturn((short) 4);
        when(saved.getComment()).thenReturn(null);
        when(saved.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 4, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(BOOKING_ID)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(Review.class))).thenReturn(saved);

        ReviewResponse response = reviewService.createReview(CLIENT_ID, request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(REVIEW_ID);
        verify(reviewRepository).saveAndFlush(any(Review.class));
        verify(eventPublisher).publishEvent(new ReviewCreatedEvent(MASTER_ID, MASTER_USER_ID, null));
    }

    @Test
    @DisplayName("403 Forbidden when the actor requesting the review is not the booking's client")
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
    @DisplayName("403 Forbidden (not 500) when an authenticated CLIENT submits a guest booking's id")
    void should_throwForbidden_when_bookingIsGuestBookingWithNullClient() {
        // Guest (LINK) booking: null client (V89 chk_bookings_guest_fields). Before the fix,
        // booking.getClient().getId() unconditionally NPE'd here — a 500 instead of a clean 403,
        // and an existence oracle (500 vs 403 told a caller a booking id was real but foreign).
        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(null);

        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 5, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(ForbiddenException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("409 Conflict when a review for this booking already exists")
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
    @DisplayName("404 Not Found when the booking does not exist")
    void should_throw404_when_bookingNotFound() {
        CreateReviewRequest request = new CreateReviewRequest(BOOKING_ID, 5, null);

        when(bookingRepository.findByIdWithFullGraph(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.createReview(CLIENT_ID, request))
                .isInstanceOf(NotFoundException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("skips rating recalculation when a duplicate review triggers DataIntegrityViolation")
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
    @DisplayName("returns paginated reviews when the master has reviews")
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
        Booking booking = mockBookingWithServiceName("Manicure");
        when(review.getBooking()).thenReturn(booking);
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

        Page<ReviewResponse> result = reviewService.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).masterId()).isEqualTo(MASTER_ID);
        assertThat(result.getContent().get(0).serviceName()).isEqualTo("Manicure");
    }

    @Test
    @DisplayName("returns an empty page when the master has no reviews")
    void should_returnEmptyPage_when_masterHasNoReviews() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<UUID> emptyIdPage = Page.empty(pageable);

        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(eq(MASTER_ID), any(Pageable.class)))
                .thenReturn(emptyIdPage);

        Page<ReviewResponse> result = reviewService.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, pageable);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        verify(reviewRepository, never()).findByIdsWithGraph(any());
    }

    @Test
    @DisplayName("returns an empty page when the master ID does not exist")
    void should_returnEmptyPage_when_masterIdDoesNotExist() {
        Pageable pageable = PageRequest.of(0, 20);
        UUID nonExistentMasterId = UUID.randomUUID();
        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(eq(nonExistentMasterId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        Page<ReviewResponse> result = reviewService.getReviewsForMaster(nonExistentMasterId, SalonReviewSort.NEWEST, pageable);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        verify(reviewRepository, never()).findByIdsWithGraph(any());
    }

    @Test
    @DisplayName("ignores caller-supplied sort and always orders reviews by createdAt desc")
    void should_ignoreCallerSort_when_gettingReviewsForMaster() {
        // Arrange — caller supplies a sort that must not reach the repository
        Pageable callerPageable = PageRequest.of(0, 20, Sort.by("comment"));
        Page<UUID> emptyIdPage = Page.empty(PageRequest.of(0, 20));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(eq(MASTER_ID), any(Pageable.class)))
                .thenReturn(emptyIdPage);

        // Act
        reviewService.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, callerPageable);

        // Assert — repository received an unsorted Pageable
        verify(reviewRepository).findIdsByMasterIdOrderByCreatedAtDesc(eq(MASTER_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort())
                .as("repository must receive Sort.unsorted() regardless of caller-supplied sort")
                .isEqualTo(Sort.unsorted());
    }

    // ── getReview ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns the review when the review ID exists")
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
        Booking booking = mockBookingWithServiceName("Manicure");
        when(review.getBooking()).thenReturn(booking);
        when(review.getRating()).thenReturn((short) 5);
        when(review.getComment()).thenReturn("Excellent");
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        when(reviewRepository.findByIdWithAssociations(REVIEW_ID)).thenReturn(Optional.of(review));

        ReviewResponse response = reviewService.getReview(REVIEW_ID);

        assertThat(response.id()).isEqualTo(REVIEW_ID);
        assertThat(response.masterId()).isEqualTo(MASTER_ID);
        assertThat(response.clientDisplayName()).isEqualTo("Ivan P.");
        assertThat(response.serviceName()).isEqualTo("Manicure");
    }

    @Test
    @DisplayName("returns 'Anonymous' as the display name when the client has no first or last name")
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
        Booking booking = mockBookingWithServiceName("Manicure");
        when(review.getBooking()).thenReturn(booking);
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
    @DisplayName("returns first name only as display name when the client has no last name")
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
        Booking booking = mockBookingWithServiceName("Manicure");
        when(review.getBooking()).thenReturn(booking);
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
    @DisplayName("404 Not Found when the review ID does not exist")
    void should_throwNotFound_when_reviewByIdNotFound() {
        when(reviewRepository.findByIdWithAssociations(REVIEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getReview(REVIEW_ID))
                .isInstanceOf(NotFoundException.class);
    }

    // ── getMyReviews (Phase 19.4 — GET /reviews/me) ───────────────────────────

    @Test
    @DisplayName("getMyReviews — queries the repository keyed on the passed principal client id, not a parameter")
    void should_scopeQueryToPrincipalClientId_when_gettingMyReviews() {
        UUID principalClientId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        ArgumentCaptor<UUID> clientIdCaptor = ArgumentCaptor.forClass(UUID.class);

        MyReviewResponse row = new MyReviewResponse(
                REVIEW_ID, MASTER_ID, "Anna", "Smith", "Manicure",
                5, "Great service", Instant.parse("2026-06-01T10:00:00Z"), BOOKING_ID);
        Page<MyReviewResponse> repoPage = new PageImpl<>(List.of(row), pageable, 1);
        when(reviewRepository.findMyReviews(eq(principalClientId), any(Pageable.class)))
                .thenReturn(repoPage);

        PageResponse<MyReviewResponse> result = reviewService.getMyReviews(principalClientId, pageable);

        verify(reviewRepository).findMyReviews(clientIdCaptor.capture(), any(Pageable.class));
        assertThat(clientIdCaptor.getValue())
                .as("repository must be queried with the exact principal client id supplied by the controller")
                .isEqualTo(principalClientId);
        assertThat(result.data())
                .as("a single-row repository page must map to one MyReviewResponse")
                .hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("getMyReviews — maps every projection field through to the PageResponse row")
    void should_mapAllProjectionFields_when_gettingMyReviews() {
        UUID clientId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        Instant createdAt = Instant.parse("2026-05-20T08:30:00Z");

        MyReviewResponse row = new MyReviewResponse(
                REVIEW_ID, MASTER_ID, "Anna", "Smith", "Manicure",
                4, "Loved it", createdAt, BOOKING_ID);
        when(reviewRepository.findMyReviews(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));

        PageResponse<MyReviewResponse> result = reviewService.getMyReviews(clientId, pageable);

        MyReviewResponse mapped = result.data().get(0);
        assertThat(mapped)
                .extracting(
                        MyReviewResponse::id, MyReviewResponse::masterId,
                        MyReviewResponse::masterFirstName, MyReviewResponse::masterLastName,
                        MyReviewResponse::serviceName, MyReviewResponse::rating,
                        MyReviewResponse::comment, MyReviewResponse::createdAt,
                        MyReviewResponse::bookingId)
                .containsExactly(
                        REVIEW_ID, MASTER_ID, "Anna", "Smith", "Manicure",
                        4, "Loved it", createdAt, BOOKING_ID);
    }

    @Test
    @DisplayName("getMyReviews — returns an empty PageResponse when the client has authored no reviews")
    void should_returnEmptyPageResponse_when_clientHasNoReviews() {
        UUID clientId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(reviewRepository.findMyReviews(eq(clientId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        PageResponse<MyReviewResponse> result = reviewService.getMyReviews(clientId, pageable);

        assertThat(result.data())
                .as("an empty repository page must yield an empty data list")
                .isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    @DisplayName("getMyReviews — strips caller-supplied sort so the repository receives Sort.unsorted()")
    void should_stripCallerSort_when_gettingMyReviews() {
        UUID clientId = UUID.randomUUID();
        Pageable callerPageable = PageRequest.of(0, 20, Sort.by("comment"));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(reviewRepository.findMyReviews(eq(clientId), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        reviewService.getMyReviews(clientId, callerPageable);

        verify(reviewRepository).findMyReviews(eq(clientId), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort())
                .as("repository must receive Sort.unsorted() — JPQL hardcodes ORDER BY createdAt DESC")
                .isEqualTo(Sort.unsorted());
    }

    @Test
    @DisplayName("getMyReviews — throws 400 when the page number exceeds the MAX_PAGE_NUMBER guard")
    void should_throw400_when_myReviewsPageNumberExceedsMaximum() {
        UUID clientId = UUID.randomUUID();
        Pageable overLimit = PageRequest.of(10_001, 20);

        assertThatThrownBy(() -> reviewService.getMyReviews(clientId, overLimit))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(reviewRepository, never()).findMyReviews(any(), any());
    }

    // ── getReviewsForMaster — timing oracle (FIX 5) ──────────────────────────

    @Test
    @DisplayName("returns an empty page via getReview path when the master ID does not exist")
    void should_return_empty_page_when_masterId_does_not_exist() {
        // Arrange — unknown master: repository returns empty page (no rows)
        UUID unknownMasterId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);

        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(unknownMasterId, PageRequest.of(0, 20)))
                .thenReturn(Page.empty(pageable));

        // Act — must not throw NotFoundException; same shape as a master with zero reviews
        Page<ReviewResponse> result = reviewService.getReviewsForMaster(unknownMasterId, SalonReviewSort.NEWEST, pageable);

        // Assert — empty page returned; timing oracle is not present
        assertThat(result.isEmpty())
                .as("unknown master must produce an empty page, not an exception")
                .isTrue();
        assertThat(result.getTotalElements())
                .as("total elements must be 0 for unknown master")
                .isZero();
    }

    // ── getSalonReviewSummary (Phase 13.6 — Public Salon Profile) ───────────────

    @Test
    @DisplayName("getSalonReviewSummary — zero-fills every rating bucket from 5 down to 1")
    void should_zeroFillAllBuckets_when_gettingSalonReviewSummary() {
        UUID salonId = UUID.randomUUID();
        Salon salon = mock(Salon.class);
        when(salon.getReviewCount()).thenReturn(3);
        when(salon.getAvgRating()).thenReturn(new BigDecimal("4.33"));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        // Only ratings 5 and 4 actually have reviews — 3, 2, 1 must still appear as count=0.
        com.beautica.review.repository.RatingCountProjection fiveStar =
                mock(com.beautica.review.repository.RatingCountProjection.class);
        when(fiveStar.getRating()).thenReturn(5);
        when(fiveStar.getCount()).thenReturn(2L);
        com.beautica.review.repository.RatingCountProjection fourStar =
                mock(com.beautica.review.repository.RatingCountProjection.class);
        when(fourStar.getRating()).thenReturn(4);
        when(fourStar.getCount()).thenReturn(1L);

        when(reviewRepository.countBySalonIdGroupByRating(salonId))
                .thenReturn(List.of(fiveStar, fourStar));

        var result = reviewService.getSalonReviewSummary(salonId);

        assertThat(result.ratingDistribution())
                .as("distribution must always carry exactly 5 buckets, ratings 5..1")
                .extracting(RatingBucket::rating)
                .containsExactly(5, 4, 3, 2, 1);
        assertThat(result.ratingDistribution())
                .extracting(RatingBucket::count)
                .containsExactly(2L, 1L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("getSalonReviewSummary — avgRating is null when reviewCount is 0")
    void should_returnNullAvgRating_when_salonReviewCountIsZero() {
        UUID salonId = UUID.randomUUID();
        Salon salon = mock(Salon.class);
        when(salon.getReviewCount()).thenReturn(0);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(reviewRepository.countBySalonIdGroupByRating(salonId)).thenReturn(List.of());

        var result = reviewService.getSalonReviewSummary(salonId);

        assertThat(result.avgRating()).isNull();
        assertThat(result.reviewCount()).isZero();
        assertThat(result.ratingDistribution()).hasSize(5);
        assertThat(result.ratingDistribution())
                .allMatch(bucket -> bucket.count() == 0L);
    }

    @Test
    @DisplayName("getSalonReviewSummary — 404 Not Found when the salon does not exist")
    void should_throwNotFound_when_salonDoesNotExistForSummary() {
        UUID salonId = UUID.randomUUID();
        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getSalonReviewSummary(salonId))
                .isInstanceOf(NotFoundException.class);

        verify(reviewRepository, never()).countBySalonIdGroupByRating(any());
    }

    // ── getSalonReviews (Phase 13.6 — sort-mapping) ─────────────────────────────

    private Review buildSalonReview(UUID reviewId, short rating) {
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Anna");
        when(client.getLastName()).thenReturn("Koval");

        User masterUser = mock(User.class);
        when(masterUser.getFirstName()).thenReturn("Iryna");
        when(masterUser.getLastName()).thenReturn("Shevchenko");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        when(master.getUser()).thenReturn(masterUser);

        com.beautica.service.entity.ServiceDefinition serviceDefinition =
                mock(com.beautica.service.entity.ServiceDefinition.class);
        when(serviceDefinition.getName()).thenReturn("Manicure");

        com.beautica.service.entity.MasterServiceAssignment masterService =
                mock(com.beautica.service.entity.MasterServiceAssignment.class);
        when(masterService.getServiceDefinition()).thenReturn(serviceDefinition);

        Booking booking = mock(Booking.class);
        when(booking.getMasterService()).thenReturn(masterService);

        Review review = mock(Review.class);
        when(review.getId()).thenReturn(reviewId);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getBooking()).thenReturn(booking);
        when(review.getRating()).thenReturn(rating);
        when(review.getComment()).thenReturn("Great service");
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        return review;
    }

    @Test
    @DisplayName("getSalonReviews — NEWEST dispatches to findIdsBySalonIdOrderByCreatedAtDesc")
    void should_dispatchToCreatedAtDesc_when_sortIsNewest() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(reviewRepository.findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        reviewService.getSalonReviews(salonId, com.beautica.review.dto.SalonReviewSort.NEWEST, pageable);

        verify(reviewRepository).findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), any(Pageable.class));
        verify(reviewRepository, never()).findIdsBySalonIdOrderByCreatedAtAsc(any(), any());
        verify(reviewRepository, never()).findIdsBySalonIdOrderByRatingDescCreatedAtDesc(any(), any());
        verify(reviewRepository, never()).findIdsBySalonIdOrderByRatingAscCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("getSalonReviews — OLDEST dispatches to findIdsBySalonIdOrderByCreatedAtAsc")
    void should_dispatchToCreatedAtAsc_when_sortIsOldest() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(reviewRepository.findIdsBySalonIdOrderByCreatedAtAsc(eq(salonId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        reviewService.getSalonReviews(salonId, com.beautica.review.dto.SalonReviewSort.OLDEST, pageable);

        verify(reviewRepository).findIdsBySalonIdOrderByCreatedAtAsc(eq(salonId), any(Pageable.class));
        verify(reviewRepository, never()).findIdsBySalonIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("getSalonReviews — HIGHEST dispatches to findIdsBySalonIdOrderByRatingDescCreatedAtDesc")
    void should_dispatchToRatingDesc_when_sortIsHighest() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(reviewRepository.findIdsBySalonIdOrderByRatingDescCreatedAtDesc(eq(salonId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        reviewService.getSalonReviews(salonId, com.beautica.review.dto.SalonReviewSort.HIGHEST, pageable);

        verify(reviewRepository).findIdsBySalonIdOrderByRatingDescCreatedAtDesc(eq(salonId), any(Pageable.class));
    }

    @Test
    @DisplayName("getSalonReviews — LOWEST dispatches to findIdsBySalonIdOrderByRatingAscCreatedAtDesc")
    void should_dispatchToRatingAsc_when_sortIsLowest() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(reviewRepository.findIdsBySalonIdOrderByRatingAscCreatedAtDesc(eq(salonId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        reviewService.getSalonReviews(salonId, com.beautica.review.dto.SalonReviewSort.LOWEST, pageable);

        verify(reviewRepository).findIdsBySalonIdOrderByRatingAscCreatedAtDesc(eq(salonId), any(Pageable.class));
    }

    @Test
    @DisplayName("getSalonReviews — defaults to NEWEST when sort is null")
    void should_defaultToNewest_when_sortIsNull() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(reviewRepository.findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        reviewService.getSalonReviews(salonId, null, pageable);

        verify(reviewRepository).findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), any(Pageable.class));
    }

    @Test
    @DisplayName("getSalonReviews — strips caller-supplied sort so the repository receives Sort.unsorted()")
    void should_stripCallerSort_when_gettingSalonReviews() {
        UUID salonId = UUID.randomUUID();
        Pageable callerPageable = PageRequest.of(0, 20, Sort.by("comment"));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(reviewRepository.findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        reviewService.getSalonReviews(salonId, com.beautica.review.dto.SalonReviewSort.NEWEST, callerPageable);

        verify(reviewRepository).findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    @DisplayName("getSalonReviews — returns an empty page when the salon has no reviews")
    void should_returnEmptyPage_when_salonHasNoReviews() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(reviewRepository.findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        Page<com.beautica.review.dto.SalonReviewResponse> result =
                reviewService.getSalonReviews(salonId, com.beautica.review.dto.SalonReviewSort.NEWEST, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(reviewRepository, never()).findByIdsWithGraphForSalonReviews(any());
    }

    @Test
    @DisplayName("getSalonReviews — hydrates and maps rows, reordering them back into the ID-list order")
    void should_returnMappedReviews_when_salonHasReviews() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        UUID reviewId = UUID.randomUUID();
        Review review = buildSalonReview(reviewId, (short) 5);

        Page<UUID> idPage = new PageImpl<>(List.of(reviewId), pageable, 1);
        when(reviewRepository.findIdsBySalonIdOrderByCreatedAtDesc(eq(salonId), any(Pageable.class)))
                .thenReturn(idPage);
        when(reviewRepository.findByIdsWithGraphForSalonReviews(List.of(reviewId)))
                .thenReturn(List.of(review));

        Page<com.beautica.review.dto.SalonReviewResponse> result =
                reviewService.getSalonReviews(salonId, com.beautica.review.dto.SalonReviewSort.NEWEST, pageable);

        assertThat(result.getContent()).hasSize(1);
        var mapped = result.getContent().get(0);
        assertThat(mapped.id()).isEqualTo(reviewId);
        assertThat(mapped.masterId()).isEqualTo(MASTER_ID);
        assertThat(mapped.masterFirstName()).isEqualTo("Iryna");
        assertThat(mapped.serviceName()).isEqualTo("Manicure");
        assertThat(mapped.clientDisplayName()).isEqualTo("Anna K.");
        assertThat(mapped.rating()).isEqualTo(5);
    }

    @Test
    @DisplayName("getSalonReviews — throws 400 when the page number exceeds the MAX_PAGE_NUMBER guard")
    void should_throw400_when_salonReviewsPageNumberExceedsMaximum() {
        UUID salonId = UUID.randomUUID();
        Pageable overLimit = PageRequest.of(10_001, 20);

        assertThatThrownBy(() -> reviewService.getSalonReviews(
                salonId, com.beautica.review.dto.SalonReviewSort.NEWEST, overLimit))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(reviewRepository, never()).findIdsBySalonIdOrderByCreatedAtDesc(any(), any());
    }
}
