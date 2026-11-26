package com.beautica.catalog.icon.dto;

import java.util.List;

/**
 * B540 — {@code data} of {@code POST /api/v1/internal/catalog/category-icons/backfill}: wire names, each sorted.
 *
 * @param set     got their bundled icon in this call
 * @param skipped already had an icon (never overwritten)
 * @param missing PENDING / APPROVED, no icon and no bundled match — the operator uploads one before enabling the gate
 */
public record CategoryIconBackfillResponse(List<String> set, List<String> skipped, List<String> missing) {}
