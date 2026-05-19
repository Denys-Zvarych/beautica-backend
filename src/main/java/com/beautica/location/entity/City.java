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
 * KATOTTH city-level administrative unit (місто).
 *
 * <p>Read-only reference data from the application's perspective — rows are
 * written only by Flyway seed migrations (Phase 10.2). No public setters.
 *
 * <p>A city is the leaf locality unit for cities that have no urban districts
 * ({@link CityDistrict}). Where urban districts exist (e.g. Kyiv, Kharkiv,
 * Lviv), the district is the primary discovery/search unit (Phase 10.5).
 */
@Entity
@Table(
        name = "cities",
        indexes = {
                // B-tree index — drives the "cities by oblast" cascading-picker query.
                @Index(name = "idx_cities_oblast_id", columnList = "oblast_id, name_uk"),
                // UNIQUE on katotth_code — mirrored so ddl-auto=validate reports drift.
                @Index(name = "uq_cities_katotth_code", columnList = "katotth_code")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = lombok.AccessLevel.PACKAGE)
@Builder
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The oblast this city belongs to.
     * LAZY to avoid pulling the whole oblast on every city query.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "oblast_id", nullable = false)
    private Oblast oblast;

    /**
     * Official KATOTTH city code. UNIQUE and NOT NULL; used as the idempotent seed key.
     */
    @Column(name = "katotth_code", nullable = false, unique = true, length = 20)
    private String katotthCode;

    /** Canonical Ukrainian name (e.g. "Київ"). */
    @Column(name = "name_uk", nullable = false, length = 255)
    private String nameUk;

    /** Official English transliteration (e.g. "Kyiv"). */
    @Column(name = "name_en", nullable = false, length = 255)
    private String nameEn;

    /**
     * Static factory — preferred construction path for service/seed code.
     *
     * @param oblast      parent oblast
     * @param katotthCode official KATOTTH city code
     * @param nameUk      canonical Ukrainian name
     * @param nameEn      English transliteration
     * @return a new, unpersisted {@code City} instance
     */
    public static City of(Oblast oblast, String katotthCode, String nameUk, String nameEn) {
        return City.builder()
                .oblast(oblast)
                .katotthCode(katotthCode)
                .nameUk(nameUk)
                .nameEn(nameEn)
                .build();
    }
}
