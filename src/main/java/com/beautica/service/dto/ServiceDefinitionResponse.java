package com.beautica.service.dto;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;

import java.math.BigDecimal;
import java.util.UUID;

public record ServiceDefinitionResponse(
        UUID id,
        OwnerType ownerType,
        UUID ownerId,
        String name,
        String description,
        ServiceCategory category,
        int baseDurationMinutes,
        BigDecimal basePrice,
        int bufferMinutesAfter,
        boolean isActive
) {
    public static ServiceDefinitionResponse from(ServiceDefinition sd) {
        return new ServiceDefinitionResponse(
                sd.getId(),
                sd.getOwnerType(),
                sd.getOwnerId(),
                sd.getName(),
                sd.getDescription(),
                sd.getCategory(),
                sd.getBaseDurationMinutes(),
                sd.getBasePrice(),
                sd.getBufferMinutesAfter(),
                sd.isActive()
        );
    }
}
