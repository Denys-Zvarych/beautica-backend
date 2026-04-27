package com.beautica.service.repository;

import com.beautica.service.entity.ServiceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<ServiceDefinition, UUID> {

    List<ServiceDefinition> findByOwnerIdAndIsActiveTrue(UUID ownerId);

    List<ServiceDefinition> findByOwnerTypeAndOwnerIdAndIsActiveTrue(String ownerType, UUID ownerId);
}
