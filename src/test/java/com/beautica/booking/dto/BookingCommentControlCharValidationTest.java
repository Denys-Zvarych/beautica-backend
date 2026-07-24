package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation unit guards for the free-text comment fields on the booking
 * DTOs ({@code CreateBookingRequest.clientComment}, {@code CancelBookingRequest.comment},
 * {@code StatusUpdateRequest.comment}). Each comment @Pattern was loosened to permit
 * line breaks and tabs (TAB / LF / CR) for long-form copy, while still rejecting NUL
 * and other C0 / DEL control characters.
 */
@DisplayName("Booking request DTOs - comment control-char validation")
class BookingCommentControlCharValidationTest {

    private static final String NUL = "\u0000";
    private static final String VTAB = "\u000B";
    private static final String DEL = "\u007F";

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static CreateBookingRequest createBookingWithComment(String comment) {
        return new CreateBookingRequest(
                UUID.randomUUID(), UUID.randomUUID(),
                ZonedDateTime.now().plusDays(2), null, comment);
    }

    private static CancelBookingRequest cancelWithComment(String comment) {
        return new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, comment);
    }

    private static StatusUpdateRequest statusWithComment(String comment) {
        return new StatusUpdateRequest(CancellationReason.CLIENT_CANCELLED, comment);
    }

    private static boolean hasCommentViolation(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("comment")
                        || v.getPropertyPath().toString().equals("clientComment"));
    }

    // -- positive: newline + tab now accepted (the bug fix) -------------------

    @Test
    @DisplayName("CreateBookingRequest accepts clientComment with a newline and a tab")
    void should_acceptClientComment_when_containsNewlineAndTab() {
        var comment = "Please call before arrival.\nGate code: 1234\tring twice";

        assertThat(validator.validate(createBookingWithComment(comment)))
                .as("clientComment with newline/tab must be accepted, actual=%s", comment)
                .isEmpty();
    }

    @Test
    @DisplayName("CancelBookingRequest accepts comment with a newline and a tab")
    void should_acceptCancelComment_when_containsNewlineAndTab() {
        var comment = "Reason:\nschedule conflict\tunavoidable";

        assertThat(validator.validate(cancelWithComment(comment)))
                .as("cancel comment with newline/tab must be accepted, actual=%s", comment)
                .isEmpty();
    }

    @Test
    @DisplayName("StatusUpdateRequest accepts comment with a newline and a tab")
    void should_acceptStatusComment_when_containsNewlineAndTab() {
        var comment = "Client did not show.\nWaited 20 min\tno contact";

        assertThat(validator.validate(statusWithComment(comment)))
                .as("status comment with newline/tab must be accepted, actual=%s", comment)
                .isEmpty();
    }

    // -- negative: NUL/VT/DEL still rejected (over-loosen guard) --------------

    @Test
    @DisplayName("CreateBookingRequest rejects clientComment with an embedded NUL byte")
    void should_rejectClientComment_when_containsNul() {
        var comment = "Good" + NUL + "bad";

        assertThat(hasCommentViolation(validator.validate(createBookingWithComment(comment))))
                .as("clientComment with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("CreateBookingRequest rejects clientComment with a vertical tab (0x0B)")
    void should_rejectClientComment_when_containsVerticalTab() {
        var comment = "Good" + VTAB + "bad";

        assertThat(hasCommentViolation(validator.validate(createBookingWithComment(comment))))
                .as("clientComment with VT (0x0B) must still fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("CancelBookingRequest rejects comment with an embedded NUL byte")
    void should_rejectCancelComment_when_containsNul() {
        var comment = "Good" + NUL + "bad";

        assertThat(hasCommentViolation(validator.validate(cancelWithComment(comment))))
                .as("cancel comment with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("CancelBookingRequest rejects comment with a DEL char (0x7F)")
    void should_rejectCancelComment_when_containsDel() {
        var comment = "Good" + DEL + "bad";

        assertThat(hasCommentViolation(validator.validate(cancelWithComment(comment))))
                .as("cancel comment with DEL (0x7F) must still fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("StatusUpdateRequest rejects comment with an embedded NUL byte")
    void should_rejectStatusComment_when_containsNul() {
        var comment = "Good" + NUL + "bad";

        assertThat(hasCommentViolation(validator.validate(statusWithComment(comment))))
                .as("status comment with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("StatusUpdateRequest rejects comment with a vertical tab (0x0B)")
    void should_rejectStatusComment_when_containsVerticalTab() {
        var comment = "Good" + VTAB + "bad";

        assertThat(hasCommentViolation(validator.validate(statusWithComment(comment))))
                .as("status comment with VT (0x0B) must still fail @Pattern validation")
                .isTrue();
    }
}
