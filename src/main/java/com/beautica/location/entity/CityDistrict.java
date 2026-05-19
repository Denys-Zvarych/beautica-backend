package com.beautica.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * KATOTTH urban district — район міста, KATOTTH category "B".
 *
 * <p>Represents the intra-city administrative subdivisions: Kyiv (10),
 * Kharkiv (9), Lviv (6), Odesa (4), Dnipro (8), Zaporizhzhia, Kryvyi Rih,
 * etc. These are <em>not</em> oblast-level raions.
 *
 * <p>Where a city has urban districts, the district is the primary
 * discovery/search unit (Phase 10.5). Cities without urban districts have no
 * rows in this table; the {@code CityDistrictRepository#existsByCityId} predicate
 * is the canonical "does this city have urban districts?" check (Phase 10.6).
 *
 * <p>Read-only reference data from the application's perspective — rows are
 * written only by Flyway seed migrations (Phase 10.2). No public setters.
 */
@Entity
@Table(
        name = "city_districts",
        indexes = {
                // B-tree index — drives the "urban districts by city" picker query
                // and the district-primary search filter in Phase 10.5.
                @Index(name = "idx_city_districts_city_id", columnList = "city_id, name_uk"),
                // UNIQUE on katotth_code — mirrored so ddl-auto=validate reports drift.
                @Index(name = "uq_city_districts_katotth_code", columnList = "katotth_code")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = lombok.AccessLevel.PACKAGE)
@Builder
public class CityDistrict {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The city this urban district belongs to.
     * LAZY to avoid pulling the full city graph on every district query.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    /**
     * Official KATOTTH category "B" code. UNIQUE and NOT NULL;
     * used as the idempotent seed key.
     */
    @Column(name = "katotth_code", nullable = false, unique = true, length = 20)
    private String katotthCode;

    /** Canonical Ukrainian district name (e.g. "Голосіївський район"). */
    @Column(name = "name_uk", nullable = false, length = 255)
    private String nameUk;

    /** Official English transliteration (e.g. "Holosiivskyi district"). */
    @Column(name = "name_en", nullable = false, length = 255)
    private String nameEn;

    /**
     * Static factory — preferred construction path for service/seed code.
     *
     * @param city        parent city
     * @param katotthCode official KATOTTH category "B" code
     * @param nameUk      canonical Ukrainian district name
     * @param nameEn      English transliteration
     * @return a new, unpersisted {@code CityDistrict} instance
     */
    public static CityDistrict of(City city, String katotthCode, String nameUk, String nameEn) {
        return CityDistrict.builder()
                .city(city)
                .katotthCode(katotthCode)
                .nameUk(nameUk)
                .nameEn(nameEn)
                .build();
    }
}
