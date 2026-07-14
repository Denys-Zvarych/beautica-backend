package com.beautica.booking.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Bean Validation constraint bounding a booking-comment field's length AFTER
 * {@link com.beautica.booking.service.BookingComments#normalize(String)}, not its raw length.
 *
 * <p>Why this exists (LOW finding): plain {@code @Size(min = N)} runs on the RAW request string
 * at the controller boundary, before {@code BookingComments.normalize()} strips bidi/zero-width/
 * other Unicode {@code Cf} characters in the service layer. A caller can pad a degenerate note
 * (e.g. {@code "ok"} + eight U+200B zero-width spaces) past a raw {@code min} floor; normalize()
 * then strips it back down to the two meaningful characters, defeating the whole point of the
 * floor (rejecting the degenerate {@code ""}/{@code "."}/{@code "ok"} cases). This constraint
 * closes that gap by normalizing BEFORE measuring, so the length that is actually checked is the
 * length that actually gets persisted and shown to the client.
 *
 * <p>{@code null} is intentionally NOT rejected here — pair with {@code @NotBlank} so the
 * "required" message stays distinct from the "too short/long after normalization" message (§A of
 * the Anti-Bug Playbook: format constraints skip null and let {@code @NotBlank} own emptiness).
 */
@Documented
@Constraint(validatedBy = NormalizedSizeValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface NormalizedSize {

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    String message() default "Length after normalization must be between {min} and {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
