package com.beautica.salon.repository;

import com.beautica.salon.entity.Salon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalonRepository extends JpaRepository<Salon, UUID> {

    List<Salon> findAllByOwnerIdAndIsActiveTrue(UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Salon> findByIdAndOwnerId(UUID id, UUID ownerId);
}
