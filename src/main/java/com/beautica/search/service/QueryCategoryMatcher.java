package com.beautica.search.service;

import com.beautica.service.service.PlatformCategoryLabel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the free-text query's tokens against the selectable platform categories'
 * Ukrainian {@code display_name}s, yielding the canonical
 * {@code platform_categories.name} values to bind into the SQL
 * {@code service_definitions.category IN (…)} disjunct.
 *
 * <h3>Why the whole query is matched against one label, not token-by-token</h3>
 * This is the crux of the bug and the reason a naive fix does not work. The
 * {@code q} predicate is <b>group-scoped</b>: for a multi-token query, ONE single
 * service row must satisfy EVERY token (see
 * {@code SearchService.appendQPredicate}). «нарощення» and «вій» co-occur in no
 * service name and in no service-type name, so a per-service-row category test that
 * still required each token to be independently satisfiable by that row would leave
 * the query returning zero rows — the bug intact.
 *
 * <p>Resolution here is therefore <b>all-tokens-against-the-label</b>: a category
 * qualifies only when its display name contains <em>every</em> token. That single
 * decision is what makes the downstream SQL sound. Because membership in the
 * resolved set already proves every token matched, the emitted
 * {@code category IN (…)} disjunct can be dropped into each per-token clause
 * without loosening the group semantics one bit: if {@code sd.category} is in the
 * set, every token's clause is satisfied <em>for the same reason</em>, so the
 * per-token and whole-group placements are provably the same predicate. That
 * equivalence is what lets the fix reuse the existing predicate skeleton — and the
 * existing single-token short-circuit identity — instead of restructuring it.
 *
 * <h3>Why in Java and not in SQL</h3>
 * Joining {@code platform_categories} into the hot search path would put a second
 * relation under the {@code ILIKE}, and an {@code OR} across two relations is the
 * classic way to lose the {@code idx_service_definitions_name_trgm} trigram plan.
 * The category list is ~20 rows of cached, admin-gated reference data, so resolving
 * it once per request costs a list scan over cached objects and leaves the SQL with
 * a plain equality set the {@code idx_service_def_category} B-tree serves.
 *
 * <h3>Match semantics mirror {@code ILIKE '%token%'}</h3>
 * Case-insensitive substring containment, with the same apostrophe fold the query
 * tokens already carry applied to the label too
 * ({@link NormalizedSearchQuery#foldApostrophes}). It is deliberately NOT a
 * prefix/word-boundary match: {@code ILIKE '%…%'} is not, and the two must agree or
 * a category would resolve while the user's mental model of "search finds
 * substrings" silently changed.
 *
 * <h3>Deliberately scoped to {@code platform_categories} only</h3>
 * {@code service_types.name_uk} is NOT searched. That table is the orphaned
 * "System A" catalogue, and its name-containment matching was already removed once
 * for producing false positives — a type's {@code name_uk} is a substring of
 * unrelated service names (see {@code ServiceTypeMatch}). {@code platform_categories}
 * is the live category system and the one whose labels the client is shown, so it is
 * the only thing a user means by "a category".
 */
final class QueryCategoryMatcher {

    private QueryCategoryMatcher() {
        // static matcher
    }

    /**
     * Returns the canonical names of every selectable category whose display name
     * contains ALL of {@code tokens}, preserving {@code labels}' order.
     *
     * <p>Order preservation is not cosmetic: the caller turns this list into
     * positional {@code :qcat{n}} binds, so a stable order keeps the generated SQL
     * text — and hence the server-side plan cache entry — stable across requests for
     * the same query. {@code labels} arrives ordered by display name from
     * {@code PlatformCategoryLabelResolver}.
     *
     * <p>The result is bounded by {@code labels.size()} — the count of admin-approved
     * active categories (a couple of dozen), not by anything caller-controlled — so
     * the {@code IN} list it produces cannot be inflated by query text. A longer
     * query can only ever SHRINK the set, since every additional token is another
     * conjunct each label must satisfy.
     *
     * @param labels selectable categories, in a deterministic order
     * @param tokens normalised, apostrophe-folded query tokens
     * @return matching canonical category names; empty when nothing matched, which
     *         is the overwhelmingly common case (the query is a name or a service)
     */
    static List<String> matchingCategoryNames(List<PlatformCategoryLabel> labels, List<String> tokens) {
        if (tokens.isEmpty() || labels.isEmpty()) {
            return List.of();
        }
        List<String> needles = tokens.stream().map(QueryCategoryMatcher::normalize).toList();
        List<String> matched = new ArrayList<>();
        for (PlatformCategoryLabel label : labels) {
            if (containsAll(normalize(label.displayName()), needles)) {
                matched.add(label.name());
            }
        }
        return List.copyOf(matched);
    }

    /** {@code true} iff {@code haystack} contains every needle (the ANDed-token rule). */
    private static boolean containsAll(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (!haystack.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Case- and apostrophe-folds one operand. {@code Locale.ROOT} is explicit rather
     * than defaulted: the JVM default locale is deployment-dependent, and Turkish
     * dotless-i lowercasing would silently change how a Latin-scripted category name
     * folds on a host that happens to run under {@code tr_TR}.
     */
    private static String normalize(String value) {
        return NormalizedSearchQuery.foldApostrophes(value).toLowerCase(Locale.ROOT);
    }
}
