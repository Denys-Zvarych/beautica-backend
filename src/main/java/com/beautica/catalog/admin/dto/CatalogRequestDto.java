package com.beautica.catalog.admin.dto;

import com.beautica.catalog.admin.CatalogRequestKind;
import com.beautica.catalog.admin.CatalogRequestStatus;

import java.time.Instant;

/**
 * B502 — one row of {@code GET /api/v1/internal/catalog/requests}, over both request kinds.
 *
 * @param id                 the category row id ({@code bigint}, as a string) or the suggestion UUID
 * @param name               CATEGORY: the immutable wire name; SERVICE_TYPE: the suggested name
 * @param displayName        CATEGORY only (the editable name); {@code null} for SERVICE_TYPE
 * @param categoryName       SERVICE_TYPE only (the category slug); {@code null} for CATEGORY
 * @param initialServiceName CATEGORY only, may be {@code null}
 * @param description        SERVICE_TYPE only, may be {@code null}
 * @param requesterLabel     the requester's provider name (owned salon's name, else the person's first + last name,
 *                           else their salon's name), {@code "—"} when unknown. Never an email or phone.
 */
public record CatalogRequestDto(
        CatalogRequestKind kind,
        String id,
        String name,
        String displayName,
        String categoryName,
        String initialServiceName,
        String description,
        String requesterLabel,
        Instant createdAt,
        CatalogRequestStatus status
) {}
