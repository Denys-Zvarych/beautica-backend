package com.beautica.master.repository;

import java.util.regex.Pattern;

/**
 * The ONE SQL definition of "a bookable master" for client discovery surfaces (search, search
 * suggestions, favourites) — locked product decision 2026-10-05: a master is bookable when they
 * offer at least one active service AND have a schedule; a salon is bookable when at least one of
 * its active masters is. This is the cheap STRUCTURAL rule: it never walks the free-slot calendar
 * (a master who is merely fully booked right now stays visible). Also gates the public salon roster
 * ({@code MasterRepository#findBookableIdsBySalonId}). The strict free-slot verdict is
 * {@code BookingMasterService#getBookableAssignmentIds} / the salon catalogue's batch gate.
 *
 * <h3>What counts as a schedule</h3>
 * Override beats template beats gap ({@code MasterScheduleService#foldDates}), so both sources are
 * represented, each over the booking horizon {@code [today, today + MAX_DAYS_AHEAD]}:
 * <ul>
 *   <li><b>Template arm</b> — a {@code weekly_schedules} row whose validity range overlaps the
 *       horizon AND that carries at least one working hour: a {@code working_intervals} row
 *       (INTERVAL weekday) or a {@code working_interval_times} row (EXPLICIT_TIMES weekday, V84).
 *       An empty template ({@code {"days":[]}} is reachable over the API) is NOT a schedule.</li>
 *   <li><b>Override arm</b> — a future {@code CUSTOM_HOURS} {@code schedule_exceptions} row inside
 *       the horizon with at least one {@code schedule_exception_intervals} /
 *       {@code schedule_exception_times} row. Matched POSITIVELY on {@code CUSTOM_HOURS} (never
 *       {@code <> 'DAY_OFF'}), so a future non-working kind is inert here.</li>
 * </ul>
 * There is no soft-delete on any of these tables (deletion is a real {@code DELETE}), and
 * cross-midnight intervals cannot exist: {@code chk_interval_order} / {@code chk_exc_interval_order}
 * pin {@code end_time > start_time} at the DB level.
 *
 * <p>"Today" is the Europe/Kyiv civil date, BOUND as the {@value #TODAY_PARAM} parameter from the
 * application clock ({@code ScheduleDateMath#today()}) — never read from the database clock. Bare
 * {@code CURRENT_DATE} resolves against the JDBC session zone (UTC on Railway/Neon), and even the
 * Kyiv-spelled {@code CURRENT_TIMESTAMP} form reads the DB clock while the strict slot verdict reads
 * the app's {@code kyivClock}: binding the date makes both halves of the bookability contract agree
 * on what "today" is, and lets a fixed {@code Clock} pin it in tests. Every query that splices a
 * form below must bind {@link #TODAY_PARAM}; dynamic callers use {@link #referencesToday} to decide.
 * Guarded by {@code SalonSearchTodayResolutionIT}.
 *
 * <h3>What counts as offering a service</h3>
 * An active {@code master_services} row on an active {@code service_definitions} row the master's
 * CURRENT context owns: a salon-attached master's definition must be SALON-owned by that same salon,
 * a salon-less (independent) master's must be INDEPENDENT_MASTER-owned by that master. A stale
 * cross-owner assignment (a master who moved between solo practice and a salon) never counts —
 * the same ownership rule {@code MasterServiceRepository#findBookableAssignmentsByMasterIds} applies
 * to the strict verdict.
 *
 * <h3>Forms</h3>
 * {@code @Query} values must be compile-time constants, so each form is assembled from constant
 * chunks around an alias and exposed per alias the static queries use; dynamic builders call the
 * {@code String}-returning methods. Every form below is built from the same chunks, so they cannot
 * drift. Internal aliases ({@code ws}, {@code wi}, {@code wit}, {@code se}, {@code sei}, {@code sxt},
 * {@code bms}, {@code bsd}, {@code bkm}) are subquery-scoped; the correlated master alias must not be
 * one of them.
 */
public final class MasterBookabilitySql {

    private MasterBookabilitySql() {
        // static SQL-fragment holder
    }

    /**
     * Name of the bound Europe/Kyiv civil date every form below reads — see the class Javadoc for why
     * it is bound from the app clock and never read from the database clock.
     */
    public static final String TODAY_PARAM = "bookableToday";

