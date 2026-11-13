package com.beautica.notification;

import com.beautica.notification.service.RequestKind;

/**
 * The ONE shared expected-copy fixture for the phase 491 request-decision e-mail (NTF-35): the requester's
 * "your category request / service-type suggestion was approved or rejected" message. Pre-push gate rule 4
 * (orchestrate.md Step 2.9): new or changed tests take the expected subject and body lines from here, never a
 * re-typed literal, so a copy change breaks in one place.
 *
 * <p>Values are written out on purpose (not read from {@code EmailNotificationService} or the template): an
 * assertion against the renderer's own constant would pass whatever the renderer says. They mirror
 * {@code EmailNotificationService#requestDecisionSubject} and {@code templates/email/request-decision.html}.
 */
public final class RequestDecisionEmailExpectedCopy {

    /** The reject-reason line prefix; the line is {@code «Причина: {reason}»} and is absent without a reason. */
    public static final String REASON_PREFIX = "Причина: ";

    /** The bare marker every "no reason block" assertion checks for absence. */
    public static final String REASON_MARKER = "Причина";

    private RequestDecisionEmailExpectedCopy() {
    }

    /**
     * The subject header: {@code «Beautica: ваш запит на категорію «{name}» схвалено|відхилено»} or
     * {@code «Beautica: вашу пропозицію послуги «{name}» схвалено|відхилено»}.
     */
    public static String subject(RequestKind kind, boolean approved, String name) {
        return kind == RequestKind.CATEGORY_REQUEST
                ? "Beautica: ваш запит на категорію «" + name + "» " + verdict(approved)
                : "Beautica: вашу пропозицію послуги «" + name + "» " + verdict(approved);
    }

    /**
     * The body's decision sentence: {@code «Ваш запит на категорію «{name}» схвалено.»} or
     * {@code «Вашу пропозицію послуги «{name}» відхилено.»} (and the two other combinations).
     */
    public static String bodyLine(RequestKind kind, boolean approved, String name) {
        return kind == RequestKind.CATEGORY_REQUEST
                ? "Ваш запит на категорію «" + name + "» " + verdict(approved) + "."
                : "Вашу пропозицію послуги «" + name + "» " + verdict(approved) + ".";
    }

    /** The reject body's reason line, {@code «Причина: {reason}»}; pass an already HTML-escaped reason for markup. */
    public static String reasonLine(String reason) {
        return REASON_PREFIX + reason;
    }

    private static String verdict(boolean approved) {
        return approved ? "схвалено" : "відхилено";
    }
}
