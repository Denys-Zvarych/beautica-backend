package com.beautica.salon.repository;

/**
 * The <b>single</b> definition of the salon free-text ({@code q}) SQL, shared by
 * the two code paths that filter salons by {@code q}:
 *
 * <ol>
 *   <li>the <b>static</b> projection queries in {@link SalonRepository} (six SQL
 *       bodies, one per location/price overload), assembled by concatenating the
 *       {@code STATIC_*} fragments below around a one-line location predicate;</li>
 *   <li>the <b>dynamic</b> per-service-filtered builder in
 *       {@code SearchService.buildSalonSearchSql}, which appends
 *       {@link #dynamicQGroupPredicate(int)} and
 *       {@link #dynamicMatchedNamesPredicate(String, int)}.</li>
 * </ol>
 *
 * <h3>Single-query pagination (perf audit 2026-07-29)</h3>
 * The static bodies used to be Spring Data {@code Page} queries: a data statement
 * plus a hand-written {@code countQuery}, <b>both</b> carrying the full correlated
 * group {@code EXISTS}, so every free-text salon search evaluated it twice
 * (measured surcharge 15–40 % on the already-slower salon path). They now mirror
 * the master path and the dynamic builder instead: ONE statement carrying
 * {@code COUNT(*) OVER()} — Postgres computes window functions before
 * {@code LIMIT}, so the full filtered count rides along in every paged row — and
 * {@code LIMIT :limit OFFSET :offset} bound <em>inside</em> the derived table
 * {@code t}. The inner {@code LIMIT} is what actually restores the post-{@code LIMIT}
 * property the {@code pn} / {@code mnq} laterals were always documented to have and
 * never had (see {@link #STATIC_MATCHED_NAMES_LATERAL}). Both changes require
 * dropping {@code Page<>} for {@code List<>}: a Spring Data {@code Pageable} can
 * express neither an inner {@code LIMIT} nor a windowed count.</p>
 *
 * <h3>Why this class exists (defect E)</h3>
 * The two paths were hand-written copies and they <b>drifted</b>: the static
 * path gated the service-name branch on the bookable-master {@code EXISTS}
 * ("an active master of this salon actually performs this service"), the
 * dynamic path did not. That silently violated the locked domain rule —
 * <em>a salon's client-visible offering is only what an active master
 * performs; orphan salon services are hidden everywhere</em> — the moment a
 * request combined {@code q} with {@code serviceTypeSlugs} and the dynamic
 * builder took over. Both forms are now derived from the same
 * {@link #BOOKABLE_SERVICE_MATCH_HEAD} body, so an edit cannot desynchronise
 * them again.
 *
 * <h3>Group-scoped token matching (the {@code q} semantics)</h3>
 * A salon matches {@code q} when <b>every</b> token is satisfied by the salon
 * name <em>or by one single bookable service</em> — the <em>same</em> service
 * for all service-satisfied tokens:
 *
 * <pre>
 *   matches(salon) :=  ( &forall; token : token matches s.name )
 *                   OR ( &exist; bookable service sdq of this salon :
 *                        &forall; token : token matches s.name OR sdq.name )
 * </pre>
 *
 * The second disjunct subsumes the first whenever the salon has at least one
 * bookable service; the first is kept so a salon with <em>no</em> bookable
 * service is still findable by its own name. Because the salon name is re-tested
 * inside the {@code EXISTS}, a mixed query such as
 * {@code "Aura манікюр"} (one token by salon name, one by a service) still
 * matches — a naive "all tokens must hit one service" form would not.
 *
 * <p><b>What this replaced.</b> The predicate used to be emitted <em>per
 * token</em> and ANDed at the salon level
 * ({@code AND (s.name ILIKE :qN OR EXISTS(… sdq.name ILIKE :qN))} once per
 * token). Tokens could then be satisfied by <em>different</em> services of the
 * same salon: {@code q=Ботокс для волосся} matched a salon offering
 * «Ботокс вій» <em>plus</em> «Щастя для волосся», which jointly contain all
 * three tokens while neither is the service the user asked for. Measured on the
 * local demo dataset: 30 salons returned, of which 4 were such cross-service
 * false positives.</p>
 *
 * <p><b>No redundant per-token pre-filter here (measured).</b> The master path
 * (see {@code SearchService.appendQPredicate}) additionally emits the old
 * per-token predicates as a logically implied, index-servable pre-filter,
 * because on {@code users}/{@code service_definitions} they collapse into
 * <em>hashed</em> sub-plans that the V98 {@code idx_service_definitions_name_trgm}
 * trigram index serves, cutting the candidate set before the exact group
 * {@code EXISTS} runs. The salon {@code EXISTS} is correlated on
 * {@code sdq.owner_id = s.id} and is served by
 * {@code ux_service_def_owner_service_type_active} — never by the trigram index,
 * in <em>any</em> formulation — so a pre-filter here only adds three more
 * per-row sub-plan evaluations. The two forms are semantically identical (the
 * per-token predicate is strictly implied by the group predicate), so this is a pure
 * plan choice, not a behaviour difference.
 *
 * <p><b>The numbers that used to be quoted here are withdrawn, not updated.</b> This
 * paragraph carried "group-only 5.3 ms / 6 246 shared buffers vs pre-filter + group
 * 7.4 ms / 7 813 buffers" for {@code q=Ботокс для волосся}. Those were measured
 * against the <em>Spring Data {@code Pageable}</em> shape of these queries — a data
 * statement plus a separate {@code countQuery}, both carrying the full correlated
 * group {@code EXISTS}, with the {@code LIMIT} applied at the OUTERMOST level so the
 * {@code pn} / {@code mnq} laterals ran over the whole candidate set. The
 * single-query rewrite (see the class Javadoc) changed all three of those properties,
 * so the figures no longer describe either side of the comparison and cannot be
 * reconciled with the current plan — they are removed rather than restated, because a
 * stale measurement that still reads as current is worse than none. The
 * <em>qualitative</em> conclusion is unchanged and is what this decision rests on: the
 * salon {@code EXISTS} is correlated on {@code sdq.owner_id = s.id} and cannot be
 * served by a trigram index in any formulation, so a per-token pre-filter here can
 * only add work. Re-measure both arms in one session against one plan before quoting
 * a delta again.</p>
 *
 * <h3>Why two forms and not one string</h3>
 * The static queries are Spring Data {@code @Query} annotations: their value
 * must be a compile-time constant, and an absent token has to be expressed as a
 * null-gated {@code CAST(:qN AS text) IS NULL OR …} branch because a fixed SQL
 * body cannot drop a parameter. The dynamic builder appends a predicate only
 * when the token exists, so it must NOT emit the {@code CAST(:p …)} idiom (the
 * builder deliberately binds typed objects — {@code SearchServiceTest} asserts
 * the generated SQL contains no {@code "CAST(:"}). Only the wrapper differs;
 * the semantic body — including the bookable-master gate — is defined once.
 *
 * <h3>Alias contract</h3>
 * The predicate forms assume the salon row is aliased {@code s}. That holds in
 * the static queries' inner derived table and in the
 * dynamic builder's inner Top-N ({@code FROM salons s}). The matched-names
 * lateral instead correlates to the <em>outer derived table</em> aliased
 * {@code t} (post-{@code LIMIT}), so it reads {@code t.id} / {@code t.name}.
 * The fragments introduce their own aliases {@code sdq} / {@code msq} /
 * {@code mmq} / {@code sd4} / {@code mnq}, which must not collide with the
 * surrounding query.
 */
