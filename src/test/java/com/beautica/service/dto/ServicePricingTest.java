package com.beautica.service.dto;

import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit ledger for {@link ServicePricing} — the SINGLE money-and-duration derivation behind every
 * response DTO that prints a price (Phase 31.4 D2, extended by Phase 311 D9).
 *
 * <p><b>Why this file exists (2026-09-13 audit, H2).</b> {@code ServicePricing.derive} is the one
 * function that resolves {@code (shape, floor, ceiling)} from an assignment's own band falling back
 * to the definition's, and until now every one of its branches was reachable only through a
 * Testcontainers integration test. A pure function with a four-way truth table does not need a
 * database to be pinned, and an IT is the wrong instrument for it: the combination that matters
 * most — an own FIXED band sitting on top of a RANGE definition, where the definition's ceiling
 * must NOT be inherited — is a single line of {@code derive} and a whole fixture in an IT.
 *
 * <p>The four combinations under test are {@code priceTypeOverride} present/absent crossed with the
 * RESOLVED shape being FIXED/RANGE, plus the independent duration fork.
 */
@DisplayName("ServicePricing — Phase 311 D9 band resolution")
class ServicePricingTest {

    private static final BigDecimal DEF_BASE = new BigDecimal("500.00");
    private static final BigDecimal DEF_MAX = new BigDecimal("900.00");
    private static final BigDecimal OWN_FLOOR = new BigDecimal("700.00");
    private static final BigDecimal OWN_CEILING = new BigDecimal("1200.00");
    private static final int DEF_DURATION = 60;
    private static final int OWN_DURATION = 95;

    @Nested
    @DisplayName("Inherited — the assignment carries no band of its own")
    class Inherited {

        @Test
        @DisplayName("FIXED definition + no override resolves the definition's FIXED band and a "
                + "null ceiling")
        void should_resolveDefinitionFixedBand_when_assignmentHasNoOwnBand() {
            var msa = assignment(fixedDefinition(), null, null, null, null);

            var pricing = ServicePricing.ofAssignment(msa);

            assertThat(pricing.priceType()).isEqualTo(PriceType.FIXED);
            assertThat(pricing.priceMin()).isEqualByComparingTo(DEF_BASE);
            assertThat(pricing.priceMax())
                    .as("FIXED has no ceiling, whatever the definition holds")
                    .isNull();
            assertThat(pricing.effectivePrice()).isEqualByComparingTo(DEF_BASE);
            assertThat(pricing.priceDisplay()).isEqualTo("500 ₴");
        }

        @Test
        @DisplayName("RANGE definition + no override resolves the DEFINITION's ceiling, not null — "
                + "the priceMaxOverride column is irrelevant while priceTypeOverride is null")
        void should_resolveDefinitionRangeCeiling_when_assignmentHasNoOwnBand() {
            var msa = assignment(rangeDefinition(), null, null, null, null);

            var pricing = ServicePricing.ofAssignment(msa);

            assertThat(pricing.priceType()).isEqualTo(PriceType.RANGE);
            assertThat(pricing.priceMin()).isEqualByComparingTo(DEF_BASE);
            assertThat(pricing.priceMax())
                    .as("an Inherited assignment tracks the definition's ceiling")
                    .isEqualByComparingTo(DEF_MAX);
            assertThat(pricing.priceDisplay()).isEqualTo("від 500 до 900 ₴");
        }
    }

    @Nested
    @DisplayName("Own band — the assignment overrides shape, floor and ceiling together (D2)")
    class OwnBand {

        @Test
        @DisplayName("own RANGE band over a FIXED definition surfaces the MASTER's floor and "
                + "ceiling, never the definition's base price")
        void should_resolveOwnRangeBand_when_definitionIsFixed() {
            var msa = assignment(fixedDefinition(), PriceType.RANGE, OWN_FLOOR, OWN_CEILING, null);

            var pricing = ServicePricing.ofAssignment(msa);

            assertThat(pricing.priceType()).isEqualTo(PriceType.RANGE);
            assertThat(pricing.priceMin()).isEqualByComparingTo(OWN_FLOOR);
            assertThat(pricing.priceMax()).isEqualByComparingTo(OWN_CEILING);
            assertThat(pricing.effectivePrice())
                    .as("effectivePrice and priceMin are the same floor by construction")
                    .isEqualByComparingTo(pricing.priceMin());
            assertThat(pricing.priceDisplay()).isEqualTo("від 700 до 1200 ₴");
        }

