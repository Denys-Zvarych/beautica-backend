package com.beautica.booking.dto;

import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit coverage for {@link BookableMasterResponse#from(MasterServiceAssignment)} — the
 * booking-flow master picker behind {@code GET /salons/{salonId}/services/{serviceDefId}/masters}.
 *
 * <p><b>Why the zero-review case matters here specifically.</b> {@code masters.avg_rating} is
 * {@code NOT NULL DEFAULT 0.00} (V4), so an unreviewed master persists a literal zero that is a
 * storage artefact, not a rating. This record's javadoc promises field-name parity with
 * {@link com.beautica.master.dto.MasterSummaryResponse} so the mobile booking screen can swap
 * data sources 1:1 — parity that is actively misleading if the two DTOs disagree about the VALUE
 * for the same master. Phase 240 (Finding 3) routed both through
 * {@link BookingDetailResponse#masterAvgRatingOrNull}; this class is the guard for the booking
 * half, which had no test file at all before.
 */
@DisplayName("BookableMasterResponse.from — booking-picker projection")
class BookableMasterResponseTest {

    @Test
    @DisplayName("nulls avgRating when the master has no reviews — never the stored 0.00")
    void should_returnNullAvgRating_when_masterHasNoReviews() {
        // A persisted-but-meaningless 0.00 is stubbed on purpose: the suppression must key off
        // the review COUNT, not off the rating being null or zero. Returning the raw value here
        // would render a phantom «0.0» star row next to a master who has simply never been rated.
        MasterServiceAssignment assignment =
                assignmentOf(0, new BigDecimal("0.00"));

        BookableMasterResponse response = BookableMasterResponse.from(assignment);

        assertThat(response.avgRating())
                .as("an unreviewed master must be presented as 'no reviews yet', not 0.0")
                .isNull();
        assertThat(response.reviewCount())
                .as("zero reviews is a true fact and stays 0, unlike the average")
                .isZero();
    }

    @Test
    @DisplayName("surfaces a genuine 1.00 average from a single one-star review")
    void should_surfaceGenuineAvgRating_when_masterHasExactlyOneReview() {
        // The boundary that separates "suppressed artefact" from "real bad rating": count 1.
        // A suppression keyed off the rating VALUE rather than the count would be indetectable
        // at 4.50 but would wrongly hide this genuinely poor 1.00.
        MasterServiceAssignment assignment =
                assignmentOf(1, new BigDecimal("1.00"));

        BookableMasterResponse response = BookableMasterResponse.from(assignment);

        assertThat(response.avgRating())
                .as("a real one-star rating must survive normalisation, actual=%s",
                        response.avgRating())
                .isEqualByComparingTo("1.00");
        assertThat(response.reviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("carries masterId, masterServiceId and the User-sourced identity fields")
    void should_projectAllIdentityFields_when_assignmentIsHydrated() {
        UUID masterId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Oksana");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getProfessionalTitle()).thenReturn("Майстер манікюру");
        when(user.getAvatarUrl()).thenReturn("https://cdn.beautica.test/oksana.png");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getReviewCount()).thenReturn(12);
        when(master.getAvgRating()).thenReturn(new BigDecimal("4.75"));

        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getMaster()).thenReturn(master);
        when(assignment.getId()).thenReturn(assignmentId);

        BookableMasterResponse response = BookableMasterResponse.from(assignment);

        assertThat(response)
                .as("every projected field must come from the stated source — masterServiceId is "
                    + "the ASSIGNMENT id, not the master id, and mixing them would send the "
                    + "downstream slot/booking calls to the wrong key")
                .extracting(
                        BookableMasterResponse::masterId,
                        BookableMasterResponse::masterServiceId,
                        BookableMasterResponse::firstName,
                        BookableMasterResponse::lastName,
                        BookableMasterResponse::professionalTitle,
                        BookableMasterResponse::avatarUrl,
                        BookableMasterResponse::reviewCount)
                .containsExactly(
                        masterId,
                        assignmentId,
                        "Oksana",
                        "Kovalenko",
                        "Майстер манікюру",
                        "https://cdn.beautica.test/oksana.png",
                        12);
        assertThat(response.avgRating()).isEqualByComparingTo("4.75");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Minimal hydrated assignment carrying only the rating state under test. */
    private static MasterServiceAssignment assignmentOf(int reviewCount, BigDecimal avgRating) {
        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(mock(User.class));
        when(master.getReviewCount()).thenReturn(reviewCount);
        when(master.getAvgRating()).thenReturn(avgRating);

        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getMaster()).thenReturn(master);
        return assignment;
    }
}
