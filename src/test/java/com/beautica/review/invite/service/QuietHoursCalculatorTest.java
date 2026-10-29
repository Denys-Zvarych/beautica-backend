package com.beautica.review.invite.service;

import com.beautica.common.TimeZones;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QuietHoursCalculator — phase 400 D3 (Europe/Kyiv)")
class QuietHoursCalculatorTest {

    private final QuietHoursCalculator calculator = new QuietHoursCalculator(ReviewInviteTestProperties.enabled());

    @ParameterizedTest(name = "completed {0} Kyiv → send {1} Kyiv")
    @DisplayName("send_after = completion + 2h, moved to the next 10:00 Kyiv when it lands in [21:00, 09:00)")
    @CsvSource({
            // 18:59 + 2h = 20:59, outside the window → as is
            "2026-06-10T18:59, 2026-06-10T20:59",
            // 19:00 + 2h = 21:00, window start (inclusive) → next day 10:00
            "2026-06-10T19:00, 2026-06-11T10:00",
            // 23:30 + 2h = 01:30 next day → 10:00 that same (next) day
            "2026-06-10T23:30, 2026-06-11T10:00",
            // 05:30 + 2h = 07:30 → 10:00 the same day
            "2026-06-10T05:30, 2026-06-10T10:00",
            // 06:59 + 2h = 08:59 → 10:00 the same day
            "2026-06-10T06:59, 2026-06-10T10:00",
            // 07:00 + 2h = 09:00, window end (exclusive) → as is
            "2026-06-10T07:00, 2026-06-10T09:00",
            // 08:30 + 2h = 10:30 → as is
            "2026-06-10T08:30, 2026-06-10T10:30"
    })
    void should_applyDelayAndQuietWindow_when_visitCompleted(String completedKyiv, String expectedKyiv) {
        Instant completedAt = kyiv(completedKyiv);

        Instant sendAfter = calculator.sendAfter(completedAt);

        assertThat(sendAfter).isEqualTo(kyiv(expectedKyiv));
    }

    @Test
    @DisplayName("a 07:30 Kyiv candidate moves to 10:00 the same day")
    void should_moveToSameDayTen_when_candidateBeforeQuietEnd() {
        Instant candidate = kyiv("2026-06-10T07:30");

        Instant allowed = calculator.nextAllowed(candidate);

        assertThat(allowed).isEqualTo(kyiv("2026-06-10T10:00"));
    }

    @Test
    @DisplayName("spring-forward day (2026-03-29, 03:00→04:00): 01:30 EET + 2h on the timeline = 04:30 EEST → 10:00 EEST")
    void should_landOnTenLocal_when_springForwardDay() {
        Instant completedAt = kyiv("2026-03-29T01:30");

        Instant sendAfter = calculator.sendAfter(completedAt);

        assertThat(sendAfter).isEqualTo(Instant.parse("2026-03-29T07:00:00Z"));
        assertThat(sendAfter.atZone(TimeZones.KYIV).toLocalTime()).hasToString("10:00");
    }

    @Test
    @DisplayName("spring-forward day: 18:00 EEST + 2h = 20:00 EEST (delay is real elapsed time, not wall clock)")
    void should_addRealHours_when_springForwardDayEvening() {
        Instant completedAt = kyiv("2026-03-29T18:00");

        Instant sendAfter = calculator.sendAfter(completedAt);

        assertThat(sendAfter).isEqualTo(completedAt.plusSeconds(7_200));
        assertThat(sendAfter.atZone(TimeZones.KYIV).toLocalTime()).hasToString("20:00");
    }

    @Test
    @DisplayName("fall-back day (2026-10-25, 04:00→03:00): 02:30 EEST + 2h = 03:30 EET → 10:00 EET")
    void should_landOnTenLocal_when_fallBackDay() {
        Instant completedAt = Instant.parse("2026-10-24T23:30:00Z"); // 02:30 EEST

        Instant sendAfter = calculator.sendAfter(completedAt);

        assertThat(sendAfter).isEqualTo(Instant.parse("2026-10-25T08:00:00Z"));
        assertThat(sendAfter.atZone(TimeZones.KYIV).toLocalTime()).hasToString("10:00");
    }

    @Test
    @DisplayName("fall-back day evening: 18:30 EET + 2h = 20:30 EET, sent as is")
    void should_keepCandidate_when_fallBackDayEvening() {
        Instant completedAt = kyiv("2026-10-25T18:30");

        Instant sendAfter = calculator.sendAfter(completedAt);

        assertThat(sendAfter).isEqualTo(kyiv("2026-10-25T20:30"));
    }

    @ParameterizedTest(name = "{0} Kyiv quiet={1}")
    @DisplayName("isQuiet at send time is true exactly inside [21:00, 09:00) Kyiv")
    @CsvSource({
            "2026-06-10T20:59, false",
            "2026-06-10T21:00, true",
            "2026-06-11T03:00, true",
            "2026-06-11T08:59, true",
            "2026-06-11T09:00, false",
            "2026-06-11T12:00, false"
    })
    void should_reportQuietWindow_when_askedAtSendTime(String nowKyiv, boolean quiet) {
        boolean result = calculator.isQuiet(kyiv(nowKyiv));

        assertThat(result).isEqualTo(quiet);
    }

    private static Instant kyiv(String localDateTime) {
        return ZonedDateTime.of(LocalDateTime.parse(localDateTime), TimeZones.KYIV).toInstant();
    }
}
