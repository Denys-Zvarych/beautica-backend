package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link LastBookedCategoryLookup}'s empty-collection guard.
 *
 * <h3>Why this class exists at all</h3>
 * The {@code masterIds == null || masterIds.isEmpty()} short-circuit on both methods prevents an
 * empty {@code IN ()} reaching Postgres — which is a <b>syntax error, not an empty result</b>, so
 * a 500 on a read endpoint. Today no caller can trip it: all four {@code FavoriteService} call
 * sites early-return on {@code rows.isEmpty()} before reaching here. That makes the guard latent
 * rather than live, and an <em>untested</em> guard on an unreachable path is exactly the kind of
 * code a future reader deletes as dead.
 *
 * <h3>Why {@code verifyNoInteractions} is the load-bearing assertion</h3>
 * Asserting only that an empty map comes back would pass an implementation that queries the
 * database first and folds an empty result — i.e. one where the guard has been removed and the
 * 500 has been reintroduced. In a unit test the mocked repository returns an empty list for an
 * unstubbed call, so the return-value assertion alone cannot distinguish the two. The proof that
 * the guard is doing its job is that the repository is <b>never reached</b>.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LastBookedCategoryLookup")
class LastBookedCategoryLookupTest {

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private LastBookedCategoryLookup lookup;

    private static final UUID CLIENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Nested
    @DisplayName("master arm")
    class MasterArm {

        @Test
        @DisplayName("returns an empty map WITHOUT querying when the id collection is empty")
        void should_returnEmptyMapWithoutQuerying_when_masterIdsIsEmpty() {
            Map<UUID, String> result = lookup.lastBookedCategoryByMaster(
                    CLIENT_ID, Collections.emptySet());

            assertThat(result).isEmpty();
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("returns an empty map WITHOUT querying when the id collection is null")
        void should_returnEmptyMapWithoutQuerying_when_masterIdsIsNull() {
            Map<UUID, String> result = lookup.lastBookedCategoryByMaster(CLIENT_ID, null);

            assertThat(result).isEmpty();
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("queries and folds the projection when ids are present")
        void should_foldProjectionIntoMap_when_masterIdsArePresent() {
            UUID masterId = UUID.randomUUID();
            when(bookingRepository.findLastBookedCategoryByMasterIds(any(), anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{masterId, "NAIL_SERVICE"}));

            Map<UUID, String> result = lookup.lastBookedCategoryByMaster(
                    CLIENT_ID, List.of(masterId));

            assertThat(result).containsExactly(Map.entry(masterId, "NAIL_SERVICE"));
        }
    }

    @Nested
    @DisplayName("salon arm")
    class SalonArm {

        @Test
        @DisplayName("returns an empty map WITHOUT querying when the id collection is empty")
        void should_returnEmptyMapWithoutQuerying_when_salonIdsIsEmpty() {
            Map<UUID, String> result = lookup.lastBookedCategoryBySalon(
                    CLIENT_ID, Collections.emptySet());

            assertThat(result).isEmpty();
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("returns an empty map WITHOUT querying when the id collection is null")
        void should_returnEmptyMapWithoutQuerying_when_salonIdsIsNull() {
            Map<UUID, String> result = lookup.lastBookedCategoryBySalon(CLIENT_ID, null);

            assertThat(result).isEmpty();
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("queries and folds the projection when ids are present")
        void should_foldProjectionIntoMap_when_salonIdsArePresent() {
            UUID salonId = UUID.randomUUID();
            when(bookingRepository.findLastBookedCategoryBySalonIds(any(), anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{salonId, "HAIRDRESSING"}));

            Map<UUID, String> result = lookup.lastBookedCategoryBySalon(
                    CLIENT_ID, List.of(salonId));

            assertThat(result).containsExactly(Map.entry(salonId, "HAIRDRESSING"));
        }
    }
}
