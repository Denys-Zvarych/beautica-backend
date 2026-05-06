package com.beautica.service.dto;

import com.beautica.service.entity.MasterServiceAssignment;

import java.math.BigDecimal;
import java.util.UUID;

public record MasterServiceResponse(
        UUID id,
        UUID masterId,
        ServiceDefinitionResponse serviceDefinition,
        BigDecimal priceOverride,
        Integer durationOverrideMinutes,
        BigDecimal effectivePrice,
        int effectiveDurationMinutes,
        boolean isActive
) {
    public static MasterServiceResponse from(MasterServiceAssignment msa) {
        var sdResponse = ServiceDefinitionResponse.from(msa.getServiceDefinition());

        var effectivePrice = msa.getPriceOverride() != null
                ? msa.getPriceOverride()
                : msa.getServiceDefinition().getBasePrice();

        var effectiveDuration = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes()
                : msa.getServiceDefinition().getBaseDurationMinutes();

        return new MasterServiceResponse(
                msa.getId(),
                msa.getMaster().getId(),
                sdResponse,
                msa.getPriceOverride(),
                msa.getDurationOverrideMinutes(),
                effectivePrice,
                effectiveDuration,
                msa.isActive()
        );
    }
}