    /**
     * The bound "today" operand. Deliberately a BARE parameter compared directly against a date
     * column, never {@code CAST(:p AS date)} nor {@code :p + n}: a bare comparison lets Postgres type
     * the parameter from the column whatever the binder sends (Hibernate binds a {@code LocalDate} as
     * an untyped JDBC date), and keeps the fragments free of the {@code CAST(:p …)} idiom the dynamic
     * builders forbid. The horizon arithmetic therefore sits on the COLUMN side
     * ({@code col - 180 <= today} ⇔ {@code col <= today + 180}).
     */
    private static final String KYIV_TODAY = ":" + TODAY_PARAM;

    /**
     * Aliases the dynamic builders accept: a lower-case SQL identifier of at most 16 characters. The
     * alias is spliced into SQL text, so anything else — quotes, spaces, operators, comments — is a
     * programming error, rejected loudly rather than emitted.
     */
    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

    /** Horizon in days — {@code BookingWindow.MAX_DAYS_AHEAD}, spelled as a literal so every form
     *  below stays a compile-time constant usable in a {@code @Query}. Pinned equal by
     *  {@code MasterBookabilitySqlTest}. */
    static final int HORIZON_DAYS = 180;

    // Plain strings, not text blocks: a text block strips the trailing space before its closing
    // delimiter, and every chunk here is spliced mid-line. Each public form starts with a space so
    // it can follow a text block that ends in "AND" (whose trailing space was likewise stripped).
    private static final String SCHEDULE_HEAD = " (EXISTS (SELECT 1 FROM weekly_schedules ws\n"
            + "                      WHERE ws.master_id = ";
    private static final String SCHEDULE_MID = ".id\n"
            + "                        AND ws.valid_from - " + HORIZON_DAYS + " <= " + KYIV_TODAY + "\n"
            + "                        AND (ws.valid_to IS NULL OR ws.valid_to >= " + KYIV_TODAY + ")\n"
            + "                        AND (EXISTS (SELECT 1 FROM working_intervals wi WHERE wi.schedule_id = ws.id)\n"
            + "                             OR EXISTS (SELECT 1 FROM working_interval_times wit WHERE wit.schedule_id = ws.id)))\n"
            + "             OR EXISTS (SELECT 1 FROM schedule_exceptions se\n"
            + "                         WHERE se.master_id = ";
    private static final String SCHEDULE_TAIL = ".id\n"
            + "                           AND se.date >= " + KYIV_TODAY + "\n"
            + "                           AND se.date - " + HORIZON_DAYS + " <= " + KYIV_TODAY + "\n"
            + "                           AND se.kind = 'CUSTOM_HOURS'\n"
            + "                           AND (EXISTS (SELECT 1 FROM schedule_exception_intervals sei WHERE sei.exception_id = se.id)\n"
            + "                                OR EXISTS (SELECT 1 FROM schedule_exception_times sxt WHERE sxt.exception_id = se.id))))\n";

    private static final String OFFERS_HEAD = " EXISTS (SELECT 1 FROM master_services bms\n"
            + "                     JOIN service_definitions bsd ON bsd.id = bms.service_def_id AND bsd.is_active = true\n"
            + "                     WHERE bms.is_active = true\n"
            + "                       AND bms.master_id = ";
    private static final String OFFERS_OWNER_SALON = ".id\n"
            + "                       AND ((";
    private static final String OFFERS_OWNER_SALON_TAIL = ".salon_id IS NOT NULL AND bsd.owner_type = 'SALON' AND bsd.owner_id = ";
    private static final String OFFERS_OWNER_INDEPENDENT = ".salon_id)\n"
            + "                         OR (";
    private static final String OFFERS_OWNER_INDEPENDENT_TAIL =
            ".salon_id IS NULL AND bsd.owner_type = 'INDEPENDENT_MASTER' AND bsd.owner_id = ";
    private static final String OFFERS_TAIL = ".id)))\n";

