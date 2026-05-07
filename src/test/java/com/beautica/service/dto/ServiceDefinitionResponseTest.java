package com.beautica.service.dto;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceDefinitionResponseTest {

    @Test
    void should_mapAllFields_when_serviceDefinitionMapped() {
        UUID id = UUID.randomUUID();

        ServiceDefinition sd = ServiceDefinition.builder()
                .id(id)
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
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
        assertThat(response.name()).isEqualTo("Test");
        assertThat(response.description()).isEqualTo("Desc");
        assertThat(response.category()).isEqualTo(ServiceCategory.MANICURE);
        assertThat(response.baseDurationMinutes()).isEqualTo(60);
        assertThat(response.basePrice()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(response.bufferMinutesAfter()).isEqualTo(10);
        assertThat(response.isActive()).isTrue();
        assertThat(response.serviceTypeId())
                .as("serviceTypeId must be null when no ServiceType is linked")
                .isNull();
        assertThat(response.serviceTypeNameUk())
                .as("serviceTypeNameUk must be null when no ServiceType is linked")
                .isNull();
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

    @Test
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
