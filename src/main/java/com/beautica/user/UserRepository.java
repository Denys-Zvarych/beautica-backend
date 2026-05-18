package com.beautica.user;

import com.beautica.auth.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Acquires a PostgreSQL row-level exclusive lock on the user row before the
     * resend-throttle check runs. This serializes concurrent resend requests for
     * the same email so the TOCTOU window between the cooldown read and the OTP
     * write is eliminated.
     *
     * <p>The lock is released when the enclosing transaction commits or rolls back.
     * Early-exit paths (unknown email, already-verified) release the lock immediately
     * without any write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);
}