public final class SalonSearchSql {

    private SalonSearchSql() {
        // static SQL-fragment holder
    }

    /**
     * Number of {@code :qN} bind parameters the static projection queries
     * declare ({@code :q0} … {@code :q3}).
     *
     * <p><b>Must stay equal to</b>
     * {@code NormalizedSearchQuery.MAX_TOKENS}. The static queries cannot size
     * themselves at runtime, so {@code SearchService} pads the normalised token
     * list out to exactly this many arguments (unused slots bound as
     * {@code null}, which short-circuits their branch to TRUE).</p>
     */
    public static final int STATIC_TOKEN_PARAM_COUNT = 4;

    // ── bookable-master gate (alias-parameterised via constant concatenation) ──

    /*
     * The gate: this salon-owned service is performed by at least one ACTIVE
     * master currently attached to THIS salon. `mmq.salon_id = <salon>.id` closes
     * the rotated-master leak (a master who moved to another salon must not keep
     * the old salon's service bookable).
     *
     * Split into three constant chunks so the same body can be re-aliased for the
     * WHERE-clause form (salon `s`, definition `sdq`) and for the post-LIMIT
     * matched-names lateral (salon `t`, definition `sd4`) while remaining a
     * compile-time constant expression, which a @Query value must be.
     */
    private static final String GATE_HEAD =
            "AND EXISTS (SELECT 1 FROM master_services msq "
                    + "JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true "
                    + "AND mmq.salon_id = ";
    private static final String GATE_MID = ".id WHERE msq.service_def_id = ";
    private static final String GATE_TAIL = ".id AND msq.is_active = true) ";

