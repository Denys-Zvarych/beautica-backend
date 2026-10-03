package com.beautica.service.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V187 — service_definitions.photo_r2_key")
class V187MigrationIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("the column exists, is nullable and VARCHAR(500)")
    void should_addNullableVarchar500Column() {
        var row = jdbcTemplate.queryForMap(
                "SELECT is_nullable, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'service_definitions' AND column_name = 'photo_r2_key'");

        assertThat(row.get("is_nullable")).isEqualTo("YES");
        assertThat(((Number) row.get("character_maximum_length")).intValue()).isEqualTo(500);
    }

    // The format CHECK is covered with constraint names by V187PhotoKeyCheckConstraintIT.
}
