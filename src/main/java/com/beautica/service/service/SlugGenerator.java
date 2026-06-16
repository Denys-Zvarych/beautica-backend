package com.beautica.service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Generates a globally-unique, ASCII-only {@code service_types.slug} from a
 * (possibly Cyrillic) display name (Phase 16.9.1).
 *
 * <p>The output ALWAYS satisfies both the entity {@code @Pattern}
 * ({@code ^[a-z0-9]+(?:-[a-z0-9]+)*$}) and the stricter DB CHECK
 * ({@code ^[a-z0-9][a-z0-9\-]*[a-z0-9]$}, min length 2), and is {@code <= 255} chars.
 *
 * <p>Pipeline: transliterate Ukrainian → Latin, lowercase, strip to {@code [a-z0-9-]},
 * collapse and trim hyphens. A name that transliterates to nothing usable falls back to
 * the category slug plus a random suffix. Uniqueness is enforced by suffixing
 * {@code -2}, {@code -3}, … until free, capped; on exhaustion a bounded number of 6-char
 * random suffixes are tried, then a single longer (16-char) token guarantees termination.
 *
 * <p>Stateless and side-effect-free apart from the injected existence check — safe to
 * call inside the promotion transaction.
 */
@Component
@RequiredArgsConstructor
public class SlugGenerator {

    private static final int MAX_SLUG_LENGTH = 255;
    private static final int MAX_NUMERIC_SUFFIX_ATTEMPTS = 50;
    private static final int MAX_RANDOM_SUFFIX_ATTEMPTS = 5;
    private static final int RANDOM_SUFFIX_LENGTH = 6;
    private static final int RANDOM_SUFFIX_FALLBACK_LENGTH = 16;
    private static final String FALLBACK_BASE = "service";
    private static final char[] BASE36 = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    /** Minimal Ukrainian → Latin transliteration map (covers the Ukrainian alphabet). */
    private static final Map<Character, String> UK_TO_LATIN = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "h"),
            Map.entry('ґ', "g"), Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('є', "ie"),
            Map.entry('ж', "zh"), Map.entry('з', "z"), Map.entry('и', "y"), Map.entry('і', "i"),
            Map.entry('ї', "i"), Map.entry('й', "i"), Map.entry('к', "k"), Map.entry('л', "l"),
            Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"), Map.entry('п', "p"),
            Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"),
            Map.entry('ф', "f"), Map.entry('х', "kh"), Map.entry('ц', "ts"), Map.entry('ч', "ch"),
            Map.entry('ш', "sh"), Map.entry('щ', "shch"), Map.entry('ь', ""), Map.entry('ю', "iu"),
            Map.entry('я', "ia"));

    /**
     * Produces a unique slug for {@code displayName}, scoped only by global slug
     * uniqueness (slugs are platform-wide unique, not per-category).
     *
     * @param displayName  the (possibly Cyrillic) service name; may be blank
     * @param categoryName the platform-category slug, used as a fallback base when the
     *                     name transliterates to nothing usable (e.g. emoji-only input)
     * @param slugExists   existence predicate (typically {@code serviceTypeRepository::existsBySlug})
     * @return a slug matching {@code ^[a-z0-9]+(?:-[a-z0-9]+)*$}, length 2..255, not in use
     */
    public String uniqueSlug(String displayName, String categoryName, Predicate<String> slugExists) {
        String base = normalize(transliterate(displayName));
        if (base.length() < 2) {
            base = normalize(transliterate(categoryName));
        }
        if (base.length() < 2) {
            base = FALLBACK_BASE;
        }
        base = truncate(base);

        if (!slugExists.test(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_NUMERIC_SUFFIX_ATTEMPTS; suffix++) {
            String candidate = truncate(base) + "-" + suffix;
            if (!slugExists.test(candidate)) {
                return candidate;
            }
        }
        // Numeric suffixes exhausted — append a random base36 token. Practically collision-free.
        for (int attempt = 0; attempt < MAX_RANDOM_SUFFIX_ATTEMPTS; attempt++) {
            String candidate = truncate(base) + "-" + randomToken(RANDOM_SUFFIX_LENGTH);
            if (!slugExists.test(candidate)) {
                return candidate;
            }
        }
        // Vanishingly unlikely (5 collisions over the 2.1B base36 keyspace) — widen the
        // token rather than loop unbounded. The longer token keeps the operation succeeding
        // while guaranteeing the loop terminates.
        return truncate(base) + "-" + randomToken(RANDOM_SUFFIX_FALLBACK_LENGTH);
    }

    private String transliterate(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() * 2);
        for (char c : input.toLowerCase().toCharArray()) {
            String mapped = UK_TO_LATIN.get(c);
            sb.append(mapped != null ? mapped : c);
        }
        return sb.toString();
    }

    /** Lowercase, strip to {@code [a-z0-9-]}, collapse repeated hyphens, trim edges. */
    private String normalize(String input) {
        String ascii = input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-");
        // Trim leading/trailing hyphens (the DB CHECK forbids edge hyphens).
        int start = 0;
        int end = ascii.length();
        while (start < end && ascii.charAt(start) == '-') {
            start++;
        }
        while (end > start && ascii.charAt(end - 1) == '-') {
            end--;
        }
        return ascii.substring(start, end);
    }

    /** Caps at {@code MAX_SLUG_LENGTH} and trims any trailing hyphen left by the cut. */
    private String truncate(String slug) {
        if (slug.length() <= MAX_SLUG_LENGTH) {
            return slug;
        }
        String cut = slug.substring(0, MAX_SLUG_LENGTH);
        while (cut.endsWith("-")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    private String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE36[random.nextInt(BASE36.length)]);
        }
        return sb.toString();
    }
}
