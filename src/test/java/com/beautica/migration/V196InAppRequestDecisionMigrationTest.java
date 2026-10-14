package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for V196__inapp_request_decision_notifications.sql: the catalog-level shape (columns, FK
 * delete rules, partial indexes, constraint names kept) that the behavioural checks in
 * {@code InAppNotificationRepositoryIT} and {@code RequestDecisionNotificationIT} rely on.
 */
@DisplayName("V196 migration — in-app request-decision notifications")
class V196InAppRequestDecisionMigrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("decision_reason is a nullable VARCHAR(300) on both request tables")
    void should_haveNullableDecisionReasonVarchar300_when_requestTablesRead() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT table_name, is_nullable, character_maximum_length FROM information_schema.columns
                 WHERE column_name = 'decision_reason' AND table_name IN ('platform_categories','service_type_suggestion')
                """);

        assertThat(columns).hasSize(2).allSatisfy(c -> {
            assertThat(c.get("is_nullable")).isEqualTo("YES");
            assertThat(((Number) c.get("character_maximum_length")).intValue()).isEqualTo(300);
        });
    }

    @Test
    @DisplayName("the two new feed FK columns have the right types and are ON DELETE CASCADE")
    void should_haveCascadingNullableFkColumns_when_feedTableRead() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                 WHERE table_name = 'in_app_notification'
                   AND column_name IN ('category_request_id','service_type_suggestion_id')
                """);
        List<String> cascadeTargets = jdbcTemplate.queryForList("""
                SELECT confrelid::regclass::text FROM pg_constraint
                 WHERE conrelid = 'in_app_notification'::regclass AND contype = 'f' AND confdeltype = 'c'
                   AND conkey IN (SELECT ARRAY[attnum] FROM pg_attribute WHERE attrelid = 'in_app_notification'::regclass
                                   AND attname IN ('category_request_id','service_type_suggestion_id'))
                """, String.class);

        assertThat(columns).extracting(c -> c.get("column_name") + ":" + c.get("data_type") + ":" + c.get("is_nullable"))
                .containsExactlyInAnyOrder("category_request_id:bigint:YES", "service_type_suggestion_id:uuid:YES");
        assertThat(cascadeTargets).containsExactlyInAnyOrder("platform_categories", "service_type_suggestion");
    }

    @Test
    @DisplayName("both FK columns are covered by a partial 'IS NOT NULL' index")
    void should_havePartialIndexes_when_feedTableRead() {
        List<String> defs = jdbcTemplate.queryForList("""
                SELECT indexdef FROM pg_indexes WHERE tablename = 'in_app_notification'
                   AND indexname IN ('in_app_notification_category_request_idx','in_app_notification_service_type_suggestion_idx')
                """, String.class);

        assertThat(defs).hasSize(2).allSatisfy(d -> assertThat(d).contains("IS NOT NULL"));
    }

    @Test
    @DisplayName("type/shape/dedup CHECKs keep their original names; type CHECK lists the 4 new literals")
    void should_keepConstraintNamesAndWidenTypeCheck_when_constraintsRead() {
        List<String> names = jdbcTemplate.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'in_app_notification'::regclass AND contype = 'c'
                """, String.class);
        String typeDef = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'in_app_notification_type_chk'",
                String.class);

        assertThat(names).contains("in_app_notification_type_chk", "in_app_notification_shape_chk",
                "in_app_notification_dedup_key_chk");
        assertThat(typeDef).contains("CATEGORY_REQUEST_APPROVED", "CATEGORY_REQUEST_REJECTED",
                "SERVICE_TYPE_SUGGESTION_APPROVED", "SERVICE_TYPE_SUGGESTION_REJECTED", "INVITE_ACCEPTED");
    }

    @Test
    @DisplayName("the V181 BEFORE INSERT trigger for empty INVITE_ACCEPTED rows is untouched")
    void should_keepInviteAcceptedTrigger_when_migrated() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgname = 'in_app_notification_reject_empty_invite_accepted_trg' AND NOT tgisinternal
                """, Long.class)).isEqualTo(1L);
    }
}
