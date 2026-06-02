package com.beautica.service.dto;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceDefinitionResponseTest {

    @Test
    @DisplayName("all fields are mapped correctly when a fully-populated ServiceDefinition is converted")
    void should_mapAllFields_when_serviceDefinitionMapped() {
        UUID id = UUID.randomUUID();

        ServiceDefinition sd = ServiceDefinition.builder()
                .id(id)
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Test")
                .description("Desc")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("50.00"))
                .priceMax(null)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Test");
        assertThat(response.description()).isEqualTo("Desc");
        assertThat(response.category()).isEqualTo("MANICURE");
        assertThat(response.baseDurationMinutes()).isEqualTo(60);
        assertThat(response.priceMin()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(response.priceMax()).isNull();
        assertThat(response.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(response.priceDisplay()).isEqualTo("50 грн");
        assertThat(response.bufferMinutesAfter()).isEqualTo(10);
        assertThat(response.isActive()).isTrue();
        assertThat(response.serviceTypeId())
                .as("serviceTypeId must be null when no ServiceType is linked")
                .isNull();
        assertThat(response.serviceTypeNameUk())
                .as("serviceTypeNameUk must be null when no ServiceType is linked")
                .isNull();
        assertThat(response.photoUrl())
                .as("photoUrl must be null when no photo has been set")
                .isNull();
    }

    // ── MEDIUM-1: RANGE pricing fields mapped correctly ───────────────────────

    @Test
    @DisplayName("RANGE priceMin, priceMax, priceType and priceDisplay are mapped when ServiceDefinition has RANGE pricing")
    void should_mapAllPriceFields_when_rangeServiceDefinitionMapped() {
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Range Haircut")
                .description("Flexible pricing haircut")
                .category("HAIRCUT")
                .baseDurationMinutes(45)
                .priceType(PriceType.RANGE)
                .basePrice(new BigDecimal("500.00"))
                .priceMax(new BigDecimal("800.00"))
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.priceType())
                .as("priceType must be RANGE")
                .isEqualTo(PriceType.RANGE);
        assertThat(response.priceMin())
                .as("priceMin maps base_price (500) — the canonical RANGE floor")
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(response.priceMax())
                .as("priceMax maps price_max (800) — the RANGE ceiling")
                .isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(response.priceDisplay())
                .as("priceDisplay must use PriceDisplayFormatter RANGE format — whole hryvnia, no .00")
                .isEqualTo("від 500 до 800 грн");
    }

    @Test
    @DisplayName("description is null in response when service definition has no description")
    void should_preserveNullDescription_when_descriptionIsNull() {
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Test")
                .description(null)
                .baseDurationMinutes(30)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.description()).isNull();
    }

    @Test
    @DisplayName("category is null in response when service definition has no category")
    void should_preserveNullCategory_when_categoryIsNull() {
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Test")
                .category(null)
                .baseDurationMinutes(30)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.category()).isNull();
    }

    @Test
    @DisplayName("priceMin and priceDisplay are null in response when service definition has no base price")
    void should_preserveNullBasePrice_when_basePriceIsNull() {
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Test")
                .priceType(null)
                .basePrice(null)
                .baseDurationMinutes(30)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.priceMin()).isNull();
        assertThat(response.priceDisplay()).isNull();
    }

    @Test
    @DisplayName("service type id and name are mapped when a ServiceType is linked to the definition")
    void should_mapServiceTypeFields_when_serviceTypeIsSet() {
        UUID serviceTypeId = UUID.randomUUID();

        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.getId()).thenReturn(serviceTypeId);
        when(serviceType.getNameUk()).thenReturn("Манікюр");

        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure")
                .baseDurationMinutes(60)
                .isActive(true)
                .build();
        sd.setServiceType(serviceType);

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.serviceTypeId()).isEqualTo(serviceTypeId);
        assertThat(response.serviceTypeNameUk()).isEqualTo("Манікюр");
    }

    @Test
    @DisplayName("service type id and name are null when no ServiceType is linked to the definition")
    void should_returnNullServiceTypeFields_when_serviceTypeIsNull() {
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure")
                .baseDurationMinutes(60)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.serviceTypeId()).isNull();
        assertThat(response.serviceTypeNameUk()).isNull();
    }
}
