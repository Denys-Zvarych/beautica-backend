package com.beautica.review.dto;

import com.beautica.master.entity.Master;
import com.beautica.review.entity.Review;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ReviewResponse.from — unit")
class ReviewResponseTest {

    private static final Instant CREATED_AT = Instant.parse("2025-08-01T10:00:00Z");

    @Test
    @DisplayName("maps all six fields correctly when review is fully populated")
    void should_mapAllFields_when_reviewMappedToResponse() {
        UUID reviewId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        var client = mock(User.class);
        when(client.getFirstName()).thenReturn("Іван");
        when(client.getLastName()).thenReturn("Франко");

        var master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);

        var review = mock(Review.class);
        when(review.getId()).thenReturn(reviewId);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 4);
        when(review.getComment()).thenReturn("Great");
        when(review.getCreatedAt()).thenReturn(CREATED_AT);

        var response = ReviewResponse.from(review);

        assertThat(response.id()).isEqualTo(reviewId);
        assertThat(response.masterId()).isEqualTo(masterId);
        assertThat(response.clientDisplayName()).isEqualTo("Іван Ф.");
        assertThat(response.rating()).isEqualTo(4);
        assertThat(response.comment()).isEqualTo("Great");
        assertThat(response.createdAt().toInstant()).isEqualTo(CREATED_AT);
        assertThat(response.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("uses first name and last initial with dot for clientDisplayName (privacy masking)")
    void should_concatFirstAndLastName_when_buildingClientDisplayName() {
        var client = mock(User.class);
        when(client.getFirstName()).thenReturn("Іван");
        when(client.getLastName()).thenReturn("Франко");

        var master = mock(Master.class);

        var review = mock(Review.class);
        when(review.getId()).thenReturn(UUID.randomUUID());
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 5);
        when(review.getComment()).thenReturn(null);
        when(review.getCreatedAt()).thenReturn(CREATED_AT);

        var response = ReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo("Іван Ф.");
    }

    @Test
    @DisplayName("falls back to 'Anonymous' when both firstName and lastName are null")
    void should_useAnonymous_when_bothNamesAreNull() {
        var client = mock(User.class);
        when(client.getFirstName()).thenReturn(null);
        when(client.getLastName()).thenReturn(null);

        var master = mock(Master.class);

        var review = mock(Review.class);
        when(review.getId()).thenReturn(UUID.randomUUID());
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 3);
        when(review.getComment()).thenReturn(null);
        when(review.getCreatedAt()).thenReturn(CREATED_AT);

        var response = ReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo("Anonymous");
    }

    @Test
    @DisplayName("uses firstName only when lastName is null")
    void should_useFirstNameOnly_when_lastNameIsNull() {
        var client = mock(User.class);
        when(client.getFirstName()).thenReturn("Іван");
        when(client.getLastName()).thenReturn(null);

        var master = mock(Master.class);

        var review = mock(Review.class);
        when(review.getId()).thenReturn(UUID.randomUUID());
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 5);
        when(review.getComment()).thenReturn(null);
        when(review.getCreatedAt()).thenReturn(CREATED_AT);

        var response = ReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo("Іван");
    }

    @Test
    @DisplayName("uses last initial with dot when firstName is null (privacy masking)")
    void should_useLastNameOnly_when_firstNameIsNull() {
        var client = mock(User.class);
        when(client.getFirstName()).thenReturn(null);
        when(client.getLastName()).thenReturn("Франко");

        var master = mock(Master.class);

        var review = mock(Review.class);
        when(review.getId()).thenReturn(UUID.randomUUID());
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 3);
        when(review.getComment()).thenReturn(null);
        when(review.getCreatedAt()).thenReturn(CREATED_AT);

        var response = ReviewResponse.from(review);

        assertThat(response.clientDisplayName()).isEqualTo("Ф.");
    }
}
