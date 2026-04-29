package com.beautica.salon.repository;

import com.beautica.salon.entity.Salon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SalonRepository extends JpaRepository<Salon, UUID> {

    Optional<Salon> findByOwnerId(UUID ownerId);

    boolean existsByOwnerId(UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);
}
