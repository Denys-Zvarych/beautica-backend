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
}
