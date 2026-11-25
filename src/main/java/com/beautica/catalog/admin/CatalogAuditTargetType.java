package com.beautica.catalog.admin;

/**
 * What a {@code catalog_admin_audit} row is about (B501). Mirrors {@code chk_catalog_admin_audit_target_type} (V264):
 * a new constant needs a migration widening that CHECK in the same change.
 */
public enum CatalogAuditTargetType {
    CATEGORY,
    SERVICE_TYPE,
    CATEGORY_REQUEST,
    TYPE_SUGGESTION
}
