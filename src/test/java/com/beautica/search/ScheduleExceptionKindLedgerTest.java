package com.beautica.search;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.salon.repository.SalonSearchSql;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cardinality ledger for {@link ScheduleExceptionKind}, owned by the search package because the
 * thing it protects is a SQL predicate here, not the enum itself.
 *
 * <h2>Why this ledger exists (2026-09-13 cycle-3 audit, A3)</h2>
 * <p>{@code SalonSearchSql#STATIC_PROJECTION_HEAD}'s {@code pr} price lateral decides which masters
 * set a salon's <b>publicly advertised</b> search price band. Its exception arm used to read
 * {@code se.kind <> 'DAY_OFF'} — fail-OPEN on the enum: every kind that is not {@code DAY_OFF} was
 * treated as a working override. With exactly two constants today (and V71's {@code chk_exc_kind}
 * pinning the column to the same pair) that was equivalent to matching {@code CUSTOM_HOURS}, so it
 * was total and correct — but only by accident of the enum's current size.</p>
 *
 * <p>The instant a third, NON-working kind is added — vacation, sick leave, a salon closure — the
 * negative form would silently admit every master holding one back into the price hull, and a salon
 * could be advertised at a price set by a master nobody can book. The arm is now matched
 * POSITIVELY ({@code se.kind = 'CUSTOM_HOURS'}), so a new kind is inert there until someone
 * deliberately opts it in. This ledger makes that decision explicit rather than implicit: adding a
 * constant turns this test RED and forces the author to revisit the predicate.</p>
 *
 * <p><b>If you are here because you added a kind:</b> decide whether it is BOOKABLE. If it is, add
 * it to the {@code pr} lateral's exception arm (and to the catalogue fold it mirrors). If it is
 * not, leave the SQL alone. Either way, update the expected set below — never delete this test.</p>
 *
 * <p><b>Measured, not assumed:</b> adding a third constant does not currently even compile —
 * {@code MasterScheduleService}'s exhaustive {@code switch} over this enum fails first (verified by
 * mutation, 2026-09-13). That compiler guard covers the FOLD; it says nothing about this SQL
 * string, which is why the second case below asserts the predicate's form directly. Reverting the
 * arm to {@code se.kind <> 'DAY_OFF'} turns that case RED with everything else untouched
 * (verified by mutation in the same pass).</p>
 */
@DisplayName("ScheduleExceptionKind ledger — pins the constant set SalonSearchSql's price-band "
        + "exception arm (se.kind = 'CUSTOM_HOURS') was written against")
class ScheduleExceptionKindLedgerTest {

    @Test
    @DisplayName("ScheduleExceptionKind has EXACTLY {DAY_OFF, CUSTOM_HOURS} — a third kind must "
            + "not silently change what prices a salon in public search")
    void should_holdExactlyTheTwoKindsTheSearchPriceBandWasWrittenAgainst() {
        assertThat(Arrays.stream(ScheduleExceptionKind.values()).map(Enum::name))
                .as("adding a kind means deciding whether it is bookable — see this class's javadoc "
                        + "and SalonSearchSql#STATIC_PROJECTION_HEAD's exception arm")
                .containsExactlyInAnyOrder("DAY_OFF", "CUSTOM_HOURS");
    }

    @Test
    @DisplayName("the search price band's exception arm matches CUSTOM_HOURS positively, never "
            + "'<> DAY_OFF' — the fail-OPEN form a new kind would silently widen")
    void should_matchTheBookableKindPositively_inTheSearchPriceBandLateral() {
        assertThat(SalonSearchSql.STATIC_PROJECTION_HEAD)
                .as("a negative match admits every future kind into the advertised price hull")
                .doesNotContain("se.kind <> 'DAY_OFF'")
                .contains("se.kind = 'CUSTOM_HOURS'");
    }
}