    /** Bookable gate for the WHERE-clause form: salon {@code s}, definition {@code sdq}. */
    private static final String BOOKABLE_MASTER_GATE = GATE_HEAD + "s" + GATE_MID + "sdq" + GATE_TAIL;

    /** Bookable gate for the matched-names lateral: salon {@code t}, definition {@code sd4}. */
    private static final String BOOKABLE_MASTER_GATE_MATCHED = GATE_HEAD + "t" + GATE_MID + "sd4" + GATE_TAIL;

    /**
     * Correlated {@code EXISTS} over the salon's <em>bookable</em> owned
     * services, up to (but excluding) the per-token conjunction — callers append
     * the token conditions and the closing parenthesis.
     */
    private static final String BOOKABLE_SERVICE_MATCH_HEAD =
            "EXISTS (SELECT 1 FROM service_definitions sdq "
                    + "WHERE sdq.owner_type = 'SALON' AND sdq.owner_id = s.id AND sdq.is_active = true "
                    + BOOKABLE_MASTER_GATE
                    + "AND ";

    // ── static (@Query) form: null-gated, one branch per bind slot ────────────

    // Each slot is spelled out as a compile-time constant expression — a
    // Spring Data @Query value must be one, so these cannot be generated by a
    // helper method. Only the null-gate wrapper is repeated; the semantic body
    // (BOOKABLE_SERVICE_MATCH_HEAD, incl. the bookable-master gate) is shared,
    // which is what actually drifted before.
    //
    // NAME_ONLY_n  — token n satisfied by the salon name (TRUE when the slot is unbound).
    // WITH_SVC_n   — token n satisfied by the salon name OR by the single service `sdq`.
    private static final String NAME_ONLY_0 =
            "(CAST(:q0 AS text) IS NULL OR s.name ILIKE CAST(:q0 AS text))";
    private static final String NAME_ONLY_1 =
            "(CAST(:q1 AS text) IS NULL OR s.name ILIKE CAST(:q1 AS text))";
    private static final String NAME_ONLY_2 =
            "(CAST(:q2 AS text) IS NULL OR s.name ILIKE CAST(:q2 AS text))";
    private static final String NAME_ONLY_3 =
            "(CAST(:q3 AS text) IS NULL OR s.name ILIKE CAST(:q3 AS text))";

    /**
     * Guard that switches the row-invariant {@code s.name} disjunct <em>inside</em>
     * the correlated {@code EXISTS} on only when the query carries MORE than one
     * token.
     *
     * <h4>Why it is sound</h4>
     * Slots are filled in order, so {@code :q1} is bound iff at least two tokens
     * exist. That left-packing is not merely conventional: it is asserted as a
     * postcondition by {@code SearchService.requireLeftPacked}, invoked from the
     * sole producer of these four values ({@code paddedSalonTokenPatterns}). A
     * future caller that bound {@code :q1} without {@code :q0} would invert this
     * guard silently — dropping the {@code s.name} disjunct from a query that needs
     * it — so it fails loudly there instead. With exactly one token the predicate is
     * {@code s.name ILIKE :q0 OR EXISTS(bookable sdq: s.name ILIKE :q0 OR sdq.name ILIKE :q0)}
     * — the inner {@code s.name ILIKE :q0} is row-invariant, so whenever it is TRUE
     * the leading disjunct has already short-circuited the whole predicate, and
     * whenever it is FALSE it contributes nothing. Removing it is therefore an
     * identity, not an approximation. With two or more tokens the guard is TRUE and
     * the form is byte-identical to before — which it must be, because the inner
     * {@code s.name} re-test is exactly what makes a mixed query such as
     * {@code "Aura манікюр"} (one token by salon name, one by a service) match.
     *
     * <h4>Why it pays</h4>
     * The single-token case is the dominant traffic shape (an incremental search box
     * sends a request per settled keystroke, and every one is single-token until the
     * user types a space). Dropping the disjunct stops the correlated sub-plan
     * re-evaluating a condition that cannot change across the services it walks.
     */
    private static final String MULTI_TOKEN_GUARD = "CAST(:q1 AS text) IS NOT NULL AND ";

