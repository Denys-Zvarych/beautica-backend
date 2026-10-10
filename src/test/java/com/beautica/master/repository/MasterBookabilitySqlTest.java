package com.beautica.master.repository;

import com.beautica.common.BookingWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MasterBookabilitySql — the one shared bookable-master/salon SQL rule")
class MasterBookabilitySqlTest {

    @Test
    @DisplayName("the schedule horizon equals BookingWindow.MAX_DAYS_AHEAD (the literal cannot drift)")
    void should_matchBookingWindowHorizon_when_comparedToBookingWindow() {
        int horizon = MasterBookabilitySql.HORIZON_DAYS;

        assertThat(horizon).isEqualTo(BookingWindow.MAX_DAYS_AHEAD);
    }

    @Test
    @DisplayName("the @Query constants and the builder methods are the SAME text per alias (no fork)")
    void should_produceIdenticalText_when_constantAndMethodShareAnAlias() {
        assertThat(MasterBookabilitySql.BOOKABLE_MASTER_M).isEqualTo(MasterBookabilitySql.bookableMaster("m"));
        assertThat(MasterBookabilitySql.BOOKABLE_SALON_S).isEqualTo(MasterBookabilitySql.bookableSalon("s"));
        assertThat(MasterBookabilitySql.HAS_SCHEDULE_MAD).isEqualTo(MasterBookabilitySql.hasSchedule("mad"));
        assertThat(MasterBookabilitySql.HAS_SCHEDULE_MMQ).isEqualTo(MasterBookabilitySql.hasSchedule("mmq"));
        assertThat(MasterBookabilitySql.HAS_SCHEDULE_MM2).isEqualTo(MasterBookabilitySql.hasSchedule("mm2"));
    }

    @Test
    @DisplayName("a schedule needs working HOURS (intervals or discrete times) on BOTH arms, CUSTOM_HOURS "
            + "matched positively, dated by the BOUND app-clock today — no DB now-source at all")
    void should_requireWorkingHoursAndKyivDate_when_scheduleFragmentRendered() {
        String sql = MasterBookabilitySql.hasSchedule("x");

        assertThat(sql)
                .contains("FROM working_intervals wi WHERE wi.schedule_id = ws.id")
                .contains("FROM working_interval_times wit WHERE wit.schedule_id = ws.id")
                .contains("FROM schedule_exception_intervals sei WHERE sei.exception_id = se.id")
                .contains("FROM schedule_exception_times sxt WHERE sxt.exception_id = se.id")
                .contains("se.kind = 'CUSTOM_HOURS'")
                .doesNotContain("<> 'DAY_OFF'")
                .contains("ws.master_id = x.id")
                .contains("se.master_id = x.id")
                .contains("ws.valid_from - 180 <= :" + MasterBookabilitySql.TODAY_PARAM)
                .contains("ws.valid_to >= :" + MasterBookabilitySql.TODAY_PARAM)
                .contains("se.date >= :" + MasterBookabilitySql.TODAY_PARAM)
                .contains("se.date - 180 <= :" + MasterBookabilitySql.TODAY_PARAM)
                .doesNotContain("CAST(:")
                .doesNotContainIgnoringCase("CURRENT_DATE")
                .doesNotContainIgnoringCase("CURRENT_TIMESTAMP")
                .doesNotContainIgnoringCase("LOCALTIMESTAMP")
                .doesNotContainIgnoringCase("now()");
    }

    @Test
    @DisplayName("offering a service follows the master's CURRENT context: salon-owned by its own salon, "
            + "or self-owned when salon-less")
    void should_bindOwnershipToCurrentContext_when_bookableMasterRendered() {
        String sql = MasterBookabilitySql.bookableMaster("m");

        assertThat(sql)
                .contains("bms.master_id = m.id")
                .contains("bms.is_active = true")
                .contains("bsd.is_active = true")
                .contains("m.salon_id IS NOT NULL AND bsd.owner_type = 'SALON' AND bsd.owner_id = m.salon_id")
                .contains("m.salon_id IS NULL AND bsd.owner_type = 'INDEPENDENT_MASTER' AND bsd.owner_id = m.id");
    }

    @Test
    @DisplayName("a bookable salon = an ACTIVE master of that salon satisfying the master rule")
    void should_composeMasterRule_when_bookableSalonRendered() {
        String sql = MasterBookabilitySql.bookableSalon("s");

        assertThat(sql)
                .contains("FROM masters bkm")
                .contains("bkm.salon_id = s.id")
                .contains("bkm.is_active = true")
                .contains(MasterBookabilitySql.bookableMaster("bkm").strip());
    }

    @Test
    @DisplayName("referencesToday — true for every spliced form, false for SQL without one")
    void should_detectTodayParameter_when_formSpliced() {
        assertThat(MasterBookabilitySql.referencesToday("SELECT 1 WHERE" + MasterBookabilitySql.bookableSalon("s")))
                .isTrue();
        assertThat(MasterBookabilitySql.referencesToday(MasterBookabilitySql.BOOKABLE_MASTER_M)).isTrue();
        assertThat(MasterBookabilitySql.referencesToday("SELECT 1 FROM masters m WHERE m.id = :id")).isFalse();
    }

    @ParameterizedTest(name = "rejects alias [{0}]")
    @ValueSource(strings = {"m; DROP TABLE masters", "m--", "m.id", "s)", "M", "1m", "m m", "'m'",
            "abcdefghijklmnopq", ""})
    @DisplayName("every dynamic builder rejects an alias that is not a short lower-case identifier")
    void should_reject_when_aliasHasSqlMetacharacters(String alias) {
        assertThatThrownBy(() -> MasterBookabilitySql.hasSchedule(alias))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MasterBookabilitySql.bookableMaster(alias))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MasterBookabilitySql.bookableSalon(alias))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null alias is rejected, and the longest legal alias (16 chars) is accepted")
    void should_acceptOnlyLegalAliases_when_boundaryChecked() {
        assertThatThrownBy(() -> MasterBookabilitySql.hasSchedule(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(MasterBookabilitySql.hasSchedule("abcdefghijklmno_"))
                .contains("ws.master_id = abcdefghijklmno_.id");
    }

    @Test
    @DisplayName("every form starts with a space so it can follow a text block ending in a bare AND")
    void should_startWithSpace_when_splicedAfterTextBlock() {
        assertThat(MasterBookabilitySql.BOOKABLE_SALON_S).startsWith(" ");
        assertThat(MasterBookabilitySql.BOOKABLE_MASTER_M).startsWith(" ");
        assertThat(MasterBookabilitySql.HAS_SCHEDULE_MAD).startsWith(" ");
    }

    @Test
    @DisplayName("parentheses balance in every form (a fragment spliced into six @Query bodies)")
    void should_balanceParentheses_when_anyFormRendered() {
        for (String sql : new String[] {
                MasterBookabilitySql.BOOKABLE_SALON_S, MasterBookabilitySql.BOOKABLE_MASTER_M,
                MasterBookabilitySql.HAS_SCHEDULE_MAD}) {
            long open = sql.chars().filter(c -> c == '(').count();
            long close = sql.chars().filter(c -> c == ')').count();

            assertThat(open).as(sql).isEqualTo(close);
        }
    }
}
