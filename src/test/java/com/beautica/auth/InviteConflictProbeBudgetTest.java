package com.beautica.auth;

import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** B520 audit-fix — {@link InviteConflictProbeBudget}: 10 counted 409s per inviter per hour, per inviter. */
@DisplayName("InviteConflictProbeBudget — 10 PHONE_ALREADY_REGISTERED answers per inviter per hour")
class InviteConflictProbeBudgetTest {

    private final MutableTestClock clock = new MutableTestClock(Instant.parse("2026-11-18T12:00:00Z"));
    private final InviteConflictProbeBudget budget = new InviteConflictProbeBudget(clock);
    private final UUID inviter = UUID.randomUUID();

    private void spend(UUID inviterId, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(budget.tryRecordConflict(inviterId)).isTrue();
        }
    }

    @Test
    @DisplayName("the first 10 conflicts are counted; the 11th is refused and the budget reads exhausted")
    void should_refuseEleventhConflict_when_tenRecordedWithinWindow() {
        spend(inviter, InviteConflictProbeBudget.MAX_CONFLICTS);

        boolean eleventh = budget.tryRecordConflict(inviter);

        assertThat(eleventh).isFalse();
        assertThat(budget.isExhausted(inviter)).isTrue();
    }

    @Test
    @DisplayName("nine conflicts leave the budget open")
    void should_notBeExhausted_when_underTheCap() {
        spend(inviter, InviteConflictProbeBudget.MAX_CONFLICTS - 1);

        assertThat(budget.isExhausted(inviter)).isFalse();
    }

    @Test
    @DisplayName("the window ends an hour after the last counted conflict; refused probes do not extend it")
    void should_reopen_when_windowPassesAfterLastCountedConflict() {
        spend(inviter, InviteConflictProbeBudget.MAX_CONFLICTS);
        clock.advance(Duration.ofMinutes(59));
        assertThat(budget.tryRecordConflict(inviter)).as("the refused 11th probe at +59 min").isFalse();

        clock.advance(Duration.ofMinutes(2));

        assertThat(budget.isExhausted(inviter)).isFalse();
        assertThat(budget.tryRecordConflict(inviter)).isTrue();
    }

    @Test
    @DisplayName("budgets are per inviter")
    void should_keepOtherInviterOpen_when_oneInviterIsExhausted() {
        spend(inviter, InviteConflictProbeBudget.MAX_CONFLICTS);

        assertThat(budget.isExhausted(UUID.randomUUID())).isFalse();
    }
}
