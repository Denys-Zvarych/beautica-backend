package com.beautica.review.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CreateReviewRequest — Bean Validation unit")
class CreateReviewRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -------------------------------------------------------------------------
    // rating — boundary tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects rating when rating is null")
    void should_rejectRating_when_ratingIsNull() {
        var request = new CreateReviewRequest(UUID.randomUUID(), null, null);

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("rating"));
    }

    @ParameterizedTest(name = "rating={0} should be invalid")
    @ValueSource(ints = {0, 6})
    @DisplayName("rejects rating when rating is out of [1,5] range")
    void should_rejectRating_when_ratingIsOutOfRange(int rating) {
        var request = new CreateReviewRequest(UUID.randomUUID(), rating, null);

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("rating"));
    }

    @ParameterizedTest(name = "rating={0} should be valid")
    @ValueSource(ints = {1, 3, 5})
    @DisplayName("accepts rating when rating is within [1,5] range")
    void should_acceptRating_when_ratingIsInRange(int rating) {
        var request = new CreateReviewRequest(UUID.randomUUID(), rating, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // bookingId — null guard
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects bookingId when bookingId is null")
    void should_rejectBookingId_when_bookingIdIsNull() {
        var request = new CreateReviewRequest(null, 3, null);

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("bookingId"));
    }

    // -------------------------------------------------------------------------
    // comment — size boundary tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects comment when comment is empty string (min=1)")
    void should_rejectComment_when_commentIsEmpty() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, "");

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }

    @Test
    @DisplayName("accepts comment when comment is null (Size skips null)")
    void should_acceptComment_when_commentIsNull() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("accepts comment when comment is exactly 2000 characters")
    void should_acceptComment_when_commentIs2000Chars() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, "x".repeat(2000));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("rejects comment when comment is 2001 characters (max=2000)")
    void should_rejectComment_when_commentIs2001Chars() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, "x".repeat(2001));

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }
}
