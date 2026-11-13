package com.beautica.notification.service;

/**
 * Phase 491 — which self-service request a {@code REQUEST_DECIDED} outbox row is about. Written into the
 * payload as its {@link #name()}; selects the entity re-read at drain time and the e-mail copy.
 */
public enum RequestKind {

    /** A {@code platform_categories} row requested by a provider (id = BIGINT). */
    CATEGORY_REQUEST,

    /** A {@code service_type_suggestions} row (id = UUID). */
    SERVICE_TYPE_SUGGESTION
}
