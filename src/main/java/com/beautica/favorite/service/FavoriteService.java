package com.beautica.favorite.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteSalonResponse;
import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Favorite / unfavorite a polymorphic target (an independent master or a salon)
 * and read the two per-client favorites lists.
 *
 * <h3>Idempotency</h3>
 * {@link #addFavorite} returns the existing row on a duplicate
 * {@code (clientId, targetType, targetId)} (→ controller {@code 200}, never
 * {@code 409}). The {@code uq_favorite} UNIQUE index is the last-resort guard: a
 * concurrent double-submit that races past the pre-check is caught as a
 * {@link DataIntegrityViolationException} and resolved by re-reading the now-present
 * row. {@link #removeFavorite} is a delete-if-exists (→ controller {@code 204}).
 *
 * <h3>Target validation (application layer, not DB CHECK)</h3>
 * A {@code MASTER} target must be an {@code INDEPENDENT_MASTER}-owned master
 * ({@code 404} if no such master; {@code 400} when the master is salon-employed,
 * i.e. role {@code SALON_MASTER}). A {@code SALON} target must exist ({@code 404}).
 *
 * <h3>Locality labels (§E no N+1, §I no raw FK ids leaked)</h3>
 * The list reads return raw projection rows carrying discovery city/district FK ids;
 * this service batch-resolves their {@code name_uk} labels through the M2 seam
 * ({@link DiscoveryLocationResolver}) — a fixed two queries per page, never per row
 * — exactly as {@code com.beautica.search.service.SearchService} does. The raw FK
 * ids never reach the response DTO.
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final DiscoveryLocationResolver discoveryLocationResolver;

    /**
     * Favorites the target for {@code clientUserId} (the authenticated principal).
     * Idempotent: a duplicate returns the existing favorite unchanged.
     *
     * @throws NotFoundException when the target master/salon does not exist
     * @throws BusinessException ({@code 400}) when the master target is a
     *                           salon-employed {@code SALON_MASTER}
     */
    @Transactional
    public FavoriteResponse addFavorite(UUID clientUserId, FavoriteTargetType targetType, UUID targetId) {
        validateTarget(targetType, targetId);

        return favoriteRepository
                .findByClientIdAndTargetTypeAndTargetId(clientUserId, targetType, targetId)
                .map(FavoriteResponse::from)
                .orElseGet(() -> insertFavorite(clientUserId, targetType, targetId));
    }

    /**
     * Unfavorites the target for {@code clientUserId}. Idempotent: succeeds whether
     * or not a row existed (the controller returns {@code 204} regardless).
     */
    @Transactional
    public void removeFavorite(UUID clientUserId, FavoriteTargetType targetType, UUID targetId) {
        favoriteRepository.deleteByClientIdAndTargetTypeAndTargetId(clientUserId, targetType, targetId);
    }

    /**
     * One bounded page of this client's favorited independent masters, newest favorite
     * first, each with resolved locality labels and this client's latest booking service
     * name with that master ({@code null} when never booked).
     *
     * <p>The {@code Pageable} bounds the LATERAL "latest booking" subquery to at most
     * {@code pageSize} evaluations per request (§E-3, §J). Locality labels are still
     * batch-resolved exactly once for the whole page (§E no N+1).
     */
    @Transactional(readOnly = true)
    public Page<FavoriteMasterResponse> listMasterFavorites(UUID clientUserId, Pageable pageable) {
        Page<Object[]> rows = favoriteRepository.findFavoriteMasterRows(clientUserId, pageable);
        if (rows.isEmpty()) {
            // No label resolution for an empty page (no N+1); preserve page metadata.
            return rows.map(row -> (FavoriteMasterResponse) null);
        }
        DiscoveryLabels labels = resolveLabels(rows.getContent(), 4, 5);
        return rows.map(row -> mapMasterRow(row, labels));
    }

    /**
     * One bounded page of this client's favorited salons, newest favorite first, each
     * with resolved locality labels and aggregate rating ({@code null} when never
     * reviewed). Bounded by {@code Pageable} (§J); labels batch-resolved once per page.
     */
    @Transactional(readOnly = true)
    public Page<FavoriteSalonResponse> listSalonFavorites(UUID clientUserId, Pageable pageable) {
        Page<Object[]> rows = favoriteRepository.findFavoriteSalonRows(clientUserId, pageable);
        if (rows.isEmpty()) {
            // No label resolution for an empty page (no N+1); preserve page metadata.
            return rows.map(row -> (FavoriteSalonResponse) null);
        }
        DiscoveryLabels labels = resolveLabels(rows.getContent(), 3, 4);
        return rows.map(row -> mapSalonRow(row, labels));
    }

    /**
     * Legacy unbounded single-page variant of
     * {@link #listMasterFavorites(UUID, Pageable)}, retained for existing unit tests;
     * the controller uses the paginated overload. Returns this client's favorited
     * masters, newest favorite first, with resolved locality labels and latest booking
     * service name.
     */
    @Transactional(readOnly = true)
    public List<FavoriteMasterResponse> listMasterFavorites(UUID clientUserId) {
        List<Object[]> rows = favoriteRepository.findFavoriteMasterRows(clientUserId);
        if (rows.isEmpty()) {
            return List.of();
        }

        DiscoveryLabels labels = resolveLabels(rows, 4, 5);
        List<FavoriteMasterResponse> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            results.add(mapMasterRow(row, labels));
        }
        return results;
    }

    /**
     * Legacy unbounded single-page variant of
     * {@link #listSalonFavorites(UUID, Pageable)}, retained for existing unit tests;
     * the controller uses the paginated overload.
     */
    @Transactional(readOnly = true)
    public List<FavoriteSalonResponse> listSalonFavorites(UUID clientUserId) {
        List<Object[]> rows = favoriteRepository.findFavoriteSalonRows(clientUserId);
        if (rows.isEmpty()) {
            return List.of();
        }

        DiscoveryLabels labels = resolveLabels(rows, 3, 4);
        List<FavoriteSalonResponse> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            results.add(mapSalonRow(row, labels));
        }
        return results;
    }

    // ── target validation ─────────────────────────────────────────────────────

    private void validateTarget(FavoriteTargetType targetType, UUID targetId) {
        switch (targetType) {
            case MASTER -> validateMasterTarget(targetId);
            case SALON -> validateSalonTarget(targetId);
        }
    }

    private void validateMasterTarget(UUID masterId) {
        Master master = masterRepository.findByIdWithUserAndSalon(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
        Role role = master.getUser().getRole();
        if (role != Role.INDEPENDENT_MASTER) {
            // A salon-employed master (SALON_MASTER) is never a favoritable target —
            // clients favorite the salon, not its staff. Reject before any row is written.
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Only independent masters can be favorited");
        }
    }

    private void validateSalonTarget(UUID salonId) {
        if (!salonRepository.existsById(salonId)) {
            throw new NotFoundException("Salon not found");
        }
    }

    // ── insert with concurrent-duplicate fallback ─────────────────────────────

    private FavoriteResponse insertFavorite(UUID clientUserId, FavoriteTargetType targetType, UUID targetId) {
        try {
            Favorite saved = favoriteRepository.saveAndFlush(
                    Favorite.of(clientUserId, targetType, targetId));
            return FavoriteResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent double-submit raced past the pre-check and hit uq_favorite.
            // Resolve idempotently by returning the row the other request inserted.
            return favoriteRepository
                    .findByClientIdAndTargetTypeAndTargetId(clientUserId, targetType, targetId)
                    .map(FavoriteResponse::from)
                    .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                            "Failed to persist favorite"));
        }
    }

    // ── label resolution (M2 seam, batched — §E no N+1) ───────────────────────

    private DiscoveryLabels resolveLabels(List<Object[]> rows, int cityIdIdx, int districtIdIdx) {
        Set<UUID> cityIds = new LinkedHashSet<>();
        Set<UUID> districtIds = new LinkedHashSet<>();
        for (Object[] row : rows) {
            if (row[cityIdIdx] != null) {
                cityIds.add((UUID) row[cityIdIdx]);
            }
            if (row[districtIdIdx] != null) {
                districtIds.add((UUID) row[districtIdIdx]);
            }
        }
        return discoveryLocationResolver.resolveLabels(cityIds, districtIds);
    }

    // ── row mapping ────────────────────────────────────────────────────────────

    /**
     * Maps a favorited-master projection row to its response DTO.
     *
     * <p>Column layout (indices 0–7):
     * {@code [master_id, first_name, last_name, avatar_url, discovery_city_id,
     * discovery_district_id, avg_rating, last_service_name]}. The internal city/
     * district FK ids (4, 5) are consumed for label resolution only and never placed
     * on the DTO (§I).
     */
    private static FavoriteMasterResponse mapMasterRow(Object[] row, DiscoveryLabels labels) {
        UUID masterId = (UUID) row[0];
        String firstName = (String) row[1];
        String lastName = (String) row[2];
        String avatarUrl = (String) row[3];
        UUID cityId = (UUID) row[4];
        UUID districtId = (UUID) row[5];
        Double avgRating = row[6] == null ? null : ((BigDecimal) row[6]).doubleValue();
        String lastServiceName = (String) row[7];

        return new FavoriteMasterResponse(
                masterId,
                firstName,
                lastName,
                avatarUrl,
                labels.cityLabel(cityId),
                labels.districtLabel(districtId),
                avgRating,
                lastServiceName
        );
    }

    /**
     * Maps a favorited-salon projection row to its response DTO.
     *
     * <p>Column layout (indices 0–5):
     * {@code [salon_id, name, avatar_url, city_id, district_id, avg_rating]}. The
     * internal city/district FK ids (3, 4) are consumed for label resolution only.
     * {@code avg_rating} is the {@code AVG(reviews.rating)} aggregate (a Postgres
     * {@code numeric}), {@code null} when the salon has no reviews.
     */
    private static FavoriteSalonResponse mapSalonRow(Object[] row, DiscoveryLabels labels) {
        UUID salonId = (UUID) row[0];
        String name = (String) row[1];
        String avatarUrl = (String) row[2];
        UUID cityId = (UUID) row[3];
        UUID districtId = (UUID) row[4];
        Double avgRating = row[5] == null ? null : ((Number) row[5]).doubleValue();

        return new FavoriteSalonResponse(
                salonId,
                name,
                avatarUrl,
                labels.cityLabel(cityId),
                labels.districtLabel(districtId),
                avgRating
        );
    }
}