    private static final String WITH_SVC_0 =
            "(CAST(:q0 AS text) IS NULL OR (" + MULTI_TOKEN_GUARD
                    + "s.name ILIKE CAST(:q0 AS text)) OR sdq.name ILIKE CAST(:q0 AS text))";
    private static final String WITH_SVC_1 =
            "(CAST(:q1 AS text) IS NULL OR s.name ILIKE CAST(:q1 AS text) OR sdq.name ILIKE CAST(:q1 AS text))";
    private static final String WITH_SVC_2 =
            "(CAST(:q2 AS text) IS NULL OR s.name ILIKE CAST(:q2 AS text) OR sdq.name ILIKE CAST(:q2 AS text))";
    private static final String WITH_SVC_3 =
            "(CAST(:q3 AS text) IS NULL OR s.name ILIKE CAST(:q3 AS text) OR sdq.name ILIKE CAST(:q3 AS text))";

    /**
     * The single group-scoped {@code q} predicate, spliced verbatim into every
     * static salon projection query (data <em>and</em> count).
     *
     * <p>Every {@code :qN} slot must be bound; an unbound slot binds {@code null},
     * whose null gate makes its branch trivially TRUE. Binding
     * {@code (pattern, null, null, null)} therefore reproduces the single-token
     * behaviour, and binding all four {@code null} makes the <em>name-only</em>
     * disjunct unconditionally TRUE — which short-circuits the whole predicate to
     * TRUE without ever touching {@code service_definitions}, i.e. "no {@code q}
     * filter" costs nothing.</p>
     *
     * <p>Leading and trailing newlines let this be concatenated between two
     * text blocks without gluing tokens together.</p>
     */
    public static final String STATIC_Q_GROUP_PREDICATE =
            "\n  AND (\n"
                    + "        (" + NAME_ONLY_0 + " AND " + NAME_ONLY_1
                    + " AND " + NAME_ONLY_2 + " AND " + NAME_ONLY_3 + ")\n"
                    + "     OR " + BOOKABLE_SERVICE_MATCH_HEAD
                    + WITH_SVC_0 + " AND " + WITH_SVC_1
                    + " AND " + WITH_SVC_2 + " AND " + WITH_SVC_3 + ")\n"
                    + "  )\n";

