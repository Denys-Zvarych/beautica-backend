package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * B541 (SVC-60) — {@code app.catalog.icon-required} ({@code APP_CATALOG_ICON_REQUIRED}, default {@code false} in every
 * profile): when {@code true}, approving a category request, un-hiding a category and the internal create without an
 * {@code svg} are refused with 409 {@code CATEGORY_ICON_REQUIRED} for a category with no icon.
 *
 * <p>Ships OFF so B539–B541 deploy in one release: per env, run the B540 backfill, confirm {@code missing = []} and
 * the read-only SQL check returns 0 rows, then set the variable to {@code true} (runbook: phase 541 doc and
 * {@code docs/release/railway-env-vars.md}).
 *
 * @param iconRequired the gate switch
 */
@ConfigurationProperties(prefix = "app.catalog")
public record CatalogIconProperties(@DefaultValue("false") boolean iconRequired) {}
