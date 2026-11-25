package com.beautica.catalog.admin;

/** B502 — the two request kinds the catalog portal lists and edits (path segment {@code /requests/{kind}/{id}}). */
public enum CatalogRequestKind {
    /** A {@code platform_categories} row created by a category request; id is the row's {@code bigint}. */
    CATEGORY,
    /** A {@code service_type_suggestion} row; id is the row's UUID. */
    SERVICE_TYPE
}
