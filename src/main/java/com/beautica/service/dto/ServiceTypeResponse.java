package com.beautica.service.dto;

import com.beautica.service.entity.ServiceType;

import java.util.UUID;

public record ServiceTypeResponse(UUID id, UUID categoryId, String nameUk, String nameEn, String slug) {

    public static ServiceTypeResponse from(ServiceType t) {
        return new ServiceTypeResponse(
                t.getId(),
                t.getCategory().getId(),
                t.getNameUk(),
                t.getNameEn(),
                t.getSlug());
    }
}
