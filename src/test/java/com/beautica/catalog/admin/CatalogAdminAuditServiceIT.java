package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.HibernateStatistics;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B501 — the audit row joins the caller's transaction (MANDATORY) on a real Postgres with V264 applied; the table is
 * append-only at the database (row trigger), and an insert costs exactly one statement (Persistable, no merge SELECT).
 */
@DisplayName("CatalogAdminAuditService — row commits and rolls back with its parent")
class CatalogAdminAuditServiceIT extends AbstractIntegrationTest {

    private static final AdminActor ACTOR = AdminActor.of("owner@beautica.app");
    private static final String TARGET_ID = "MANICURE";

    @Autowired
    private CatalogAdminAuditService auditService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory emf;

    private int auditRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM catalog_admin_audit WHERE target_id = ?", Integer.class, TARGET_ID);
        return count == null ? 0 : count;
    }

    private void recordRename() {
        Map<String, Object> before = new HashMap<>();
        before.put("displayNameUk", "Манікюр");
        before.put("nameEn", null);
        auditService.record(ACTOR, "RENAME", CatalogAuditTargetType.CATEGORY, TARGET_ID,
                before, Map.of("displayNameUk", "Манікюр і педикюр"));
    }

    @Test
    @DisplayName("a row commits with its parent transaction, fields and jsonb intact")
    void should_persistRow_when_parentCommits() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> recordRename());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor, action, target_type, before ->> 'displayNameUk' AS before_name,"
                        + " jsonb_exists(before, 'nameEn') AS keeps_null_key, after ->> 'displayNameUk' AS after_name,"
                        + " created_at IS NOT NULL AS stamped FROM catalog_admin_audit WHERE target_id = ?", TARGET_ID);
        assertThat(row).containsEntry("actor", "owner@beautica.app")
                .containsEntry("action", "RENAME")
                .containsEntry("target_type", "CATEGORY")
                .containsEntry("before_name", "Манікюр")
                .containsEntry("keeps_null_key", true)
                .containsEntry("after_name", "Манікюр і педикюр")
                .containsEntry("stamped", true);
    }

    @Test
    @DisplayName("a parent rollback leaves no row")
    void should_leaveNoRow_when_parentRollsBack() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recordRename();
            status.setRollbackOnly();
        });

        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("a call outside a transaction throws and writes nothing")
    void should_throw_when_calledOutsideTransaction() {
        assertThatThrownBy(this::recordRename).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("record() issues exactly one INSERT and no SELECT (assigned id does not trigger a merge)")
    void should_issueSingleInsert_when_recordingRow() {
        Statistics statistics = HibernateStatistics.enabledOn(emf);
        statistics.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> recordRename());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("the database rejects an UPDATE of an audit row and leaves it unchanged")
    void should_rejectUpdate_when_rowExists() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> recordRename());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE catalog_admin_audit SET action = 'TAMPERED' WHERE target_id = ?", TARGET_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("catalog_admin_audit is append-only: UPDATE is not allowed");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM catalog_admin_audit WHERE target_id = ?", String.class, TARGET_ID))
                .isEqualTo("RENAME");
    }

    @Test
    @DisplayName("the database rejects a DELETE of an audit row and keeps it")
    void should_rejectDelete_when_rowExists() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> recordRename());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM catalog_admin_audit WHERE target_id = ?", TARGET_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("catalog_admin_audit is append-only: DELETE is not allowed");

        assertThat(auditRows()).isEqualTo(1);
    }
}
