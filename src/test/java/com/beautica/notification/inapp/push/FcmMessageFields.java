package com.beautica.notification.inapp.push;

import com.google.firebase.messaging.Message;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Reads the fields the FCM SDK builder set on a {@link Message} (it has no getters) — the same reflective read
 * {@code AbstractInAppPushFlowIT} does, public so ITs outside this package (phase 495 {@code BookingReminderFeedIT})
 * share one copy instead of a fourth hand-rolled one.
 */
public final class FcmMessageFields {

    private FcmMessageFields() {
    }

    public static String titleOf(Message message) {
        return (String) field(field(message, "notification"), "title");
    }

    public static String bodyOf(Message message) {
        return (String) field(field(message, "notification"), "body");
    }

    public static String tokenOf(Message message) {
        return (String) field(message, "token");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> dataOf(Message message) {
        return (Map<String, String>) field(message, "data");
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
