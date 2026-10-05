package com.beautica.search;

import com.beautica.DataJpaPostgresContainer;
import com.beautica.common.TimeZones;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.master.repository.MasterBookabilitySql;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonSearchSql;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wall-clock-INDEPENDENT guard on how the bookability SQL ({@link MasterBookabilitySql}, spliced into
 * {@link SalonSearchSql#STATIC_PROJECTION_HEAD}, the favourites queries, the dynamic search builders
 * and the search-membership verdict) resolves the word "today".
 *
 * <h2>History</h2>
 * <p>A bare {@code CURRENT_DATE} once resolved "today" against the JDBC session zone (UTC on Railway
 * and Neon), so for the three hours a night Kyiv runs a day ahead of UTC a master whose template
 * expired yesterday-Kyiv kept pricing the salon (PR #125; caught only by a 21:22Z CI run). The fix
 * spelled {@code (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Kyiv')::date}, and this class then pinned
 * that SQL expression's clock source and swept it. That got the ZONE right but still read the
 * DATABASE clock, while the strict free-slot verdict reads the app's {@code kyivClock}
 * (perf audit 2026-10-05, finding 7). "Today" is now BOUND from the app clock
 * ({@link ScheduleDateMath#today()}) as {@code :}{@value MasterBookabilitySql#TODAY_PARAM}, so the SQL
 * carries no clock at all and the property to guard moves:
 * <ol>
 *   <li>the bound value is the Kyiv civil date of the app clock at every quarter-hour of four
 *       reference days, whatever the JVM zone — swept with a FIXED {@link Clock} per instant;</li>
 *   <li>every date clause of the shipped SQL constant (extracted, never retyped) decides its
 *       boundary from the bound date alone — exact at {@code today}/{@code today + 180} and one day
 *       either side — under session zones on both sides of Kyiv (a raw, unpooled connection this
 *       class owns, so {@code SET TIME ZONE} never leaks into the pool);</li>
 *   <li>no SQL now-source survives in any bookability form or in any repository query that splices
 *       one, and every such repository method actually declares the parameter (Spring Data binds
 *       named parameters lazily, so a missing {@code @Param} would only fail on first call).</li>
 * </ol>
 * <p>{@code SalonSearchPriceBandIT} case 22 stays the end-to-end behavioural proof; with the date
 * bound from the app clock it no longer depends on the CI wall clock either.
 */
@DisplayName("Bookability 'today' — bound from the app Clock as the Kyiv civil date; no DB clock anywhere")
class SalonSearchTodayResolutionIT {

    /** Session zones on both sides of Kyiv (+14 and −12) plus the zone production runs. */
    private static final List<String> PROBE_ZONES = List.of("Pacific/Kiritimati", "Etc/GMT+12", "UTC");

    /** A summer day, a winter day and both 2026 DST transition days (Kyiv). */
    private static final List<LocalDate> REFERENCE_DAYS = List.of(
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25));

    private static final int SWEEP_STEP_MINUTES = 15;

    /** Every SQL spelling that reads a clock. Matched case-insensitively. */
    private static final Pattern DB_NOW_SOURCE = Pattern.compile(
            "current_date|current_timestamp|current_time|localtimestamp|localtime|\\bnow\\s*\\(|"
                    + "transaction_timestamp|statement_timestamp|clock_timestamp|timeofday",
            Pattern.CASE_INSENSITIVE);

    /** The repositories whose {@code @Query} bodies splice a bookability form. */
    private static final List<Class<?>> BOOKABILITY_REPOSITORIES =
            List.of(SalonRepository.class, FavoriteRepository.class, MasterRepository.class);

    // ── 1. the bound value ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ScheduleDateMath#today() on a fixed clock is the Kyiv civil date at all 96 quarter-hours "
            + "of four reference days (incl. both DST switches) — the value every query binds")
    void should_bindKyivCivilDate_atEveryPinnedInstant() {
        List<String> failures = new ArrayList<>();

        for (Instant instant : sweepInstants()) {
            LocalDate bound = new ScheduleDateMath(Clock.fixed(instant, ZoneOffset.UTC)).today();
            LocalDate kyiv = instant.atZone(TimeZones.KYIV).toLocalDate();
            if (!kyiv.equals(bound)) {
                failures.add(instant + ": bound " + bound + " but Kyiv is " + kyiv);
            }
        }

        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("non-vacuity: the sweep contains instants where the UTC civil date is NOT Kyiv's — the "
            + "exact condition under which a session-zone or DB-clock 'today' would have been wrong")
    void should_containInstantsWhereUtcDisagreesWithKyiv_inTheSweep() {
        long disagreeing = sweepInstants().stream()
                .filter(i -> !i.atZone(ZoneOffset.UTC).toLocalDate().equals(i.atZone(TimeZones.KYIV).toLocalDate()))
                .count();

        assertThat(disagreeing).isPositive();
    }

    // ── 2. the SQL anchors every horizon bound on the bound date ────────────────────────────────

    @Test
    @DisplayName("each production date clause (extracted from STATIC_PROJECTION_HEAD, never retyped) "
            + "decides its boundary from the BOUND date alone — exact at today/today+180 and one day "
            + "either side, under session zones on both sides of Kyiv")
    void should_anchorEveryClauseOnBoundDate_underEverySessionZone() throws SQLException {
        List<Clause> clauses = extractTodayClauses();
        List<LocalDate> boundDates = REFERENCE_DAYS;
        List<String> failures = new ArrayList<>();

        try (Connection conn = ownConnection(); Statement st = conn.createStatement()) {
            for (String zone : PROBE_ZONES) {
                st.execute("SET TIME ZONE '" + zone + "'");
                for (Clause clause : clauses) {
                    String sql = "SELECT (" + clause.sql()
                            .replace(clause.column(), "CAST(? AS date)")
                            .replace(":" + MasterBookabilitySql.TODAY_PARAM, "?") + ")";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        for (LocalDate bound : boundDates) {
                            for (int offset : new int[] {-1, 0, 1, 179, 180, 181}) {
                                LocalDate columnValue = bound.plusDays(offset);
                                ps.setObject(1, columnValue);
                                ps.setObject(2, bound);
                                try (ResultSet rs = ps.executeQuery()) {
                                    assertThat(rs.next()).isTrue();
                                    boolean actual = rs.getBoolean(1);
                                    boolean expected = clause.expected(columnValue, bound);
                                    if (actual != expected) {
                                        failures.add("zone %s, %s with column=%s today=%s → %s (expected %s)"
                                                .formatted(zone, clause.sql(), columnValue, bound, actual, expected));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("cardinality: the price-band lateral still carries exactly four bound-today clauses "
            + "(valid_from, valid_to, se.date >=, se.date <=) — a reshape cannot shrink the guard silently")
    void should_extractExactlyFourTodayOperands_fromTheProductionSql() {
        assertThat(extractTodayClauses())
                .extracting(Clause::column)
                .containsExactly("ws.valid_from", "ws.valid_to", "se.date", "se.date");
    }

    // ── 3. no clock in the SQL, and every splicing query declares the parameter ─────────────────

    @Test
    @DisplayName("no SQL now-source in any bookability form, nor in the static salon-search head")
    void should_containNoDbNowSource_inAnyBookabilityForm() {
        List<String> forms = List.of(
                MasterBookabilitySql.BOOKABLE_MASTER_M, MasterBookabilitySql.BOOKABLE_SALON_S,
                MasterBookabilitySql.HAS_SCHEDULE_MAD, MasterBookabilitySql.HAS_SCHEDULE_MMQ,
                MasterBookabilitySql.HAS_SCHEDULE_MM2, MasterBookabilitySql.hasSchedule("x"),
                MasterBookabilitySql.bookableMaster("x"), MasterBookabilitySql.bookableSalon("x"),
                SalonSearchSql.STATIC_PROJECTION_HEAD);

        assertThat(forms).allSatisfy(sql -> assertThat(DB_NOW_SOURCE.matcher(sql).find())
                .as("DB clock read in: %s", sql)
                .isFalse());
    }

    @Test
    @DisplayName("every repository @Query that splices a bookability form is clock-free and declares "
            + "@Param(\"" + MasterBookabilitySql.TODAY_PARAM + "\")")
    void should_declareTodayParam_when_repositoryQuerySplicesBookability() {
        List<Method> splicing = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (Class<?> repository : BOOKABILITY_REPOSITORIES) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || !MasterBookabilitySql.referencesToday(query.value() + query.countQuery())) {
                    continue;
                }
                splicing.add(method);
                if (!declaresTodayParam(method)) {
                    failures.add(repository.getSimpleName() + "#" + method.getName() + " binds no today");
                }
                if (DB_NOW_SOURCE.matcher(query.value() + query.countQuery()).find()) {
                    failures.add(repository.getSimpleName() + "#" + method.getName() + " reads the DB clock");
                }
            }
        }

        assertThat(splicing)
                .as("6 static salon searches + 4 favourites lists + the search-membership verdict")
                .hasSize(11);
        assertThat(failures).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static List<Instant> sweepInstants() {
        List<Instant> instants = new ArrayList<>();
        for (LocalDate day : REFERENCE_DAYS) {
            for (int minute = 0; minute < 24 * 60; minute += SWEEP_STEP_MINUTES) {
                instants.add(day.atTime(LocalTime.MIDNIGHT).plusMinutes(minute).toInstant(ZoneOffset.UTC));
            }
        }
        return instants;
    }

    /**
     * Every {@code <column> [- n] <op> :today} clause of the shipped head, verbatim — the column, the
     * column-side horizon shift and the operator are parsed back out so the expectation is computed
     * independently of the SQL.
     */
    private static List<Clause> extractTodayClauses() {
        Matcher m = Pattern.compile("(ws\\.valid_from|ws\\.valid_to|se\\.date)(?: - (\\d+))? (<=|>=) :"
                        + MasterBookabilitySql.TODAY_PARAM + "\\b")
                .matcher(SalonSearchSql.STATIC_PROJECTION_HEAD);
        List<Clause> clauses = new ArrayList<>();
        while (m.find()) {
            clauses.add(new Clause(m.group(), m.group(1),
                    m.group(2) == null ? 0 : Integer.parseInt(m.group(2)), m.group(3)));
        }
        return clauses;
    }

    private record Clause(String sql, String column, int shiftDays, String operator) {

        boolean expected(LocalDate columnValue, LocalDate today) {
            int cmp = columnValue.minusDays(shiftDays).compareTo(today);
            return "<=".equals(operator) ? cmp <= 0 : cmp >= 0;
        }
    }

    private static boolean declaresTodayParam(Method method) {
        return Arrays.stream(method.getParameters())
                .map(p -> p.getAnnotation(Param.class))
                .anyMatch(p -> p != null && MasterBookabilitySql.TODAY_PARAM.equals(p.value()));
    }

    /**
     * A connection this class owns outright — {@link DriverManager}, never the Hikari pool — so
     * {@code SET TIME ZONE} is closed with the try-with-resources and reaches nobody.
     */
    private static Connection ownConnection() throws SQLException {
        return DriverManager.getConnection(
                DataJpaPostgresContainer.INSTANCE.getJdbcUrl(),
                DataJpaPostgresContainer.INSTANCE.getUsername(),
                DataJpaPostgresContainer.INSTANCE.getPassword());
    }

}
