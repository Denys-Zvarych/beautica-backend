package com.beautica.service.dto;

import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MasterServiceResponseTest {

    private static final int BASE_DURATION = 60;
    private static final int OVERRIDE_DURATION = 45;
    private static final BigDecimal BASE_PRICE = new BigDecimal("500.00");
    private static final BigDecimal OVERRIDE_PRICE = new BigDecimal("450.00");

    @Test
    @DisplayName("effective duration is the override value when a duration override is set")
    void should_computeEffectiveDurationAsOverride_when_durationOverrideSet() {
        var msa = buildAssignment(OVERRIDE_PRICE, OVERRIDE_DURATION);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectiveDurationMinutes()).isEqualTo(OVERRIDE_DURATION);
    }

    @Test
    @DisplayName("effective duration falls back to base duration when no duration override is set")
    void should_computeEffectiveDurationAsBase_when_noOverride() {
        var msa = buildAssignment(null, null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectiveDurationMinutes()).isEqualTo(BASE_DURATION);
    }

    @Test
    @DisplayName("effective price is the override value when a price override is set")
    void should_computeEffectivePriceAsOverride_when_priceOverrideSet() {
        var msa = buildAssignment(OVERRIDE_PRICE, null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectivePrice()).isEqualByComparingTo(OVERRIDE_PRICE);
    }

    @Test
    @DisplayName("effective price falls back to base price when no price override is set")
    void should_computeEffectivePriceAsBasePrice_when_noOverride() {
        var msa = buildAssignment(null, null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectivePrice()).isEqualByComparingTo(BASE_PRICE);
    }

    @Test
    @DisplayName("all passthrough fields are mapped correctly when a fully-populated MSA is converted")
    void should_mapAllPassthroughFields_when_msaMapped() {
        UUID expectedMsaId = UUID.randomUUID();
        UUID expectedMasterId = UUID.randomUUID();
        UUID expectedSdId = UUID.randomUUID();

        var serviceDefinition = ServiceDefinition.builder()
                .id(expectedSdId)
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure Classic")
                .description("Classic manicure service")
                .category("MANICURE")
                .baseDurationMinutes(BASE_DURATION)
                .priceType(PriceType.FIXED)
                .basePrice(BASE_PRICE)
                .priceMax(null)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        var master = Master.builder()
                .id(expectedMasterId)
                .build();

        var msa = MasterServiceAssignment.builder()
                .id(expectedMsaId)
                .master(master)
                .serviceDefinition(serviceDefinition)
                .priceOverride(OVERRIDE_PRICE)
                .durationOverrideMinutes(OVERRIDE_DURATION)
                .isActive(true)
                .build();

        var response = MasterServiceResponse.from(msa);

        assertThat(response.id()).isEqualTo(expectedMsaId);
        assertThat(response.masterId()).isEqualTo(expectedMasterId);
        assertThat(response.priceOverride()).isEqualByComparingTo(OVERRIDE_PRICE);
        assertThat(response.durationOverrideMinutes()).isEqualTo(OVERRIDE_DURATION);
        assertThat(response.isActive()).isTrue();
        assertThat(response.serviceDefinition().id()).isEqualTo(expectedSdId);
    }

    @Test
    @DisplayName("effective price is null when neither base price nor price override is set")
    void should_returnNullEffectivePrice_when_noPriceSetAnywhere() {
        var serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure Classic")
                .baseDurationMinutes(BASE_DURATION)
                .priceType(PriceType.FIXED)
                .basePrice(null)
                .priceMax(null)
                .isActive(true)
                .build();

        var master = Master.builder()
                .id(UUID.randomUUID())
                .build();

        var msa = MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(serviceDefinition)
                .priceOverride(null)
                .isActive(true)
                .build();

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectivePrice()).isNull();
    }

    // ── HIGH-2: RANGE pricing fields surfaced through MasterServiceResponse ──────

    @Test
    @DisplayName("RANGE priceType, priceMin, priceMax and priceDisplay are surfaced when MSA has a RANGE ServiceDefinition")
    void should_surfaceRangePriceFields_when_msaHasRangeServiceDefinition() {
        var rangeServiceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Range Manicure")
                .description("Flexible pricing manicure")
                .category("MANICURE")
                .baseDurationMinutes(BASE_DURATION)
                .priceType(PriceType.RANGE)
                .basePrice(new BigDecimal("600.00"))
                .priceMax(new BigDecimal("1200.00"))
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        var master = Master.builder()
                .id(UUID.randomUUID())
                .build();

        var msa = MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(rangeServiceDefinition)
                .priceOverride(null)
                .durationOverrideMinutes(null)
                .isActive(true)
                .build();

        var response = MasterServiceResponse.from(msa);

        assertThat(response.priceType())
                .as("priceType must be RANGE for a RANGE service definition")
                .isEqualTo(PriceType.RANGE);
        assertThat(response.priceMin())
                .as("priceMin must equal base_price (600) — the RANGE floor")
                .isEqualByComparingTo(new BigDecimal("600.00"));
        assertThat(response.priceMax())
                .as("priceMax must equal price_max (1200) — the RANGE ceiling")
                .isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(response.priceDisplay())
                .as("priceDisplay must use PriceDisplayFormatter RANGE format — whole hryvnia, no .00")
                .isEqualTo("від 600 до 1200 ₴");
    }

    // ── Phase 16.4: serviceTypeId + serviceTypeNameUk lifted from the nested definition ──

    @Test
    @DisplayName("serviceTypeId + serviceTypeNameUk + serviceTypeSlug are lifted from the nested ServiceDefinition's ServiceType")
    void should_mapServiceTypeFields_when_serviceDefinitionHasServiceType() {
        UUID serviceTypeId = UUID.randomUUID();
        var serviceType = ServiceType.builder()
                .id(serviceTypeId)
                .nameUk("Манікюр")
                .slug("manicure")
                .platformCategoryName("MANICURE")
                .active(true)
                .build();

        var response = MasterServiceResponse.from(buildAssignmentWithType(serviceType));

        assertThat(response.serviceTypeId())
                .as("serviceTypeId must be lifted from the nested ServiceDefinition's ServiceType")
                .isEqualTo(serviceTypeId);
        assertThat(response.serviceTypeNameUk())
                .as("serviceTypeNameUk must be the chosen type's Ukrainian display name")
                .isEqualTo("Манікюр");
        assertThat(response.serviceTypeSlug())
                .as("serviceTypeSlug must be lifted from the nested ServiceDefinition's ServiceType.slug")
                .isEqualTo("manicure");
        assertThat(response.serviceDefinition().serviceTypeSlug())
                .as("the nested ServiceDefinitionResponse must also carry the slug it was lifted from")
                .isEqualTo("manicure");
    }

    @Test
    @DisplayName("serviceTypeId + serviceTypeNameUk + serviceTypeSlug are all null when the ServiceDefinition has no ServiceType")
    void should_mapNullServiceTypeFields_when_serviceDefinitionHasNoServiceType() {
        var response = MasterServiceResponse.from(buildAssignmentWithType(null));

        assertThat(response.serviceTypeId())
                .as("serviceTypeId must be null when no service type was chosen (picker is optional)")
                .isNull();
        assertThat(response.serviceTypeNameUk())
                .as("serviceTypeNameUk must be null when no service type was chosen")
                .isNull();
        assertThat(response.serviceTypeSlug())
                .as("serviceTypeSlug must be null when no service type was chosen (nullable-link case)")
                .isNull();
        assertThat(response.serviceDefinition().serviceTypeSlug())
                .as("the nested ServiceDefinitionResponse slug must also be null")
                .isNull();
    }

    // ── Phase 311 D9: the assignment's OWN band, not the definition's (2026-09-13 audit, H3) ──
    //
    // The RANGE case above sets NO band overrides, so its assertions hold identically before and
    // after MasterServiceResponse.from() was rewritten from `sdResponse.priceType()` to
    // `ServicePricing.ofAssignment(msa)` — it is vacuous with respect to that change. These two
    // cases are the ones that go RED if the rewrite is reverted: they are exactly the shapes where
    // the definition's band and the assignment's resolved band DISAGREE.

    @Test
    @DisplayName("D9: an own FIXED band over a RANGE definition surfaces FIXED with a NULL priceMax "
            + "— the definition's ceiling must not leak onto this master's menu row")
    void should_surfaceOwnFixedBand_when_definitionIsRange() {
        var rangeDefinition = definitionBuilder()
                .priceType(PriceType.RANGE)
                .basePrice(new BigDecimal("600.00"))
                .priceMax(new BigDecimal("1200.00"))
                .build();

        var msa = assignmentWithBand(rangeDefinition, PriceType.FIXED, new BigDecimal("850.00"), null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.priceType())
                .as("the RESOLVED shape is the master's own FIXED, not the definition's RANGE")
                .isEqualTo(PriceType.FIXED);
        assertThat(response.priceMin())
                .as("the floor is the master's own 850, not the definition's 600 base_price")
                .isEqualByComparingTo(new BigDecimal("850.00"));
        assertThat(response.priceMax())
                .as("a FIXED band has no ceiling — the definition's 1200 must not survive")
                .isNull();
        assertThat(response.priceDisplay())
                .as("the rendered band follows the RESOLVED values, not the definition's")
                .isEqualTo("850 ₴");
        assertThat(response.serviceDefinition().priceMax())
                .as("non-vacuity: the nested definition still reports its OWN 1200 ceiling, so the "
                        + "top-level null above can only have come from ofAssignment")
                .isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    @Test
    @DisplayName("D9: an own RANGE band over a FIXED definition surfaces RANGE with the master's "
            + "own floor AND ceiling, neither of which the definition has")
    void should_surfaceOwnRangeBand_when_definitionIsFixed() {
        var fixedDefinition = definitionBuilder()
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .priceMax(null)
                .build();

        var msa = assignmentWithBand(fixedDefinition, PriceType.RANGE,
                new BigDecimal("700.00"), new BigDecimal("1100.00"));

        var response = MasterServiceResponse.from(msa);

        assertThat(response.priceType())
                .as("the RESOLVED shape is the master's own RANGE, not the definition's FIXED")
                .isEqualTo(PriceType.RANGE);
        assertThat(response.priceMin()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(response.priceMax())
                .as("a FIXED definition carries no priceMax at all, so a non-null ceiling here "
                        + "cannot have come from sdResponse")
                .isEqualByComparingTo(new BigDecimal("1100.00"));
        assertThat(response.priceDisplay()).isEqualTo("від 700 до 1100 ₴");
        assertThat(response.effectivePrice())
                .as("effectivePrice tracks the same resolved floor")
                .isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(response.serviceDefinition().priceType())
                .as("non-vacuity: the nested definition still reports its OWN FIXED shape")
                .isEqualTo(PriceType.FIXED);
    }

    // ── Phase 32.1: isFavorite — never populated by the factories, only by withIsFavorite ──

    @Test
    @DisplayName("isFavorite is always null when built from a MasterServiceAssignment — the cached "
            + "factory must never know a caller's identity")
    void should_leaveIsFavoriteNull_when_builtFromAssignment() {
        var msa = buildAssignment(null, null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.isFavorite())
                .as("from() backs the cached ServiceCatalogService.getMasterServices method; "
                        + "isFavorite is decorated per-request by a separate bean, never here")
                .isNull();
    }

    @Test
    @DisplayName("withIsFavorite is a pure positional copy: it sets isFavorite and leaves every "
            + "other field byte-identical (S5 retired fromPublic, this is now the only copier)")
    void should_copyEveryOtherFieldVerbatim_when_withIsFavoriteApplied() {
        var msa = buildAssignment(new BigDecimal("777.00"), 95);
        var original = MasterServiceResponse.from(msa);

        var decorated = original.withIsFavorite(true);

        assertThat(decorated.isFavorite())
                .as("withIsFavorite must set the flag it is named for")
                .isTrue();
        assertThat(decorated)
                .as("a 16-argument positional copy constructor is exactly where two adjacent "
                        + "same-typed arguments get transposed; a recursive comparison catches "
                        + "that, and is automatically extended by any future field")
                .usingRecursiveComparison()
                .ignoringFields("isFavorite")
                .isEqualTo(original);
    }

    // --- helpers ---

    private MasterServiceAssignment buildAssignmentWithType(ServiceType serviceType) {
        var serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure Classic")
                .category("MANICURE")
                .baseDurationMinutes(BASE_DURATION)
                .priceType(PriceType.FIXED)
                .basePrice(BASE_PRICE)
                .priceMax(null)
                .bufferMinutesAfter(10)
                .isActive(true)
                .serviceType(serviceType)
                .build();

        var master = Master.builder()
                .id(UUID.randomUUID())
                .build();

        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(serviceDefinition)
                .priceOverride(null)
                .durationOverrideMinutes(null)
                .isActive(true)
                .build();
    }

    private static ServiceDefinition.ServiceDefinitionBuilder definitionBuilder() {
        return ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Манікюр")
                .category("MANICURE")
                .baseDurationMinutes(BASE_DURATION)
                .bufferMinutesAfter(0)
                .isActive(true);
    }

    private static MasterServiceAssignment assignmentWithBand(ServiceDefinition definition,
                                                              PriceType priceTypeOverride,
                                                              BigDecimal priceOverride,
                                                              BigDecimal priceMaxOverride) {
        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(Master.builder().id(UUID.randomUUID()).build())
                .serviceDefinition(definition)
                .priceTypeOverride(priceTypeOverride)
                .priceOverride(priceOverride)
                .priceMaxOverride(priceMaxOverride)
                .isActive(true)
                .build();
    }

    private MasterServiceAssignment buildAssignment(BigDecimal priceOverride, Integer durationOverride) {
        var serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure Classic")
                .description("Classic manicure service")
                .category("MANICURE")
                .baseDurationMinutes(BASE_DURATION)
                .priceType(PriceType.FIXED)
                .basePrice(BASE_PRICE)
                .priceMax(null)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        var master = Master.builder()
                .id(UUID.randomUUID())
                .build();

        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(serviceDefinition)
                .priceOverride(priceOverride)
                .durationOverrideMinutes(durationOverride)
                .isActive(true)
                .build();
    }
}
