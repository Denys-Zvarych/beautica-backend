package com.beautica.salon.dto;

import com.beautica.TestConstants;
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
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .avgRating(new BigDecimal("4.50")) // stale/leftover value — must still be nulled
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.avgRating())
                .as("avgRating must be null, not a fabricated value, when reviewCount is 0")
                .isNull();
        assertThat(response.reviewCount()).isZero();
    }

    @Test
    @DisplayName("returns the persisted avgRating when reviewCount is greater than 0")
    void should_returnPersistedAvgRating_when_reviewCountIsPositive() {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .avgRating(new BigDecimal("4.75"))
                .reviewCount(12)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.avgRating()).isEqualByComparingTo("4.75");
        assertThat(response.reviewCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("maps coverImageUrl through unchanged")
    void should_mapCoverImageUrl_when_present() {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .coverImageUrl("https://cdn.example.com/cover.jpg")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.coverImageUrl()).isEqualTo("https://cdn.example.com/cover.jpg");
    }

    @Test
    @DisplayName("maps phone through unchanged (public business contact, deliberate — see DTO javadoc)")
    void should_mapPhone_when_present() {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .phone("+380671234567")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.phone())
                .as("the salon phone is the client-facing contact rendered next to instagramUrl; "
                        + "dropping it left the mobile «Контакти» block blank until an unrelated PATCH")
                .isEqualTo("+380671234567");
    }

    @Test
    @DisplayName("leaves phone null when the salon has none")
    void should_returnNullPhone_when_salonHasNone() {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.phone()).isNull();
    }

    @Test
    @DisplayName("serves an empty-string phone verbatim — never normalised to null (locked wire contract)")
    void should_servePhoneVerbatim_when_salonPhoneIsEmptyString() {
        // A salon whose owner cleared the phone stores "" (UpdateSalonRequest treats null as
        // "omitted from this PATCH" and "" as the explicit clear signal — see SalonServiceTest
        // #should_clearPhone_when_updateSalonSendsEmptyString). from() is a pure passthrough: the
        // wire may carry null OR "" for "no phone", and the mobile client is defensive about both.
        // Locked product decision — do NOT add blank-to-null normalisation here; it would silently
        // change the wire shape (and SalonResponse's existing behaviour) for a cosmetic gain.
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .phone("")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.phone())
                .as("a persisted empty-string phone must be served as \"\", not tidied into null")
                .isEmpty();
    }

    @Test
    @DisplayName("does not expose the salon owner or any other internal id (public DTO, §I)")
    void should_notExposeOwnerId_onPublicDto() {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.getClass().getRecordComponents())
                .as("PublicSalonResponse must never grow an ownerId/owner-identifying field")
                .noneMatch(c -> c.getName().toLowerCase().contains("owner"));
    }

    // ── oblastId passthrough — resolution itself is tested in SalonServiceTest ────────
    // (getPublicSalon), mirroring SalonResponseTest/SalonServiceTest for SalonResponse#oblastId.
    // `from` never resolves oblastId itself; it only carries whatever the caller resolved.

    @Test
    @DisplayName("carries the caller-resolved oblastId through unchanged")
    void should_mapOblastId_when_present() {
        UUID cityId = UUID.randomUUID();
        UUID oblastId = UUID.randomUUID();
        Salon salon = Salon.builder()
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .cityId(cityId)
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, oblastId);

        assertThat(response.cityId()).isEqualTo(cityId);
        assertThat(response.oblastId())
                .as("from() is a pure passthrough — resolution is the caller's job")
                .isEqualTo(oblastId);
    }

    @Test
    @DisplayName("leaves oblastId null when the caller passes null (from() never re-derives it from the salon)")
    void should_returnNullOblastId_when_callerResolvesNull() {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .name("Beauty Bar")
                .reviewCount(0)
                .build();

        PublicSalonResponse response = PublicSalonResponse.from(salon, null);

        assertThat(response.oblastId()).isNull();
    }
}
