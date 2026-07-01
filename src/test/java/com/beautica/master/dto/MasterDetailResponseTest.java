package com.beautica.master.dto;

import com.beautica.master.entity.MasterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static MasterDetailResponse fullDetailFor(MasterType masterType, UUID cityUuid, UUID oblastUuid,
            UUID districtUuid) {
        return new MasterDetailResponse(
                UUID.randomUUID(), "Oksana", "Kovalenko", "+380671234567", "Київ",
                "вул. Хрещатик", "1A", "green door",
                "Nail artist", "@oksana.nails", "https://cdn.beautica.test/a.png",
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
        assertThat(publicView.avatarUrl()).isEqualTo(full.avatarUrl());
        assertThat(publicView.avgRating()).isEqualTo(full.avgRating());
        assertThat(publicView.reviewCount()).isEqualTo(full.reviewCount());
        assertThat(publicView.masterType()).isEqualTo(full.masterType());
        assertThat(publicView.salon()).isEqualTo(full.salon());
        assertThat(publicView.workingHours()).isEqualTo(full.workingHours());
    }
}
