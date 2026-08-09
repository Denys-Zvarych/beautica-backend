package com.beautica.common.util;

/**
 * Ukrainian numeral-agreement helpers for user-facing notification copy.
 *
 * <p>Ukrainian nouns take three forms after a numeral, selected by the numeral's last digit (with
 * the 11–14 exception, which always takes the "many" form):
 * <pre>
 *   1, 21, 31, …                 → послуга   (singular)
 *   2–4, 22–24, …                → послуги   (few)
 *   0, 5–20, 25–30, …            → послуг    (many)
 * </pre>
 *
 * <p>Extracted to {@code common/util} because two unrelated notification channels need the exact
 * same wording and must not drift: the push/e-mail copy in
 * {@code com.beautica.notification.service.NotificationService} and the guest confirmation SMS in
 * {@code com.beautica.booking.service.GuestBookingService}.
 */
public final class UkrainianPlurals {

    private static final String SERVICES_ONE = "послуга";
    private static final String SERVICES_FEW = "послуги";
    private static final String SERVICES_MANY = "послуг";

    private UkrainianPlurals() {
    }

    /**
     * Renders {@code "{count} послуг(а|и|)"} — the numeral followed by the correctly-agreeing form
     * of «послуга».
     *
     * <p><b>Intended for {@code count >= 2} only.</b> Every production call site is guarded by a
     * multi-service branch, because the single-service wording is never a bare numeral phrase — it
     * names the service («…забронював Стрижка»), and the accusative singular «послугу» that a
     * "1 послуга" phrase would need in that sentence differs from the nominative returned here.
     * A {@code count} of 1 (or 0) is therefore not reachable in production; it still returns a
     * grammatically well-formed nominative phrase rather than throwing, since a notification must
     * never fail on copy.
     */
    public static String servicesPhrase(int count) {
        return count + " " + servicesNoun(count);
    }

    /**
     * The form of «послуга» agreeing with {@code count} — see {@link #servicesPhrase(int)}.
     *
     * <p>Uses {@link Math#floorMod} rather than {@code Math.abs(count) % n}: {@code Math.abs} is
     * NOT total over {@code int} — {@code Math.abs(Integer.MIN_VALUE)} overflows back to
     * {@code Integer.MIN_VALUE}, so the subsequent {@code %} yields a NEGATIVE remainder that
     * matches no {@code case} and silently falls through to the "many" form. {@code floorMod}
     * always returns a non-negative remainder for a positive modulus, so every {@code int} —
     * including {@code Integer.MIN_VALUE} — lands in a deliberate branch.
     */
    public static String servicesNoun(int count) {
        int lastTwo = Math.floorMod(count, 100);
        if (lastTwo >= 11 && lastTwo <= 14) {
            return SERVICES_MANY;
        }
        return switch (Math.floorMod(count, 10)) {
            case 1 -> SERVICES_ONE;
            case 2, 3, 4 -> SERVICES_FEW;
            default -> SERVICES_MANY;
        };
    }
}
