package com.beautica.service.dto;

import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
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
    void should_computeEffectiveDurationAsOverride_when_durationOverrideSet() {
        var msa = buildAssignment(OVERRIDE_PRICE, OVERRIDE_DURATION);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectiveDurationMinutes()).isEqualTo(OVERRIDE_DURATION);
    }

    @Test
    void should_computeEffectiveDurationAsBase_when_noOverride() {
        var msa = buildAssignment(null, null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectiveDurationMinutes()).isEqualTo(BASE_DURATION);
    }

    @Test
    void should_computeEffectivePriceAsOverride_when_priceOverrideSet() {
        var msa = buildAssignment(OVERRIDE_PRICE, null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectivePrice()).isEqualByComparingTo(OVERRIDE_PRICE);
    }

    @Test
    void should_computeEffectivePriceAsBasePrice_when_noOverride() {
        var msa = buildAssignment(null, null);

        var response = MasterServiceResponse.from(msa);

        assertThat(response.effectivePrice()).isEqualByComparingTo(BASE_PRICE);
    }

    @Test
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
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(BASE_DURATION)
                .basePrice(BASE_PRICE)
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
    void should_returnNullEffectivePrice_when_noPriceSetAnywhere() {
        var serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure Classic")
                .baseDurationMinutes(BASE_DURATION)
                .basePrice(null)
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

    // --- helpers ---

    private MasterServiceAssignment buildAssignment(BigDecimal priceOverride, Integer durationOverride) {
        var serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure Classic")
                .description("Classic manicure service")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(BASE_DURATION)
                .basePrice(BASE_PRICE)
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
