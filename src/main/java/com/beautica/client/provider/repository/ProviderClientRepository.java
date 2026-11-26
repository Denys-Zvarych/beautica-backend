package com.beautica.client.provider.repository;

import com.beautica.booking.entity.Booking;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * B507 — read-only native aggregate behind the provider client list. Repository methods are UNSCOPED: the caller (the
 * service, from a {@code ClientScope} the resolver authorised) supplies the salon / master ids. Three scope variants
 * as three methods; each page carries the filtered total on every row (always >= 1 row, see {@link ClientListSql});
 * {@code q} is trimmed + LIKE-escaped by the service (null = no filter).
 * Served by the existing booking partition indexes (D4, no migration).
 */
public interface ProviderClientRepository extends Repository<Booking, UUID> {

    @Query(value = ClientListSql.PAGE_SALON, nativeQuery = true)
    List<ClientListRowProjection> pageBySalon(
            @Param("salonId") UUID salonId, @Param("now") OffsetDateTime now, @Param("q") String q,
            @Param("digits") String digits, @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = ClientListSql.PAGE_SALON_MASTER, nativeQuery = true)
    List<ClientListRowProjection> pageBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("now") OffsetDateTime now,
            @Param("q") String q, @Param("digits") String digits, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = ClientListSql.PAGE_MASTER, nativeQuery = true)
    List<ClientListRowProjection> pageByMaster(
            @Param("masterId") UUID masterId, @Param("now") OffsetDateTime now, @Param("q") String q,
            @Param("digits") String digits, @Param("limit") int limit, @Param("offset") long offset);

    // ── B508: client card (one aggregate row, always returned; totalVisits 0 = unknown / out of scope) ──

    @Query(value = ClientCardSql.CARD_USER_SALON, nativeQuery = true)
    ClientCardRowProjection cardUserBySalon(
            @Param("salonId") UUID salonId, @Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    @Query(value = ClientCardSql.CARD_USER_SALON_MASTER, nativeQuery = true)
    ClientCardRowProjection cardUserBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("userId") UUID userId,
            @Param("now") OffsetDateTime now);

    @Query(value = ClientCardSql.CARD_USER_MASTER, nativeQuery = true)
    ClientCardRowProjection cardUserByMaster(
            @Param("masterId") UUID masterId, @Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    @Query(value = ClientCardSql.CARD_GUEST_SALON, nativeQuery = true)
    ClientCardRowProjection cardGuestBySalon(
            @Param("salonId") UUID salonId, @Param("bookingId") UUID bookingId, @Param("now") OffsetDateTime now);

    @Query(value = ClientCardSql.CARD_GUEST_SALON_MASTER, nativeQuery = true)
    ClientCardRowProjection cardGuestBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("bookingId") UUID bookingId,
            @Param("now") OffsetDateTime now);

    @Query(value = ClientCardSql.CARD_GUEST_MASTER, nativeQuery = true)
    ClientCardRowProjection cardGuestByMaster(
            @Param("masterId") UUID masterId, @Param("bookingId") UUID bookingId, @Param("now") OffsetDateTime now);

    // ── B508: visit page (always >= 1 row; visitKey null when the page is empty; total on every row) ──

    @Query(value = ClientCardSql.VISITS_USER_SALON, nativeQuery = true)
    List<ClientVisitPageRowProjection> visitsUserBySalon(
            @Param("salonId") UUID salonId, @Param("userId") UUID userId, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = ClientCardSql.VISITS_USER_SALON_MASTER, nativeQuery = true)
    List<ClientVisitPageRowProjection> visitsUserBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("userId") UUID userId,
            @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = ClientCardSql.VISITS_USER_MASTER, nativeQuery = true)
    List<ClientVisitPageRowProjection> visitsUserByMaster(
            @Param("masterId") UUID masterId, @Param("userId") UUID userId, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = ClientCardSql.VISITS_GUEST_SALON, nativeQuery = true)
    List<ClientVisitPageRowProjection> visitsGuestBySalon(
            @Param("salonId") UUID salonId, @Param("bookingId") UUID bookingId, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = ClientCardSql.VISITS_GUEST_SALON_MASTER, nativeQuery = true)
    List<ClientVisitPageRowProjection> visitsGuestBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("bookingId") UUID bookingId,
            @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = ClientCardSql.VISITS_GUEST_MASTER, nativeQuery = true)
    List<ClientVisitPageRowProjection> visitsGuestByMaster(
            @Param("masterId") UUID masterId, @Param("bookingId") UUID bookingId, @Param("limit") int limit,
            @Param("offset") long offset);

    // ── B508: the page's visits' rows in scope, with service + master names (one statement) ──

    @Query(value = ClientCardSql.ITEMS_SALON, nativeQuery = true)
    List<ClientVisitItemProjection> visitItemsBySalon(
            @Param("salonId") UUID salonId, @Param("visitKeys") Collection<UUID> visitKeys);

    @Query(value = ClientCardSql.ITEMS_SALON_MASTER, nativeQuery = true)
    List<ClientVisitItemProjection> visitItemsBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId,
            @Param("visitKeys") Collection<UUID> visitKeys);

    @Query(value = ClientCardSql.ITEMS_MASTER, nativeQuery = true)
    List<ClientVisitItemProjection> visitItemsByMaster(
            @Param("masterId") UUID masterId, @Param("visitKeys") Collection<UUID> visitKeys);

    // ── B526: the viewer's own visible ratings of a registered client (page + count) ──

    @Query(value = ClientRatingSql.PAGE_SALON, nativeQuery = true)
    List<ClientOwnRatingProjection> ratingsBySalon(
            @Param("salonId") UUID salonId, @Param("userId") UUID userId, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = ClientRatingSql.PAGE_SALON_MASTER, nativeQuery = true)
    List<ClientOwnRatingProjection> ratingsBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("userId") UUID userId,
            @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = ClientRatingSql.PAGE_MASTER, nativeQuery = true)
    List<ClientOwnRatingProjection> ratingsByMaster(
            @Param("masterId") UUID masterId, @Param("userId") UUID userId, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = ClientRatingSql.COUNT_SALON, nativeQuery = true)
    long countRatingsBySalon(@Param("salonId") UUID salonId, @Param("userId") UUID userId);

    @Query(value = ClientRatingSql.COUNT_SALON_MASTER, nativeQuery = true)
    long countRatingsBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("userId") UUID userId);

    @Query(value = ClientRatingSql.COUNT_MASTER, nativeQuery = true)
    long countRatingsByMaster(@Param("masterId") UUID masterId, @Param("userId") UUID userId);

    // B526 existence checks (the uniform 404 gate) — registered by client_id, guest by a valid anchor booking

    @Query(value = ClientRatingSql.EXISTS_USER_SALON, nativeQuery = true)
    boolean existsUserBySalon(@Param("salonId") UUID salonId, @Param("userId") UUID userId);

    @Query(value = ClientRatingSql.EXISTS_USER_SALON_MASTER, nativeQuery = true)
    boolean existsUserBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("userId") UUID userId);

    @Query(value = ClientRatingSql.EXISTS_USER_MASTER, nativeQuery = true)
    boolean existsUserByMaster(@Param("masterId") UUID masterId, @Param("userId") UUID userId);

    @Query(value = ClientRatingSql.EXISTS_GUEST_SALON, nativeQuery = true)
    boolean existsGuestBySalon(@Param("salonId") UUID salonId, @Param("bookingId") UUID bookingId);

    @Query(value = ClientRatingSql.EXISTS_GUEST_SALON_MASTER, nativeQuery = true)
    boolean existsGuestBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("bookingId") UUID bookingId);

    @Query(value = ClientRatingSql.EXISTS_GUEST_MASTER, nativeQuery = true)
    boolean existsGuestByMaster(@Param("masterId") UUID masterId, @Param("bookingId") UUID bookingId);

    // B564 — a guest anchor's E.164 phone in scope, one read for gate + identity (empty = uniform 404)

    @Query(value = ClientRatingSql.ANCHOR_PHONE_SALON, nativeQuery = true)
    Optional<String> findGuestAnchorPhoneBySalon(@Param("salonId") UUID salonId, @Param("bookingId") UUID bookingId);

    @Query(value = ClientRatingSql.ANCHOR_PHONE_MASTER, nativeQuery = true)
    Optional<String> findGuestAnchorPhoneByMaster(
            @Param("masterId") UUID masterId, @Param("bookingId") UUID bookingId);
}
