package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V122__service_def_owner_type_active_index.sql} — the migration that
 * closed a silent drift between {@code ServiceDefinition}'s {@code @Table(indexes = ...)} and the
 * real schema.
 *
 * <p><b>Why this drift was invisible.</b> {@code ServiceDefinition} declared two indices,
 * {@code idx_service_def_owner_active} and {@code idx_service_def_owner_type_active}, that existed
 * in NO migration. Hibernate 6.5's {@code hbm2ddl.auto=validate} does not check
 * {@code @Table(indexes = ...)} against the live schema (empirically confirmed in the Phase 26.8
 * audit — see {@code V118__drop_price_sort_indices.sql}'s header), so an {@code @Index} naming a
 * nonexistent index never fails a boot and no existing test could catch it. Hence the LAST test
 * here, which closes the loop generically rather than only pinning this one migration.
 *
 * <p><b>Why the two were resolved in opposite directions.</b>
 * {@code idx_service_def_owner_type_active (owner_type, owner_id, is_active)} has a real production
 * caller — {@code ServiceRepository#findBookableServicesBySalon}, the salon public-profile bookable
 * catalogue, filters exactly those three columns — so V122 creates it.
 * {@code idx_service_def_owner_active (owner_id, is_active)} has none: nothing queries
 * {@code owner_id} without the {@code owner_type} discriminator that says which table it points
 * into, so it was deleted from the entity rather than built.
 *
 * <p>Read-only against {@code pg_indexes} on the fully migrated chain, so it doubles as the
 * "applies cleanly on a fresh DB" proof. No fixture or cleanup required. ASCII-only.
 */
@DisplayName("V122 migration — create the owner_type/owner_id/is_active index, drop its redundant prefix")
class V122ServiceDefOwnerTypeActiveIndexMigrationTest extends AbstractIntegrationTest {

    private static final String TABLE = "service_definitions";

    private static final String INDEX_EXISTS_QUERY = """
            SELECT COUNT(*)
            FROM pg_indexes
            WHERE tablename = ?
              AND indexname = ?
            """;

    private static final String INDEX_DEF_QUERY = """
            SELECT indexdef
            FROM pg_indexes
            WHERE tablename = ?
              AND indexname = ?
            """;

    private boolean indexExists(String indexName) {
        Integer n = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY, Integer.class, TABLE, indexName);
        return n != null && n == 1;
    }

    private List<String> indexColumns(String indexName) {
        String indexDef = jdbcTemplate.queryForObject(INDEX_DEF_QUERY, String.class, TABLE, indexName);
        assertThat(indexDef).as("index %s must exist", indexName).isNotNull();

        String columnList = indexDef.replaceFirst(".*USING btree \\(([^)]*)\\).*", "$1");
        return Arrays.stream(columnList.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("idx_service_def_owner_type_active exists with exactly (owner_type, owner_id, is_active)")
    void should_createOwnerTypeActiveIndex_when_v122Applied() {
        assertThat(indexExists("idx_service_def_owner_type_active"))
                .as("the entity has declared this index all along; V122 is what finally created it")
                .isTrue();

        assertThat(indexColumns("idx_service_def_owner_type_active"))
                .as("column ORDER is load-bearing: findBookableServicesBySalon filters "
                        + "owner_type = SALON AND owner_id = :salonId AND is_active = true, and only "
                        + "this leading order lets Postgres satisfy all three from the index")
                .containsExactly("owner_type", "owner_id", "is_active");
    }

    /**
     * Deliberately NOT partial. A {@code WHERE is_active = true} partial index would be smaller, but
     * JPA's {@code @Index} cannot express a partial index — that is exactly why V121's
     * {@code ux_service_def_owner_service_type_active} is absent from the entity (see
     * {@code ServiceDefinition}'s class javadoc). Making this one partial would recreate the same
     * annotation-cannot-mirror-schema gap V122 exists to close, and would also stop it subsuming the
     * unfiltered {@code (owner_type, owner_id)} lookups that let the old index be dropped.
     */
    @Test
    @DisplayName("idx_service_def_owner_type_active is a full index, not a partial one")
    void should_beNonPartial_when_ownerTypeActiveIndexInspected() {
        String indexDef = jdbcTemplate.queryForObject(
                INDEX_DEF_QUERY, String.class, TABLE, "idx_service_def_owner_type_active");

        assertThat(indexDef)
                .as("a WHERE clause here would make the index inexpressible as a JPA @Index and "
                        + "reintroduce the very drift this migration closed")
                .doesNotContain("WHERE");
    }

    @Test
    @DisplayName("idx_service_def_owner is dropped — a strict leading prefix of the new index")
    void should_dropRedundantOwnerIndex_when_v122Applied() {
        assertThat(indexExists("idx_service_def_owner"))
                .as("V6's (owner_type, owner_id) is a strict leading prefix of the new "
                        + "(owner_type, owner_id, is_active), so Postgres serves every read it used "
                        + "to from the wider btree — keeping both would pay two index writes per "
                        + "service insert/update for one index's worth of read benefit")
                .isFalse();
    }

    @Test
    @DisplayName("idx_service_def_owner_active was never created — it has no justifying query")
    void should_notCreateOwnerIdLeadingIndex_when_v122Applied() {
        assertThat(indexExists("idx_service_def_owner_active"))
                .as("no query filters service_definitions on owner_id without owner_type, so this "
                        + "index would cost a write on every service mutation to serve nothing — it "
                        + "was removed from the entity rather than built")
                .isFalse();
    }

    @Test
    @DisplayName("V121's partial unique index survives V122 untouched")
    void should_keepPartialUniqueIndex_when_v122Applied() {
        assertThat(indexExists("ux_service_def_owner_service_type_active"))
                .as("V122 must not disturb the duplicate-service guard V121 installed")
                .isTrue();
    }

    /**
     * The generic guard, and the real point of this class: every index {@code ServiceDefinition}
     * declares must actually exist. {@code ddl-auto=validate} will not do this for us, so without an
     * explicit test an aspirational {@code @Index} can sit in the entity indefinitely, telling every
     * future reader the planner has an index it has never seen.
     *
     * <p>Note the asymmetry — this asserts declared-implies-real, NOT the converse. Real-but-undeclared
     * is fine and expected: {@code idx_service_def_category}, the GIN trigram
     * {@code idx_service_definitions_name_trgm} (V98) and V121's partial unique index are all absent
     * from the entity because {@code @Index} cannot express GIN or partial indices at all.
     */
    @Test
    @DisplayName("every @Index declared on ServiceDefinition exists in the real schema")
    void should_haveRealIndexForEveryDeclaredIndex_when_entityAnnotationsInspected() {
        Table table = com.beautica.service.entity.ServiceDefinition.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.indexes())
                .as("guard is vacuous if the entity stops declaring indexes")
                .isNotEmpty();

        for (Index declared : table.indexes()) {
            assertThat(indexExists(declared.name()))
                    .as("ServiceDefinition declares @Index(name = \"%s\", columnList = \"%s\") but no "
                            + "such index exists — ddl-auto=validate does NOT catch this, so the "
                            + "annotation would silently misinform every reader", declared.name(),
                            declared.columnList())
                    .isTrue();

            assertThat(indexColumns(declared.name()))
                    .as("index %s exists but its real columns differ from the entity's declaration",
                            declared.name())
                    .containsExactlyElementsOf(
                            Arrays.stream(declared.columnList().split(","))
                                    .map(String::trim)
                                    .collect(Collectors.toList()));
        }
    }
}