    /**
     * Post-{@code LIMIT} correlated lateral producing {@code matched_names} — the
     * capped, DISTINCT bookable service names of the paged salon that
     * <em>explain</em> the {@code q} match.
     *
     * <p>A service is reported when it (a) satisfies the same group-scoped
     * condition the WHERE clause used, and (b) contributes at least one token
     * <em>through its own name</em>. Condition (b) is what keeps a pure
     * salon-name match (e.g. {@code q=Aura Corner}) from listing an arbitrary
     * alphabetical slice of the salon's whole catalogue: every service trivially
     * satisfies (a) in that case, but none satisfies (b), so
     * {@code matchedServiceNames} is empty and the card falls back to the
     * {@code serviceNames} preview.</p>
     *
     * <p>Condition (b) also doubles as the "no {@code q}" short-circuit: with
     * every slot bound {@code null} the contribution disjunction is
     * {@code NULL} → not TRUE → no rows → {@code array_agg} returns {@code NULL}
     * → an empty list. The leading {@code CAST(:q0 AS text) IS NOT NULL} guard
     * references no column, so Postgres folds it into a <em>one-time filter</em>
     * and skips the sub-plan entirely when no query was supplied (slots are
     * filled in order, so {@code q0} is bound iff any token exists).</p>
     *
     * <p><b>Correlation and cardinality — corrected (perf audit 2026-07-29).</b>
     * This lateral correlates to the outer derived table {@code t}. An earlier
     * revision of this Javadoc concluded from that alone that it "runs for only the
     * ~{@code pageSize} paged rows — never the whole candidate set". <b>That was
     * false while the static queries paged through a Spring Data {@code Pageable}</b>:
     * Spring owned the {@code LIMIT} and appended it to the OUTERMOST block, the
     * planner flattened {@code t}, and the measured plan was
     * {@code Limit → Sort → NestLoop(pr, pn, mnq)} with {@code mnq} at
     * {@code loops=296} for a 20-row page — a 14.8× overcount ({@code q=ння}, 296
     * candidate salons). The claim is true again only because the six static
     * projection queries now bind {@code LIMIT :limit OFFSET :offset} <em>inside</em>
     * {@code t} (mirroring {@code SearchService.buildSalonSearchSql}, which never had
     * the defect). If any caller ever reverts to a {@code Pageable}-driven
     * {@code LIMIT}, this lateral — and the sibling {@code pn} preview — silently
     * expand back to the whole candidate set.</p>
     *
     * <h4>Why there is no {@code ORDER BY} / {@code LIMIT} here</h4>
     * Spring Data decides whether to append its {@link org.springframework.data.domain.Sort}
     * as {@code " order by …"} or {@code ", …"} using
     * {@code QueryUtils.hasOrderByClause}, which compares
     * {@code count(/order\\s+by\\s+/)} against
     * {@code count(/\\([\\s\\S]*order\\s+by\\s[\\s\\S]*\\)/)}. The second pattern is
     * greedy, so it can only ever match <b>once</b> per query. A native query may
     * therefore contain <b>at most one</b> literal {@code ORDER BY}; a second one —
     * even nested inside a lateral — flips the heuristic and Spring emits
     * {@code ") mnq ON true, name asc fetch first ? rows only"}, a hard Postgres
     * syntax error. The one {@code ORDER BY} budget is already spent by the
     * {@code pn} service-name preview lateral. So this aggregate is emitted
     * unordered and unbounded, and {@code SearchService.toMatchedServiceNames}
     * applies the deterministic sort and the {@code SERVICE_NAME_CAP} slice when
     * mapping the row. The set is bounded by the salon's own bookable catalogue
     * and is computed for only the paged rows, so the un-capped aggregate is
     * cheap — and it never reaches the wire.
     */
    public static final String STATIC_MATCHED_NAMES_LATERAL = """

            LEFT JOIN LATERAL (
                SELECT array_agg(DISTINCT sd4.name) AS matched_names
                FROM service_definitions sd4
                WHERE CAST(:q0 AS text) IS NOT NULL
                  AND sd4.owner_type = 'SALON'
                  AND sd4.owner_id = t.id
                  AND sd4.is_active = true
                  """ + BOOKABLE_MASTER_GATE_MATCHED + """

                  AND (sd4.name ILIKE CAST(:q0 AS text)
                    OR sd4.name ILIKE CAST(:q1 AS text)
                    OR sd4.name ILIKE CAST(:q2 AS text)
                    OR sd4.name ILIKE CAST(:q3 AS text))
                  AND (CAST(:q0 AS text) IS NULL OR t.name ILIKE CAST(:q0 AS text) OR sd4.name ILIKE CAST(:q0 AS text))
                  AND (CAST(:q1 AS text) IS NULL OR t.name ILIKE CAST(:q1 AS text) OR sd4.name ILIKE CAST(:q1 AS text))
                  AND (CAST(:q2 AS text) IS NULL OR t.name ILIKE CAST(:q2 AS text) OR sd4.name ILIKE CAST(:q2 AS text))
                  AND (CAST(:q3 AS text) IS NULL OR t.name ILIKE CAST(:q3 AS text) OR sd4.name ILIKE CAST(:q3 AS text))
                ) mnq ON true
            """;

    // ── static (@Query) body fragments ────────────────────────────────────────

    /*
     * The six static overloads differ ONLY in their one-line location predicate and
     * in whether the price band-overlap predicate is present. Everything else used
     * to be copy-pasted twelve times (data + count for each overload) — 700 lines in
     * which the rotated-master correlation, the bookable gate and the category gate
     * had to be kept identical by hand. They are defined once here and concatenated;
     * a @Query value must be a compile-time constant expression, and concatenation
     * of static final Strings is one.
     */

