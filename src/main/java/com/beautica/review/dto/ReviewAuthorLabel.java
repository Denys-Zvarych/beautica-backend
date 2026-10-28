package com.beautica.review.dto;

import com.beautica.review.entity.Review;
import com.beautica.review.entity.ReviewSource;
import com.beautica.user.User;

/**
 * The ONE author-name rule of the public review shapes ({@link ReviewResponse}, {@link SalonReviewResponse}):
 *
 * <ul>
 *   <li>{@link ReviewSource#GUEST_LINK} (phase 399 D2) — the {@code guestDisplayName} snapshot, verbatim.</li>
 *   <li>{@link ReviewSource#APP}, author self-deleted (phase 300 D3) — {@link ReviewResponse#DETACHED_CLIENT_LABEL}.</li>
 *   <li>{@link ReviewSource#APP} — «FirstName L.» / «FirstName» / «L.» / «Anonymous», unchanged since Phase 7.</li>
 * </ul>
 *
 * Extracted from the two DTOs, which carried identical copies of the APP rules; behaviour for APP rows is unchanged.
 * Reads {@code review.client} only for APP rows, so a guest review never touches the (null) association.
 */
final class ReviewAuthorLabel {

    private static final String ANONYMOUS = "Anonymous";

    private ReviewAuthorLabel() {
    }

    static String of(Review review) {
        if (review.getSource() == ReviewSource.GUEST_LINK) {
            return review.getGuestDisplayName();
        }
        User client = review.getClient();
        if (client == null) {
            return ReviewResponse.DETACHED_CLIENT_LABEL;
        }
        return maskedAccountName(client.getFirstName(), client.getLastName());
    }

    private static String maskedAccountName(String firstName, String rawLastName) {
        // A blank lastName has no initial to show — treat it as absent (no codePointAt(0) on "").
        String lastName = rawLastName == null || rawLastName.isBlank() ? null : rawLastName.strip();
        if (firstName == null && lastName == null) {
            return ANONYMOUS;
        }
        if (firstName != null && lastName != null) {
            return firstName + " " + initialOf(lastName) + ".";
        }
        return firstName != null ? firstName : initialOf(lastName) + ".";
    }

    /** The first whole code point — a supplementary first character is never split into a lone surrogate. */
    private static String initialOf(String lastName) {
        return new String(Character.toChars(lastName.codePointAt(0)));
    }
}
