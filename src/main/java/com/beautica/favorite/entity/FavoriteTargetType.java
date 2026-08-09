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
 */
public enum FavoriteTargetType {
    MASTER,
    SALON,
    SERVICE
}