    /**
     * Outer projection + the head of the inner Top-N derived table {@code t}, up to
     * and including {@code WHERE s.is_active = true}. Callers append the location
     * predicate, then {@link #STATIC_CATEGORY_GATE}, {@link #STATIC_Q_GROUP_PREDICATE},
     * optionally {@link #STATIC_PRICE_PREDICATE}, then {@link #STATIC_ORDER_LIMIT_TAIL}.
     *
     * <p>{@code COUNT(*) OVER()} is the single-query pagination window (see the class
     * Javadoc): Postgres evaluates it before the {@code LIMIT} on the same query
     * level, so it reports the FULL filtered count in every paged row and replaces
     * the second {@code countQuery} statement.</p>
     *
     * <p>The {@code pr} price-band lateral stays <em>inside</em> {@code t} because
     * {@code pmin}/{@code pmax} feed the price {@code WHERE} and the {@code ORDER BY}.
     * The two name laterals do not, so they are attached outside — see
     * {@link #STATIC_NAME_PREVIEW_LATERAL}.</p>
     */
    public static final String STATIC_PROJECTION_HEAD = """
            SELECT t.id           AS id,
                   t.name         AS name,
                   t.city_id      AS city_id,
                   t.district_id  AS district_id,
                   t.avatar_url   AS avatar_url,
                   t.price_min    AS price_min,
                   t.price_max    AS price_max,
                   pn.pnames      AS service_names,
                   t.street       AS street,
                   t.building_no  AS building_no,
                   t.location_note AS location_note,
                   mnq.matched_names AS matched_service_names,
                   t.total_count  AS total_count
            FROM (
                SELECT s.id           AS id,
                       s.name         AS name,
                       s.city_id      AS city_id,
                       s.district_id  AS district_id,
                       s.avatar_url   AS avatar_url,
                       pr.pmin        AS price_min,
                       pr.pmax        AS price_max,
                       s.street       AS street,
                       s.building_no  AS building_no,
                       s.location_note AS location_note,
                       COUNT(*) OVER() AS total_count
                FROM salons s
                LEFT JOIN LATERAL (
                    SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                           MAX(COALESCE(ms.price_override,
                                        CASE WHEN sd.price_type = 'RANGE'
                                             THEN sd.price_max ELSE sd.base_price END)) AS pmax
                    FROM master_services ms
                    JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                    JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true AND mad.salon_id = s.id
                    WHERE sd.owner_type = 'SALON'
                      AND sd.owner_id = s.id
                      AND ms.is_active = true
                      AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
                ) pr ON true
                WHERE s.is_active = true
            """;

    // ── the ONE ORDER BY definition, re-aliased for the inner and outer levels ────
    //
    // The inner Top-N (STATIC_ORDER_LIMIT_TAIL) and the outer restatement
    // (STATIC_OUTER_ORDER_BY) MUST stay column-for-column identical: a LEFT JOIN LATERAL
    // does not preserve the inner ordering, so the outer ORDER BY is what the client
    // actually receives, while the inner one decides WHICH rows the page contains. If they
    // drift, the page holds the right rows in the wrong order — a wrong-page-order bug with
    // NO failing test, because every count, total and membership assertion still passes.
    // They were two hand-maintained text blocks; they are now one definition, split into
    // constant chunks so each level can substitute its own price expressions
    // (pr.pmin/pr.pmax vs t.price_min/t.price_max) and row alias (s vs t) while staying a
    // compile-time constant expression, which a @Query value must be. Same technique, and
    // same reason, as GATE_HEAD/GATE_MID/GATE_TAIL above.
    private static final String ORDER_BY_HEAD =
            "            ORDER BY CASE WHEN CAST(:sortMode AS text) = 'PRICE_ASC' THEN ";
    private static final String ORDER_BY_MID =
            " END ASC NULLS LAST,\n"
                    + "                     CASE WHEN CAST(:sortMode AS text) = 'PRICE_DESC' THEN ";
    private static final String ORDER_BY_TAIL = " END DESC NULLS LAST,\n                     ";
    private static final String ORDER_BY_NAME = ".name, ";
    private static final String ORDER_BY_ID = ".id\n";

    /** District-primary location predicate for the inner Top-N. */
    public static final String STATIC_DISTRICT_PREDICATE = "      AND s.district_id = :districtId\n";

    /** City-level location predicate for the inner Top-N. */
    public static final String STATIC_CITY_PREDICATE = "      AND s.city_id = :cityId\n";

    /**
     * Category-membership gate: the salon owns at least one <em>bookable</em>
     * service in the searched category. TRUE (and never evaluated) when
     * {@code :category} is null.
     */
    public static final String STATIC_CATEGORY_GATE = """
                  AND (CAST(:category AS text) IS NULL OR EXISTS (
                      SELECT 1 FROM service_definitions sdc
                      WHERE sdc.owner_type = 'SALON'
                        AND sdc.owner_id = s.id
                        AND sdc.is_active = true
                        AND EXISTS (SELECT 1 FROM master_services msc
                                    JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true AND mmc.salon_id = s.id
                                    WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                        AND sdc.category = CAST(:category AS text)))
            """;

