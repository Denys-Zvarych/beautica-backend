package com.beautica.master.dto;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit coverage for {@link MasterSummaryResponse#from(Master)} — no Spring
 * context, the projection lives entirely in the static factory.
 *
 * <p>V110 regression guard: the salon-master list card (GET /masters/by-salon)
 * must surface the provider's {@code professionalTitle} headline. The value is
 * read off the already graph-fetched {@link User}, so a mapping omission would
 * silently drop the field from every list row.
 */
@DisplayName("MasterSummaryResponse.from — projection carries professionalTitle (V110)")
class MasterSummaryResponseTest {

    @Test
    @DisplayName("from carries professionalTitle read off the linked User")
    void should_carryProfessionalTitle_when_userHasTitle() {
        User user = mock(User.class);
        UUID userId = UUID.randomUUID();
        when(user.getId()).thenReturn(userId);
        when(user.getFirstName()).thenReturn("Oksana");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getProfessionalTitle()).thenReturn("Майстер манікюру");

        Master master = mock(Master.class);
        UUID masterId = UUID.randomUUID();
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getAvgRating()).thenReturn(new BigDecimal("4.50"));
        when(master.getReviewCount()).thenReturn(7);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);

        MasterSummaryResponse summary = MasterSummaryResponse.from(master);

        assertThat(summary)
                .extracting(MasterSummaryResponse::masterId,
                        MasterSummaryResponse::firstName,
                        MasterSummaryResponse::lastName,
                        MasterSummaryResponse::professionalTitle,
                        MasterSummaryResponse::masterType)
                .containsExactly(masterId, "Oksana", "Kovalenko", "Майстер манікюру", MasterType.SALON_MASTER);
    }

    @Test
    @DisplayName("from carries a null professionalTitle when the User has no title set")
    void should_carryNullProfessionalTitle_when_userHasNoTitle() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getProfessionalTitle()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(UUID.randomUUID());
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        MasterSummaryResponse summary = MasterSummaryResponse.from(master);

        assertThat(summary.professionalTitle())
                .as("a provider with no headline projects a null professionalTitle, never a placeholder")
                .isNull();
    }

    // ── Zero-review rating normalisation (Phase 240 audit, Finding 3) ─────────────────

    @Test
    @DisplayName("from nulls avgRating when the master has no reviews")
    void should_returnNullAvgRating_when_masterHasNoReviews() {
        User user = mock(User.class);
        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(user);
        when(master.getReviewCount()).thenReturn(0);
        // Persisted-but-meaningless value: masters.avg_rating is NOT NULL DEFAULT 0.00 (V4),
        // so the suppression must key off the count, not off the rating being null.
        when(master.getAvgRating()).thenReturn(new BigDecimal("0.00"));

        MasterSummaryResponse summary = MasterSummaryResponse.from(master);

        assertThat(summary.avgRating())
                .as("the salon roster must render 'no reviews yet', not a phantom 0.0")
                .isNull();
        assertThat(summary.reviewCount()).isZero();
    }

    @Test
    @DisplayName("from surfaces the persisted avgRating once the master has reviews")
    void should_surfacePersistedAvgRating_when_masterHasReviews() {
        User user = mock(User.class);
        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(user);
        when(master.getReviewCount()).thenReturn(7);
        when(master.getAvgRating()).thenReturn(new BigDecimal("4.50"));

        MasterSummaryResponse summary = MasterSummaryResponse.from(master);

        assertThat(summary.avgRating()).isEqualByComparingTo("4.50");
    }
}
