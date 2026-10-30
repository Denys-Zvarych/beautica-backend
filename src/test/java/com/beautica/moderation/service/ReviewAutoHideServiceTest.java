package com.beautica.moderation.service;

import com.beautica.moderation.config.ModerationProperties;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.service.ReviewModerationService;
import com.beautica.review.service.ReviewModerationService.LockedReview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewAutoHideService — unit (phase 396 D2 + burst guard + per-provider cap)")
class ReviewAutoHideServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final Instant WINDOW_START = NOW.minus(Duration.ofMinutes(10));
    private static final Instant CAP_WINDOW_START = NOW.minus(Duration.ofHours(24));
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID SALON_ID = UUID.randomUUID();

    @Mock
    private ContentReportRepository repository;

    @Mock
    private ReviewModerationService reviewModerationService;

    @Mock
    private ProviderModerationLock providerLock;

    private ReviewAutoHideService service;

    @BeforeEach
    void setUp() {
        ModerationProperties properties = new ModerationProperties(3, 5, Duration.ofMinutes(10),
                1, Duration.ofHours(24), new ModerationProperties.Retention(false, "0 37 3 * * *", 500, 200));
        service = new ReviewAutoHideService(repository, reviewModerationService, providerLock, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("3 eligible reporters on a VISIBLE review, no burst, cap free → auto-hidden, not a burst")
    void should_hideReview_when_thresholdReached() {
        LockedReview locked = stubLocked(ModerationStatus.VISIBLE);
        when(repository.countRecentEligibleOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START)).thenReturn(3L);
        when(repository.findEligibleOpenReporterIds(REVIEW_ID)).thenReturn(threeReporters());
        when(reviewModerationService.countProviderAutoHidesSince(locked, CAP_WINDOW_START)).thenReturn(0L);

        boolean burst = service.onReviewReported(REVIEW_ID);

        assertThat(burst).isFalse();
        verify(reviewModerationService).autoHideLockedReview(locked, NOW);
        verify(repository, never()).holdOpenReportsOnReview(any());
    }

    @Test
    @DisplayName("row lock, then provider advisory lock, then the counts (D20 lock order)")
    void should_lockProviderAfterReviewRow_when_reportArrives() {
        LockedReview locked = stubLocked(ModerationStatus.VISIBLE);
        when(repository.countRecentEligibleOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START)).thenReturn(0L);
        when(repository.findEligibleOpenReporterIds(REVIEW_ID)).thenReturn(List.of());

        service.onReviewReported(REVIEW_ID);

        InOrder order = inOrder(reviewModerationService, providerLock, repository);
        order.verify(reviewModerationService).lockReview(REVIEW_ID);
        order.verify(providerLock).lock(locked);
        order.verify(repository).countRecentEligibleOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START);
    }

    @Test
    @DisplayName("threshold reached but the provider already had 1 auto-hide in 24 h → reports held, [BURST], no hide")
    void should_holdSecondAutoHide_when_providerHidCapReachedIn24h() {
        LockedReview locked = stubLocked(ModerationStatus.VISIBLE);
        when(repository.countRecentEligibleOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START)).thenReturn(3L);
        when(repository.findEligibleOpenReporterIds(REVIEW_ID)).thenReturn(threeReporters());
        when(reviewModerationService.countProviderAutoHidesSince(locked, CAP_WINDOW_START)).thenReturn(1L);

        boolean burst = service.onReviewReported(REVIEW_ID);

        assertThat(burst).as("flags the support e-mail").isTrue();
        verify(repository).holdOpenReportsOnReview(REVIEW_ID);
        verify(reviewModerationService, never()).autoHideLockedReview(any(), any());
    }

    @Test
    @DisplayName("2 eligible reporters (staff / non-booking reporters are filtered by the query) → stays VISIBLE")
    void should_notHide_when_belowThreshold() {
        stubLocked(ModerationStatus.VISIBLE);
        when(repository.countRecentEligibleOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START)).thenReturn(4L);
        when(repository.findEligibleOpenReporterIds(REVIEW_ID)).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        boolean burst = service.onReviewReported(REVIEW_ID);

        assertThat(burst).isFalse();
        verify(reviewModerationService, never()).countProviderAutoHidesSince(any(), any());
        verify(reviewModerationService, never()).autoHideLockedReview(any(), any());
    }

    @Test
    @DisplayName("5 eligible OPEN reports on the provider within the window → reports held, burst, no auto-hide count")
    void should_holdAndReportBurst_when_burstThresholdReached() {
        stubLocked(ModerationStatus.VISIBLE);
        when(repository.countRecentEligibleOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START)).thenReturn(5L);

        boolean burst = service.onReviewReported(REVIEW_ID);

        assertThat(burst).isTrue();
        verify(repository).holdRecentOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START);
        verify(repository, never()).findEligibleOpenReporterIds(any());
        verify(reviewModerationService, never()).autoHideLockedReview(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ModerationStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("an already hidden / removed review → no recount")
    void should_notRecount_when_reviewNotVisible(ModerationStatus status) {
        stubLocked(status);
        when(repository.countRecentEligibleOpenReviewReportsForProvider(REVIEW_ID, WINDOW_START)).thenReturn(1L);

        boolean burst = service.onReviewReported(REVIEW_ID);

        assertThat(burst).isFalse();
        verify(repository, never()).findEligibleOpenReporterIds(any());
        verify(reviewModerationService, never()).autoHideLockedReview(any(), any());
    }

    @Test
    @DisplayName("review vanished between intake and lock → nothing to do, no provider lock")
    void should_doNothing_when_reviewGone() {
        when(reviewModerationService.lockReview(REVIEW_ID)).thenReturn(Optional.empty());

        boolean burst = service.onReviewReported(REVIEW_ID);

        assertThat(burst).isFalse();
        verifyNoInteractions(repository, providerLock);
    }

    private LockedReview stubLocked(ModerationStatus status) {
        LockedReview locked = new LockedReview(REVIEW_ID, MASTER_ID, SALON_ID, status);
        when(reviewModerationService.lockReview(REVIEW_ID)).thenReturn(Optional.of(locked));
        return locked;
    }

    private static List<UUID> threeReporters() {
        return List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