    /** Price band-overlap predicate — spliced in only by the price-bound overloads. */
    public static final String STATIC_PRICE_PREDICATE = """
                  AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
                  AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            """;

    /**
     * Closes the inner Top-N: the allow-listed {@code ORDER BY} and the
     * {@code LIMIT}/{@code OFFSET} that make {@code t} at most one page wide before
     * the name laterals are joined.
     *
     * <h4>Why the ordering is a CASE and not three query bodies</h4>
     * The sort is one of three shapes and the caller supplies it as an enum, but a
     * {@code @Query} value is a compile-time constant, so it cannot branch. Binding
     * the enum <em>name</em> and selecting the sort key with a {@code CASE} keeps ONE
     * body per overload instead of eighteen. The caller's raw text never reaches the
     * SQL — {@code :sortMode} is bound from {@code SearchSort.name()}, and an
     * unrecognised value simply leaves both {@code CASE}s NULL and falls through to
     * the {@code name}/{@code id} tiebreaker, so it is injection-safe and total.
     *
     * <p>Non-selected branches evaluate to NULL for every row, which ties and defers
     * to {@code s.name, s.id}. {@code NULLS LAST} is explicit on both so a salon with
     * no priced service sorts last in either direction rather than flipping with the
     * direction default.</p>
     *
     * <p>This is not a plan regression: the previous form applied Spring Data's
     * {@code Sort} to the OUTERMOST block over the flattened candidate set, so the
     * static path never had an index-ordered Top-N to lose. It gains one bound sort
     * over ≤ pageSize+offset rows instead.</p>
     */
    public static final String STATIC_ORDER_LIMIT_TAIL =
            "    " + ORDER_BY_HEAD + "pr.pmin" + ORDER_BY_MID + "pr.pmax" + ORDER_BY_TAIL
                    + "s" + ORDER_BY_NAME + "s" + ORDER_BY_ID
                    + "                LIMIT :limit OFFSET :offset\n"
                    + "            ) t\n";

    /**
     * Post-{@code LIMIT} {@code pnames} preview lateral — the capped DISTINCT
     * bookable service names shown on the card when no {@code q} explains the match.
     * Correlated to {@code t.id}; now genuinely post-{@code LIMIT} because
     * {@link #STATIC_ORDER_LIMIT_TAIL} bounds {@code t}.
     *
     * <p>This lateral owns the query's ONE permitted literal {@code ORDER BY} budget
     * under Spring Data's {@code Sort}-append heuristic — see
     * {@link #STATIC_MATCHED_NAMES_LATERAL}. That budget no longer binds now that the
     * static queries take no {@code Pageable} (Spring appends nothing, so the
     * heuristic never runs), but the constraint is retained rather than relaxed:
     * re-introducing a {@code Pageable} would silently re-arm it.</p>
     */
    public static final String STATIC_NAME_PREVIEW_LATERAL = """
            LEFT JOIN LATERAL (
                SELECT array_agg(z.name) AS pnames
                  FROM (SELECT DISTINCT sd2.name AS name
                        FROM service_definitions sd2
                        WHERE sd2.owner_type = 'SALON'
                          AND sd2.owner_id = t.id
                          AND sd2.is_active = true
                          AND EXISTS (SELECT 1 FROM master_services ms2
                                      JOIN masters mm2 ON mm2.id = ms2.master_id AND mm2.is_active = true AND mm2.salon_id = t.id
                                      WHERE ms2.service_def_id = sd2.id AND ms2.is_active = true)
                          AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                        ORDER BY sd2.name
                        LIMIT 3) z) pn ON true
            """;

    /**
     * Outer {@code ORDER BY}, restated on {@code t} because a {@code LEFT JOIN
     * LATERAL} does not preserve the inner ordering. Over the already-bounded
     * ≤ pageSize rows.
     *
     * <p>Column-for-column identical to {@link #STATIC_ORDER_LIMIT_TAIL} <b>by
     * construction, not by hand</b>: both are assembled from the same
     * {@code ORDER_BY_*} chunks with only the price expressions
     * ({@code pr.pmin}/{@code pr.pmax} → {@code t.price_min}/{@code t.price_max}) and
     * the row alias ({@code s} → {@code t}) substituted. The two used to be separate
     * text blocks — see the {@code ORDER_BY_HEAD} comment for what silent drift
     * between them would have cost.</p>
     */
    public static final String STATIC_OUTER_ORDER_BY =
            ORDER_BY_HEAD + "t.price_min" + ORDER_BY_MID + "t.price_max" + ORDER_BY_TAIL
                    + "t" + ORDER_BY_NAME + "t" + ORDER_BY_ID;

