package com.beautica.favorite.service;

import org.springframework.data.domain.Sort;
import com.beautica.common.web.SortWhitelist;
import com.beautica.auth.Role;
import com.beautica.booking.domain.MasterBookability;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteSalonResponse;
import com.beautica.favorite.dto.FavoriteServiceResponse;
import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
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
 * row. The insert attempt itself runs in {@link FavoritePersistenceService}'s own
 * {@code REQUIRES_NEW} transaction, so a losing caller's re-read runs on an unaffected
 * transaction instead of one Postgres has already marked aborted — see that class's
 * javadoc. {@link #removeFavorite} is a delete-if-exists (→ controller {@code 204}).
 *
 * <h3>Target validation (application layer, not DB CHECK)</h3>
 * A {@code MASTER} target must be an {@code INDEPENDENT_MASTER}-owned master
 * ({@code 404} if no such master; {@code 400} when the master is salon-employed,
 * i.e. role {@code SALON_MASTER}). A {@code SALON} target must exist ({@code 404}).
 * A {@code SERVICE} target must be an active {@code master_services} row whose service
 * definition is also active ({@code 404} unknown; {@code 400} inactive) — with
 * <b>no role check</b>: a salon-employed master's service IS wish-listable, deliberately
 * asymmetric to the {@code MASTER} rule above (see {@link FavoriteTargetType}'s javadoc).
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
    private final MasterServiceRepository masterServiceRepository;
    private final ServiceRepository serviceRepository;
    private final DiscoveryLocationResolver discoveryLocationResolver;
    private final FavoritePersistenceService favoritePersistenceService;

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
        Page<Object[]> rows = favoriteRepository.findFavoriteMasterRows(
                clientUserId, SortWhitelist.stripSort(pageable));
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
        Page<Object[]> rows = favoriteRepository.findFavoriteSalonRows(
                clientUserId, SortWhitelist.stripSort(pageable));
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

    /**
     * One bounded page of this client's BEAUTY WISH LIST — favorited services, newest
     * favorite first (Phase 31.4; extended by the salon-service-favourites track to merge a
     * second source arm — see {@code FavoriteRepository.findFavoriteServiceRows}).
     *
     * <p>Each row carries what the card renders (service name, master OR salon identity,
     * duration, price band) and, for a MASTER row, the two ids {@code POST /bookings} needs, so
     * «Записатись» costs no extra call. Money is derived from the same three
     * {@code service_definitions} columns {@code ServicePricing.ofDefinition} reads, for both
     * arms, so the wish list and the master's own menu (or the salon catalogue) can never print
     * different prices.
     *
     * <p>Rows whose assignment, service definition, performing master, <b>or (for a SALON row)
     * owning salon</b> went inactive are filtered out by the query, not deleted: the favourite
     * row survives (consistent with MASTER/SALON/SERVICE), it simply stops appearing rather than
     * offering a dead CTA — and reappears if a master is re-assigned to the service, since
     * nothing was ever deleted.
     */
    @Transactional(readOnly = true)
    public Page<FavoriteServiceResponse> listServiceFavorites(UUID clientUserId, Pageable pageable) {
        Page<Object[]> rows = favoriteRepository.findFavoriteServiceRows(
                clientUserId, SortWhitelist.stripSort(pageable));
        return rows.map(FavoriteServiceResponse::fromRow);
    }

    // ── target validation ─────────────────────────────────────────────────────

    private void validateTarget(FavoriteTargetType targetType, UUID targetId) {
        // Exhaustive switch over the enum with NO default: adding a FavoriteTargetType value
        // must fail compilation here rather than silently persist an unvalidated target.
        // Do not add a default branch to silence it.
        switch (targetType) {
            case MASTER -> validateMasterTarget(targetId);
            case SALON -> validateSalonTarget(targetId);
            case SERVICE -> validateServiceTarget(targetId);
            case SALON_SERVICE -> validateSalonServiceTarget(targetId);
        }
    }

    /**
     * Validates a {@code MASTER} target: the master must exist, be independent, and be ACTIVE.
     *
     * <p>The active check was added by the 2026-08 security audit alongside the read query's
     * {@code m.is_active = true} predicate — favoriting a deactivated master would write a row
     * the list then hides, which is merely useless; but leaving the write open also lets a
     * client heart a provider who can never be booked. It costs no extra query: the master is
     * already loaded here. Same rule, same 400, as the SERVICE arm.
     */
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
        if (!master.isActive()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Only an active master can be favorited");
        }
    }

    /**
     * Validates a {@code SALON} target — it must exist AND be active.
     *
     * <p>{@code existsByIdAndIsActiveTrue} rather than {@code existsById} (2026-08 security
     * audit): {@code salons.is_active} is a soft-delete flag, and the read query now filters on
     * it, so admitting an inactive salon here would write a favourite that can never render.
     * A deactivated salon is indistinguishable from a missing one for an unprivileged client,
     * so this stays a {@code 404} rather than leaking the salon's continued existence.
     */
    private void validateSalonTarget(UUID salonId) {
        if (!salonRepository.existsByIdAndIsActiveTrue(salonId)) {
            throw new NotFoundException("Salon not found");
        }
    }

    /**
     * Validates a {@code SERVICE} target — {@code targetId} is a {@code master_services.id}
     * (a master+service pair), never a {@code service_definitions.id}.
     *
     * <p><b>No role check, deliberately.</b> Unlike {@link #validateMasterTarget}, a
     * salon-employed {@code SALON_MASTER}'s service IS wish-listable. A wish-list entry is a
     * rebook shortcut, not an endorsement of a person; applying the MASTER identity rule here
     * would make most of the catalogue un-wish-listable. Locked user decision, 2026-08-07 —
     * see {@link FavoriteTargetType}'s javadoc for the full asymmetry note. Do not "fix" this
     * into consistency with the MASTER arm.
     *
     * <p>All THREE {@code isActive} flags are checked because they are independent:
     * {@code ServiceCatalogService.deactivateServiceDefinition} soft-deletes the definition
     * without touching the assignment row, so an inactive definition can carry an active
     * assignment; and a master can deactivate themself while both of their service rows stay
     * active. The master flag was added by the 2026-08 security audit — without it a
     * deactivated master's service could be wish-listed with a live «Записатись» that
     * {@code BookingService.doCreateBooking} then rejects with 404. It costs no extra query:
     * {@code findByIdWithServiceDefinitionAndMaster} already {@code JOIN FETCH}es the master.
     * Rejecting at write time keeps a dead «Записатись» out of the list; the read query filters
     * the same three flags for rows that went inactive afterwards.
     *
     * <p><b>A FOURTH flag — the owning salon's</b> (2026-08 security re-audit MEDIUM).
     * {@code SalonService.deactivateSalon} flips {@code salons.is_active} but does NOT cascade to
     * {@code masters.is_active}, so a closed salon's masters all still report
     * {@code isActive() == true}. Because this arm deliberately admits {@code SALON_MASTER}
     * services (unlike {@link #validateMasterTarget}), it is the one write path that can attach a
     * closed salon to a client's wish list. Mirrors the read query's
     * {@code (m.salon IS NULL OR sal.isActive = true)} predicate and the booking path's guard, so
     * write-time, read-time and booking-time now agree on one rule. The master + salon halves are
     * evaluated by {@link com.beautica.booking.domain.MasterBookability#isBookable} itself rather
     * than re-derived here, so this site cannot drift from the rule it mirrors.
     *
     * <p><b>{@code salon == null} must pass.</b> An {@code INDEPENDENT_MASTER} has no salon and
     * stays fully wish-listable — a bare {@code getSalon().isActive()} would both NPE and lock out
     * every independent master. Costs no extra query: the finder {@code LEFT JOIN FETCH}es the
     * salon.
     */
    private void validateServiceTarget(UUID masterServiceId) {
        MasterServiceAssignment assignment = masterServiceRepository
                .findByIdWithServiceDefinitionAndMaster(masterServiceId)
                .orElseThrow(() -> new NotFoundException("Service not found"));

        Master master = assignment.getMaster();

        // The master + salon terms are NOT hand-rolled here: MasterBookability.isBookable is the
        // single canonical definition (see that class's javadoc), so a change to the rule reaches
        // this write path automatically instead of silently diverging from the booking paths.
        if (!assignment.isActive()
                || !assignment.getServiceDefinition().isActive()
                || !MasterBookability.isBookable(master)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Only an active service can be favorited");
        }
    }

    /**
     * Validates a {@code SALON_SERVICE} target — {@code targetId} is a
     * {@code service_definitions.id} where {@code owner_type = 'SALON'}, <b>never</b> a
     * {@code master_services.id}. This is the "browse the salon catalogue, favorite a service
     * before choosing a master" arm; the {@code SERVICE} arm above stays the "already chose a
     * master" one. See {@link FavoriteTargetType}'s javadoc for the full identity rationale.
     *
     * <p><b>Three checks, all load-bearing:</b>
     * <ol>
     *   <li>The definition must exist and be active — {@code 404} unknown, {@code 400} inactive
     *       (mirrors every other arm's "missing vs. inactive" split).</li>
     *   <li>{@code ownerType} must be {@code SALON} — an {@code INDEPENDENT_MASTER}-owned
     *       definition has no {@code master_services} row to point a {@code SALON_SERVICE}
     *       favourite at coherently; that definition is favouritable only via {@code SERVICE}
     *       (a {@code master_services.id}), never via this arm.</li>
     *   <li><b>Master-performed invariant</b> (locked product decision — "salon offering =
     *       master-performed only"): at least one active {@link MasterServiceAssignment} by an
     *       active master of that active salon must exist. Without this a client could favourite
     *       a definition no one currently performs — indistinguishable from a live catalogue
     *       entry until they try to book it. {@code existsBookableAssignmentForSalonService}
     *       mirrors the exact predicate {@code MasterServiceRepository
     *       #findBookableAssignmentsBySalon} uses to build the public catalogue, so write-time
     *       and read-time can never disagree about what counts as "performed".</li>
     * </ol>
     *
     * <p>The favourite is a pointer, not a guarantee: visibility stays derived at read time
     * exactly as the {@code SERVICE} arm already works (a master or salon can go inactive after
     * the favourite is written; the row survives and the list query filters it out, then back in
     * if a master is re-assigned — no cleanup job, consistent with every other arm).
     */
    private void validateSalonServiceTarget(UUID serviceDefId) {
        ServiceDefinition definition = serviceRepository.findById(serviceDefId)
                .orElseThrow(() -> new NotFoundException("Service not found"));

        if (!definition.isActive()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Only an active service can be favorited");
        }
        if (definition.getOwnerType() != OwnerType.SALON) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Only a salon-owned service can be favorited as SALON_SERVICE");
        }
        if (!masterServiceRepository.existsBookableAssignmentForSalonService(
                definition.getOwnerId(), serviceDefId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "No active master currently performs this service");
        }
    }

    // ── insert with concurrent-duplicate fallback ─────────────────────────────

    private FavoriteResponse insertFavorite(UUID clientUserId, FavoriteTargetType targetType, UUID targetId) {
        try {
            // Delegates to a separate bean's REQUIRES_NEW transaction (see
            // FavoritePersistenceService's javadoc) — calling an annotated method on THIS bean
            // instead would bypass the @Transactional proxy entirely and run the insert inside
            // this method's own (already-open) transaction, reintroducing the aborted-transaction
            // bug the re-read below exists to avoid.
            Favorite saved = favoritePersistenceService.persistNew(
                    Favorite.of(clientUserId, targetType, targetId));
            return FavoriteResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent double-submit raced past the pre-check and hit uq_favorite in the OTHER
            // transaction (FavoritePersistenceService's). That transaction rolled back on its own;
            // THIS transaction never touched the failing statement, so it is still healthy and this
            // re-read succeeds instead of hitting Postgres 25P02 (aborted transaction).
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
