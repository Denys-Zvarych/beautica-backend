package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public seam exposing "what did this client last book here, categorically?" to features
 * outside {@code booking} — the mirror of {@link com.beautica.service.service
 * .PlatformCategoryLabelResolver} for the <em>history</em> half of the favourites category
 * axis. Cross-feature access goes through this public service type, never through
 * {@link BookingRepository} directly.
 *
 * <h3>Why it exists</h3>
 * The approved favourites design filters saved providers by service category, and defines
 * that category as <em>derived server-side from the last booked service</em> — one axis for
 * both masters and salons. Deriving it needs {@code bookings}, which the {@code favorite}
 * feature has no business reading. This component is the whole of that dependency: two
 * methods, both batched, both returning plain scalars.
 *
 * <h3>Batched by contract, not by accident</h3>
 * Each method issues <b>exactly one</b> statement for a whole page and is bounded by the
 * page size the caller passes (§E-3). Neither ever runs per row. The underlying queries are
 * top-1-per-provider index seeks, not history scans — see
 * {@link BookingRepository#findLastBookedCategoryByMasterIds} and the block comment above it
 * for the measured plans and the indexes involved.
 *
 * <h3>Absence, not null</h3>
 * A provider the client has never booked with — and a provider whose most recently booked
 * service carries no category — is simply <b>missing</b> from the returned map. Both collapse
 * to the same "no usable booked category" answer, deliberately: an unprivileged caller must
 * not be able to tell "I have never been here" from "the thing I booked is uncategorised",
 * and the favourites card renders the two identically anyway. Callers read the absence with
 * {@link Map#get} and surface {@code null}.
 *
 * <h3>Zero entity hydration (§I)</h3>
 * Both queries project scalars only. Nothing here loads a {@code Booking},
 * {@code MasterServiceAssignment} or {@code ServiceDefinition} into the persistence context,
 * so no association can lazily escape into a response DTO.
 */
@Component
@RequiredArgsConstructor
public class LastBookedCategoryLookup {

    private final BookingRepository bookingRepository;

    /**
     * The platform category code ({@code platform_categories.name}, e.g. {@code MANICURE}) of
     * the service in {@code clientId}'s most recent booking with each of {@code masterIds}.
     *
     * <p><b>{@code clientId} must be the authenticated principal.</b> This is one client's own
     * history; the repository finder is unscoped by itself (§E-4) and this method adds no
     * ownership check of its own — passing a body-supplied id here would expose another
     * client's booking history one category at a time.
     *
     * @param clientId  the client whose history is being read
     * @param masterIds the page's master ids; an empty collection short-circuits to an empty
     *                  map without touching the database (an empty {@code IN ()} is a SQL
     *                  syntax error, not an empty result)
     * @return master id → category code, for those masters that have one; never {@code null}
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> lastBookedCategoryByMaster(UUID clientId, Collection<UUID> masterIds) {
        if (masterIds == null || masterIds.isEmpty()) {
            return Map.of();
        }
        return toCategoryMap(bookingRepository.findLastBookedCategoryByMasterIds(clientId, masterIds));
    }

    /**
     * Salon counterpart of {@link #lastBookedCategoryByMaster(UUID, Collection)}, scoped to
     * bookings placed AT each salon ({@code bookings.salon_id} as stamped at creation time).
     * Same principal requirement, same empty short-circuit, same absence semantics.
     *
     * @param clientId the client whose history is being read
     * @param salonIds the page's salon ids
     * @return salon id → category code, for those salons that have one; never {@code null}
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> lastBookedCategoryBySalon(UUID clientId, Collection<UUID> salonIds) {
        if (salonIds == null || salonIds.isEmpty()) {
            return Map.of();
        }
        return toCategoryMap(bookingRepository.findLastBookedCategoryBySalonIds(clientId, salonIds));
    }

    /**
     * Folds the {@code [providerId, categoryCode]} projection into a map.
     *
     * <p>A {@link HashMap} rather than {@code Collectors.toMap}: the latter throws on a
     * duplicate key, and while the queries cannot produce one (the {@code LATERAL} yields at
     * most one row per driver id, and the driver is a PK scan), a throw would turn a future
     * query edit into a 500 on a read endpoint rather than a harmless last-write-wins.
     * {@code null} values are already excluded by the queries' {@code category IS NOT NULL}.
     */
    private static Map<UUID, String> toCategoryMap(List<Object[]> rows) {
        Map<UUID, String> byProvider = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            byProvider.put((UUID) row[0], (String) row[1]);
        }
        return byProvider;
    }
}
