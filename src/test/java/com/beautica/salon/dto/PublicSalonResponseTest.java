package com.beautica.salon.dto;

import com.beautica.salon.entity.Salon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicSalonResponse.from — unit")
class PublicSalonResponseTest {

    @Test
    @DisplayName("returns null avgRating when reviewCount is 0, even if a value is persisted")
    void should_returnNullAvgRating_when_reviewCountIsZero() {
        Salon salon = Salon.builder()
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .avgRating(new BigDecimal("4.50")) // stale/leftover value — must still be nulled
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon);

        assertThat(response.avgRating())
                .as("avgRating must be null, not a fabricated value, when reviewCount is 0")
                .isNull();
        assertThat(response.reviewCount()).isZero();
    }

    @Test
    @DisplayName("returns the persisted avgRating when reviewCount is greater than 0")
    void should_returnPersistedAvgRating_when_reviewCountIsPositive() {
        Salon salon = Salon.builder()
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .avgRating(new BigDecimal("4.75"))
                .reviewCount(12)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon);

        assertThat(response.avgRating()).isEqualByComparingTo("4.75");
        assertThat(response.reviewCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("maps coverImageUrl through unchanged")
    void should_mapCoverImageUrl_when_present() {
        Salon salon = Salon.builder()
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .coverImageUrl("https://cdn.example.com/cover.jpg")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon);

        assertThat(response.coverImageUrl()).isEqualTo("https://cdn.example.com/cover.jpg");
    }

    @Test
    @DisplayName("does not expose the salon owner or any other internal id (public DTO, §I)")
    void should_notExposeOwnerId_onPublicDto() {
        Salon salon = Salon.builder()
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon);

        assertThat(response.getClass().getRecordComponents())
                .as("PublicSalonResponse must never grow an ownerId/owner-identifying field")
                .noneMatch(c -> c.getName().toLowerCase().contains("owner"));
    }
}
