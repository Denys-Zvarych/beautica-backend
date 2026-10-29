package com.beautica.moderation.dto;

/**
 * Phase 397 D4 — the list label for a blocked client: «Ім'я П.» (first name + surname initial), capped at the
 * {@code client_blocks.display_name} column length. Never empty: a client with no usable name reads «Клієнт».
 */
public final class BlockDisplayNames {

    public static final int MAX_LENGTH = 64;
    public static final String FALLBACK = "Клієнт";

    private BlockDisplayNames() {
    }

    /** @return «Ім'я П.», or {@code null} when there is no first name */
    public static String shortName(String firstName, String lastName) {
        if (firstName == null || firstName.isBlank()) {
            return null;
        }
        String name = firstName.strip();
        if (lastName != null && !lastName.isBlank()) {
            name = name + " " + lastName.strip().substring(0, lastName.strip().offsetByCodePoints(0, 1)) + ".";
        }
        return truncate(name);
    }

    /** {@link #shortName}, falling back to {@link #FALLBACK} — for the NOT NULL snapshot column. */
    public static String snapshot(String firstName, String lastName) {
        String name = shortName(firstName, lastName);
        return name != null ? name : FALLBACK;
    }

    private static String truncate(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        // Never split a surrogate pair at the cut.
        int end = Character.isHighSurrogate(name.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
        return name.substring(0, end);
    }
}
