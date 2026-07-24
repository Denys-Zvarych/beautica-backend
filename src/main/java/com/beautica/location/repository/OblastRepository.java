package com.beautica.location.repository;

import com.beautica.location.entity.Oblast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access interface for {@link Oblast} reference data.
 *
 * <p>All reads are read-only from the application perspective; no write
 * methods are defined here — mutations are exclusively via Flyway migrations.
 */
public interface OblastRepository extends JpaRepository<Oblast, UUID> {

    /**
     * Returns all oblasts sorted alphabetically by Ukrainian name.
     * Used to populate the top-level tier of the cascading locality picker.
     */
    List<Oblast> findAllByOrderByNameUkAsc();

    /**
     * Looks up an oblast by its stable KATOTTH code.
     * Used by Phase 10.2 seed validation and Phase 10.3 FK resolution.
     *
     * @param katotthCode official KATOTTH oblast code
     * @return the matching oblast, if present
     */
    Optional<Oblast> findByKatotthCode(String katotthCode);
}
