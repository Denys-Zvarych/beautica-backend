package com.beautica.master.dto;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit coverage for {@link MasterDetailResponse#fromPublic(MasterDetailResponse)} —
 * no Spring context needed, the masking rule lives entirely in this static factory.
 *
 * <p>Regression guard for the conditional-masking change: {@code phoneNumber} is
 * unconditionally masked for every {@link MasterType}, while street/buildingNo/locationNote/
 * cityId/oblastId/districtId are masked only for {@link MasterType#SALON_MASTER} and
 * {@link MasterType#SALON_OWNER} — an {@link MasterType#INDEPENDENT_MASTER}'s address is the
 * discoverable business location clients need, so it now survives {@code fromPublic()} unmasked.
 * Complements the {@code @WebMvcTest}-level assertions in {@code MasterControllerTest}.
 */
@DisplayName("MasterDetailResponse.fromPublic — conditional address masking by MasterType")
class MasterDetailResponseTest {

    private static final String PROFESSIONAL_TITLE = "Майстер манікюру";

    private static MasterDetailResponse fullDetailFor(MasterType masterType, UUID cityUuid, UUID oblastUuid,
            UUID districtUuid) {
        return new MasterDetailResponse(
                UUID.randomUUID(), "Oksana", "Kovalenko", "+380671234567", "Київ",
                "вул. Хрещатик", "1A", "green door",
                "Nail artist", "@oksana.nails", PROFESSIONAL_TITLE, "https://cdn.beautica.test/a.png",
                new BigDecimal("4.75"), 12, masterType, null, List.of(),
                cityUuid, oblastUuid, districtUuid);
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER: address fields stay unmasked; phoneNumber is masked")
    void should_keepAddressFieldsUnmasked_when_masterTypeIsIndependentMaster() {
        var cityUuid = UUID.randomUUID();
        var oblastUuid = UUID.randomUUID();
        var districtUuid = UUID.randomUUID();
        MasterDetailResponse full = fullDetailFor(MasterType.INDEPENDENT_MASTER, cityUuid, oblastUuid, districtUuid);

        MasterDetailResponse publicView = MasterDetailResponse.fromPublic(full);

        assertThat(publicView.phoneNumber())
                .as("phoneNumber must be masked for every master type").isNull();
        assertThat(publicView.street())
                .as("INDEPENDENT_MASTER street must survive fromPublic() unmasked").isEqualTo(full.street());
        assertThat(publicView.buildingNo())
                .as("INDEPENDENT_MASTER buildingNo must survive fromPublic() unmasked").isEqualTo(full.buildingNo());
        assertThat(publicView.locationNote())
                .as("INDEPENDENT_MASTER locationNote must survive fromPublic() unmasked")
                .isEqualTo(full.locationNote());
        assertThat(publicView.cityId())
                .as("INDEPENDENT_MASTER cityId must survive fromPublic() unmasked").isEqualTo(cityUuid);
        assertThat(publicView.oblastId())
                .as("INDEPENDENT_MASTER oblastId must survive fromPublic() unmasked").isEqualTo(oblastUuid);
        assertThat(publicView.districtId())
                .as("INDEPENDENT_MASTER districtId must survive fromPublic() unmasked").isEqualTo(districtUuid);
    }

    @Test
    @DisplayName("SALON_MASTER: address fields and phoneNumber are masked")
    void should_maskAddressFields_when_masterTypeIsSalonMaster() {
        MasterDetailResponse full = fullDetailFor(MasterType.SALON_MASTER,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        MasterDetailResponse publicView = MasterDetailResponse.fromPublic(full);

        assertThat(publicView.phoneNumber()).as("phoneNumber must be masked").isNull();
        assertThat(publicView.street()).as("SALON_MASTER street must be masked").isNull();
        assertThat(publicView.buildingNo()).as("SALON_MASTER buildingNo must be masked").isNull();
        assertThat(publicView.locationNote()).as("SALON_MASTER locationNote must be masked").isNull();
        assertThat(publicView.cityId()).as("SALON_MASTER cityId must be masked").isNull();
        assertThat(publicView.oblastId()).as("SALON_MASTER oblastId must be masked").isNull();
        assertThat(publicView.districtId()).as("SALON_MASTER districtId must be masked").isNull();
    }

    @Test
    @DisplayName("SALON_OWNER: address fields and phoneNumber are masked")
    void should_maskAddressFields_when_masterTypeIsSalonOwner() {
        MasterDetailResponse full = fullDetailFor(MasterType.SALON_OWNER,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        MasterDetailResponse publicView = MasterDetailResponse.fromPublic(full);

        assertThat(publicView.phoneNumber()).as("phoneNumber must be masked").isNull();
        assertThat(publicView.street()).as("SALON_OWNER street must be masked").isNull();
        assertThat(publicView.buildingNo()).as("SALON_OWNER buildingNo must be masked").isNull();
        assertThat(publicView.locationNote()).as("SALON_OWNER locationNote must be masked").isNull();
        assertThat(publicView.cityId()).as("SALON_OWNER cityId must be masked").isNull();
        assertThat(publicView.oblastId()).as("SALON_OWNER oblastId must be masked").isNull();
        assertThat(publicView.districtId()).as("SALON_OWNER districtId must be masked").isNull();
    }

    @Test
    @DisplayName("non-address fields (name, bio, rating, salon, workingHours) pass through unchanged for every master type")
    void should_preserveNonAddressFields_regardlessOfMasterType() {
        MasterDetailResponse full = fullDetailFor(MasterType.SALON_MASTER,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        MasterDetailResponse publicView = MasterDetailResponse.fromPublic(full);

        assertThat(publicView.masterId()).isEqualTo(full.masterId());
        assertThat(publicView.firstName()).isEqualTo(full.firstName());
        assertThat(publicView.lastName()).isEqualTo(full.lastName());
        assertThat(publicView.city()).isEqualTo(full.city());
        assertThat(publicView.bio()).isEqualTo(full.bio());
        assertThat(publicView.instagram()).isEqualTo(full.instagram());
        assertThat(publicView.professionalTitle()).isEqualTo(full.professionalTitle());
        assertThat(publicView.avatarUrl()).isEqualTo(full.avatarUrl());
        assertThat(publicView.avgRating()).isEqualTo(full.avgRating());
        assertThat(publicView.reviewCount()).isEqualTo(full.reviewCount());
        assertThat(publicView.masterType()).isEqualTo(full.masterType());
        assertThat(publicView.salon()).isEqualTo(full.salon());
        assertThat(publicView.workingHours()).isEqualTo(full.workingHours());
    }

    // ── professionalTitle (V110) — public-facing headline, never masked ────────

    @Test
    @DisplayName("professionalTitle survives fromPublic() unmasked for INDEPENDENT_MASTER (public headline like bio/instagram)")
    void should_keepProfessionalTitleUnmasked_when_masterTypeIsIndependentMaster() {
        MasterDetailResponse full = fullDetailFor(MasterType.INDEPENDENT_MASTER,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        MasterDetailResponse publicView = MasterDetailResponse.fromPublic(full);

        assertThat(publicView.professionalTitle())
                .as("professionalTitle is a public-facing headline — it must survive fromPublic() unmasked")
                .isEqualTo(PROFESSIONAL_TITLE);
    }

    @Test
    @DisplayName("professionalTitle survives fromPublic() unmasked even for SALON_MASTER (headline, not address PII)")
    void should_keepProfessionalTitleUnmasked_when_masterTypeIsSalonMaster() {
        MasterDetailResponse full = fullDetailFor(MasterType.SALON_MASTER,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        MasterDetailResponse publicView = MasterDetailResponse.fromPublic(full);

        assertThat(publicView.professionalTitle())
                .as("professionalTitle is not address PII — it stays visible on the public salon-master card")
                .isEqualTo(PROFESSIONAL_TITLE);
    }

    @Test
    @DisplayName("MasterDetailResponse.from carries professionalTitle from the linked User")
    void should_carryProfessionalTitle_when_builtFromMasterEntity() {
        User user = mock(User.class);
        when(user.getProfessionalTitle()).thenReturn("Візажист");
        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(user);

        MasterDetailResponse response = MasterDetailResponse.from(master, List.of(), null);

        assertThat(response.professionalTitle())
                .as("MasterDetailResponse.from reads professionalTitle off the linked User")
                .isEqualTo("Візажист");
    }

    // ── Zero-review rating normalisation (Phase 240 audit, Finding 3) ─────────────────
    //
    // masters.avg_rating is NOT NULL DEFAULT 0.00 (V4), so an unreviewed master persists a
    // literal 0.00. GET /masters/{id} used to serve that raw while GET /bookings/{id} served
    // null for the same master — the app rendered «—» on the booking and «0.0» on the profile.

    @Test
    @DisplayName("from nulls avgRating when the master has no reviews")
    void should_returnNullAvgRating_when_masterHasNoReviews() {
        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(mock(User.class));
        when(master.getReviewCount()).thenReturn(0);
        // A persisted value that must STILL be suppressed — proves the branch keys off the
        // count, not off a null/zero check on the rating itself.
        when(master.getAvgRating()).thenReturn(new BigDecimal("0.00"));

        MasterDetailResponse response = MasterDetailResponse.from(master, List.of(), null);

        assertThat(response.avgRating())
                .as("an unreviewed master must not be served a fabricated 0.00 rating")
                .isNull();
        assertThat(response.reviewCount())
                .as("zero reviews is a true fact and stays 0, unlike the average")
                .isZero();
    }

    @Test
    @DisplayName("from surfaces a genuine 1.00 average from a single review")
    void should_surfaceGenuineAvgRating_when_masterHasExactlyOneReview() {
        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(mock(User.class));
        when(master.getReviewCount()).thenReturn(1);
        when(master.getAvgRating()).thenReturn(new BigDecimal("1.00"));

        MasterDetailResponse response = MasterDetailResponse.from(master, List.of(), null);

        assertThat(response.avgRating())
                .as("suppression triggers on count == 0 only — a real low rating must survive")
                .isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("fromPublic passes the already-normalised null through unchanged")
    void should_keepNullAvgRating_when_maskingForPublicCaller() {
        MasterDetailResponse full = new MasterDetailResponse(
                UUID.randomUUID(), "Oksana", "Kovalenko", "+380671234567", "Київ",
                "вул. Хрещатик", "1A", "green door", "Nail artist", "@oksana.nails",
                PROFESSIONAL_TITLE, "https://cdn.beautica.test/a.png",
                null, 0, MasterType.SALON_MASTER, null, List.of(), null, null, null);

        MasterDetailResponse publicView = MasterDetailResponse.fromPublic(full);

        assertThat(publicView.avgRating())
                .as("the public masking copy must not resurrect a 0.00 from a null average")
                .isNull();
    }
}
