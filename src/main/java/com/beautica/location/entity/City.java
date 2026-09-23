package com.beautica.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * KATOTTH settlement — the leaf populated place of the locality taxonomy.
 *
 * <p>The table is named {@code cities} for historical reasons: Phase 10.2 seeded only
 * category-M cities, and Phase 325 widened the same table to every settlement (місто, селище,
 * село) rather than adding a parallel {@code settlements} table that would drift from the
 * taxonomy {@code users.city_id} and {@code salons.city_id} already point at. Read
 * {@link #settlementType} for what a given row actually is.
 *
 * <p><b>Occupied settlements are never present.</b> Phase 324's exclusion set is applied to the
 * import SOURCE (see {@code V171__import_free_settlements}), not stored as a flag — there is
 * deliberately no {@code occupationStatus} field to consult or forget.
 *
 * <p>Read-only reference data from the application's perspective — rows are
 * written only by Flyway seed migrations (Phase 10.2, Phase 325). No public setters.
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
                // NOTE: idx_cities_major_name_uk (V170) is a PARTIAL index
                // — `ON cities (name_uk) WHERE is_major` — which @Index cannot express.
                // Declaring it here without the predicate would describe a different,
                // 25 698-entry index and mislead the next reader, so it is documented
                // rather than mirrored.
                //
                // Same for idx_cities_oblast_city_name (V172):
                // `ON cities (oblast_id, name_uk) WHERE settlement_type = 'CITY'`.
                // It backs CityRepository#findByOblastIdAndSettlementTypeOrderByNameUkAsc
                // — 353 entries instead of 25 698, index-only, no sort, no filter.
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
    @Size(max = 20)
    private String katotthCode;

    /** Canonical Ukrainian name (e.g. "Київ"). */
    @Column(name = "name_uk", nullable = false, length = 255)
    @Size(max = 255)
    private String nameUk;

    /** Official English transliteration (e.g. "Kyiv"). */
    @Column(name = "name_en", nullable = false, length = 255)
    @Size(max = 255)
    private String nameEn;

    /**
     * What kind of populated place this row is, from the KATOTTH category letter.
     *
     * <p>{@code STRING}, never {@code ORDINAL}: the column is a {@code VARCHAR(20)} guarded by
     * {@code chk_cities_settlement_type}, and an ordinal mapping would make reordering the enum
     * silently rewrite the meaning of every row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false, length = 20)
    @NotNull
    private SettlementType settlementType;

    /**
     * Whether this settlement appears in the "biggest places" list shown before the user types.
     *
     * <p>Curated by hand — KATOTTH carries no population data — in
     * {@code scripts/locality/build_settlement_import.py}: the 23 serviceable oblast centres plus
     * the 27 next-largest cities, 50 in total.
     */
    @Column(name = "is_major", nullable = false)
    private boolean major;

    /**
     * Static factory for a plain city — {@link SettlementType#CITY}, not flagged major.
     *
     * <p>Kept at its original arity so the Phase 10.1 call sites keep compiling. Every row it can
     * describe genuinely IS a city, so the defaulted {@code settlementType} states a fact rather
     * than papering over a missing one. Use {@link #of(Oblast, String, String, String,
     * SettlementType, boolean)} for anything else.
     *
     * @param oblast      parent oblast
     * @param katotthCode official KATOTTH settlement code
     * @param nameUk      canonical Ukrainian name
     * @param nameEn      English transliteration
     * @return a new, unpersisted {@code City} instance
     */
    public static City of(Oblast oblast, String katotthCode, String nameUk, String nameEn) {
        return of(oblast, katotthCode, nameUk, nameEn, SettlementType.CITY, false);
    }

    /**
     * Static factory — preferred construction path for service/seed code.
     *
     * @param oblast         parent oblast
     * @param katotthCode    official KATOTTH settlement code
     * @param nameUk         canonical Ukrainian name
     * @param nameEn         English transliteration
     * @param settlementType kind of populated place
     * @param major          whether it appears in the pre-typing "biggest places" list
     * @return a new, unpersisted {@code City} instance
     */
    public static City of(Oblast oblast, String katotthCode, String nameUk, String nameEn,
                          SettlementType settlementType, boolean major) {
        return City.builder()
                .oblast(oblast)
                .katotthCode(katotthCode)
                .nameUk(nameUk)
                .nameEn(nameEn)
                .settlementType(settlementType)
                .major(major)
                .build();
    }
}
