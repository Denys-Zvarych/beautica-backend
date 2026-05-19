package com.beautica.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * KATOTTH oblast-level administrative unit.
 *
 * <p>Read-only reference data from the application's perspective — rows are
 * written only by Flyway seed migrations (Phase 10.2). No public setters;
 * construction via the static factory or the Lombok builder.
 *
 * <p>The {@code katotth_code} column is the stable external business key
 * (UNIQUE NOT NULL). The surrogate UUID PK is used for FK joins from
 * {@code cities} and, in Phase 10.3, from {@code users} / {@code salons}.
 */
@Entity
@Table(
        name = "oblasts",
        indexes = {
                // UNIQUE on katotth_code is a DB constraint (uq_oblasts_katotth_code);
                // mirrored here so ddl-auto=validate reports drift if the index is dropped.
                @Index(name = "uq_oblasts_katotth_code", columnList = "katotth_code")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = lombok.AccessLevel.PACKAGE)
@Builder
public class Oblast {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Official KATOTTH oblast code (e.g. "UA80000000000093317" for Kyiv City).
     * UNIQUE and NOT NULL; used as the idempotent seed key.
     */
    @Column(name = "katotth_code", nullable = false, unique = true, length = 20)
    private String katotthCode;

    /** Canonical Ukrainian name (e.g. "Київська область"). */
    @Column(name = "name_uk", nullable = false, length = 255)
    private String nameUk;

    /** Official English transliteration (e.g. "Kyiv Oblast"). */
    @Column(name = "name_en", nullable = false, length = 255)
    private String nameEn;

    /**
     * Static factory — preferred construction path for service/seed code.
     *
     * @param katotthCode official KATOTTH oblast code
     * @param nameUk      canonical Ukrainian name
     * @param nameEn      English transliteration
     * @return a new, unpersisted {@code Oblast} instance
     */
    public static Oblast of(String katotthCode, String nameUk, String nameEn) {
        return Oblast.builder()
                .katotthCode(katotthCode)
                .nameUk(nameUk)
                .nameEn(nameEn)
                .build();
    }
}
