package com.beautica.salon.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Spring Data interface projection for the salon discovery search path.
 *
 * <p>Only the five columns consumed by
 * {@link com.beautica.search.service.SearchService#toSalonSearchResult} are
 * fetched: {@code id}, {@code name}, {@code city_id}, {@code district_id}, and
 * {@code avatar_url}. The projection is backed by the
 * {@code @Query}-annotated repository methods in {@link SalonRepository} and
 * is translated by Hibernate into a {@code SELECT} that references exactly
 * those columns — none of the remaining {@code Salon} entity columns
 * ({@code description}, {@code city}, {@code region}, {@code address},
 * {@code street}, {@code building_no}, {@code location_note}, {@code phone},
 * {@code instagram_url}, {@code is_active}, {@code is_primary},
 * {@code owner_id}, {@code created_at}, {@code updated_at}) are loaded.
 *
 * <p>This replaces the earlier {@code Page<Salon>} return type on the three
 * {@code findActive*}/{@code findByIsActiveTrue} search-path methods (LOW PERF
 * finding: "Loads Salon entity then maps via Page#map"). No full entity
 * hydration, no lazy-proxy creation for the {@code owner} association.
 *
 * <p>Getters follow Spring Data's interface-projection naming convention
 * (camelCase matching the JPQL alias / entity field name):
 * <ul>
 *   <li>{@code getId()} → {@code s.id}</li>
 *   <li>{@code getName()} → {@code s.name}</li>
 *   <li>{@code getCityId()} → {@code s.cityId} ({@code city_id} column)</li>
 *   <li>{@code getDistrictId()} → {@code s.districtId} ({@code district_id} column)</li>
 *   <li>{@code getAvatarUrl()} → {@code s.avatarUrl} ({@code avatar_url} column)</li>
 *   <li>{@code getPriceMin()} / {@code getPriceMax()} → price-range aggregates
 *       over the salon's masters' active services (Phase 19.7, decision 5).
 *       Computed in a single {@code LEFT JOIN LATERAL} pass per salon (both
 *       aggregates in one nested-loop probe — Phase 19.7 PERF MEDIUM, replacing
 *       the earlier two byte-identical correlated sub-queries), not columns on
 *       the {@code salons} table; both are {@code null} when the salon has no
 *       active, priced services (LATERAL over an empty set yields NULL
 *       aggregates).</li>
 * </ul>
 */
public interface SalonSearchProjection {

    UUID getId();

    String getName();

    UUID getCityId();

    UUID getDistrictId();

    String getAvatarUrl();

    /**
     * Lowest service-price floor ({@code MIN(base_price)}) across the salon's
     * masters' active services, scoped to the searched category when present.
     * {@code null} when the salon has no active, priced services.
     */
    BigDecimal getPriceMin();

    /**
     * Highest service-price ceiling across the salon's masters' active services
     * ({@code MAX(price_max)} for {@code RANGE} services, else {@code base_price}
     * for {@code FIXED}), scoped to the searched category when present.
     * {@code null} when the salon has no active, priced services.
     */
    BigDecimal getPriceMax();
}
