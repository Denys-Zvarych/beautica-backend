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
 */
@DisplayName("BookingPriceRange.resolveCeiling — unit")
class BookingPriceRangeTest {

    private static final BigDecimal CEILING = new BigDecimal("500.00");
    private static final BigDecimal OVERRIDE = new BigDecimal("400.00");

    private static MasterServiceAssignment assignment(
            BigDecimal priceOverride, PriceType priceType, BigDecimal priceMax) {
        var serviceDefinition = mock(ServiceDefinition.class);
        when(serviceDefinition.getPriceType()).thenReturn(priceType);
        when(serviceDefinition.getPriceMax()).thenReturn(priceMax);

        var masterService = mock(MasterServiceAssignment.class);
        when(masterService.getPriceOverride()).thenReturn(priceOverride);
        when(masterService.getServiceDefinition()).thenReturn(serviceDefinition);
        return masterService;
    }

    @Test
    @DisplayName("returns the ceiling when the service is RANGE and the assignment has no priceOverride")
    void should_returnCeiling_when_rangeServiceHasNoOverride() {
        var masterService = assignment(null, PriceType.RANGE, CEILING);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling).isEqualByComparingTo(CEILING);
    }

    @Test
    @DisplayName("returns null when the service is RANGE but the assignment has a priceOverride — the master fixed their own price")
    void should_returnNull_when_rangeServiceHasOverride() {
        var masterService = assignment(OVERRIDE, PriceType.RANGE, CEILING);

        BigDecimal ceiling = BookingPriceRange.resolveCeiling(masterService);

        assertThat(ceiling).isNull();
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
