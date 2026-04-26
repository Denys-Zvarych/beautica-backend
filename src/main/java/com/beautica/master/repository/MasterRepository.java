package com.beautica.master.repository;

import com.beautica.master.entity.Master;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MasterRepository extends JpaRepository<Master, UUID> {

    Optional<Master> findByUserId(UUID userId);

    List<Master> findBySalonId(UUID salonId);

    Page<Master> findBySalonIdAndIsActiveTrue(UUID salonId, Pageable pageable);

    @Query(
        value = "SELECT m FROM Master m JOIN FETCH m.user WHERE m.salon.id = :salonId AND m.isActive = true",
        countQuery = "SELECT COUNT(m) FROM Master m WHERE m.salon.id = :salonId AND m.isActive = true"
    )
    Page<Master> findBySalonIdAndIsActiveTrueWithUser(@Param("salonId") UUID salonId, Pageable pageable);

    /**
     * Eagerly fetches the master together with its user, salon, and salon owner.
     * Used in authorization checks that run outside an active JPA session.
     */
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            WHERE m.id = :masterId
            """)
    Optional<Master> findByIdWithSalonAndOwner(@Param("masterId") UUID masterId);
}
