package com.beautica.location.entity;

/**
 * Kind of populated place, mirroring the KATOTTH category letter of the row it was imported from
 * (Phase 325).
 *
 * <p>Persisted as a {@code VARCHAR(20)} on {@code cities.settlement_type}, guarded by the
 * {@code chk_cities_settlement_type} CHECK constraint added in {@code V170}. Stored by name, never
 * by ordinal — reordering this enum must not silently rewrite 25 000 rows.
 */
public enum SettlementType {

    /** KATOTTH category {@code M} — місто. */
    CITY,

    /**
     * KATOTTH category {@code T} — селище міського типу.
     *
     * <p>Declared but currently unpopulated: the classifier version this taxonomy was imported
     * from (valid_on 2025-07-02) contains zero category-T rows, because the
     * селище-міського-типу category was retired and those places now carry {@code X}. The value is
     * kept so a later classifier that reintroduces {@code T} imports without a schema change.
     */
    TOWN,

    /** KATOTTH category {@code C} — село. */
    VILLAGE,

    /** KATOTTH category {@code X} — селище. */
    SETTLEMENT
}
