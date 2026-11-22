package com.beautica.review.service;

import com.beautica.common.RatingBucket;
import com.beautica.common.exception.NotFoundException;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.RatingCountProjection;
import com.beautica.user.UserRatingProjection;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientRatingSummaryService — B526")
class ClientRatingSummaryServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ClientReviewRepository clientReviewRepository;
    @InjectMocks private ClientRatingSummaryService service;

    private final UUID userId = UUID.randomUUID();

    private static RatingCountProjection bucket(int rating, long count) {
        RatingCountProjection p = mock(RatingCountProjection.class);
        when(p.getRating()).thenReturn(rating);
        when(p.getCount()).thenReturn(count);
        return p;
    }

    private static UserRatingProjection projection(BigDecimal avg, int count) {
        UserRatingProjection p = mock(UserRatingProjection.class);
        when(p.getAvgRating()).thenReturn(avg);
        when(p.getReviewCount()).thenReturn(count);
        return p;
    }

    @Test
    @DisplayName("never reviewed: null avg, count 0, five zero buckets 5 down to 1")
    void should_returnNullAvgAndZeroBuckets_when_neverReviewed() {
        UserRatingProjection scalars = projection(null, 0);
        when(userRepository.findRatingById(userId)).thenReturn(Optional.of(scalars));
        when(clientReviewRepository.countBySubjectClientIdGroupByRating(userId)).thenReturn(List.of());

        ClientRatingSummary summary = service.summaryFor(userId);

        assertThat(summary.avg()).isNull();
        assertThat(summary.count()).isZero();
        assertThat(summary.distribution()).containsExactly(new RatingBucket(5, 0), new RatingBucket(4, 0),
                new RatingBucket(3, 0), new RatingBucket(2, 0), new RatingBucket(1, 0));
    }

    @Test
    @DisplayName("partial ratings: missing buckets zero-filled, order stays 5 down to 1")
    void should_zeroFillAndOrderDescending_when_someRatingsExist() {
        UserRatingProjection scalars = projection(new BigDecimal("4.00"), 2);
        List<RatingCountProjection> rows = List.of(bucket(3, 1), bucket(5, 1));
        when(userRepository.findRatingById(userId)).thenReturn(Optional.of(scalars));
        when(clientReviewRepository.countBySubjectClientIdGroupByRating(userId)).thenReturn(rows);

        ClientRatingSummary summary = service.summaryFor(userId);

        assertThat(summary.avg()).isEqualByComparingTo("4.00");
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.distribution()).containsExactly(new RatingBucket(5, 1), new RatingBucket(4, 0),
                new RatingBucket(3, 1), new RatingBucket(2, 0), new RatingBucket(1, 0));
    }

    @Test
    @DisplayName("unknown user: NotFoundException")
    void should_throwNotFound_when_userMissing() {
        when(userRepository.findRatingById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summaryFor(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("zeroDistribution: five zero buckets 5 down to 1")
    void should_buildZeroBuckets_when_zeroDistributionCalled() {
        assertThat(ClientRatingSummaryService.zeroDistribution()).extracting(RatingBucket::rating)
                .containsExactly(5, 4, 3, 2, 1);
    }
}