    // ── dynamic (StringBuilder) form: emitted only for tokens that exist ──────

    /**
     * The group-scoped {@code q} predicate for the dynamic builder: no
     * {@code CAST(:p …)} wrapper and no null gate, because the builder emits this
     * only when at least one token exists and binds each pattern as a plain
     * {@code String}.
     *
     * <p><b>Single-token short-circuit.</b> For {@code tokenCount == 1} the
     * row-invariant {@code s.name ILIKE :q0} disjunct inside the {@code EXISTS} is
     * omitted, producing
     * {@code AND (s.name ILIKE :q0 OR EXISTS(bookable sdq: sdq.name ILIKE :q0))}.
     * That is an <em>identity</em>, not an approximation — see
     * {@link #MULTI_TOKEN_GUARD}, which achieves the same thing in the static form
     * (where the token count cannot be known at compile time). Single-token is the
     * dominant traffic shape from an incremental search box.</p>
     *
     * @param tokenCount number of bound {@code :q0}…{@code :q{n-1}} slots, {@code >= 1}
     * @return the {@code AND (…)} predicate, or the empty string when {@code tokenCount == 0}
     */
    public static String dynamicQGroupPredicate(int tokenCount) {
        if (tokenCount <= 0) {
            return "";
        }
        boolean multiToken = tokenCount > 1;
        StringBuilder sb = new StringBuilder("AND ((");
        for (int i = 0; i < tokenCount; i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append("s.name ILIKE :q").append(i);
        }
        sb.append(") OR ").append(BOOKABLE_SERVICE_MATCH_HEAD);
        for (int i = 0; i < tokenCount; i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append("(");
            if (multiToken) {
                sb.append("s.name ILIKE :q").append(i).append(" OR ");
            }
            sb.append("sdq.name ILIKE :q").append(i).append(")");
        }
        return sb.append(")) ").toString();
    }

    /**
     * The matched-names condition for the dynamic builder, in the same semantics
     * as {@link #STATIC_MATCHED_NAMES_LATERAL} — the service must satisfy the
     * group condition <em>and</em> contribute at least one token through its own
     * name. Emitted inside the caller's own lateral (which owns the
     * {@code FROM} / {@code ORDER BY} / {@code LIMIT}), correlated to the outer
     * derived table {@code t}.
     *
     * <h4>PRECONDITION — the caller MUST emit the bookable-master gate</h4>
     * Unlike {@link #STATIC_MATCHED_NAMES_LATERAL}, which embeds
     * {@code BOOKABLE_MASTER_GATE_MATCHED} itself, this fragment carries <b>no</b>
     * bookable gate: it emits only the token conditions, and relies on its caller
     * ({@code SearchService.appendSalonMatchedNamesLateral}, via
     * {@code appendSalonBookableGate(sb, defAlias, "t")}) to have already restricted
     * {@code defAlias} to services an ACTIVE master of THIS salon performs. Omitting
     * that call would list orphan salon services as the explanation of a match —
     * violating the locked rule that a salon's client-visible offering is
     * master-performed only, and re-opening exactly the static-vs-dynamic drift this
     * class exists to prevent (see the class Javadoc, defect E). The precondition
     * cannot be enforced here because the caller owns the surrounding
     * {@code FROM}/alias, so it is stated as a contract instead.
     *
     * @param defAlias   alias of the {@code service_definitions} row in the caller's
     *                   lateral, already gated to bookable services by the caller
     * @param tokenCount number of bound {@code :q0}…{@code :q{n-1}} slots, {@code >= 1}
     * @return the {@code AND …} conditions, or the empty string when {@code tokenCount == 0}
     */
    public static String dynamicMatchedNamesPredicate(String defAlias, int tokenCount) {
        if (tokenCount <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("AND (");
        for (int i = 0; i < tokenCount; i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append(defAlias).append(".name ILIKE :q").append(i);
        }
        sb.append(") ");
        for (int i = 0; i < tokenCount; i++) {
            sb.append("AND (t.name ILIKE :q").append(i)
                    .append(" OR ").append(defAlias).append(".name ILIKE :q").append(i).append(") ");
        }
        return sb.toString();
    }
}
