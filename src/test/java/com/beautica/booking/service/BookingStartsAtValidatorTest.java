package com.beautica.booking.service;

import com.beautica.common.BookingWindow;
import com.beautica.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit pin for {@link BookingStartsAtValidator}'s <b>actor-dispatching overload</b>
 * {@code validate(startsAt, clock, initiatedByProvider)} — the reschedule-parity fix.
 *
 * <h2>What was untested before</h2>
 * The two floors themselves ({@code validate} = 15 min, {@code validateStaff} = 0 min) had coverage
 * through their call sites; the DISPATCH between them had none. Every existing sub-15-minute
 * reschedule test is CLIENT-initiated ({@code BookingRescheduleGuardChainIT:121},
 * {@code AppointmentRescheduleIT:254}), and every provider-initiated reschedule test targets a
 * {@code plusDays(2)}-class time far above BOTH floors — so reverting the dispatch to the strict
 * client floor left the whole suite green.
 *
 * <h2>Why a plain Mockito-free unit test</h2>
 * The class is a package-private static utility whose only collaborator is a {@link Clock}. A fixed
 * clock makes every boundary ("exactly now", "exactly the 15-minute cutoff", "one second past the
 * horizon") deterministic — none of which is expressible over the HTTP stack, where {@code @Future}
 * on the request DTO intercepts a past instant at the controller boundary before this validator is
 * ever reached. That is precisely why the "the relaxed floor is 0, not unbounded" pin
 * ({@link ProviderFloor#should_rejectPastStart_when_initiatedByProvider()}) has to live HERE and
 * cannot be an integration test.
 */
@DisplayName("BookingStartsAtValidator — actor-dispatching floor (reschedule parity)")
class BookingStartsAtValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final boolean PROVIDER = true;
    private static final boolean CLIENT = false;

    /** Inside the 15-minute CLIENT floor, comfortably outside the 0-minute STAFF floor. */
    private static OffsetDateTime inFiveMinutes() {
        return at(NOW.plusSeconds(5 * 60));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ── the relaxed (provider) arm ───────────────────────────────────────────────

    @Nested
    @DisplayName("initiatedByProvider = true — the STAFF floor (minimum lead 0)")
    class ProviderFloor {

        @Test
        @DisplayName("accepts a start 5 minutes out — the very start the CLIENT floor rejects "
                + "(the whole point of the dispatch)")
        void should_acceptFiveMinutesFromNow_when_initiatedByProvider() {
            assertThatCode(() -> BookingStartsAtValidator.validate(inFiveMinutes(), CLOCK, PROVIDER))
                    .as("a master moving a booking to 'in 5 minutes' is a start the same master "
                            + "could have CREATED through the walk-in path — it must not 400")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts a start of exactly now — the 0-minute floor's inclusive boundary")
        void should_acceptExactlyNow_when_initiatedByProvider() {
            assertThatCode(() -> BookingStartsAtValidator.validate(at(NOW), CLOCK, PROVIDER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a start one second in the past with 400 \"Booking cannot start in the "
                + "past\" — the relaxed floor is ZERO, not unbounded")
        void should_rejectPastStart_when_initiatedByProvider() {
            OffsetDateTime aSecondAgo = at(NOW.minusSeconds(1));

            assertThatThrownBy(() -> BookingStartsAtValidator.validate(aSecondAgo, CLOCK, PROVIDER))
                    .as("removing the floor entirely (rather than relaxing it to 0) would let a "
                            + "provider back-date a booking into a state that is born needing closure")
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Booking cannot start in the past")
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("still enforces the MAX_DAYS_AHEAD horizon — the relaxed floor moves the LOWER "
                + "bound only")
        void should_rejectBeyondHorizon_when_initiatedByProvider() {
            OffsetDateTime pastHorizon = at(NOW.plusSeconds((BookingWindow.MAX_DAYS_AHEAD + 1) * 86_400L));

            assertThatThrownBy(() -> BookingStartsAtValidator.validate(pastHorizon, CLOCK, PROVIDER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Booking cannot be more than " + BookingWindow.MAX_DAYS_AHEAD
                            + " days in the future")
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── the untouched (client) arm ───────────────────────────────────────────────

    @Nested
    @DisplayName("initiatedByProvider = false — the CLIENT floor (15 min), untouched")
    class ClientFloor {

        @Test
        @DisplayName("rejects a start 5 minutes out with the 15-minute message — non-vacuity for the "
                + "provider arm above, and the guard against a blanket relaxation")
        void should_rejectFiveMinutesFromNow_when_initiatedByClient() {
            assertThatThrownBy(() -> BookingStartsAtValidator.validate(inFiveMinutes(), CLOCK, CLIENT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Booking must start at least " + BookingWindow.MIN_MINUTES_AHEAD
                            + " minutes from now")
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("accepts a start exactly at the 15-minute cutoff (inclusive boundary)")
        void should_acceptExactlyAtCutoff_when_initiatedByClient() {
            OffsetDateTime atCutoff = at(NOW.plusSeconds(BookingWindow.MIN_MINUTES_AHEAD * 60L));

            assertThatCode(() -> BookingStartsAtValidator.validate(atCutoff, CLOCK, CLIENT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a start one second below the 15-minute cutoff (exclusive on the other "
                + "side — the off-by-one pin)")
        void should_rejectOneSecondBelowCutoff_when_initiatedByClient() {
            OffsetDateTime justBelow = at(NOW.plusSeconds(BookingWindow.MIN_MINUTES_AHEAD * 60L - 1));

            assertThatThrownBy(() -> BookingStartsAtValidator.validate(justBelow, CLOCK, CLIENT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(String.valueOf(BookingWindow.MIN_MINUTES_AHEAD));
        }
    }

    // ── the dispatch itself ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the two arms genuinely disagree on the SAME instant — the flag, not the instant, "
            + "decides the verdict")
    void should_produceOppositeVerdictsForTheSameInstant_when_onlyTheActorFlagDiffers() {
        OffsetDateTime sameInstant = inFiveMinutes();

        assertThatCode(() -> BookingStartsAtValidator.validate(sameInstant, CLOCK, PROVIDER))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> BookingStartsAtValidator.validate(sameInstant, CLOCK, CLIENT))
                .isInstanceOf(BusinessException.class);

        assertThat(BookingWindow.MIN_MINUTES_AHEAD)
                .as("fixture sanity: 5 minutes must genuinely sit below the client floor, or the "
                        + "assertions above prove nothing")
                .isGreaterThan(5);
    }
}
