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
    @DisplayName("accepts comment when comment is exactly 1 non-whitespace character (min boundary)")
    void should_acceptComment_when_commentIs1Char() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, "x");

        assertThat(validator.validate(request)).isEmpty();
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

    // -------------------------------------------------------------------------
    // comment — whitespace-only rejection (@Pattern guard)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects comment when comment consists solely of whitespace")
    void should_rejectComment_when_commentIsBlankWhitespace() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, "   ");

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }

    @Test
    @DisplayName("accepts comment when comment starts with non-whitespace followed by spaces")
    void should_acceptComment_when_commentStartsWithNonWhitespace() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, "Great service!");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("rejects comment when comment starts with whitespace (leading-space)")
    void should_rejectComment_when_commentStartsWithWhitespace() {
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, " leading-space");

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }

    // ── QA-MEDIUM: @Pattern control-char ban — embedded NUL/newline ───────────

    @Test
    @DisplayName("rejects comment when comment contains an embedded NUL byte in position 2 (\\p{Cntrl} ban)")
    void should_rejectComment_when_commentContainsEmbeddedControlCharacter() {
        // "x" passes the leading-char check; NUL at position 1 must be caught by
        // the second character class [^\p{Cntrl}]{0,1999} in the @Pattern.
        var comment = "x" + "\u0000" + "rest of comment";
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, comment);

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("comment with embedded NUL byte must fail @Pattern validation")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }

    @Test
    @DisplayName("rejects comment when comment contains a leading control character (NUL in position 0)")
    void should_rejectComment_when_commentStartsWithControlCharacter() {
        // NUL byte at position 0 — fails both the leading-char class and the \p{Cntrl} ban
        var comment = "\u0000" + "starts with null";
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, comment);

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("comment starting with NUL byte must fail @Pattern validation")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }

    // ── newline/tab loosening regression: body now allows \n, \r, \t ──────────

    @Test
    @DisplayName("accepts comment when comment body contains line breaks and a tab (long-form copy)")
    void should_acceptComment_when_commentBodyContainsNewlineAndTab() {
        // Leading char is visible (anchor satisfied); body carries \n, \r, \t which are now
        // permitted by the second char class [^\x00-\x08\x0B\x0C\x0E-\x1F\x7F].
        var comment = "Great service!\nSecond line\r\n\tindented closing remark";
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, comment);

        assertThat(validator.validate(request))
                .as("comment body with \\n/\\r/\\t must be accepted, actual=%s", comment)
                .isEmpty();
    }

    @Test
    @DisplayName("rejects comment when comment STARTS with a newline (first-char anchor still holds)")
    void should_rejectComment_when_commentStartsWithNewline() {
        // The leading-char anchor [^\p{Cntrl}\s] forbids a comment that begins with whitespace
        // or a control char — a leading \n must still be rejected even though \n is body-legal.
        var comment = "\n leading newline then text";
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, comment);

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("comment starting with a newline must fail the first-char anchor")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }

    @Test
    @DisplayName("rejects comment when comment body contains a non-newline C0 control char (VT 0x0B)")
    void should_rejectComment_when_commentBodyContainsVerticalTab() {
        // Vertical tab (0x0B) is explicitly in the banned class — confirms the loosening did NOT
        // open the gate to every control char, only \t \n \r.
        var comment = "x" + "\u000B" + "vertical tab in body";
        var request = new CreateReviewRequest(UUID.randomUUID(), 3, comment);

        Set<ConstraintViolation<CreateReviewRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("comment with embedded VT (0x0B) must still fail @Pattern validation")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("comment"));
    }
}
