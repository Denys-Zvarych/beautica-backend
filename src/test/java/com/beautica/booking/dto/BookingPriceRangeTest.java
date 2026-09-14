package com.beautica.booking.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the creation-time ceiling rule. Every case drives the single public entry point
 * ({@code resolveCeiling(MasterServiceAssignment)}) — the component-wise overload was made private
 * once V119 froze the ceiling and the CLIENT projection stopped re-deriving it, so the assignment
 * is now the only shape callers ever have.
 *
 * <p>Phase 311 D9 — {@code resolveCeiling} now delegates to {@code ServicePricing.ofAssignment},
 * which resolves the RESOLVED band (the assignment's own {@code priceTypeOverride} when it holds
 * one, else the definition's — see that class's javadoc). {@code assignment(...)} below was
 * widened to stub {@code priceTypeOverride}/{@code priceMaxOverride} so a case can model either
 * state precisely.
 */
@DisplayName("BookingPriceRange.resolveCeiling — unit")
class BookingPriceRangeTest {

    private static final BigDecimal CEILING = new BigDecimal("500.00");
    private static final BigDecimal OVERRIDE = new BigDecimal("400.00");
    private static final BigDecimal OWN_CEILING = new BigDecimal("900.00");

    /** Inherited assignment: no override of any kind — the band comes wholly from the definition. */
    private static MasterServiceAssignment assignment(
            BigDecimal priceOverride, PriceType priceType, BigDecimal priceMax) {
        return assignment(null, priceOverride, null, priceType, priceMax);
    }

    /** Full form — lets a case stub the assignment's OWN band (Phase 311 D2/D9) explicitly. */
    private static MasterServiceAssignment assignment(
            PriceType priceTypeOverride, BigDecimal priceOverride, BigDecimal priceMaxOverride,
            PriceType defPriceType, BigDecimal defPriceMax) {
        var serviceDefinition = mock(ServiceDefinition.class);
        when(serviceDefinition.getPriceType()).thenReturn(defPriceType);
        when(serviceDefinition.getPriceMax()).thenReturn(defPriceMax);

        var masterService = mock(MasterServiceAssignment.class);
        when(masterService.getPriceOverride()).thenReturn(priceOverride);
        when(masterService.getPriceTypeOverride()).thenReturn(priceTypeOverride);
        when(masterService.getPriceMaxOverride()).thenReturn(priceMaxOverride);
        when(masterService.getServiceDefinition()).thenReturn(serviceDefinition);
        return masterService;
    }

    @Test
    @DisplayName("returns the ceiling when the service is RANGE and the assignment is Inherited (no override at all)")
    void should_returnCeiling_when_rangeServiceHasNoOverride() {
        var masterService = assignment(null, PriceType.RANGE, CEILING);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling).isEqualByComparingTo(CEILING);
    }

    @Test
    @DisplayName("Phase 311 D9 — returns the MASTER'S OWN ceiling when the assignment holds an own RANGE band, "
            + "not the definition's ceiling — mutation-check 3's pin")
    void should_returnOwnCeiling_when_assignmentHasOwnRangeBand() {
        var masterService = assignment(PriceType.RANGE, OVERRIDE, OWN_CEILING, PriceType.FIXED, null);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling)
                .as("own RANGE band on a FIXED definition — the resolved shape is RANGE and the "
                        + "ceiling must be the master's own, never the (FIXED, ceiling-less) definition's")
                .isEqualByComparingTo(OWN_CEILING);
    }

    @Test
    @DisplayName("returns null when the assignment holds an own FIXED band — the master fixed their own price")
    void should_returnNull_when_assignmentHasOwnFixedBand() {
        var masterService = assignment(PriceType.FIXED, OVERRIDE, null, PriceType.RANGE, CEILING);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling)
                .as("own FIXED band on a RANGE definition — resolved shape is FIXED, no ceiling, "
                        + "regardless of the definition's own ceiling")
                .isNull();
    }

    @Test
    @DisplayName("returns null when the service is FIXED, regardless of priceMax")
    void should_returnNull_when_serviceIsFixed() {
        var masterService = assignment(null, PriceType.FIXED, null);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling).isNull();
    }

    @Test
    @DisplayName("returns null (not a NPE) when a RANGE service defensively has a null priceMax")
    void should_returnNull_when_rangeServiceHasNullPriceMaxDefensively() {
        var masterService = assignment(null, PriceType.RANGE, null);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling).isNull();
    }

    @Test
    @DisplayName("a FIXED service that still carries a stale priceMax yields no ceiling — priceType decides, not the column's presence")
    void should_returnNull_when_fixedServiceStillCarriesAPriceMax() {
        var masterService = assignment(null, PriceType.FIXED, CEILING);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling).isNull();
    }
}
