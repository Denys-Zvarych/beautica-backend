package com.beautica.search;

import com.beautica.DataJpaPostgresContainer;
import com.beautica.common.TimeZones;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.salon.repository.SalonSearchSql;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wall-clock-INDEPENDENT guard on how {@link SalonSearchSql#STATIC_PROJECTION_HEAD}'s price-band
 * lateral resolves the word "today".
 *
 * <h2>Why this class exists — the guard it backstops only fired for three hours a night</h2>
 * <p>The {@code pr} lateral gates which masters set a salon's publicly advertised price band on a
 * currently-valid schedule. Both of its arms must resolve "today" as the <em>Europe/Kyiv</em> civil
 * date, because the catalogue half of the same contract resolves it from
 * {@link ScheduleDateMath#today()} unconditionally. A bare {@code CURRENT_DATE} resolves against the
 * JDBC <em>session</em>'s {@code TimeZone}, which pgjdbc sets from the JVM default — UTC on Railway
 * and Neon — so a master whose weekly template expired yesterday-Kyiv kept pricing the salon for the
 * three hours each night that Kyiv runs a day ahead of UTC.</p>
 *
 * <p>That defect shipped and stayed green.
 * {@code SalonSearchPriceBandIT#should_excludeMasterWithExpiredScheduleFromTheSearchBand} (case 22)
 * is the behavioural regression test and it DID catch it — but only because the CI run that happened
 * to matter started at 21:22Z. Every earlier run that day, 09:42Z through 19:16Z, passed with the
 * broken SQL. <b>That was luck, and luck is not a regression net.</b> The lucky window is measured
 * rather than recalled, in {@link #should_showThatUtcAloneCoversOnlyAThinSliceOfTheDay()} — 180
 * minutes of a summer day and 120 of a winter one at the time of writing, though that method asserts
 * only the tzdb-independent property (it is a thin slice, and never the whole day) rather than those
 * two figures, which belong to the IANA database and not to Beautica.</p>
 *
 * <h2>Why case 22 cannot itself be made deterministic (a proof, not a preference)</h2>
 * <p>Case 22 reaches Postgres over the pooled Hikari connection, whose session zone is the JVM
 * default. Its fixture seeds {@code valid_to = D_kyiv - 1} and the predicate is
 * {@code valid_to >= <today>}. When the session zone's civil date {@code D_session} equals
 * {@code D_kyiv} — 21 of every 24 hours under {@code TZ=UTC}, and all 24 under
 * {@code TZ=Europe/Kyiv} — the broken predicate and the fixed predicate are <em>literally the same
 * comparison</em>, so NO choice of {@code valid_to} separates them. Discrimination through that path
 * requires {@code D_session != D_kyiv}, which is a statement about the wall clock. The only levers
 * that would force the disagreement — the JVM default zone, or a Hikari {@code connectionInitSql} —
 * are process-global and would change the session zone for every other test in the JVM; and pinning
 * CI to {@code TZ=Europe/Kyiv} is the one thing that must never happen, because under Kyiv the
 * broken SQL passes 13/13 and the defect is hidden rather than fixed (see
 * {@code STATIC_PROJECTION_HEAD}'s javadoc, which is load-bearing on exactly this point).</p>
 *
 * <h2>How the determinism is bought instead</h2>
 * <p>On a <b>raw, unpooled</b> connection this class owns outright — so it may set the session zone
 * freely without leaking it — and with the SQL's <b>clock source pinned</b>, which is the same
 * fixed-{@code Clock} pattern the schedule unit tests use, transposed from Java to SQL:</p>
 * <ol>
 *   <li>The four date expressions are <b>extracted from the production SQL constant</b>
 *       ({@link #TODAY_OPERANDS}) by anchoring on the column each is compared against, never
 *       retyped — so the string under test is the shipped one, and a {@code CURRENT_DATE} revert is
 *       extracted and executed rather than skipped.</li>
 *   <li>Every SQL now-source inside the extracted operand is rewritten to a <b>pinned instant</b>
 *       ({@link #NOW_SOURCE_REWRITES}), each rewrite being that function's own documented definition
 *       at that instant under the session zone. {@link #should_rewriteEveryKnownNowSource()} proves
 *       the rewrite is faithful by reproducing the live values, and the sweep refuses to run if any
 *       unrecognised now-source survives the rewrite.</li>
 *   <li>The operand is then swept over <b>every 15 minutes of four reference days × three session
 *       zones</b> and asserted equal to the Kyiv civil date of the pinned instant. No wall-clock
 *       input reaches the assertion at all, so the result is identical at 03:00Z and at 22:00Z.</li>
 * </ol>
 *
 * <p>{@link #should_haveASessionZoneDisagreeingWithKyiv_atEveryPinnedInstant()} is the non-vacuity
 * pin: it proves that at every instant in the sweep at least one probe zone's session date differs
 * from Kyiv's, which is precisely the condition under which the assertion above is a real
 * comparison rather than a date against itself. Together the two mean a {@code CURRENT_DATE} revert
 * is caught at every pinned instant, hence at whatever instant CI happens to run.</p>
 *
 * <h2>No Spring context</h2>
 * <p>This class deliberately extends nothing. Every probe runs over a raw, unpooled
 * {@link DriverManager} connection it opens itself — that is the whole point, so {@code SET TIME
 * ZONE} can never reach a pooled connection — so it needs the container's JDBC URL and nothing else.
 * It used to extend {@code AbstractDataJpaTest} and boot a full {@code @DataJpaTest} slice context
 * (EntityManagerFactory, every repository bean, a transaction manager) that no test here ever
 * touched (the per-class Testcontainers finding, backend-QA 2026-09-20). It now reads the same
 * JVM-wide singleton container straight from
 * {@link DataJpaPostgresContainer}, which {@code AbstractDataJpaTest} also delegates to — one
 * container, no context.</p>
 *
 * <p>Case 22 and case 23 are left exactly as they were: case 22 remains the end-to-end behavioural
 * proof through the real HTTP + JPA path, case 23 its non-vacuity pin, and this class is the
 * deterministic backstop. Neither substitutes for the other — a guard on the SQL string cannot show
 * that the band actually moves, and a guard on the band cannot fire at 10:00Z.</p>
 *
 * @see SalonSearchPriceBandIT
 */
@DisplayName("SalonSearchSql 'today' resolution — the price-band lateral must read Europe/Kyiv "
        + "whatever zone the JDBC session is in, at every hour of the day")
class SalonSearchTodayResolutionIT {

    /**
     * Session zones the extracted operands are evaluated under.
     *
     * <p><b>Two zones carry the coverage, deliberately these two.</b> Kyiv is UTC+3 in summer and
     * UTC+2 in winter. {@code Pacific/Kiritimati} is UTC+14 — the furthest real zone AHEAD of Kyiv
     * (+11/+12 h), so its civil date is Kyiv's tomorrow whenever Kyiv's local hour is late.
     * {@code Etc/GMT+12} is UTC<b>−</b>12: the POSIX sign is inverted, which is exactly why that
     * spelling is used and not {@code "UTC-12"}. It is the furthest real zone BEHIND Kyiv
     * (−14/−15 h), so its civil date is Kyiv's yesterday whenever Kyiv's local hour is early. The
     * two windows overlap, so their union is the whole day. No single zone achieves that: two zones
     * disagree on the civil date for at most (offset difference) hours, and no real zone sits 24 h
     * from Kyiv.
     *
     * <p>{@code UTC} is third as documentation rather than coverage — it is the zone Railway and
     * Neon actually run, i.e. the one the production defect occurred in. On its own it discriminates
     * for only a thin slice of the day (measured at 180 summer minutes / 120 winter minutes; see
     * {@link #should_showThatUtcAloneCoversOnlyAThinSliceOfTheDay()} for why the figures are
     * documented rather than asserted).
     */
    private static final List<String> PROBE_ZONES =
            List.of("Pacific/Kiritimati", "Etc/GMT+12", "UTC");

    /**
     * The SQL now-sources this class knows how to pin, each mapped to its own documented definition
     * at the pinned instant {@code %s}.
     *
     * <p>Every entry is <b>byte-identically</b> equal to the live form at the same transaction
     * timestamp, measured in {@link #should_rewriteEveryKnownNowSource()} rather than assumed.
     * {@code clock_timestamp()} and {@code statement_timestamp()} are deliberately NOT here — over a
     * JDBC connection that issues several statements in one implicit transaction, neither equals the
     * transaction timestamp (measured: 83 µs apart on the first run of this class), so no
     * transaction-timestamp rewrite reproduces them. They live on {@link #UNPINNABLE_NOW_SOURCES}
     * instead.
     *
     * <p>The zone-dependent forms deliberately go through {@code current_setting('TimeZone')}
     * rather than a literal zone, because reproducing their session-zone dependence is the entire
     * point: a rewrite that hardcoded Kyiv would make the {@code CURRENT_DATE} mutant pass.
     */
    private static final List<Map.Entry<String, String>> NOW_SOURCE_REWRITES = List.of(
            Map.entry("CURRENT_TIMESTAMP", "%s"),
            Map.entry("transaction_timestamp()", "%s"),
            Map.entry("now()", "%s"),
            Map.entry("LOCALTIMESTAMP", "(%s AT TIME ZONE current_setting('TimeZone'))"),
            Map.entry("CURRENT_DATE", "(%s AT TIME ZONE current_setting('TimeZone'))::date"));

    /**
     * Now-sources this class cannot faithfully pin. If the price-band gate ever grows one — or
     * spells a pinnable one in a case the rewrite does not match —
     * {@link #should_leaveNoLiveClockSource_inAnyRewrittenOperand()} goes RED and forces a
     * deliberate decision, instead of the sweep quietly reverting to a live clock and going back to
     * passing by luck.
     *
     * <p>{@code clock_timestamp} reads the wall clock per call rather than per transaction, and
     * {@code statement_timestamp} per statement, so no transaction-timestamp rewrite reproduces
     * either — this class's own fidelity test measured the latter 83 µs off on its first run, which
     * is exactly the drift that would let a pinned sweep quietly read a live clock.
     * {@code current_time} returns
     * {@code time with time zone}, which no {@code timestamp}-shaped rewrite reproduces, and has no
     * business in a date predicate anyway; it is matched only AFTER {@code CURRENT_TIMESTAMP} has
     * been rewritten away, so the prefix overlap between the two is not a false positive.
     */
    private static final List<String> UNPINNABLE_NOW_SOURCES =
            List.of("clock_timestamp", "statement_timestamp", "current_time");

    /**
     * The four "today" operands of the price-band lateral's schedule gate, pulled out of the
     * production constant by anchoring on the column each one is compared against.
     *
     * <p>Anchoring on the columns rather than on the expression text is what makes this a guard
     * instead of a tautology: a revert to {@code CURRENT_DATE} still matches these patterns and is
     * then handed to Postgres and caught. If the SQL is reshaped so an anchor stops matching,
     * {@link #should_extractExactlyFourTodayOperands_fromTheProductionSql()} goes RED rather than
     * this class silently testing nothing.
     */
    private static final List<Pattern> OPERAND_ANCHORS = List.of(
            Pattern.compile("ws\\.valid_from\\s*<=\\s*(.+?)\\s*\\+\\s*180"),
            Pattern.compile("ws\\.valid_to\\s*>=\\s*(.+?)\\)\\)\\s*$", Pattern.MULTILINE),
            Pattern.compile("se\\.date\\s*>=\\s*(.+?)\\s*$", Pattern.MULTILINE),
            Pattern.compile("se\\.date\\s*<=\\s*(.+?)\\s*\\+\\s*180"));

    private static final List<String> TODAY_OPERANDS = extractTodayOperands();

    /** Reference days: both Kyiv DST seasons plus both 2026 transition days. */
    private static final List<LocalDate> REFERENCE_DAYS = List.of(
            LocalDate.of(2026, 1, 15),   // winter, Kyiv = UTC+2
            LocalDate.of(2026, 3, 29),   // spring-forward Sunday
            LocalDate.of(2026, 7, 15),   // summer, Kyiv = UTC+3
            LocalDate.of(2026, 10, 25)); // fall-back Sunday

    /** Sweep granularity. 15 minutes = 96 pinned instants per reference day. */
    private static final String SWEEP_STEP = "15 minutes";

    private static List<String> extractTodayOperands() {
        List<String> operands = new ArrayList<>();
        for (Pattern anchor : OPERAND_ANCHORS) {
            Matcher m = anchor.matcher(SalonSearchSql.STATIC_PROJECTION_HEAD);
            if (m.find()) {
                operands.add(m.group(1).trim());
            }
        }
        return List.copyOf(operands);
    }

    // ── the deterministic guard ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every extracted operand resolves to the PINNED instant's Kyiv civil date, at all "
            + "96 quarter-hours of four reference days under three session zones — no wall-clock "
            + "input, so a CURRENT_DATE revert is RED at 03:00Z exactly as at 22:00Z")
    void should_resolveTodayInKyiv_atEveryPinnedInstantAndSessionZone() throws SQLException {
        assertThat(TODAY_OPERANDS)
                .as("nothing extracted means nothing guarded — see the cardinality test")
                .hasSize(OPERAND_ANCHORS.size());

        List<String> failures = new ArrayList<>();

        try (Connection conn = ownConnection(); Statement st = conn.createStatement()) {
            for (String zone : PROBE_ZONES) {
                st.execute("SET TIME ZONE '" + zone + "'");

                for (int i = 0; i < TODAY_OPERANDS.size(); i++) {
                    String operand = TODAY_OPERANDS.get(i);
                    String pinned = pinClockSource(operand, "t");

                    for (LocalDate day : REFERENCE_DAYS) {
                        String sql = "SELECT t,"
                                + " (" + pinned + ") AS resolved,"
                                + " (t AT TIME ZONE 'Europe/Kyiv')::date AS kyiv"
                                + " FROM " + sweepSeries(day) + " AS t";
                        try (ResultSet rs = st.executeQuery(sql)) {
                            while (rs.next()) {
                                LocalDate resolved = rs.getObject("resolved", LocalDate.class);
                                LocalDate kyiv = rs.getObject("kyiv", LocalDate.class);
                                if (!kyiv.equals(resolved)) {
                                    failures.add("operand %d, session zone %s, instant %s: "
                                            .formatted(i + 1, zone, rs.getString("t"))
                                            + "resolved %s but Kyiv is %s".formatted(resolved, kyiv));
                                }
                            }
                        }
                    }
                }
            }
        }

        assertThat(failures)
                .as("each entry is an instant at which the price-band gate would resolve a "
                        + "different 'today' than ScheduleDateMath#today() — search advertising a "
                        + "floor the catalogue will not honour. Operands under test: %s",
                        TODAY_OPERANDS)
                .isEmpty();
    }

    @Test
    @DisplayName("non-vacuity of that sweep: at EVERY pinned instant at least one probe zone's "
            + "session date really does differ from Kyiv's — so the sweep is comparing two "
            + "different things, and the probe set can never go blind at some hour")
    void should_haveASessionZoneDisagreeingWithKyiv_atEveryPinnedInstant() throws SQLException {
        List<String> blindInstants = new ArrayList<>();

        try (Connection conn = ownConnection(); Statement st = conn.createStatement()) {
            for (LocalDate day : REFERENCE_DAYS) {
                String anyDisagrees = PROBE_ZONES.stream()
                        .map(z -> "(t AT TIME ZONE '" + z + "')::date "
                                + "<> (t AT TIME ZONE 'Europe/Kyiv')::date")
                        .reduce((a, b) -> a + " OR " + b)
                        .orElseThrow();
                try (ResultSet rs = st.executeQuery(
                        "SELECT t FROM " + sweepSeries(day) + " AS t WHERE NOT (" + anyDisagrees + ")")) {
                    while (rs.next()) {
                        blindInstants.add(rs.getString("t"));
                    }
                }
            }
        }

        assertThat(blindInstants)
                .as("every listed instant is a wall-clock time at which a CURRENT_DATE revert would "
                        + "slip past the sweep above; the probe set must leave none. If this is "
                        + "non-empty, add a zone further from Kyiv rather than deleting the test.")
                .isEmpty();
    }

    @Test
    @DisplayName("the pinned-clock rewrite is faithful: every now-source in the map, rewritten onto "
            + "the CURRENT_TIMESTAMP of the same transaction, reproduces its own live value "
            + "byte-for-byte — so pinning the clock cannot be what makes the sweep pass")
    void should_rewriteEveryKnownNowSource() throws SQLException {
        try (Connection conn = ownConnection(); Statement st = conn.createStatement()) {
            // A zone whose civil date differs from both UTC and Kyiv for most of the day, so a
            // rewrite that quietly lost its session-zone dependence shows up here.
            st.execute("SET TIME ZONE 'Etc/GMT+12'");

            for (Map.Entry<String, String> rewrite : NOW_SOURCE_REWRITES) {
                String live = rewrite.getKey();
                String pinned = rewrite.getValue().formatted("CURRENT_TIMESTAMP");
                try (ResultSet rs = st.executeQuery(
                        "SELECT (" + live + ")::text AS live, (" + pinned + ")::text AS pinned")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("pinned"))
                            .as("the rewrite of %s must mean exactly what %s means; a wrong rewrite "
                                    + "would silently defang the sweep", live, live)
                            .isEqualTo(rs.getString("live"));
                }
            }
        }
    }

    @Test
    @DisplayName("the rewrite leaves no live clock in any extracted operand — a now-source the map "
            + "does not cover, or one spelled in an unexpected case, must fail loudly here instead "
            + "of making the sweep wall-clock-dependent again")
    void should_leaveNoLiveClockSource_inAnyRewrittenOperand() {
        List<String> forbidden = new ArrayList<>(UNPINNABLE_NOW_SOURCES);
        NOW_SOURCE_REWRITES.forEach(r -> forbidden.add(r.getKey().toLowerCase()));

        for (String operand : TODAY_OPERANDS) {
            String pinned = pinClockSource(operand, "timestamptz '2026-07-15 12:00:00+00'");

            assertThat(pinned.toLowerCase())
                    .as("operand <%s> rewrote to <%s>, which still reads a live clock; add the "
                            + "missing now-source to NOW_SOURCE_REWRITES or decide it is "
                            + "unpinnable", operand, pinned)
                    .doesNotContain(forbidden.toArray(new String[0]));
            assertThat(pinned)
                    .as("operand <%s> reads no clock at all, so the sweep would compare a constant "
                            + "to Kyiv's date; the gate must resolve 'today' from the clock",
                            operand)
                    .isNotEqualTo(operand);
        }
    }

    // ── live-clock end-to-end check, and the measurements that explain the gap ────────────────

    @Test
    @DisplayName("live clock, unpinned: the operands still resolve to Kyiv right now, under a "
            + "session zone a day behind Kyiv — the production shape the pinned sweep abstracts")
    void should_resolveTodayInKyiv_onTheLiveClockUnderAForeignSessionZone() throws SQLException {
        try (Connection conn = ownConnection(); Statement st = conn.createStatement()) {
            for (String zone : PROBE_ZONES) {
                st.execute("SET TIME ZONE '" + zone + "'");
                for (String operand : TODAY_OPERANDS) {
                    // One statement, so operand and oracle share a transaction timestamp: no
                    // midnight-straddle race between two round trips.
                    try (ResultSet rs = st.executeQuery("SELECT (" + operand + ") AS resolved,"
                            + " (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Kyiv')::date AS kyiv,"
                            + " CURRENT_DATE AS session_date")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getObject("resolved", LocalDate.class))
                                .as("live clock, session zone %s (session date %s): operand <%s> "
                                        + "must resolve the Kyiv civil date %s", zone,
                                        rs.getObject("session_date", LocalDate.class), operand,
                                        rs.getObject("kyiv", LocalDate.class))
                                .isEqualTo(rs.getObject("kyiv", LocalDate.class));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the SQL-side Kyiv resolution agrees with ScheduleDateMath#today() — the "
            + "catalogue's own definition of today, which is the contract search has to mirror")
    void should_agreeWithScheduleDateMath_onWhatTodayIs() throws SQLException {
        ScheduleDateMath dateMath = new ScheduleDateMath(Clock.systemUTC());

        // The two reads bracket the DB read, so a genuine Kyiv-midnight crossing mid-test yields
        // two different dates and the DB value matches one of them: exact, but not a daily flake.
        LocalDate before = dateMath.today();
        LocalDate fromDb;
        try (Connection conn = ownConnection(); Statement st = conn.createStatement()) {
            st.execute("SET TIME ZONE 'Etc/GMT+12'"); // deliberately NOT the JVM zone
            try (ResultSet rs = st.executeQuery(
                    "SELECT (" + TODAY_OPERANDS.getFirst() + ") AS resolved")) {
                assertThat(rs.next()).isTrue();
                fromDb = rs.getObject("resolved", LocalDate.class);
            }
        }
        LocalDate after = dateMath.today();

        assertThat(fromDb)
                .as("search's SQL 'today' must be the catalogue's 'today'; the bracketing "
                        + "ScheduleDateMath reads were %s and %s", before, after)
                .isIn(before, after);
    }

    /**
     * Why case 22 alone was never enough, asserted as the PROPERTY rather than the figures.
     *
     * <p>Under {@code TZ=UTC} — the zone Railway and Neon actually run, and the one the production
     * defect occurred in — the UTC and Kyiv civil dates differ only while Kyiv's local clock has
     * already rolled over and UTC's has not. That window is exactly Kyiv's UTC offset: measured on
     * the JDK's tzdb at the time of writing, 180 minutes on a summer day (Kyiv UTC+3, 21:00-24:00Z)
     * and 120 on a winter one (Kyiv UTC+2, 22:00-24:00Z). Case 22 can only discriminate inside that
     * window, so on a CI run started at any other hour it passes whether the SQL reads Kyiv or the
     * session zone.
     *
     * <p><b>The exact minute counts are documentation, not assertions</b> (the tzdb-pinned
     * minute-count finding, backend-QA 2026-09-20). Asserting {@code 180} and {@code 120} literally would pin the IANA tz database's
     * Ukrainian DST rules, which are not Beautica code: if Ukraine ever abolishes seasonal clock
     * changes — a bill that has been before the Rada more than once — or if IANA restates the zone,
     * this test goes RED for a change no Beautica commit made and with no Beautica regression behind
     * it. What actually justifies this whole class is the weaker, tzdb-independent property below:
     * UTC's discriminating window is a SMALL FRACTION of the day and is never the whole of it, in
     * both halves of the year. That is what makes a single-zone, wall-clock-dependent guard
     * insufficient, and it stays true under any plausible tzdb revision — it would only break if
     * Kyiv moved to UTC+0 (window collapses to zero, and the whole timezone concern with it) or past
     * UTC+12.
     */
    @Test
    @DisplayName("and the property that says why case 22 alone was never enough: under TZ=UTC the "
            + "UTC and Kyiv civil dates differ for only a small fraction of the day — in both "
            + "summer and winter — so a UTC-only guard passes at almost every hour CI might start")
    void should_showThatUtcAloneCoversOnlyAThinSliceOfTheDay() {
        int minutesPerDay = 24 * 60;

        for (LocalDate reference : List.of(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 1, 15))) {
            int disagreeing = minutesWhereZoneDisagreesWithKyiv(ZoneOffset.UTC, reference);

            assertThat(disagreeing)
                    .as("%s: UTC must discriminate for SOME part of the day, or case 22 could "
                            + "never have caught the defect at all and this class would be "
                            + "guarding nothing", reference)
                    .isPositive();
            assertThat(disagreeing)
                    .as("%s: UTC discriminated for %d of %d minutes. The whole reason the pinned "
                            + "sweep exists is that this is a THIN slice — a guard confined to it "
                            + "passes at whatever other hour CI happens to start. Measured at the "
                            + "time of writing: 180 minutes in summer (Kyiv UTC+3), 120 in winter "
                            + "(Kyiv UTC+2); those figures are documentation, not a contract — see "
                            + "this method's javadoc.", reference, disagreeing, minutesPerDay)
                    .isLessThan(minutesPerDay / 4);
        }
    }

    // ── ledgers on the extraction itself ─────────────────────────────────────────────────────

    @Test
    @DisplayName("cardinality: all four anchors still match the production SQL — a reshape that "
            + "silently drops one must fail loudly here instead of shrinking the guard")
    void should_extractExactlyFourTodayOperands_fromTheProductionSql() {
        assertThat(TODAY_OPERANDS)
                .as("anchors are ws.valid_from, ws.valid_to, se.date >=, se.date <= in the pr "
                        + "lateral's schedule gate; if the SQL moved, re-anchor them")
                .hasSize(4);
    }

    @Test
    @DisplayName("belt and braces: the price-band SQL contains no bare CURRENT_DATE anywhere, in "
            + "ANY case, so a FIFTH session-zone-dependent date added outside the four anchors is "
            + "caught too")
    void should_containNoBareCurrentDate_anywhereInThePriceBandSql() {
        assertThat(SalonSearchSql.STATIC_PROJECTION_HEAD)
                .as("CURRENT_DATE resolves against the pgjdbc session zone (the JVM default); this "
                        + "SQL must resolve every civil date from Europe/Kyiv explicitly. The "
                        + "check is case-INSENSITIVE (the case-sensitive CURRENT_DATE ledger "
                        + "finding, backend-QA 2026-09-20): SQL keywords are "
                        + "case-insensitive to Postgres, this file already mixes cases freely, and "
                        + "a case-sensitive ledger let a lowercase `current_date` added outside the "
                        + "four anchors through — inside an anchored operand the pinned sweep still "
                        + "catches it, outside one nothing did.")
                .doesNotContainIgnoringCase("CURRENT_DATE");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Rewrites every known SQL now-source in {@code operand} to read {@code pinnedInstant}. */
    private static String pinClockSource(String operand, String pinnedInstant) {
        String rewritten = operand;
        for (Map.Entry<String, String> rewrite : NOW_SOURCE_REWRITES) {
            rewritten = rewritten.replace(rewrite.getKey(),
                    rewrite.getValue().formatted(pinnedInstant));
        }
        return rewritten;
    }

    private static String sweepSeries(LocalDate day) {
        return "generate_series(timestamptz '%s 00:00:00+00', timestamptz '%s 23:59:00+00', "
                .formatted(day, day) + "interval '" + SWEEP_STEP + "')";
    }

    private static int minutesWhereZoneDisagreesWithKyiv(ZoneId zone, LocalDate day) {
        int count = 0;
        for (int minute = 0; minute < 24 * 60; minute++) {
            Instant instant = day.atTime(LocalTime.MIDNIGHT).plusMinutes(minute)
                    .toInstant(ZoneOffset.UTC);
            if (!instant.atZone(zone).toLocalDate()
                    .equals(instant.atZone(TimeZones.KYIV).toLocalDate())) {
                count++;
            }
        }
        return count;
    }

    /**
     * A connection this class owns outright — {@link DriverManager}, never the Hikari pool.
     *
     * <p>{@code SET TIME ZONE} mutates session state for the life of the connection. Issued on a
     * pooled connection it would be handed to whichever test borrows it next, so the determinism
     * this class buys would be paid for by every other class in the JVM. Unpooled, it is closed with
     * the try-with-resources and reaches nobody.
     */
    private static Connection ownConnection() throws SQLException {
        return DriverManager.getConnection(
                DataJpaPostgresContainer.INSTANCE.getJdbcUrl(),
                DataJpaPostgresContainer.INSTANCE.getUsername(),
                DataJpaPostgresContainer.INSTANCE.getPassword());
    }
}
