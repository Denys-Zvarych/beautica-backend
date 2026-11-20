package com.beautica.client.provider.repository;

import com.beautica.booking.entity.Booking;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * B507 — read-only native aggregate behind the provider client list. Repository methods are UNSCOPED: the caller (the
 * service, from a {@code ClientScope} the resolver authorised) supplies the salon / master ids. Three scope variants
 * as three methods, each with its count twin; {@code q} is trimmed + LIKE-escaped by the service (null = no filter).
 * Served by the existing booking partition indexes (D4, no migration).
 */
public interface ProviderClientRepository extends Repository<Booking, UUID> {

    @Query(value = ClientListSql.PAGE_SALON, nativeQuery = true)
    List<ClientListRowProjection> pageBySalon(
            @Param("salonId") UUID salonId, @Param("now") OffsetDateTime now, @Param("q") String q,
            @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = ClientListSql.COUNT_SALON, nativeQuery = true)
    long countBySalon(@Param("salonId") UUID salonId, @Param("now") OffsetDateTime now, @Param("q") String q);

    @Query(value = ClientListSql.PAGE_SALON_MASTER, nativeQuery = true)
    List<ClientListRowProjection> pageBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("now") OffsetDateTime now,
            @Param("q") String q, @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = ClientListSql.COUNT_SALON_MASTER, nativeQuery = true)
    long countBySalonAndMaster(
            @Param("salonId") UUID salonId, @Param("masterId") UUID masterId, @Param("now") OffsetDateTime now,
            @Param("q") String q);

    @Query(value = ClientListSql.PAGE_MASTER, nativeQuery = true)
    List<ClientListRowProjection> pageByMaster(
            @Param("masterId") UUID masterId, @Param("now") OffsetDateTime now, @Param("q") String q,
            @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = ClientListSql.COUNT_MASTER, nativeQuery = true)
    long countByMaster(@Param("masterId") UUID masterId, @Param("now") OffsetDateTime now, @Param("q") String q);
}
