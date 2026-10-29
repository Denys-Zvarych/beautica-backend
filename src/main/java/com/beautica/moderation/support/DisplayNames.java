package com.beautica.moderation.support;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * "First Last" for support-facing moderation views (queue and report e-mail), promoted in phase 414 out of
 * {@code ModerationQueueService} so both share it.
 */
public final class DisplayNames {

    private DisplayNames() {
    }

    /** {@code first} and {@code last} joined by a space, skipping nulls; {@code null} when nothing is left. */
    public static String fullName(String first, String last) {
        String joined = Stream.of(first, last).filter(Objects::nonNull).collect(Collectors.joining(" ")).strip();
        return joined.isEmpty() ? null : joined;
    }
}
