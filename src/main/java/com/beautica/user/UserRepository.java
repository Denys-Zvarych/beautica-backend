package com.beautica.user;

import com.beautica.auth.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsBySalonIdAndRole(UUID salonId, Role role);

    @Query("SELECT u.salonId FROM User u WHERE u.id = :userId")
    Optional<UUID> findSalonIdById(@Param("userId") UUID userId);
}
