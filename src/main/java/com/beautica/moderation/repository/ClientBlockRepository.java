package com.beautica.moderation.repository;

import com.beautica.moderation.entity.ClientBlock;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 397 — client blocks. Every finder is scoped by blocker; the service resolves the caller's scope first.
 *
 * <p>Scope arguments: exactly one of {@code masterId} / {@code salonId} is non-null; the null one compares as
 * unknown in SQL and never matches.
 *
 * <p><b>Identity is per type, never merged</b> (V211; the V210 header's "and/or" model is superseded): a CLIENT row
 * carries only {@code blocked_user_id}, a GUEST row only {@code phone_hash} (+ {@code phone_last4}). An app booking
 * is matched by user id against CLIENT rows ({@link #findIdByBlockedUser}), a guest booking by phone hash against
 * GUEST rows ({@link #findIdByGuestPhone}) — there is no OR across the two keys anywhere. Both probes are
 * {@code LIMIT 1} id reads ({@link Limit} → {@code fetch first 1 rows only}; Spring Data's JPQL parser rejects an
 * inline {@code LIMIT}), which also serve as the phase-398 existence check.
 */
public interface ClientBlockRepository extends JpaRepository<ClientBlock, UUID> {

    /** The CLIENT block of this account in this scope (at most one: V210 partial uniques on (blocker, user)). */
    default Optional<UUID> findIdByBlockedUser(UUID masterId, UUID salonId, UUID userId) {
        return findIdByBlockedUser(masterId, salonId, userId, Limit.of(1));
    }

    @Query("""
            SELECT cb.id FROM ClientBlock cb
             WHERE (cb.blockerMasterId = :masterId OR cb.blockerSalonId = :salonId)
               AND cb.blockedUserId = :userId
            """)
    Optional<UUID> findIdByBlockedUser(
            @Param("masterId") UUID masterId,
            @Param("salonId") UUID salonId,
            @Param("userId") UUID userId,
            Limit limit);

    /** The GUEST block of this verified phone hash in this scope (at most one: V210 partial uniques). */
    default Optional<UUID> findIdByGuestPhone(UUID masterId, UUID salonId, byte[] phoneHash) {
        return findIdByGuestPhone(masterId, salonId, phoneHash, Limit.of(1));
    }

    @Query("""
            SELECT cb.id FROM ClientBlock cb
             WHERE (cb.blockerMasterId = :masterId OR cb.blockerSalonId = :salonId)
               AND cb.blockedUserId IS NULL
               AND cb.phoneHash = :phoneHash
            """)
    Optional<UUID> findIdByGuestPhone(
            @Param("masterId") UUID masterId,
            @Param("salonId") UUID salonId,
            @Param("phoneHash") byte[] phoneHash,
            Limit limit);

    /**
     * Race-safe insert: a concurrent twin colliding on any V210 partial unique index inserts nothing (0) and the
     * caller re-reads the winner. HQL insert so nullable parameters are typed from the entity attributes.
     */
    @Modifying
    @Query("""
            INSERT INTO ClientBlock (id, blockerMasterId, blockerSalonId, blockedUserId, phoneHash, phoneLast4,
                                     displayName, createdByUserId, createdAt)
            VALUES (:id, :masterId, :salonId, :userId, :phoneHash, :phoneLast4, :displayName, :createdBy, :createdAt)
            ON CONFLICT DO NOTHING
            """)
    int insertIgnoringDuplicate(
            @Param("id") UUID id,
            @Param("masterId") UUID masterId,
            @Param("salonId") UUID salonId,
            @Param("userId") UUID userId,
            @Param("phoneHash") byte[] phoneHash,
            @Param("phoneLast4") String phoneLast4,
            @Param("displayName") String displayName,
            @Param("createdBy") UUID createdBy,
            @Param("createdAt") Instant createdAt);

    /** One block as a list row (the POST response). */
    @Query("""
            SELECT new com.beautica.moderation.repository.ClientBlockListRow(
                       cb.id, cb.blockedUserId, cb.phoneLast4, cb.displayName, cb.createdAt,
                       u.firstName, u.lastName, u.avatarUrl)
              FROM ClientBlock cb LEFT JOIN cb.blockedUser u
             WHERE cb.id = :id
            """)
    Optional<ClientBlockListRow> findListRowById(@Param("id") UUID id);

    /** {@code GET /blocks} for an independent master — one statement whatever the row count. */
    @Query("""
            SELECT new com.beautica.moderation.repository.ClientBlockListRow(
                       cb.id, cb.blockedUserId, cb.phoneLast4, cb.displayName, cb.createdAt,
                       u.firstName, u.lastName, u.avatarUrl)
              FROM ClientBlock cb LEFT JOIN cb.blockedUser u
             WHERE cb.blockerMasterId = :masterId
             ORDER BY cb.createdAt DESC, cb.id DESC
            """)
    List<ClientBlockListRow> findListRowsByMasterScope(@Param("masterId") UUID masterId, Pageable pageable);

    /** {@code GET /blocks} for a salon owner/admin — one statement whatever the row count. */
    @Query("""
            SELECT new com.beautica.moderation.repository.ClientBlockListRow(
                       cb.id, cb.blockedUserId, cb.phoneLast4, cb.displayName, cb.createdAt,
                       u.firstName, u.lastName, u.avatarUrl)
              FROM ClientBlock cb LEFT JOIN cb.blockedUser u
             WHERE cb.blockerSalonId IN :salonIds
             ORDER BY cb.createdAt DESC, cb.id DESC
            """)
    List<ClientBlockListRow> findListRowsBySalonScope(
            @Param("salonIds") Collection<UUID> salonIds, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ClientBlock cb WHERE cb.id = :id AND cb.blockerMasterId = :masterId")
    int deleteInMasterScope(@Param("id") UUID id, @Param("masterId") UUID masterId);

    @Modifying
    @Query("DELETE FROM ClientBlock cb WHERE cb.id = :id AND cb.blockerSalonId IN :salonIds")
    int deleteInSalonScope(@Param("id") UUID id, @Param("salonIds") Collection<UUID> salonIds);
}
