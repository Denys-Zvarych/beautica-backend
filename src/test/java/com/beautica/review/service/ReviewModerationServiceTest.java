package com.beautica.review.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.master.entity.Master;
import com.beautica.review.entity.ClientReview;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.entity.Review;
import com.beautica.review.event.ClientReviewModerationChangedEvent;
import com.beautica.review.event.ReviewModerationChangedEvent;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewModerationService — status transitions + recalculation events (phase 396 D3)")
class ReviewModerationServiceTest {

    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID MASTER_USER_ID = UUID.randomUUID();
    private static final UUID SALON_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ClientReviewRepository clientReviewRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReviewModerationService service;

    @BeforeEach
    void setUp() {
        service = new ReviewModerationService(reviewRepository, clientReviewRepository, eventPublisher);
    }

    @Test
    @DisplayName("lockReview → ids + current status of the locked row")
    void should_returnLockedSnapshot_when_reviewExists() {
        when(reviewRepository.findByIdForUpdate(REVIEW_ID)).thenReturn(Optional.of(review()));

        Optional<ReviewModerationService.LockedReview> locked = service.lockReview(REVIEW_ID);

        assertThat(locked).contains(new ReviewModerationService.LockedReview(
                REVIEW_ID, MASTER_ID, SALON_ID, ModerationStatus.VISIBLE));
    }

    @Test
    @DisplayName("moderateLockedReview VISIBLE → REMOVED: loads via the persistence context (no 2nd FOR UPDATE), "
            + "publishes the recalculation event with master, master user and salon")
    void should_publishEvent_when_statusChanges() {
        Review review = review();
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        service.moderateLockedReview(locked(ModerationStatus.VISIBLE), ModerationStatus.REMOVED);

        assertThat(review.getModerationStatus()).isEqualTo(ModerationStatus.REMOVED);
        verify(reviewRepository, never()).findByIdForUpdate(any());
        verify(eventPublisher).publishEvent(
                new ReviewModerationChangedEvent(REVIEW_ID, MASTER_ID, MASTER_USER_ID, SALON_ID));
    }

    @Test
    @DisplayName("same status → no event (no needless recalculation)")
    void should_notPublish_when_statusUnchanged() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review()));

        service.moderateLockedReview(locked(ModerationStatus.VISIBLE), ModerationStatus.VISIBLE);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("REMOVED is final: a late RESTORE / DISMISS → 409, stays REMOVED, no event")
    void should_conflict_when_restoreAfterRemove() {
        Review review = review();
        review.moderate(ModerationStatus.REMOVED);
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.moderateLockedReview(locked(ModerationStatus.REMOVED), ModerationStatus.VISIBLE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(review.getModerationStatus()).isEqualTo(ModerationStatus.REMOVED);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("REMOVE of an already REMOVED review → no-op, no event, no 409")
    void should_noop_when_removeAfterRemove() {
        Review review = review();
        review.moderate(ModerationStatus.REMOVED);
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        service.moderateLockedReview(locked(ModerationStatus.REMOVED), ModerationStatus.REMOVED);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("autoHideLockedReview VISIBLE → HIDDEN_PENDING, stamps auto_hidden_at, publishes the event")
    void should_stampAutoHiddenAt_when_autoHidden() {
        Review review = review();
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        boolean changed = service.autoHideLockedReview(locked(ModerationStatus.VISIBLE), NOW);

        assertThat(changed).isTrue();
        assertThat(review.getModerationStatus()).isEqualTo(ModerationStatus.HIDDEN_PENDING);
        assertThat(review.getAutoHiddenAt()).isEqualTo(NOW);
        verify(eventPublisher).publishEvent(
                new ReviewModerationChangedEvent(REVIEW_ID, MASTER_ID, MASTER_USER_ID, SALON_ID));
    }

    @Test
    @DisplayName("countProviderAutoHidesSince delegates to the provider-scoped count of the locked review")
    void should_countProviderAutoHides_when_asked() {
        when(reviewRepository.countAutoHiddenForProviderSince(REVIEW_ID, NOW)).thenReturn(2L);

        assertThat(service.countProviderAutoHidesSince(locked(ModerationStatus.VISIBLE), NOW)).isEqualTo(2L);
    }

    @Test
    @DisplayName("client review REMOVED → a late RESTORE is 409 too")
    void should_conflict_when_clientReviewRestoreAfterRemove() {
        ClientReview review = ClientReview.builder().subjectClient(user(CLIENT_ID)).build();
        review.moderate(ModerationStatus.REMOVED);
        when(clientReviewRepository.findByIdForUpdate(REVIEW_ID)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.moderateClientReview(REVIEW_ID, ModerationStatus.VISIBLE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("client review REMOVED → client-rating recalculation event for the reviewed client")
    void should_publishClientEvent_when_clientReviewRemoved() {
        ClientReview review = ClientReview.builder().subjectClient(user(CLIENT_ID)).build();
        when(clientReviewRepository.findByIdForUpdate(REVIEW_ID)).thenReturn(Optional.of(review));

        boolean found = service.moderateClientReview(REVIEW_ID, ModerationStatus.REMOVED);

        assertThat(found).isTrue();
        verify(eventPublisher).publishEvent(new ClientReviewModerationChangedEvent(CLIENT_ID));
    }

    @Test
    @DisplayName("missing client review → false, no event")
    void should_returnFalse_when_clientReviewMissing() {
        when(clientReviewRepository.findByIdForUpdate(REVIEW_ID)).thenReturn(Optional.empty());

        assertThat(service.moderateClientReview(REVIEW_ID, ModerationStatus.REMOVED)).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    private static ReviewModerationService.LockedReview locked(ModerationStatus status) {
        return new ReviewModerationService.LockedReview(REVIEW_ID, MASTER_ID, SALON_ID, status);
    }

    private static Review review() {
        Master master = BeanUtils.instantiateClass(Master.class);
        ReflectionTestUtils.setField(master, "id", MASTER_ID);
        ReflectionTestUtils.setField(master, "user", user(MASTER_USER_ID));
        Salon salon = BeanUtils.instantiateClass(Salon.class);
        ReflectionTestUtils.setField(salon, "id", SALON_ID);
        Review review = Review.builder().master(master).salon(salon).build();
        ReflectionTestUtils.setField(review, "id", REVIEW_ID);
        return review;
    }

    private static User user(UUID id) {
        User user = BeanUtils.instantiateClass(User.class);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
