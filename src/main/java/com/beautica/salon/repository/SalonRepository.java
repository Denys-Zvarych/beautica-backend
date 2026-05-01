package com.beautica.salon.repository;

import com.beautica.salon.entity.Salon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalonRepository extends JpaRepository<Salon, UUID> {

    List<Salon> findAllByOwnerId(UUID ownerId);

    @Query("SELECT s FROM Salon s JOIN FETCH s.owner WHERE s.owner.id = :ownerId")
    List<Salon> findAllByOwnerIdFetchOwner(@Param("ownerId") UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Salon> findByIdAndOwnerId(UUID id, UUID ownerId);
}
