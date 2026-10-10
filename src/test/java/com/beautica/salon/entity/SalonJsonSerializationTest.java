package com.beautica.salon.entity;

import com.beautica.config.JsonConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 343 audit (SEC LOW) — the {@code Salon} entity's R2 object keys are internal storage handles and must
 * never reach a JSON body, even if future code accidentally serialises the entity. Mirrors the
 * {@code @JsonIgnore} on {@code User.avatarR2Key} / {@code MediaFile.r2Key}. Uses the app's Jackson
 * configuration ({@link JsonTest} slice + {@link JsonConfig}), not a hand-built {@code new ObjectMapper()}.
 */
@JsonTest
@Import(JsonConfig.class)
@DisplayName("Salon entity JSON — R2 keys never serialised")
class SalonJsonSerializationTest {

    private static final UUID SALON_ID = UUID.randomUUID();
    private static final String LOGO_URL = "https://cdn.example/salons/" + SALON_ID + "/logo/l.jpg";
    private static final String COVER_URL = "https://cdn.example/salons/" + SALON_ID + "/cover/c.jpg";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("serialising a Salon with logo + cover set omits avatarR2Key and coverR2Key")
    void should_omitR2Keys_when_salonSerialised() throws Exception {
        // Arrange
        Salon salon = Salon.builder().id(SALON_ID).name("Json Salon").build();
        salon.replaceImage(SalonImageSlot.LOGO, LOGO_URL, SalonImageSlot.LOGO.keyPrefix(SALON_ID) + "l.jpg");
        salon.replaceImage(SalonImageSlot.COVER, COVER_URL, SalonImageSlot.COVER.keyPrefix(SALON_ID) + "c.jpg");

        // Act
        String json = objectMapper.writeValueAsString(salon);
        JsonNode root = objectMapper.readTree(json);

        // Assert — keys absent, while the public URLs still serialise (proves the fixture populated the slots)
        assertThat(root.has("avatarR2Key")).as("avatarR2Key leaked: %s", json).isFalse();
        assertThat(root.has("coverR2Key")).as("coverR2Key leaked: %s", json).isFalse();
        assertThat(json).as("no raw R2 key value anywhere in the body").doesNotContain("\"salons/");
        assertThat(root.path("avatarUrl").asText()).isEqualTo(LOGO_URL);
        assertThat(root.path("coverImageUrl").asText()).isEqualTo(COVER_URL);
    }
}
