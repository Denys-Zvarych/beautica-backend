package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary ledger for {@link MasterServiceBand#isLegal} — Phase 311 D2/D3, amended by Phase 312
 * D8. Before D8 this static method had NO unit test at all; its RANGE floor/ceiling comparison
 * (originally {@code >=}, tightened to strict {@code >} by D8) is exactly the kind of
 * one-character operator decision that needs a ledger cheap and direct enough to fail on the
 * first bad edit, rather than being discovered only through a Testcontainers integration test.
 *
 * <p>The {@code 500.00} vs {@code 500} case is load-bearing, not decorative: it is the only case
 * in this file that distinguishes {@code compareTo} from {@code equals}. {@code BigDecimal}
 * {@code equals} treats different scales as unequal ({@code 500.00.equals(500)} is {@code false}),
 * so a regression from {@code compareTo} to {@code equals} would make this degenerate band look
 * numerically distinct and incorrectly pass as legal. See Phase 312 D8's mutation check 11.
 */
class MasterServiceBandTest {

    // ── RANGE — the D8 boundary itself ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("RANGE 500/501 (ceiling strictly above floor) is legal")
    void should_returnTrue_when_rangeCeilingStrictlyAboveFloor() {
        assertThat(MasterServiceBand.isLegal(
                PriceType.RANGE, new BigDecimal("500"), new BigDecimal("501")))
                .isTrue();
    }

    @Test
    @DisplayName("RANGE 500/500 (degenerate: ceiling equals floor) is ILLEGAL — the D8 boundary")
    void should_returnFalse_when_rangeCeilingEqualsFloor() {
        assertThat(MasterServiceBand.isLegal(
                PriceType.RANGE, new BigDecimal("500"), new BigDecimal("500")))
                .isFalse();
    }

    @Test
    @DisplayName("RANGE 500/499 (ceiling below floor) is illegal")
    void should_returnFalse_when_rangeCeilingBelowFloor() {
        assertThat(MasterServiceBand.isLegal(
                PriceType.RANGE, new BigDecimal("500"), new BigDecimal("499")))
                .isFalse();
    }

    @Test
    @DisplayName("RANGE 500.00/500 (degenerate, different scale) is ILLEGAL — the comparison must "
            + "stay compareTo, never equals, or scale smuggles a degenerate band through")
    void should_returnFalse_when_rangeCeilingEqualsFloorAtDifferentScale() {
        assertThat(MasterServiceBand.isLegal(
                PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("500")))
                .isFalse();
    }

    // ── FIXED ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIXED 500/null (no ceiling) is legal")
    void should_returnTrue_when_fixedHasNoCeiling() {
        assertThat(MasterServiceBand.isLegal(PriceType.FIXED, new BigDecimal("500"), null))
                .isTrue();
    }

    @Test
    @DisplayName("FIXED 500/600 (carries a ceiling) is illegal")
    void should_returnFalse_when_fixedCarriesCeiling() {
        assertThat(MasterServiceBand.isLegal(
                PriceType.FIXED, new BigDecimal("500"), new BigDecimal("600")))
                .isFalse();
    }

    // ── Inherited / partial ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(null, null, null) — fully absent band — is legal (Inherited)")
    void should_returnTrue_when_allFieldsAbsent() {
        assertThat(MasterServiceBand.isLegal(null, null, null)).isTrue();
    }

    @Test
    @DisplayName("(null, 500, null) — a floor with no priceType — is illegal (partial band, D2)")
    void should_returnFalse_when_priceSetWithNoPriceType() {
        assertThat(MasterServiceBand.isLegal(null, new BigDecimal("500"), null)).isFalse();
    }

    @Test
    @DisplayName("(null, null, 900) — a CEILING with no priceType — is illegal (the mirror-image "
            + "partial band the floor case above does not cover)")
    void should_returnFalse_when_priceMaxSetWithNoPriceType() {
        assertThat(MasterServiceBand.isLegal(null, null, new BigDecimal("900"))).isFalse();
    }

    // ── Missing-floor arms: `price != null` on both switch branches (2026-09-13 audit, Q10) ──

    @Test
    @DisplayName("FIXED with a null floor is illegal — a shape with nothing to charge is not a band")
    void should_returnFalse_when_fixedHasNullPrice() {
        assertThat(MasterServiceBand.isLegal(PriceType.FIXED, null, null)).isFalse();
    }

    @Test
    @DisplayName("RANGE with a null floor but a present ceiling is illegal")
    void should_returnFalse_when_rangeHasNullFloor() {
        assertThat(MasterServiceBand.isLegal(PriceType.RANGE, null, new BigDecimal("900"))).isFalse();
    }

    @Test
    @DisplayName("RANGE with a floor but no ceiling is illegal — RANGE requires both")
    void should_returnFalse_when_rangeHasNullCeiling() {
        assertThat(MasterServiceBand.isLegal(PriceType.RANGE, new BigDecimal("500"), null)).isFalse();
    }

    // ── Positivity arms: `price.compareTo(ZERO) > 0` (2026-09-13 audit, Q10) ─────────────────────
    //
    // Untested until now on BOTH branches. A mutant weakening either to >= would have survived the
    // whole original ledger, and a zero-priced band is not merely odd — it is a free service the
    // DB CHECK does not refuse either (chk_master_service_price_mode says nothing about sign).

    @Test
    @DisplayName("FIXED 0 is illegal — the floor must be strictly positive")
    void should_returnFalse_when_fixedPriceIsZero() {
        assertThat(MasterServiceBand.isLegal(PriceType.FIXED, BigDecimal.ZERO, null)).isFalse();
    }

    @Test
    @DisplayName("FIXED with a NEGATIVE floor is illegal")
    void should_returnFalse_when_fixedPriceIsNegative() {
        assertThat(MasterServiceBand.isLegal(PriceType.FIXED, new BigDecimal("-1"), null)).isFalse();
    }

    @Test
    @DisplayName("RANGE 0/500 is illegal — a zero floor fails positivity even though the ceiling "
            + "is strictly above it, so the D8 comparison alone cannot be what rejects it")
    void should_returnFalse_when_rangeFloorIsZero() {
        assertThat(MasterServiceBand.isLegal(
                PriceType.RANGE, BigDecimal.ZERO, new BigDecimal("500")))
                .isFalse();
    }

    @Test
    @DisplayName("RANGE -100/-1 is illegal — both ends non-positive, pinning the ceiling's own "
            + "positivity arm, which a positive-floor case can never reach")
    void should_returnFalse_when_rangeCeilingIsNegative() {
        assertThat(MasterServiceBand.isLegal(
                PriceType.RANGE, new BigDecimal("-100"), new BigDecimal("-1")))
                .isFalse();
    }

    // ── isAbsent — the PATCH's "band present?" discriminator (2026-09-13 audit, Q10) ─────────────
    //
    // Zero direct tests until now, despite being what UpdateMasterServiceBandRequest's @AssertTrue
    // rules use to tell "leave the band unchanged" (D4) from "band present, must be legal".

    @Test
    @DisplayName("isAbsent is true only for the all-null triple")
    void should_returnTrue_when_noBandFieldWasSent() {
        assertThat(MasterServiceBand.isAbsent(null, null, null)).isTrue();
    }

    @Test
    @DisplayName("isAbsent is false when ONLY priceType was sent — a partial band is present, not "
            + "absent, so the PATCH must validate it rather than treat it as 'leave unchanged'")
    void should_returnFalse_when_onlyPriceTypeWasSent() {
        assertThat(MasterServiceBand.isAbsent(PriceType.FIXED, null, null)).isFalse();
    }

    @Test
    @DisplayName("isAbsent is false when ONLY price was sent")
    void should_returnFalse_when_onlyPriceWasSent() {
        assertThat(MasterServiceBand.isAbsent(null, new BigDecimal("500"), null)).isFalse();
    }

    @Test
    @DisplayName("isAbsent is false when ONLY priceMax was sent")
    void should_returnFalse_when_onlyPriceMaxWasSent() {
        assertThat(MasterServiceBand.isAbsent(null, null, new BigDecimal("900"))).isFalse();
    }
}
