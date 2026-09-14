package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;

import java.math.BigDecimal;

/**
 * The single coherence check for a master's own price band (Phase 311 D2/D3, amended by Phase
 * 312 D8).
 *
 * <h2>The floor/ceiling comparison is STRICT ({@code >}) — deliberately NOT the DB CHECK's
 * {@code >=}</h2>
 * {@code chk_service_def_price_mode} (V67) and {@code chk_master_service_price_mode} (V165) both
 * use {@code >=}, and V67 states why in its own comment: <i>"chk_service_def_price_mode
 * intentionally uses &gt;= (not &gt;) … the application validator enforces strict &gt; at the
 * service layer."</i> The DB CHECK is the tolerant outer backstop; the application layer is
 * strict. This class is the application layer, so it is strict, exactly like
 * {@link com.beautica.service.validation.ServicePriceValidator}.
 *
 * <p>Consequence: a degenerate RANGE band (floor == ceiling) is <b>storable but not writable</b> —
 * the database would accept such a row, no API write path will produce one. {@code RANGE x–x} is
 * {@code FIXED x} spelled a second way, and the flexible-pricing model has exactly one canonical
 * spelling for a single price ({@code FIXED}, with {@code base_price} as the canonical floor).
 * Before "aligning" this comparison back to the CHECK, read Phase 312 D8: copying the CHECK's
 * tolerance into Java is the defect D8 fixed, not the design.
 *
 * <h2>Why this exists (REUSE-FIRST) — and which paths actually call it</h2>
 * <b>Two</b> write paths validate a master's band through this class, each via an
 * {@code @AssertTrue} on its request record:
 * <ul>
 *   <li>{@code PATCH .../masters/{masterId}/services/{serviceDefId}} —
 *       {@link UpdateMasterServiceBandRequest#isBandLegal()} (Phase 311)</li>
 *   <li>{@code POST .../masters/{masterId}/services} —
 *       {@link AssignServiceToMasterRequest#isBandLegal()} (Phase 312)</li>
 * </ul>
 *
 * <p>The third write path, {@code POST .../masters/{masterId}/services/bulk}, does <b>NOT</b>
 * return its {@code 400} from this class, and earlier javadoc here claiming it was "wired into
 * PATCH, single-assign and bulk" was false. A bulk item is a <i>definition-shaped</i> payload
 * validated by the pre-existing class-level {@code @ServicePriceValid} on
 * {@link BulkServiceItemRequest}, whose field convention differs from this class's: it reads
 * {@code price} for FIXED and {@code priceMin}/{@code priceMax} for RANGE, where this class reads
 * {@code price} as the floor in both modes. {@code ServiceCatalogService#resolveBulkReuseBand}
 * translates that payload into a band and then re-checks it here as an
 * {@code IllegalStateException} assertion, so the two truth tables cannot silently drift — but
 * bean validation on the item, not this class, is what a bulk caller's {@code 400} comes from.
 *
 * <p>The coherence rule for a master's band is still written exactly ONCE, here; copying the
 * comparisons into a request DTO is the fork that produced the original single-assign validation
 * gap (Phase 312 background) and must not be repeated.
 *
 * <h2>D2 + D3's table</h2>
 * <table border="1">
 *   <caption>Legal band shapes</caption>
 *   <tr><th>{@code priceType}</th><th>{@code price} (floor)</th><th>{@code priceMax} (ceiling)</th><th>Legal?</th></tr>
 *   <tr><td>{@code null}</td><td>{@code null}</td><td>{@code null}</td><td>yes — Inherited</td></tr>
 *   <tr><td>{@code FIXED}</td><td>required</td><td>must be absent</td><td>yes</td></tr>
 *   <tr><td>{@code RANGE}</td><td>required</td><td>required, strictly {@code >} floor</td><td>yes</td></tr>
 *   <tr><td>{@code RANGE}</td><td>required</td><td>{@code ==} floor (degenerate)</td><td>no — use {@code FIXED} (D8)</td></tr>
 *   <tr><td>anything else (partial)</td><td>—</td><td>—</td><td>no</td></tr>
 * </table>
 *
 * <p><b>There is deliberately NO envelope check against a salon definition's band here (D3).</b>
 * A master's own band may sit entirely outside, above, below, or of a different shape than the
 * definition's — that is the capability Phase 311 exists to add. Adding a containment check
 * against {@code ServiceDefinition} would reintroduce the rejected guard under a new name; see
 * Phase 311 D3's justification before "fixing" this class to compare against a definition.
 *
 * <p>This class is a pure function over three values — it takes no entity and performs no I/O, so
 * it is equally callable from a record's {@code @AssertTrue} method (bean validation, the standard
 * {@code MethodArgumentNotValidException} 400 envelope — no typed error code, Phase 311 D3) and
 * from plain service-layer code.
 */
public final class MasterServiceBand {

    private MasterServiceBand() {
    }

    /**
     * Returns {@code true} iff {@code (priceType, price, priceMax)} is a legal band per D2/D3's
     * table: either fully absent (Inherited) or fully, coherently specified (own band). A partial
     * band — any one or two of the three fields set — is never legal.
     *
     * @param priceType the band's pricing mode; {@code null} means "no band" and is only legal
     *                   when {@code price} and {@code priceMax} are also {@code null}
     * @param price     the band's floor; required and must be positive for both FIXED and RANGE
     * @param priceMax  the band's ceiling; must be {@code null} for FIXED, required and strictly
     *                  {@code > price} for RANGE
     */
    public static boolean isLegal(PriceType priceType, BigDecimal price, BigDecimal priceMax) {
        if (priceType == null) {
            return price == null && priceMax == null;
        }
        return switch (priceType) {
            case FIXED -> price != null
                    && price.compareTo(BigDecimal.ZERO) > 0
                    && priceMax == null;
            case RANGE -> price != null
                    && priceMax != null
                    && price.compareTo(BigDecimal.ZERO) > 0
                    && priceMax.compareTo(BigDecimal.ZERO) > 0
                    && priceMax.compareTo(price) > 0;
        };
    }

    /**
     * Convenience predicate for "no band fields were sent at all" — the shape a PATCH's
     * {@code @AssertTrue} rules use to distinguish "band absent, leave unchanged" (D4) from "band
     * present, must be legal".
     */
    public static boolean isAbsent(PriceType priceType, BigDecimal price, BigDecimal priceMax) {
        return priceType == null && price == null && priceMax == null;
    }
}
