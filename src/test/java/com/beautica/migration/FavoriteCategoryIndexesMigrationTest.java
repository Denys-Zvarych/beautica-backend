package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content contract for the migrations the favourites CATEGORY AXIS shipped, plus its later
 * reversal:
 * <ul>
 *   <li>{@code V142} → CREATE {@code idx_bookings_salon_client_starts_at} on {@code bookings}
 *       (salon_id, client_id, starts_at DESC) PARTIAL on both operands being NOT NULL — the
 *       salon arm of the favourites LATERAL that derived "the category of the last service
 *       this client booked with this provider".</li>
 *   <li>{@code V143} → DROP {@code idx_bookings_salon_id}, a strict leading-column prefix of
 *       {@code idx_bookings_salon_starts_at}.</li>
 *   <li>{@code V144} → DROP {@code idx_bookings_salon_client_starts_at}: the category axis was
 *       reversed from "last booked category" to "every category the provider offers", which
 *       deleted the salon-arm LATERAL finder — the only query that ever combined
 *       {@code (salon_id, client_id)} in one predicate. The axis is now served by
 *       {@code OfferedCategoryLookup}, which never queries {@code bookings}. See V144's header
 *       for the full consumer audit.</li>
 * </ul>
 *
 * <p><b>Why this class exists.</b> Per QA playbook Q21 a migration's correctness IS its
 * contract, and "Flyway applied with no error" asserts nothing about the outcome. Before this
 * test these migrations were exercised only IMPLICITLY — every Testcontainers IT replays the
 * chain, so a V142 that created the index on the wrong columns, in the wrong order, or without
 * its partial predicate would have kept the whole suite green while silently costing the salon
 * arm its top-1 seek. Both V143 and V144 are worse: a DROP is an irreversible production
 * change, and nothing asserted either that it happened or that any survivor it relies on is
 * still there.
 *
 * <p><b>Column ORDER was asserted, not merely membership, while the index existed.</b>
 * {@code (salon_id, client_id, starts_at)} and {@code (client_id, salon_id, starts_at)} both
 * "contain" all three columns but only the first is a left-prefix match for the LATERAL's
 * correlated {@code salon_id = s.id} term — an index-def assertion built from
 * {@code contains()} calls alone would have passed for a migration that transposed them. That
 * shape assertion is gone now that V144 dropped the index; only its non-existence is asserted
 * below.
 *
 * <p>Fully read-only and order-independent; {@code cleanDb()} never touches catalog metadata,
 * so no fixture or cleanup is required. ASCII-only.
 */
@DisplayName("V142/V143/V144 migrations — favourites category-axis indexes on bookings")
class FavoriteCategoryIndexesMigrationTest extends AbstractIntegrationTest {

    /** V142's addition: the salon arm's top-1 seek. Dropped by V144 — the category axis
     * reversal deleted its only consumer. */
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

    private List<String> allBookingsIndexDefs() {
        return jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings'", String.class);
    }

    // ── V142/V144 — the salon arm's index, created then dropped ────────────────────

    /**
     * V142 created this composite so the salon arm's favourites LATERAL could seek straight to
     * a (salon, client) group instead of walking the salon's whole timeline. V144 dropped it:
     * the category axis reversal ("last booked category" -> "every category the provider
     * offers") deleted that LATERAL — the only query that ever combined
     * {@code (salon_id, client_id)} in one predicate. See V144's header for the consumer audit.
     */
    @Test
    @DisplayName("idx_bookings_salon_client_starts_at no longer exists (V144 drop — the "
            + "favourites category axis reversal deleted its only consumer)")
    void should_dropSalonClientStartsAtIndex_when_v144Applied() {
        assertThat(indexExists(V142_INDEX))
                .as("V142 built %s for the favorites LATERAL top-1 lookup (salon_id, client_id, "
                        + "starts_at DESC). The category axis was reversed and its only consumer "
                        + "— BookingRepository's salon-arm LATERAL finder — was deleted. V144 must "
                        + "have dropped it.", V142_INDEX)
                .isFalse();
    }

    /**
     * V142's partial predicate ({@code salon_id IS NOT NULL AND client_id IS NOT NULL}) is a
     * distinct fact from the index's mere existence-by-name: a migration could drop the named
     * index and something else could reintroduce an equivalent partial construct under a
     * different name without either the drop-by-name test above or the survivor tests below
     * noticing. This scans every index on {@code bookings} — not just the one V142 named — so
     * the construct itself, not just its label, is confirmed gone.
     */
    @Test
    @DisplayName("no index on bookings carries V142's partial predicate (salon_id IS NOT NULL "
            + "AND client_id IS NOT NULL) under any name")
    void should_dropSalonClientPartialPredicate_when_v144Applied() {
        var defs = allBookingsIndexDefs();

        assertThat(defs)
                .as("after V144, no surviving index on bookings should carry both nullable "
                        + "operands V142's WHERE clause combined — that predicate existed only to "
                        + "serve the now-deleted salon-arm LATERAL. Surviving definitions: %s", defs)
                .noneMatch(def -> {
                    String predicate = predicateOf(def);
                    return predicate.contains("salon_id IS NOT NULL")
                            && predicate.contains("client_id IS NOT NULL");
                });
    }

    /**
     * V142's key SHAPE (salon_id, client_id, starts_at combined in one key list) is likewise a
     * fact independent of the index's name. This confirms no surviving index on bookings — by
     * any name — still combines all three columns, so a rename or a copy-paste of V142's
     * {@code CREATE INDEX} body under a different identifier would still fail this test.
     */
    @Test
    @DisplayName("no index on bookings combines salon_id, client_id and starts_at in one key "
            + "list any more")
    void should_dropSalonClientStartsAtKeyShape_when_v144Applied() {
        var defs = allBookingsIndexDefs();

        assertThat(defs)
                .as("V142's composite was the only bookings index ever combining these three "
                        + "columns; V144 removed it and nothing should have recreated the shape "
                        + "under another name. Surviving definitions: %s", defs)
                .noneMatch(def -> {
                    String keys = keyListOf(def);
                    return keys.contains("salon_id") && keys.contains("client_id")
                            && keys.contains("starts_at");
                });
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
     * V142's closing paragraph and V143's checklist BOTH argued that the (now-dropped)
     * {@code (salon_id, client_id, starts_at DESC)} index could not replace
     * {@code (salon_id, starts_at DESC)} — {@code client_id} sat between the equality key and
     * the sort key, so the two indexes served different query shapes and neither subsumed the
     * other. V144 removed the first half of that pair entirely. Pin that its removal did NOT
     * take the survivor with it, and that the survivor still lacks client_id between the
     * equality key and the sort key — i.e. nothing silently widened
     * {@code idx_bookings_salon_starts_at} to plug the gap V144 left.
     */
    @Test
    @DisplayName("after V144 drops the salon/client composite, idx_bookings_salon_starts_at "
            + "survives unchanged and still does not carry client_id")
    void should_keepSalonStartsAtUnwidened_when_v144DropsSalonClientIndex() {
        assertThat(indexExists(V142_INDEX))
                .as("V144 must have dropped %s before this test's premise holds", V142_INDEX)
                .isFalse();

        String survivor = keyListOf(indexDef(V143_SURVIVOR_INDEX));

        assertThat(survivor)
                .as("the survivor must NOT carry client_id between salon_id and starts_at — that "
                        + "is precisely the shape that cannot serve "
                        + "`salon_id = ? ORDER BY starts_at DESC` without a Sort node, and it must "
                        + "not have been widened to compensate for V144's drop")
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
