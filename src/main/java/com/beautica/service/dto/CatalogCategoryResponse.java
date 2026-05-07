package com.beautica.service.dto;

import com.beautica.service.entity.CatalogCategory;

import java.util.UUID;

public record CatalogCategoryResponse(UUID id, String nameUk, String nameEn, int sortOrder) {

    public static CatalogCategoryResponse from(CatalogCategory c) {
        return new CatalogCategoryResponse(c.getId(), c.getNameUk(), c.getNameEn(), c.getSortOrder());
    }
}
