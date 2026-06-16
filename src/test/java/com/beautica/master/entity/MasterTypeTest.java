package com.beautica.master.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link MasterType} enum surface: the exact set of constants and their
 * on-the-wire serialized form.
 *
 * <p>{@code MasterType} declares no {@code @JsonValue}/{@code @JsonFormat} and there is no
 * custom enum {@code SerializationFeature} override in the app's Jackson config, so the
 * wire form is Jackson's default — {@link Enum#name()}. This test locks that contract:
 * the constant order/set and the JSON string each constant serializes to. A rename, a
 * reorder that shifts {@code ordinal()}, or an accidental {@code @JsonValue} on a different
 * field would break a mobile client that matches on these literals — these tests catch it.
 */
@DisplayName("MasterType — enum constants + JSON serialization contract")
class MasterTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("declares exactly SALON_MASTER, INDEPENDENT_MASTER, SALON_OWNER in that order")
    void should_declareExactlyThreeConstants_inDeclaredOrder() {
        assertThat(MasterType.values())
                .as("MasterType constants and their declared order (ordinal-sensitive)")
                .containsExactly(
                        MasterType.SALON_MASTER,
                        MasterType.INDEPENDENT_MASTER,
                        MasterType.SALON_OWNER);
    }

    @Test
    @DisplayName("name() matches the constant literal for every value")
    void should_serializeNameToConstantLiteral_forEveryValue() {
        assertThat(MasterType.SALON_MASTER.name()).isEqualTo("SALON_MASTER");
        assertThat(MasterType.INDEPENDENT_MASTER.name()).isEqualTo("INDEPENDENT_MASTER");
        assertThat(MasterType.SALON_OWNER.name()).isEqualTo("SALON_OWNER");
    }

    @Test
    @DisplayName("Jackson serializes SALON_MASTER to the JSON string \"SALON_MASTER\"")
    void should_serializeSalonMasterToJsonNameString() throws Exception {
        assertThat(objectMapper.writeValueAsString(MasterType.SALON_MASTER))
                .as("Jackson default enum serialization uses name()")
                .isEqualTo("\"SALON_MASTER\"");
    }

    @Test
    @DisplayName("Jackson serializes INDEPENDENT_MASTER to the JSON string \"INDEPENDENT_MASTER\"")
    void should_serializeIndependentMasterToJsonNameString() throws Exception {
        assertThat(objectMapper.writeValueAsString(MasterType.INDEPENDENT_MASTER))
                .isEqualTo("\"INDEPENDENT_MASTER\"");
    }

    @Test
    @DisplayName("Jackson serializes SALON_OWNER to the JSON string \"SALON_OWNER\"")
    void should_serializeSalonOwnerToJsonNameString() throws Exception {
        assertThat(objectMapper.writeValueAsString(MasterType.SALON_OWNER))
                .isEqualTo("\"SALON_OWNER\"");
    }

    @Test
    @DisplayName("Jackson round-trips each constant from its JSON name string back to the enum")
    void should_deserializeJsonNameStringBackToEnum() throws Exception {
        for (MasterType type : MasterType.values()) {
            String json = "\"" + type.name() + "\"";
            assertThat(objectMapper.readValue(json, MasterType.class))
                    .as("round-trip of %s", type.name())
                    .isEqualTo(type);
        }
    }
}
