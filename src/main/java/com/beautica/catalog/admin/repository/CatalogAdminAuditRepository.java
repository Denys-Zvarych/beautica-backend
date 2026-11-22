package com.beautica.catalog.admin.repository;

import com.beautica.catalog.admin.entity.CatalogAdminAudit;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/**
 * Append-only by construction (B501): extends the bare {@link Repository} marker instead of {@code JpaRepository},
 * so no update, delete or deleteAll method exists for the audit table. Add a read finder (bounded by a
 * {@code Pageable}) only when a phase needs the history read.
 */
public interface CatalogAdminAuditRepository extends Repository<CatalogAdminAudit, UUID> {

    CatalogAdminAudit save(CatalogAdminAudit audit);
}
