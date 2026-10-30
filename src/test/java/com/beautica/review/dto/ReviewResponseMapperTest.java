package com.beautica.review.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.review.entity.Review;
import com.beautica.review.entity.ReviewSource;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Phase 399 D2 — the public review shapes render a {@link ReviewSource#GUEST_LINK} review under its
 * {@code guestDisplayName} snapshot with {@code source = GUEST_LINK}, never touching the (absent) account, while an
 * {@link ReviewSource#APP} review keeps its existing name rules and gains {@code source = APP}. Built from the real
 * {@link Review#guestLinkReview} factory (spied only for the DB-assigned id / createdAt).
 */
@DisplayName("ReviewResponse / SalonReviewResponse author mapping — phase 399 unit")
class ReviewResponseMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-28T10:00:00Z");
    private static final String GUEST_NAME = "Марія";
    private static final String GUEST_SURNAME = "Шевченко";
    private static final String GUEST_LABEL = "Марія Ш.";

    @Test
    @DisplayName("guest review — ReviewResponse shows the guest display name and source GUEST_LINK")
    void should_renderGuestDisplayName_when_reviewResponseFromGuestReview() {
        Review review = persisted(
                Review.guestLinkReview(booking(), (short) 5, "Дуже сподобалось", GUEST_NAME, GUEST_SURNAME));

        ReviewResponse response = ReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo(GUEST_LABEL);
        assertThat(response.source()).isEqualTo(ReviewSource.GUEST_LINK);
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Дуже сподобалось");
        assertThat(response.authoredByViewer()).isFalse();
    }

    @Test
    @DisplayName("guest review — SalonReviewResponse shows the guest display name and source GUEST_LINK")
    void should_renderGuestDisplayName_when_salonReviewResponseFromGuestReview() {
        Review review = persisted(Review.guestLinkReview(booking(), (short) 4, null, GUEST_NAME, GUEST_SURNAME));

        SalonReviewResponse response = SalonReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo(GUEST_LABEL);
        assertThat(response.source()).isEqualTo(ReviewSource.GUEST_LINK);
        assertThat(response.masterFirstName()).isEqualTo("Ірина");
    }

    @Test
    @DisplayName("guest review — the factory leaves the account empty and copies master + salon from the booking")
    void should_buildAccountlessGuestReview_when_factoryUsed() {
        Booking booking = booking();

        Review review = Review.guestLinkReview(booking, (short) 3, "Норм", GUEST_NAME, GUEST_SURNAME);

        assertThat(review.getClient()).isNull();
        assertThat(review.getSource()).isEqualTo(ReviewSource.GUEST_LINK);
        assertThat(review.getGuestDisplayName()).isEqualTo(GUEST_LABEL);
        assertThat(review.getMaster()).isSameAs(booking.getMaster());
        assertThat(review.getBooking()).isSameAs(booking);
    }

    @Test
    @DisplayName("app review — default source APP, existing «Ім'я П.» rule unchanged")
    void should_keepAccountNameAndSourceApp_when_appReview() {
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Олена");
        when(client.getLastName()).thenReturn("Кравець");
        Booking booking = booking();
        Review review = persisted(Review.builder()
                .booking(booking).client(client).master(booking.getMaster()).rating((short) 5).build());

        ReviewResponse response = ReviewResponse.from(review);
        SalonReviewResponse salonResponse = SalonReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo("Олена К.");
        assertThat(response.source()).isEqualTo(ReviewSource.APP);
        assertThat(salonResponse.clientDisplayName()).isEqualTo("Олена К.");
        assertThat(salonResponse.source()).isEqualTo(ReviewSource.APP);
    }

    @Test
    @DisplayName("app review of a self-deleted author — still the detached label, source APP")
    void should_renderDetachedLabel_when_appReviewHasNoClient() {
        Booking booking = booking();
        Review review = persisted(Review.builder()
                .booking(booking).master(booking.getMaster()).rating((short) 2).build());

        ReviewResponse response = ReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo(ReviewResponse.DETACHED_CLIENT_LABEL);
        assertThat(response.source()).isEqualTo(ReviewSource.APP);
    }

    @Test
    @DisplayName("withAuthoredByViewer keeps the source")
    void should_keepSource_when_authoredByViewerStamped() {
        Review review = persisted(Review.guestLinkReview(booking(), (short) 5, null, GUEST_NAME, GUEST_SURNAME));

        ReviewResponse stamped = ReviewResponse.from(review).withAuthoredByViewer(true);
        SalonReviewResponse salonStamped = SalonReviewResponse.from(review).withAuthoredByViewer(true);

        assertThat(stamped.source()).isEqualTo(ReviewSource.GUEST_LINK);
        assertThat(salonStamped.source()).isEqualTo(ReviewSource.GUEST_LINK);
    }

    /** Stands in for the DB-assigned id and {@code @CreationTimestamp}; every other getter is the real entity's. */
    private static Review persisted(Review review) {
        Review spied = spy(review);
        doReturn(UUID.randomUUID()).when(spied).getId();
        doReturn(CREATED_AT).when(spied).getCreatedAt();
        return spied;
    }

    private static Booking booking() {
        ServiceDefinition serviceDefinition = mock(ServiceDefinition.class);
        when(serviceDefinition.getName()).thenReturn("Манікюр");
        MasterServiceAssignment masterService = mock(MasterServiceAssignment.class);
        when(masterService.getServiceDefinition()).thenReturn(serviceDefinition);
        Master master = mock(Master.class);
        when(master.getId()).thenReturn(UUID.randomUUID());
        when(master.displayFirstName()).thenReturn("Ірина");
        when(master.displayLastName()).thenReturn("Коваль");
        Booking booking = mock(Booking.class);
        when(booking.getMasterService()).thenReturn(masterService);
        when(booking.getMaster()).thenReturn(master);
        return booking;
    }
}
