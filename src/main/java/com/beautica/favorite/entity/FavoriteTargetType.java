package com.beautica.favorite.entity;

/**
 * Discriminator for a polymorphic {@link Favorite} row.
 *
 * <p>{@code MASTER} means an <b>independent master</b> — a {@code users} row with
 * role {@code INDEPENDENT_MASTER} that owns a {@code masters} row; the favorite's
 * {@code targetId} is the {@code masters.id}. A {@code SALON_MASTER} is never a
 * valid favorite target; that rejection lives in
 * {@link com.beautica.favorite.service.FavoriteService}, not in the DB CHECK.
 *
 * <p>{@code SALON} means a {@code salons} row; the favorite's {@code targetId} is
 * the {@code salons.id}.
 *
 * <p>{@code SERVICE} (Phase 31.3, the BEAUTY WISH LIST) means a
 * {@code master_services} row — a <b>(master, service) pair</b>, not a
 * {@code service_definitions} row. {@code master_services.id} is the canonical booking
 * identity everywhere that matters ({@code CreateBookingRequest.masterServiceId},
 * {@code MasterServiceResponse.id}, {@code GET /masters/&#123;id&#125;/slots?serviceId=}), so a
 * wish-listed row can start a rebook on its own. Favouriting a
 * {@code service_definitions.id} would store a row that cannot.
 *
 * <h3>Deliberate asymmetry — SALON_MASTER is rejected for MASTER but ALLOWED for SERVICE</h3>
 * <b>This is not an inconsistency to "fix".</b> The {@code MASTER} rule above is about
 * <em>identity</em>: a client favourites the salon, not its staff. A {@code SERVICE}
 * favourite is about <em>rebooking a procedure</em>, and it is meaningless without the
 * master who performs it. Copying the {@code MASTER} role check into the service path
 * would make every service at every salon un-wish-listable — i.e. would gut the feature
 * for salon clients, since most of the catalogue is salon-performed. So
 * {@code FavoriteService.validateServiceTarget} deliberately applies <b>no role check</b>:
 * an active {@code master_services} row belonging to a {@code SALON_MASTER} is a valid
 * {@code SERVICE} target. Locked user decision, 2026-08-07 (Phase 31.3).
 *
 * <p>{@code SALON_SERVICE} (salon-service-favourites track) means a
 * {@code service_definitions} row where {@code owner_type = 'SALON'} — the favorite's
 * {@code targetId} is the {@code service_definitions.id}, <b>never</b> a
 * {@code master_services.id}. Today, only an independent master's service can be
 * favourited via {@code SERVICE}; a salon's catalogue is browsed one step earlier — before
 * a master is chosen — so there is no {@code master_services} row yet to point at.
 * {@code service_definitions} is polymorphically owned ({@code owner_type}/{@code owner_id}),
 * so a definition id functionally determines its salon; the salon is derived server-side
 * (one join) rather than carried as a second id, which is why this is a single UUID and not
 * a composite {@code (salonId, serviceDefId)} key — {@code uq_favorite UNIQUE (client_id,
 * target_type, target_id)} (V92) does not dedupe NULLs, so a nullable second column would
 * silently break uniqueness for every other arm.
 *
 * <p><b>Master-performed invariant applies here too.</b> Per the locked "salon offering =
 * master-performed only" rule, {@code FavoriteService.validateSalonServiceTarget} additionally
 * requires at least one active assignment by an active master of that (active) salon — a
 * favourite is a pointer, not a guarantee; visibility of the pointer stays derived, exactly as
 * the {@code SERVICE} arm's read-time filtering already works (see
 * {@code FavoriteRepository.findFavoriteServiceRows}'s javadoc). The write-time check mirrors
 * {@code MasterServiceRepository#findBookableAssignmentsBySalon}.
 */
public enum FavoriteTargetType {
    MASTER,
    SALON,
    SERVICE,
    SALON_SERVICE
}
