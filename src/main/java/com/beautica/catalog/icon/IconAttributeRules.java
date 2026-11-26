package com.beautica.catalog.icon;

import com.beautica.common.exception.CategoryIconInvalidException;
import com.beautica.common.exception.CategoryIconInvalidException.Reason;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B539 (SVC-59) — the attribute half of the category-icon allow-list: which attribute names are allowed, which values
 * they may carry, and the canonical form of each value. Pure; used only by {@link CategoryIconSanitizer}.
 *
 * <p>Order of the checks for one attribute: external reference first (any {@code href}, any {@code url(} /
 * {@code data:} / URL-scheme value — the most dangerous class wins the reason), then the name allow-list, then the
 * colour rule, then the value grammar.
 */
final class IconAttributeRules {

    /** Exact viewBox every icon must declare (SVC-59). */
    static final String VIEW_BOX = "0 0 24 24";

    private static final Pattern NUMBER =
            Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?(px|%)?$");
    private static final Pattern PATH_DATA = Pattern.compile("^[0-9MmZzLlHhVvCcSsQqTtAaEe.,+\\-\\s]*$");
    private static final Pattern POINTS = Pattern.compile("^[0-9eE.,+\\-\\s]*$");
    private static final Pattern TRANSFORM = Pattern.compile(
            "^((matrix|translate|scale|rotate|skewX|skewY)\\s*\\([0-9eE.,+\\-\\s]*\\)[\\s,]*)*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern VIEW_BOX_SEPARATOR = Pattern.compile("[\\s,]+");

    private static final Set<String> NUMERIC = Set.of("cx", "cy", "r", "rx", "ry", "x", "y", "width", "height",
            "x1", "y1", "x2", "y2", "stroke-width", "stroke-miterlimit");
    private static final Map<String, Set<String>> KEYWORDS = Map.of(
            "fill-rule", Set.of("nonzero", "evenodd"),
            "clip-rule", Set.of("nonzero", "evenodd"),
            "stroke-linecap", Set.of("butt", "round", "square"),
            "stroke-linejoin", Set.of("miter", "round", "bevel", "arcs", "miter-clip"));
    private static final Set<String> COLOUR_ATTRIBUTES = Set.of("fill", "stroke");
    private static final Map<String, String> COLOUR_VALUES = Map.of("currentcolor", "currentColor", "none", "none");

    /**
     * Inert metadata the bundled set carries on the root ({@code id="Layer_1" data-name="Layer 1" version="1.1"}):
     * accepted and dropped from the canonical bytes, like comments and the XML declaration, so the byte-identical
     * B540 backfill passes. Nothing can reference an {@code id}: every {@code url(} / {@code href} is rejected.
     */
    private static final Set<String> INERT_ROOT_ATTRIBUTES = Set.of("id", "data-name", "version");

    private IconAttributeRules() {
    }

    /** Rejects any {@code href} (any namespace) and any value that could load or reference something. */
    static void requireNoExternalRef(String localName, String value) {
        String compact = WHITESPACE.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
        if ("href".equals(localName) || compact.contains("url(") || compact.contains("data:")
                || compact.contains("javascript:") || compact.contains("http:") || compact.contains("https:")
                || compact.contains("//")) {
            throw new CategoryIconInvalidException(Reason.EXTERNAL_REF);
        }
    }

    /** {@code true} for a root attribute that is accepted but not kept. */
    static boolean isInertRootAttribute(String name) {
        return INERT_ROOT_ATTRIBUTES.contains(name);
    }

    /**
     * The canonical value of an allow-listed presentation / geometry attribute.
     *
     * @throws CategoryIconInvalidException {@code FORBIDDEN_ATTRIBUTE} for a name outside the allow-list or a
     *                                      malformed value, {@code FORBIDDEN_COLOUR} for a colour other than
     *                                      {@code currentColor} / {@code none}
     */
    static String canonicalValue(String name, String rawValue) {
        String value = WHITESPACE.matcher(rawValue.strip()).replaceAll(" ");
        if (COLOUR_ATTRIBUTES.contains(name)) {
            return Optional.ofNullable(COLOUR_VALUES.get(value.toLowerCase(Locale.ROOT)))
                    .orElseThrow(() -> new CategoryIconInvalidException(Reason.FORBIDDEN_COLOUR));
        }
        if (!matchesGrammar(name, value)) {
            throw new CategoryIconInvalidException(Reason.FORBIDDEN_ATTRIBUTE);
        }
        return value;
    }

    /** {@code true} when the root's viewBox is exactly {@value #VIEW_BOX} (whitespace / comma separated). */
    static boolean isExpectedViewBox(String value) {
        return value != null && String.join(" ", VIEW_BOX_SEPARATOR.split(value.strip())).equals(VIEW_BOX);
    }

    private static boolean matchesGrammar(String name, String value) {
        if (NUMERIC.contains(name)) {
            return NUMBER.matcher(value).matches();
        }
        Set<String> keywords = KEYWORDS.get(name);
        if (keywords != null) {
            return keywords.contains(value);
        }
        return switch (name) {
            case "d" -> PATH_DATA.matcher(value).matches();
            case "points" -> POINTS.matcher(value).matches();
            case "transform" -> TRANSFORM.matcher(value).matches();
            default -> false;
        };
    }
}