        @Test
        @DisplayName("own FIXED band over a RANGE definition must NOT inherit the definition's "
                + "ceiling — priceMax is gated on the RESOLVED shape, not the definition's")
        void should_dropDefinitionCeiling_when_ownBandIsFixedOverRangeDefinition() {
            var msa = assignment(rangeDefinition(), PriceType.FIXED, OWN_FLOOR, null, null);

            var pricing = ServicePricing.ofAssignment(msa);

            assertThat(pricing.priceType()).isEqualTo(PriceType.FIXED);
            assertThat(pricing.priceMin()).isEqualByComparingTo(OWN_FLOOR);
            assertThat(pricing.priceMax())
                    .as("the RANGE definition's 900 ceiling must not leak onto a master who "
                            + "declared a FIXED band — that is the exact defect the RESOLVED-shape "
                            + "gate in derive() prevents")
                    .isNull();
            assertThat(pricing.priceDisplay()).isEqualTo("700 ₴");
        }
    }

    @Nested
    @DisplayName("Duration fork — independent of the band (D2: duration has no shape, no ceiling)")
    class Duration {

        @Test
        @DisplayName("a duration override wins over the definition's base duration while the band "
                + "stays Inherited")
        void should_useDurationOverride_when_setWithNoBandOverride() {
            var msa = assignment(fixedDefinition(), null, null, null, OWN_DURATION);

            var pricing = ServicePricing.ofAssignment(msa);

            assertThat(pricing.effectiveDurationMinutes()).isEqualTo(OWN_DURATION);
            assertThat(pricing.priceMin())
                    .as("overriding duration must not touch money")
                    .isEqualByComparingTo(DEF_BASE);
        }

        @Test
        @DisplayName("a null duration override falls back to the definition's base duration even "
                + "when the assignment DOES carry its own band")
        void should_fallBackToBaseDuration_when_durationOverrideIsNullDespiteOwnBand() {
            var msa = assignment(fixedDefinition(), PriceType.RANGE, OWN_FLOOR, OWN_CEILING, null);

            var pricing = ServicePricing.ofAssignment(msa);

            assertThat(pricing.effectiveDurationMinutes()).isEqualTo(DEF_DURATION);
        }
    }

    @Test
    @DisplayName("ofDefinition is the zero-override case of the same derivation: effectivePrice == "
            + "priceMin and effectiveDurationMinutes == baseDurationMinutes")
    void should_deriveBareDefinition_when_ofDefinitionCalled() {
        var pricing = ServicePricing.ofDefinition(rangeDefinition());

        assertThat(pricing.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(pricing.priceMin()).isEqualByComparingTo(DEF_BASE);
        assertThat(pricing.priceMax()).isEqualByComparingTo(DEF_MAX);
        assertThat(pricing.effectivePrice()).isEqualByComparingTo(DEF_BASE);
        assertThat(pricing.effectiveDurationMinutes()).isEqualTo(DEF_DURATION);
    }

    @Test
    @DisplayName("priceDisplay is null — not an exception — when a legacy definition carries no "
            + "priceType, so a list read cannot be blown up by one malformed row")
    void should_leavePriceDisplayNull_when_definitionHasNoPriceType() {
        var definition = definitionBuilder().priceType(null).build();

        var pricing = ServicePricing.ofDefinition(definition);

        assertThat(pricing.priceType()).isNull();
        assertThat(pricing.priceDisplay()).isNull();
    }

    @Test
    @DisplayName("effectivePriceOf is the band-free twin of the floor derive() computes — the two "
            + "must never disagree, or the menu and the wish list print different prices")
    void should_matchDeriveFloor_when_effectivePriceOfIsCalled() {
        var inherited = assignment(rangeDefinition(), null, null, null, null);
        var ownBand = assignment(rangeDefinition(), PriceType.FIXED, OWN_FLOOR, null, null);

        assertThat(ServicePricing.effectivePriceOf(inherited))
                .isEqualByComparingTo(ServicePricing.ofAssignment(inherited).effectivePrice());
        assertThat(ServicePricing.effectivePriceOf(ownBand))
                .isEqualByComparingTo(ServicePricing.ofAssignment(ownBand).effectivePrice());
    }

    // --- fixtures ---

    private static ServiceDefinition.ServiceDefinitionBuilder definitionBuilder() {
        return ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Манікюр")
                .category("MANICURE")
                .baseDurationMinutes(DEF_DURATION)
                .basePrice(DEF_BASE)
                .bufferMinutesAfter(0)
                .isActive(true);
    }

    private static ServiceDefinition fixedDefinition() {
        return definitionBuilder().priceType(PriceType.FIXED).priceMax(null).build();
    }

    private static ServiceDefinition rangeDefinition() {
        return definitionBuilder().priceType(PriceType.RANGE).priceMax(DEF_MAX).build();
    }

    private static MasterServiceAssignment assignment(ServiceDefinition definition,
                                                      PriceType priceTypeOverride,
                                                      BigDecimal priceOverride,
                                                      BigDecimal priceMaxOverride,
                                                      Integer durationOverride) {
        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(Master.builder().id(UUID.randomUUID()).build())
                .serviceDefinition(definition)
                .priceTypeOverride(priceTypeOverride)
                .priceOverride(priceOverride)
                .priceMaxOverride(priceMaxOverride)
                .durationOverrideMinutes(durationOverride)
                .isActive(true)
                .build();
    }
}
