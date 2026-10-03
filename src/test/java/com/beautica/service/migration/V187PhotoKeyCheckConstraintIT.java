package com.beautica.service.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V187 — chk_service_def_photo_r2_key_format rejects malformed keys, accepts real ones")
class V187PhotoKeyCheckConstraintIT extends AbstractIntegrationTest {

    @ParameterizedTest(name = "rejects [{0}]")
    @ValueSource(strings = {
            "services/../other/x.jpg",
            "services/x..jpg",
            "..",
            "/services/x.jpg",
            "services/x y.jpg",
            "services/x.jpg\n",
            "services/ключ.jpg",
            "services/x?.jpg",
            "services/x:y.jpg",
            "services\\x.jpg",
            ""
    })
    @DisplayName("should_rejectKey_when_formatInvalid")
    void should_rejectKey_when_formatInvalid(String badKey) {
        UUID id = insertDefinition();

        assertThatThrownBy(() -> setKey(id, badKey))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_service_def_photo_r2_key_format");

        assertThat(readKey(id)).isNull();
    }

    @ParameterizedTest(name = "accepts [{0}]")
    @ValueSource(strings = {
            "services/3f2a/1700000000000-abc.webp",
            "a",
            "services/A-b_c.d/E.PNG"
    })
    @DisplayName("should_acceptKey_when_formatValid")
    void should_acceptKey_when_formatValid(String goodKey) {
        UUID id = insertDefinition();

        setKey(id, goodKey);

        assertThat(readKey(id)).isEqualTo(goodKey);
    }

    @Test
    @DisplayName("should_rejectKey_when_longerThan500Chars")
    void should_rejectKey_when_longerThan500Chars() {
        UUID id = insertDefinition();

        assertThatThrownBy(() -> setKey(id, "a".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(readKey(id)).isNull();
    }

    private void setKey(UUID id, String key) {
        jdbcTemplate.update("UPDATE service_definitions SET photo_r2_key = ? WHERE id = ?", key, id);
    }

    private String readKey(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT photo_r2_key FROM service_definitions WHERE id = ?", String.class, id);
    }

    private UUID insertDefinition() {
        UUID typeId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_types WHERE is_active = TRUE LIMIT 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'V187chk', ?, 60, 100.00, 0, TRUE)",
                id, UUID.randomUUID(), typeId);
        return id;
    }
}