    private static final String SALON_HEAD = " EXISTS (SELECT 1 FROM masters bkm\n"
            + "                     WHERE bkm.salon_id = ";
    private static final String SALON_TAIL = ".id\n"
            + "                       AND bkm.is_active = true\n"
            + "                       AND "
            + OFFERS_HEAD + "bkm" + OFFERS_OWNER_SALON + "bkm" + OFFERS_OWNER_SALON_TAIL + "bkm"
            + OFFERS_OWNER_INDEPENDENT + "bkm" + OFFERS_OWNER_INDEPENDENT_TAIL + "bkm" + OFFERS_TAIL
            + "  AND " + SCHEDULE_HEAD + "bkm" + SCHEDULE_MID + "bkm" + SCHEDULE_TAIL
            + "            )\n";

    // ── compile-time constant forms (for @Query bodies) ─────────────────────────────────────

    /** Schedule predicate for the search price lateral's master alias {@code mad}. */
    public static final String HAS_SCHEDULE_MAD = SCHEDULE_HEAD + "mad" + SCHEDULE_MID + "mad" + SCHEDULE_TAIL;

    /** Schedule predicate for the salon-search bookable-service gate's master alias {@code mmq}. */
    public static final String HAS_SCHEDULE_MMQ = SCHEDULE_HEAD + "mmq" + SCHEDULE_MID + "mmq" + SCHEDULE_TAIL;

    /** Schedule predicate for the salon-search name-preview lateral's master alias {@code mm2}. */
    public static final String HAS_SCHEDULE_MM2 = SCHEDULE_HEAD + "mm2" + SCHEDULE_MID + "mm2" + SCHEDULE_TAIL;

    /** Bookable-master predicate (offers + schedule) for a master aliased {@code m}. */
    public static final String BOOKABLE_MASTER_M =
            OFFERS_HEAD + "m" + OFFERS_OWNER_SALON + "m" + OFFERS_OWNER_SALON_TAIL + "m"
                    + OFFERS_OWNER_INDEPENDENT + "m" + OFFERS_OWNER_INDEPENDENT_TAIL + "m" + OFFERS_TAIL
                    + "  AND " + SCHEDULE_HEAD + "m" + SCHEDULE_MID + "m" + SCHEDULE_TAIL;

    /** Bookable-salon predicate (at least one active bookable master) for a salon aliased {@code s}. */
    public static final String BOOKABLE_SALON_S = SALON_HEAD + "s" + SALON_TAIL;

    // ── dynamic forms (for StringBuilder query builders) ────────────────────────────────────

    /**
     * The schedule predicate (no leading {@code AND}) correlated to the master aliased
     * {@code masterAlias}.
     */
    public static String hasSchedule(String masterAlias) {
        requireAlias(masterAlias);
        return SCHEDULE_HEAD + masterAlias + SCHEDULE_MID + masterAlias + SCHEDULE_TAIL;
    }

    /**
     * The full bookable-master predicate — offers ≥1 active, correctly-owned service AND has a
     * schedule — (no leading {@code AND}) correlated to the master aliased {@code masterAlias}.
     * Does NOT test {@code is_active}; every caller already filters active masters.
     */
    public static String bookableMaster(String masterAlias) {
        requireAlias(masterAlias);
        return OFFERS_HEAD + masterAlias + OFFERS_OWNER_SALON + masterAlias + OFFERS_OWNER_SALON_TAIL
                + masterAlias + OFFERS_OWNER_INDEPENDENT + masterAlias + OFFERS_OWNER_INDEPENDENT_TAIL
                + masterAlias + OFFERS_TAIL
                + "  AND " + hasSchedule(masterAlias);
    }

    /**
     * The bookable-salon predicate — at least one ACTIVE master of the salon satisfies
     * {@link #bookableMaster} — (no leading {@code AND}) correlated to the salon aliased
     * {@code salonAlias}.
     */
    public static String bookableSalon(String salonAlias) {
        requireAlias(salonAlias);
        return SALON_HEAD + salonAlias + SALON_TAIL;
    }

    /**
     * True when {@code sql} splices any form above and therefore needs {@link #TODAY_PARAM} bound —
     * for dynamic builders whose fragments are optional. Binding a parameter the query does not
     * declare is an error in Hibernate, so callers bind it only when this holds.
     */
    public static boolean referencesToday(String sql) {
        return sql.contains(":" + TODAY_PARAM);
    }

    private static void requireAlias(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Illegal SQL alias for a bookability fragment");
        }
    }
}
