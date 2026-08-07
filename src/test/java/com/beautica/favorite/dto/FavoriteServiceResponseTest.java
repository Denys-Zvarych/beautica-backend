package com.beautica.favorite.dto;

import com.beautica.master.entity.Master;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FavoriteServiceResponse} — the BEAUTY WISH LIST row (Phase 31.4).
 *
 * <p>Covers the two price shapes (FIXED / RANGE), the override interactions, duration
 * override, null avatar, and the D2 guarantee that this DTO and
 * {@link MasterServiceResponse} derive money through the SAME code path
 * ({@code ServicePricing}) and can therefore never print different prices for one service.
 *
 * <p>Pure POJO mapping — no Spring, no Hibernate. ASCII-only fixture data except the
 * hryvnia sign and the Ukrainian «від … до …» band, which are the values under test.
 */
@DisplayName("FavoriteServiceResponse — wish-list row mapping")
class FavoriteServiceResponseTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID MASTER_SERVICE_ID = UUID.randomUUID();

    // ── fixtures ───────────────────────────────────────────────────────────────

    private static MasterServiceAssignment assignment(ServiceDefinition sd,
                                                      BigDecimal priceOverride,
                                                      Integer durationOverrideMinutes,
                                                      String avatarUrl) {
        User user = new User("master@beautica.test", "hash",
                com.beautica.auth.Role.INDEPENDENT_MASTER, "Maria", "Levchenko", "+380501234567");
        user.setAvatarUrl(avatarUrl);

        Master master = Master.builder().id(MASTER_ID).user(user).build();

        return MasterServiceAssignment.builder()
                .id(MASTER_SERVICE_ID)
                .master(master)
                .serviceDefinition(sd)
                .priceOverride(priceOverride)
                .durationOverrideMinutes(durationOverrideMinutes)
                .isActive(true)
                .build();
    }

    /**
     * Mirrors what the repository projection hands the factory: the assignment plus the
     * master's identity read as SCALARS (see FavoriteRepository#findFavoriteServiceRows).
     * Sourced here from the fixture's User so the null-avatar case still exercises a null.
     */
    private static FavoriteServiceResponse map(MasterServiceAssignment msa) {
        User u = msa.getMaster().getUser();
        return FavoriteServiceResponse.from(msa, u.getFirstName(), u.getLastName(), u.getAvatarUrl());
    }

    private static ServiceDefinition definition(PriceType priceType,
                                                BigDecimal basePrice,
                                                BigDecimal priceMax) {
        return ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(UUID.randomUUID())
                .name("Manicure")
                .baseDurationMinutes(60)
                .priceType(priceType)
                .basePrice(basePrice)
                .priceMax(priceMax)
                .isActive(true)
                .build();
    }

    // ── price shapes ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("price")
    class Price {

        @Test
        @DisplayName("carries a FIXED price as a single amount with a null ceiling")
        void should_carrySingleAmountAndNullMax_when_priceTypeFixed() {
            var msa = assignment(definition(PriceType.FIXED, new BigDecimal("600.00"), null),
                    null, null, "https://cdn/avatar.png");

            FavoriteServiceResponse response = map(msa);

            assertThat(response.priceType()).isEqualTo(PriceType.FIXED);
            assertThat(response.priceMin()).isEqualByComparingTo("600.00");
            assertThat(response.priceMax()).isNull();
            assertThat(response.priceDisplay()).isEqualTo("600 ₴");
        }

        @Test
        @DisplayName("carries a RANGE price as a frozen band the client renders verbatim")
        void should_carryBand_when_priceTypeRange() {
            var msa = assignment(
                    definition(PriceType.RANGE, new BigDecimal("600.00"), new BigDecimal("900.00")),
                    null, null, null);

            FavoriteServiceResponse response = map(msa);

            assertThat(response.priceType()).isEqualTo(PriceType.RANGE);
            assertThat(response.priceMin()).isEqualByComparingTo("600.00");
            assertThat(response.priceMax()).isEqualByComparingTo("900.00");
            assertThat(response.priceDisplay()).isEqualTo("від 600 до 900 ₴");
        }

        @Test
        @DisplayName("keeps the advertised band coherent when a RANGE service also has a price override")
        void should_keepMinBelowMax_when_rangeServiceHasPriceOverride() {
            // The trap: there is NO priceMaxOverride. Pairing an override-aware floor (1000)
            // with the raw ceiling (900) would emit priceMin > priceMax and a nonsense band.
            var msa = assignment(
                    definition(PriceType.RANGE, new BigDecimal("600.00"), new BigDecimal("900.00")),
                    new BigDecimal("1000.00"), null, null);

            FavoriteServiceResponse response = map(msa);

            assertThat(response.priceMin())
                    .as("display band stays the definition's advertised band, not the override")
                    .isEqualByComparingTo("600.00");
            assertThat(response.priceMin()).isLessThanOrEqualTo(response.priceMax());
            assertThat(response.priceDisplay()).isEqualTo("від 600 до 900 ₴");
        }

        @Test
        @DisplayName("leaves priceDisplay null when a legacy definition carries no price")
        void should_returnNullDisplay_when_definitionHasNoPrice() {
            var msa = assignment(definition(PriceType.FIXED, null, null), null, null, null);

            FavoriteServiceResponse response = map(msa);

            assertThat(response.priceDisplay()).isNull();
            assertThat(response.priceMin()).isNull();
        }
    }

    // ── duration, identity, avatar ─────────────────────────────────────────────

    @Nested
    @DisplayName("row fields")
    class RowFields {

        @Test
        @DisplayName("applies the master's duration override over the definition's base duration")
        void should_useOverride_when_durationOverridePresent() {
            var msa = assignment(definition(PriceType.FIXED, new BigDecimal("600.00"), null),
                    null, 90, null);

            assertThat(map(msa).durationMinutes()).isEqualTo(90);
        }

        @Test
        @DisplayName("falls back to the definition's base duration when no override is set")
        void should_useBaseDuration_when_noDurationOverride() {
            var msa = assignment(definition(PriceType.FIXED, new BigDecimal("600.00"), null),
                    null, null, null);

            assertThat(map(msa).durationMinutes()).isEqualTo(60);
        }

        @Test
        @DisplayName("carries both ids POST /bookings requires, plus the master's name and avatar")
        void should_carryBookingIdsAndMasterIdentity_when_mapped() {
            var msa = assignment(definition(PriceType.FIXED, new BigDecimal("600.00"), null),
                    null, null, "https://cdn/avatar.png");

            FavoriteServiceResponse response = map(msa);

            assertThat(response)
                    .extracting(FavoriteServiceResponse::masterServiceId,
                            FavoriteServiceResponse::masterId,
                            FavoriteServiceResponse::serviceName,
                            FavoriteServiceResponse::masterFirstName,
                            FavoriteServiceResponse::masterLastName,
                            FavoriteServiceResponse::masterAvatarUrl)
                    .containsExactly(MASTER_SERVICE_ID, MASTER_ID, "Manicure",
                            "Maria", "Levchenko", "https://cdn/avatar.png");
        }

        @Test
        @DisplayName("leaves masterAvatarUrl null when the master has no avatar")
        void should_returnNullAvatar_when_masterHasNoAvatar() {
            var msa = assignment(definition(PriceType.FIXED, new BigDecimal("600.00"), null),
                    null, null, null);

            assertThat(map(msa).masterAvatarUrl()).isNull();
        }
    }

    // ── D2: one derivation, two DTOs ───────────────────────────────────────────

    @Nested
    @DisplayName("price parity with MasterServiceResponse (Phase 31.4 D2)")
    class PriceParity {

        @Test
        @DisplayName("prints the identical band as the master's own service menu for a RANGE service with overrides")
        void should_matchMasterServiceResponse_when_sameAssignment() {
            var msa = assignment(
                    definition(PriceType.RANGE, new BigDecimal("600.00"), new BigDecimal("900.00")),
                    new BigDecimal("650.00"), 90, "https://cdn/avatar.png");

            FavoriteServiceResponse wishList = map(msa);
            MasterServiceResponse menu = MasterServiceResponse.from(msa);

            assertThat(wishList.priceDisplay()).isEqualTo(menu.priceDisplay());
            assertThat(wishList.priceType()).isEqualTo(menu.priceType());
            assertThat(wishList.priceMin()).isEqualByComparingTo(menu.priceMin());
            assertThat(wishList.priceMax()).isEqualByComparingTo(menu.priceMax());
            assertThat(wishList.durationMinutes()).isEqualTo(menu.effectiveDurationMinutes());
        }

        @Test
        @DisplayName("prints the identical band as the master's own service menu for a FIXED service")
        void should_matchMasterServiceResponse_when_fixedPrice() {
            var msa = assignment(definition(PriceType.FIXED, new BigDecimal("750.50"), null),
                    null, null, null);

            FavoriteServiceResponse wishList = map(msa);
            MasterServiceResponse menu = MasterServiceResponse.from(msa);

            assertThat(wishList.priceDisplay()).isEqualTo(menu.priceDisplay());
            assertThat(wishList.priceMax()).isNull();
            assertThat(menu.priceMax()).isNull();
        }
    }
}
