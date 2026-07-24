package com.beautica.media.service;

import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link MediaFile#getR2Key()} is not serialized to JSON.
 *
 * <p>The {@code r2Key} field holds an internal Cloudflare R2 bucket path that must
 * never be exposed to API consumers. The {@code @JsonIgnore} annotation on the field
 * is the guard; this test proves it is present and effective.
 *
 * <p>Uses a plain {@link ObjectMapper} — no Spring context required.
 */
@DisplayName("MediaFile serialization — r2Key must not be exposed")
class MediaFileSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("r2Key value and field name are absent from JSON serialization output")
    void should_notSerializeR2Key_when_mediaFileIsSerializedToJson() throws Exception {
        // Arrange
        String knownR2Key = "portfolio/salons/" + UUID.randomUUID() + "/photo.jpg";
        MediaFile mediaFile = MediaFile.builder()
                .id(UUID.randomUUID())
                .entityType(EntityType.SALON)
                .entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO)
                .r2Key(knownR2Key)
                .r2Url("https://pub.r2.dev/" + UUID.randomUUID() + "/photo.jpg")
                .build();

        // Act
        String json = objectMapper.writeValueAsString(mediaFile);

        // Assert — neither the value nor the field names appear in the output
        assertThat(json)
                .as("r2Key value must not appear in serialized JSON")
                .doesNotContain(knownR2Key);
        assertThat(json)
                .as("r2Key field name must not appear in serialized JSON")
                .doesNotContain("r2Key");
        assertThat(json)
                .as("r2_key field name must not appear in serialized JSON")
                .doesNotContain("r2_key");
    }
}
