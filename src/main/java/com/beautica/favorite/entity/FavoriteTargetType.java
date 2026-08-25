package com.beautica.favorite.entity;

/**
 * Discriminator for a polymorphic {@link Favorite} row.
 *
 * <p>{@code MASTER} means <b>any master</b> — a {@code masters} row of any
 * {@code MasterType} (independent, salon-employed, or a salon owner working as a master);
 * the favorite's {@code targetId} is the {@code masters.id}. <b>Mobile Phase 111 removed the
 * former {@code INDEPENDENT_MASTER}-only restriction</b> (locked user decision: a client may
 * heart any provider they can book). The only surviving write-time rule is that the master must
 * exist and be ACTIVE, and it lives in
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
 * <h3>No role check on ANY arm (the historical MASTER/SERVICE asymmetry is RESOLVED)</h3>
 * {@code FavoriteService.validateServiceTarget} has always applied <b>no role check</b>: an
 * active {@code master_services} row belonging to a {@code SALON_MASTER} is a valid
 * {@code SERVICE} target, because a {@code SERVICE} favourite is about <em>rebooking a
 * procedure</em> and is meaningless without the master who performs it — a role check there
 * would gut the feature for salon clients, since most of the catalogue is salon-performed.
 * Locked user decision, 2026-08-07 (Phase 31.3).
 *
 * <p>This USED to be documented as a deliberate asymmetry with the {@code MASTER} arm, whose
 * older rule was about <em>identity</em> ("a client favourites the salon, not its staff").
 * <b>Mobile Phase 111 reversed that identity rule</b>, so both arms now admit a salon-employed
 * master and there is no asymmetry left. Do not reintroduce a role check on either arm.
 *
 * <p>{@code SALON_SERVICE} (salon-service-favourites track) means a
 * {@code service_definitions} row where {@code owner_type = 'SALON'} — the favorite's
 * {@code targetId} is the {@code service_definitions.id}, <b>never</b> a
 * {@code master_services.id}. The distinction is <b>whether a master has been chosen</b>, not
 * who owns them: a salon's catalogue is browsed one step earlier — before a master is picked —
 * so there is no {@code master_services} row yet to point at.
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
