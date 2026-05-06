package com.beautica.service.dto;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDefinitionResponseTest {

    @Test
    void should_mapAllFields_when_serviceDefinitionMapped() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        ServiceDefinition sd = ServiceDefinition.builder()
                .id(id)
                .ownerType(OwnerType.SALON)
                .ownerId(ownerId)
                .name("Test")
                .description("Desc")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("50.00"))
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.ownerType()).isEqualTo(OwnerType.SALON);
        assertThat(response.ownerId()).isEqualTo(ownerId);
        assertThat(response.name()).isEqualTo("Test");
        assertThat(response.description()).isEqualTo("Desc");
        assertThat(response.category()).isEqualTo(ServiceCategory.MANICURE);
        assertThat(response.baseDurationMinutes()).isEqualTo(60);
        assertThat(response.basePrice()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(response.bufferMinutesAfter()).isEqualTo(10);
        assertThat(response.isActive()).isTrue();
    }

    @Test
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
    void should_preserveNullBasePrice_when_basePriceIsNull() {
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Test")
                .basePrice(null)
                .baseDurationMinutes(30)
                .isActive(true)
                .build();

        ServiceDefinitionResponse response = ServiceDefinitionResponse.from(sd);

        assertThat(response.basePrice()).isNull();
    }
}
