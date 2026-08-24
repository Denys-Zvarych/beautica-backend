package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content contract for the two migrations the favourites CATEGORY AXIS shipped:
 * <ul>
 *   <li>{@code V142} → CREATE {@code idx_bookings_salon_client_starts_at} on {@code bookings}
 *       (salon_id, client_id, starts_at DESC) PARTIAL on both operands being NOT NULL — the
 *       salon arm of {@code BookingRepository#findLastBookedCategoryBySalonIds}.</li>
 *   <li>{@code V143} → DROP {@code idx_bookings_salon_id}, a strict leading-column prefix of
 *       {@code idx_bookings_salon_starts_at}.</li>
 * </ul>
 *
 * <p><b>Why this class exists.</b> Per QA playbook Q21 a migration's correctness IS its
 * contract, and "Flyway applied with no error" asserts nothing about the outcome. Before this
 * test both migrations were exercised only IMPLICITLY — every Testcontainers IT replays the
 * chain, so a V142 that created the index on the wrong columns, in the wrong order, or without
 * its partial predicate would have kept the whole suite green while silently costing the salon
 * arm its top-1 seek. V143 is worse: a DROP is an irreversible production change, and nothing
 * asserted either that it happened or that the survivor it relies on is still there.
 *
 * <p><b>Column ORDER is asserted, not merely membership.</b> {@code (salon_id, client_id,
 * starts_at)} and {@code (client_id, salon_id, starts_at)} both "contain" all three columns but
 * only the first is a left-prefix match for the LATERAL's correlated {@code salon_id = s.id}
 * term — an index-def assertion built from {@code contains()} calls alone would pass for a
 * migration that transposed them. Same for the PARTIAL predicate: V142's comment argues at
 * length that the {@code WHERE} clause is pure write-side saving, and dropping it changes
 * nothing observable at read time, so only a definition assertion can hold it.
 *
 * <p>Fully read-only and order-independent; {@code cleanDb()} never touches catalog metadata,
 * so no fixture or cleanup is required. ASCII-only.
 */
@DisplayName("V142/V143 migrations — favourites category-axis indexes on bookings")
class FavoriteCategoryIndexesMigrationTest extends AbstractIntegrationTest {

    /** V142's addition: the salon arm's top-1 seek. */
    private static final String V142_INDEX = "idx_bookings_salon_client_starts_at";

    /** V143's removal: a strict prefix of the composite below. */
    private static final String V143_DROPPED_INDEX = "idx_bookings_salon_id";

    /** The survivor V143's whole justification rests on — salon-calendar reads still need it. */
    private static final String V143_SURVIVOR_INDEX = "idx_bookings_salon_starts_at";

    private String indexDef(String indexName) {
        var defs = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?",
                String.class, indexName);
        return defs.isEmpty() ? null : defs.get(0);
    }

    private boolean indexExists(String indexName) {
        return indexDef(indexName) != null;
    }

    // ── V142 — the salon arm's index ──────────────────────────────────────────────

    /**
     * The index exists AND is shaped the way the query needs it. The three assertions are
     * separated by what each catches:
     * <ol>
     *   <li>existence — the migration ran at all;</li>
     *   <li>ordered key list — a transposition, which membership assertions cannot see;</li>
     *   <li>DESC on the sort column — a plain ASC index still SERVES the query (Postgres reads
     *       a B-tree backwards) but does so as a Backward Index Scan, which is the shape V142's
     *       closing paragraph explicitly chose against.</li>
     * </ol>
     */
    @Test
    @DisplayName("idx_bookings_salon_client_starts_at exists on bookings(salon_id, client_id, "
            + "starts_at DESC) in that exact key order")
    void should_createSalonClientStartsAtIndex_when_v142Applied() {
        String def = indexDef(V142_INDEX);

        assertThat(def)
                .as("V142's index is the ONLY thing making the salon arm a top-1 seek rather than "
                        + "a walk of the salon's whole timeline with client_id as a post-scan "
                        + "Filter (measured 1.372ms -> 0.626ms, and degrading linearly with the "
                        + "salon's booking volume without it)")
                .isNotNull();
        assertThat(keyListOf(def))
                .as("key ORDER is load-bearing, not just membership: the LATERAL correlates on "
                        + "salon_id and equality-matches client_id, so salon_id must lead. "
                        + "(client_id, salon_id, starts_at) contains the same three columns and "
                        + "would pass a contains()-based assertion while losing the prefix match. "
                        + "Actual definition: %s", def)
                // containsSubsequence, not containsSequence: the key list carries modifiers
                // ("starts_at DESC") between the names, so the ordered-but-not-adjacent form is
                // the one that expresses "these columns, in this order".
                .containsSubsequence("salon_id", "client_id", "starts_at");
        assertThat(keyListOf(def).toUpperCase(Locale.ROOT))
                .as("starts_at must be DESC so the seek reads forward from the index head — an "
                        + "ASC index still answers the query, as a Backward Index Scan, so no "
                        + "behavioural test can tell the difference. Actual definition: %s", def)
                .contains("STARTS_AT DESC");
    }

    /**
     * The partial predicate. V142 argues it is pure write-side saving on the busiest table in
     * the schema: {@code salon_id} is NULL for every independent-master booking and
     * {@code client_id} is NULL for every guest/LINK booking, and neither can satisfy the
     * query's strict equalities. Removing the {@code WHERE} would change NOTHING any read test
     * can observe — the planner still selects the index — so this assertion is the only guard
     * on it.
     */
    @Test
    @DisplayName("idx_bookings_salon_client_starts_at is PARTIAL on both nullable operands")
    void should_createSalonClientIndexAsPartial_when_v142Applied() {
        String def = indexDef(V142_INDEX);

        assertThat(def)
                .as("V142's index must exist before its predicate can be asserted")
                .isNotNull();
        assertThat(predicateOf(def))
                .as("both operands are nullable for whole booking populations (independent-master "
                        + "bookings carry no salon_id, guest/LINK bookings carry no client_id) and "
                        + "neither can satisfy the query's strict equalities — indexing them is "
                        + "write amplification on the busiest table in the schema. Actual "
                        + "definition: %s", def)
                .contains("salon_id IS NOT NULL")
                .contains("client_id IS NOT NULL");
    }

    // ── V143 — the redundant-prefix drop ──────────────────────────────────────────

    @Test
    @DisplayName("idx_bookings_salon_id no longer exists (V143 drop — strict prefix of "
            + "idx_bookings_salon_starts_at)")
    void should_dropRedundantSalonIdIndex_when_v143Applied() {
        assertThat(indexExists(V143_DROPPED_INDEX))
                .as("V143 must have dropped %s — a B-tree seeks on its leading key regardless of "
                        + "what follows it, so every predicate this prefix index could satisfy is "
                        + "satisfied by %s with the same index condition. `bookings` carries the "
                        + "heaviest write load in the schema and every surviving index is "
                        + "maintained on each INSERT and non-HOT UPDATE.",
                        V143_DROPPED_INDEX, V143_SURVIVOR_INDEX)
                .isFalse();
    }

    /**
     * The other half of the drop, and the half a lone "is it gone?" assertion cannot give: V143's
     * entire safety argument is that {@code idx_bookings_salon_starts_at} subsumes what was
     * removed. A later migration that dropped or narrowed the SURVIVOR would leave
     * {@code should_dropRedundantSalonIdIndex_when_v143Applied} green while leaving
     * {@code salon_id = ?} with no index at all.
     *
     * <p>It must also still be FULL (no {@code WHERE}): V143 records that a partial survivor
     * would not subsume the dropped index unconditionally — that is the V123 case its checklist
     * explicitly distinguishes itself from.
     */
    @Test
    @DisplayName("idx_bookings_salon_starts_at survives V143 as a FULL index — it is the "
            + "subsumption the drop relies on")
    void should_keepSalonStartsAtComposite_when_v143Applied() {
        String def = indexDef(V143_SURVIVOR_INDEX);

        assertThat(def)
                .as("V143 removed %s only because %s subsumes it; without the survivor, "
                        + "`salon_id = ?` has no index at all and the drop becomes a regression",
                        V143_DROPPED_INDEX, V143_SURVIVOR_INDEX)
                .isNotNull();
        assertThat(keyListOf(def))
                .as("the survivor must still LEAD on salon_id for the subsumption to hold. "
                        + "Actual definition: %s", def)
                .startsWith("salon_id");
        assertThat(predicateOf(def))
                .as("the survivor must stay FULL: a partial survivor would exclude some query "
                        + "family the dropped full index used to serve, which is exactly the V123 "
                        + "case V143's checklist distinguishes itself from. Actual definition: %s",
                        def)
                .isEmpty();
    }

    /**
     * V142's closing paragraph and V143's checklist BOTH assert that the new
     * {@code (salon_id, client_id, starts_at DESC)} index cannot replace
     * {@code (salon_id, starts_at DESC)} — {@code client_id} sits between the equality key and
     * the sort key. That is the reasoning that kept the survivor alive, so pin that the two are
     * genuinely different indexes and neither has quietly become the other.
     */
    @Test
    @DisplayName("V142's index and the V143 survivor are distinct — client_id sits between the "
            + "equality key and the sort key, so neither subsumes the other")
    void should_keepBothSalonIndexesDistinct_when_v142AndV143Applied() {
        String v142 = keyListOf(indexDef(V142_INDEX));
        String survivor = keyListOf(indexDef(V143_SURVIVOR_INDEX));

        assertThat(v142)
                .as("V142's index is only a top-1 seek because client_id is its SECOND key; if it "
                        + "ever collapsed to the survivor's shape the salon arm silently loses its "
                        + "index and V142's whole measurement stops applying")
                .isNotEqualTo(survivor);
        assertThat(survivor)
                .as("the survivor must NOT carry client_id between salon_id and starts_at — that "
                        + "is precisely the shape that cannot serve "
                        + "`salon_id = ? ORDER BY starts_at DESC` without a Sort node")
                .doesNotContain("client_id");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * The parenthesised key list of a {@code CREATE INDEX} definition, e.g.
     * {@code "salon_id, client_id, starts_at DESC"} — everything between the first {@code (}
     * after {@code USING} and its closing {@code )}.
     *
     * <p>Taken from {@code USING} rather than the first {@code (} in the whole string so a
     * schema-qualified or quoted table name cannot shift the window.
     */
    private static String keyListOf(String indexDef) {
        assertThat(indexDef).as("no index definition to parse").isNotNull();
        int using = indexDef.toUpperCase(Locale.ROOT).indexOf(" USING ");
        assertThat(using)
                .as("index definition <%s> has no USING clause — pg_indexes emitted an unexpected "
                        + "shape and this parser would silently read the wrong window", indexDef)
                .isGreaterThan(-1);
        int open = indexDef.indexOf('(', using);
        int close = indexDef.indexOf(')', open);
        assertThat(open).as("index definition <%s> has no key list", indexDef).isGreaterThan(-1);
        assertThat(close).as("index definition <%s> has an unterminated key list", indexDef)
                .isGreaterThan(open);
        return indexDef.substring(open + 1, close).trim();
    }

    /**
     * The {@code WHERE ...} tail of a {@code CREATE INDEX} definition, or {@code ""} for a full
     * index. Returns empty rather than throwing so a caller can assert either direction —
     * "must be partial" and "must be full" are both real contracts here.
     */
    private static String predicateOf(String indexDef) {
        assertThat(indexDef).as("no index definition to parse").isNotNull();
        int where = indexDef.toUpperCase(Locale.ROOT).lastIndexOf(" WHERE ");
        return where < 0 ? "" : indexDef.substring(where + " WHERE ".length()).trim();
    }
}
