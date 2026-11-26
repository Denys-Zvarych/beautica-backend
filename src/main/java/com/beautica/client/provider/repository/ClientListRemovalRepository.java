package com.beautica.client.provider.repository;

import com.beautica.client.provider.entity.ClientListRemoval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * B564 — writes of {@code client_list_removals} (V268). UNSCOPED like every repository here: the caller (the service,
 * from a resolver-authorised {@code ClientScope} and an in-scope identity) supplies the ids. One upsert per partial
 * unique index (its predicate repeated in the ON CONFLICT clause so Postgres can infer the arbiter); a re-remove moves
 * {@code removed_at} (CLI-83). Restore deletes; zero rows is fine (idempotent, D5).
 */
public interface ClientListRemovalRepository extends JpaRepository<ClientListRemoval, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO client_list_removals (id, salon_id, client_user_id, removed_at, removed_by_user_id)
            VALUES (:id, :salonId, :userId, :removedAt, :actorId)
            ON CONFLICT (salon_id, client_user_id) WHERE salon_id IS NOT NULL AND client_user_id IS NOT NULL
            DO UPDATE SET removed_at = EXCLUDED.removed_at, removed_by_user_id = EXCLUDED.removed_by_user_id
            """, nativeQuery = true)
    int upsertSalonUser(@Param("id") UUID id, @Param("salonId") UUID salonId, @Param("userId") UUID userId,
                        @Param("removedAt") Instant removedAt, @Param("actorId") UUID actorId);

    @Modifying
    @Query(value = """
            INSERT INTO client_list_removals (id, salon_id, guest_phone, removed_at, removed_by_user_id)
            VALUES (:id, :salonId, :phone, :removedAt, :actorId)
            ON CONFLICT (salon_id, guest_phone) WHERE salon_id IS NOT NULL AND guest_phone IS NOT NULL
            DO UPDATE SET removed_at = EXCLUDED.removed_at, removed_by_user_id = EXCLUDED.removed_by_user_id
            """, nativeQuery = true)
    int upsertSalonGuest(@Param("id") UUID id, @Param("salonId") UUID salonId, @Param("phone") String phone,
                         @Param("removedAt") Instant removedAt, @Param("actorId") UUID actorId);

    @Modifying
    @Query(value = """
            INSERT INTO client_list_removals (id, master_id, client_user_id, removed_at, removed_by_user_id)
            VALUES (:id, :masterId, :userId, :removedAt, :actorId)
            ON CONFLICT (master_id, client_user_id) WHERE master_id IS NOT NULL AND client_user_id IS NOT NULL
            DO UPDATE SET removed_at = EXCLUDED.removed_at, removed_by_user_id = EXCLUDED.removed_by_user_id
            """, nativeQuery = true)
    int upsertMasterUser(@Param("id") UUID id, @Param("masterId") UUID masterId, @Param("userId") UUID userId,
                         @Param("removedAt") Instant removedAt, @Param("actorId") UUID actorId);

    @Modifying
    @Query(value = """
            INSERT INTO client_list_removals (id, master_id, guest_phone, removed_at, removed_by_user_id)
            VALUES (:id, :masterId, :phone, :removedAt, :actorId)
            ON CONFLICT (master_id, guest_phone) WHERE master_id IS NOT NULL AND guest_phone IS NOT NULL
            DO UPDATE SET removed_at = EXCLUDED.removed_at, removed_by_user_id = EXCLUDED.removed_by_user_id
            """, nativeQuery = true)
    int upsertMasterGuest(@Param("id") UUID id, @Param("masterId") UUID masterId, @Param("phone") String phone,
                          @Param("removedAt") Instant removedAt, @Param("actorId") UUID actorId);

    @Modifying
    @Query(value = "DELETE FROM client_list_removals WHERE salon_id = :salonId AND client_user_id = :userId",
            nativeQuery = true)
    int deleteSalonUser(@Param("salonId") UUID salonId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM client_list_removals WHERE salon_id = :salonId AND guest_phone = :phone",
            nativeQuery = true)
    int deleteSalonGuest(@Param("salonId") UUID salonId, @Param("phone") String phone);

    @Modifying
    @Query(value = "DELETE FROM client_list_removals WHERE master_id = :masterId AND client_user_id = :userId",
            nativeQuery = true)
    int deleteMasterUser(@Param("masterId") UUID masterId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM client_list_removals WHERE master_id = :masterId AND guest_phone = :phone",
            nativeQuery = true)
    int deleteMasterGuest(@Param("masterId") UUID masterId, @Param("phone") String phone);

    /**
     * GST-51 (D3) — deletes the removal rows of those {@code phones} that have no open guest booking left (any
     * scope). "Open" repeats the predicate of {@code BookingRepository#findGuestPhonesWithOpenBookings} (partial index
     * {@code idx_bookings_guest_phone_open}): a phone still listed through another un-anonymised booking keeps its
     * removal, so a partial retention batch never brings a removed guest back. Bounded by the caller's batch.
     */
    @Modifying
    @Query(value = """
            DELETE FROM client_list_removals r
             WHERE r.guest_phone IN (:phones)
               AND NOT EXISTS (
                   SELECT 1 FROM bookings b
                    WHERE b.guest_phone = r.guest_phone
                      AND b.client_id IS NULL
                      AND b.guest_pii_anonymised_at IS NULL
                      AND b.client_detached_at IS NULL
                      AND b.booking_source IN ('LINK', 'STAFF'))
            """, nativeQuery = true)
    int deleteGuestPhonesWithoutOpenBookings(@Param("phones") Collection<String> phones);
}
