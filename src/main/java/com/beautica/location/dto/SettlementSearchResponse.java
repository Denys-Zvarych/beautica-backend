package com.beautica.location.dto;

import com.beautica.location.entity.SettlementType;
import com.beautica.location.repository.CityRepository.SettlementSearchRow;

import java.util.UUID;

/**
 * One row of the settlement autocomplete behind the {@code permitAll}
 * {@code GET /api/v1/settlements} (Phase 326) — the surface that replaces the «Область» +
 * «Місто» cascade for address entry.
 *
 * <p><b>{@code settlementId} is the contract</b> (phase-326 D6). The client stores this id and
 * submits it as {@code cityId}; it never round-trips the name, which is neither unique (99
 * «Іванівка») nor stable.
 *
 * <p><b>{@code oblastNameUk} is the disambiguator</b> (phase-326 D5). Duplicate settlement names
 * are the norm, not the exception: 99 «Іванівка» across 20 oblasts, «Львів» is also two villages,
 * «Київ» is also a village in Миколаївська. The row is meaningless without its oblast label, so
 * this field is never optional.
 *
 * <p>It carries the oblast's name EXACTLY as {@code oblasts.name_uk} stores it («Полтавська»,
 * «Київ») — the server does not append «область». Kyiv is an oblast-EQUIVALENT whose oblast row is
 * named «Київ», so a server-side «… область» suffix would render «Київ, Київ область». Composing
 * the user-visible label is the client's job, and the grammatical form it needs depends on where
 * the label is shown.
 *
 * <p><b>No {@code oblastId}, no {@code katotthCode}</b> (§I). This is an UNAUTHENTICATED response,
 * reached during registration before any token exists. {@code settlementId} is the one identifier
 * the client demonstrably needs; every other internal id is withheld rather than shipped "in case".
 * The oblast is surfaced as a human label because that is its purpose here — the caller is picking
 * a place, not navigating a taxonomy. (The retiring cascade's {@code CityResponse} does expose
 * {@code oblastId} and {@code katotthCode}; this record is not modelled on it, and widening this
 * one to match would be a regression, not consistency.)
 *
 * <p><b>{@code hromadaNameUk} is the SECOND disambiguator, and its nullability IS the contract</b>
 * (phase-327 D3). The oblast label alone resolves only 76.25 % of the 25 697 imported rows: 2 234
 * name+oblast groups covering 6 103 rows collide, worst «Миколаївка, Харківська» ×15. Adding the
 * hromada takes the residue to 111 groups / 226 rows (0.88 %). The raion does not — it leaves
 * 9.72 % broken for the same cost, and hromada is a subset of raion (D1).
 *
 * <p>The field is populated ONLY for rows whose name+oblast pair is ambiguous, so the client
 * composes «‹назва›, ‹область›» on null and «‹назва›, ‹громада›, ‹область›» on non-null, and holds
 * no ambiguity logic of its own. Like {@code oblastNameUk} it carries the BARE adjective
 * («Шишацька»); the server does not append «громада» and does not concatenate the label, because
 * the grammatical form depends on where the label is shown.
 *
 * <p>226 rows stay ambiguous even with the hromada and that is accepted (phase-327 D6): KATOTTH
 * exposes no level below the settlement, so there is nothing further to add.
 *
 * @param settlementId   surrogate PK — the value the client stores and submits
 * @param nameUk         canonical Ukrainian settlement name
 * @param settlementType what kind of populated place this is (місто / селище / село), so the
 *                       picker can render a secondary marker and the user can tell «Львів» the
 *                       city from «Львів» the village
 * @param oblastNameUk   Ukrainian name of the parent oblast, as stored
 * @param hromadaNameUk  bare hromada adjective, or {@code null} when the oblast already
 *                       disambiguates this row — which it does for 76 % of them
 */
public record SettlementSearchResponse(
        UUID settlementId,
        String nameUk,
        SettlementType settlementType,
        String oblastNameUk,
        String hromadaNameUk
) {

    /**
     * Maps one native-query projection row.
     *
     * <p>The {@code settlement_type} column is a {@code VARCHAR(20)} guarded by
     * {@code chk_cities_settlement_type}, so {@link SettlementType#valueOf(String)} is total over
     * every value the database can hold; a value it cannot parse means the CHECK constraint and
     * this enum have diverged, which must fail loudly rather than be defaulted away.
     *
     * @param row a projection row from {@code CityRepository}
     * @return the public response row
     */
    public static SettlementSearchResponse from(SettlementSearchRow row) {
        return new SettlementSearchResponse(
                row.getSettlementId(),
                row.getNameUk(),
                SettlementType.valueOf(row.getSettlementType()),
                row.getOblastNameUk(),
                row.getHromadaNameUk());
    }
}
