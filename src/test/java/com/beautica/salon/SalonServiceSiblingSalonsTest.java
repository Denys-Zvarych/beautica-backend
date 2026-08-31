package com.beautica.salon;

import com.beautica.location.repository.CityRepository;
import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalonService#getSiblingSalons} (Phase 21.3b).
 *
 * <p>Authorization is NOT covered here — it lives entirely in the controller's
 * {@code @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and
 * &#64;authz.canManageSalon(authentication, #salonId)")} gate (the byte-identical expression
 * already guarding {@code GET /{salonId}/staff}) and is proven end-to-end by
 * {@code SalonSiblingSalonsEndpointIT}.
 *
 * <p>What these tests pin is the wiring the integration test cannot see: that the service reads the
 * <b>self-excluding, owner-scoped</b> repository query and not the caller-scoped
 * {@code findAllByOwnerIdAndIsActiveTrue} that backs {@code GET /salons/mine} (which would both
 * include the source salon and resolve the wrong owner), and that the picker costs exactly ONE
 * statement with no post-processing of any kind.
 *
 * <p><b>Since Perf LOW-B the repository returns {@link SiblingSalonOption} directly</b> — a JPQL
 * constructor projection selecting {@code id, name, street, buildingNo} — so the service holds no
 * {@code Salon} entity at all. The old "the mapper never dereferences {@code owner}" test is
 * therefore obsolete as a unit assertion (there is no mapper left to dereference anything); it is
 * replaced below by the stronger property that the service does <em>no</em> per-row work, and the
 * emitted-SQL shape it used to stand in for is pinned for real, against Postgres, by
 * {@code SalonSiblingProjectionShapeIT}.
 *
 * <p>Only the two collaborators these assertions actually exercise are mocked. Seven further
 * {@code @Mock} fields ({@code userRepository}, {@code inviteService}, {@code masterRepository},
 * {@code localityWriteValidator}, {@code masterService}, {@code cacheManager},
 * {@code authorizationService}) were never stubbed and never verified — Mockito's constructor
 * injection simply passes {@code null} for the arguments they used to fill, which
 * {@code getSiblingSalons} never touches.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService.getSiblingSalons — unit")
class SalonServiceSiblingSalonsTest {

    @Mock
    private SalonRepository salonRepository;

    /**
     * Not stubbed — verified. It backs the batch oblast resolution that {@code GET /salons/mine}
     * still performs, and the picker must NOT: {@link SiblingSalonOption} carries no
     * {@code oblastId}, so a second statement here would be pure waste.
     */
    @Mock
    private CityRepository cityRepository;

    @InjectMocks
    private SalonService salonService;

    @Test
    @DisplayName("returns every sibling row the self-excluding owner-scoped query produced")
    void should_mapEverySiblingRow_when_ownerHasOtherSalons() {
        // Arrange
        UUID sourceSalonId = UUID.randomUUID();
        SiblingSalonOption siblingA = option("Sibling A", "вул. Хрещатик", "1");
        SiblingSalonOption siblingB = option("Sibling B", "вул. Соборна", "42А");
        when(salonRepository.findActiveSiblingsBySalonId(sourceSalonId))
                .thenReturn(List.of(siblingA, siblingB));

        // Act
        List<SiblingSalonOption> result = salonService.getSiblingSalons(sourceSalonId);

        // Assert
        assertThat(result)
                .as("every row the sibling query returns must reach the response, unfiltered and in "
                        + "the query's order, carrying the id the caller will submit plus the text "
                        + "it renders")
                .containsExactly(siblingA, siblingB);
    }

    @Test
    @DisplayName("reads the self-excluding sibling query, never the /salons/mine owner query")
    void should_useSelfExcludingSiblingQuery_when_listingSiblings() {
        // Arrange — the exclusion of the source salon is a QUERY predicate, so reading the
        // GET /salons/mine finder instead would silently re-introduce the source salon as a
        // rotation destination (and scope to the CALLER's owner, not the salon's owner).
        UUID sourceSalonId = UUID.randomUUID();
        when(salonRepository.findActiveSiblingsBySalonId(sourceSalonId))
                .thenReturn(List.of());

        // Act
        salonService.getSiblingSalons(sourceSalonId);

        // Assert
        verify(salonRepository).findActiveSiblingsBySalonId(sourceSalonId);
        verify(salonRepository, never()).findAllByOwnerIdAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("returns an empty list when the owner has no other active salon")
    void should_returnEmptyList_when_ownerHasNoOtherSalon() {
        // Arrange
        UUID sourceSalonId = UUID.randomUUID();
        when(salonRepository.findActiveSiblingsBySalonId(sourceSalonId))
                .thenReturn(List.of());

        // Act
        List<SiblingSalonOption> result = salonService.getSiblingSalons(sourceSalonId);

        // Assert
        assertThat(result)
                .as("a single-salon owner has no rotation destination — empty list, not an error")
                .isEmpty();
    }

    @Test
    @DisplayName("costs ONE statement — no oblast resolution, however many siblings come back")
    void should_issueNoOblastQuery_when_siblingsSpanMultipleCities() {
        // Arrange — while the picker returned SalonResponse it had to batch-resolve each sibling's
        // oblast, making every picker open two statements. SiblingSalonOption has no oblastId (and
        // since Perf LOW-B the projection does not even select city_id), so that second statement
        // must be gone entirely — not merely batched (§E).
        UUID sourceSalonId = UUID.randomUUID();
        when(salonRepository.findActiveSiblingsBySalonId(sourceSalonId))
                .thenReturn(List.of(
                        option("Sibling A", "вул. Хрещатик", "1"),
                        option("Sibling B", "вул. Соборна", "42А")));

        // Act
        List<SiblingSalonOption> result = salonService.getSiblingSalons(sourceSalonId);

        // Assert
        assertThat(result).hasSize(2);
        verify(cityRepository, never()).findOblastIdsByIdIn(anyCollection());
    }

    @Test
    @DisplayName("does no per-row work at all — the projection rows are returned verbatim")
    void should_returnProjectionRowsVerbatim_when_repositoryProjectsTheDto() {
        // Arrange — the successor to the old "the mapper never dereferences getOwner()" test. That
        // property is now structural: SalonRepository#findActiveSiblingsBySalonId is a constructor
        // projection, so no Salon entity ever reaches this service and there is nothing to
        // dereference. What is still worth pinning is that the service adds no step of its own —
        // any filtering, re-sorting or re-mapping here would be a second place the picker's
        // contract could drift from the query predicate that SalonSiblingRotationParityIT binds to
        // rotateAdmin. A row carrying nulls in both nullable address fields (a pre-Phase-10.6
        // salon) proves it is passed through rather than reconstructed.
        UUID sourceSalonId = UUID.randomUUID();
        SiblingSalonOption addresslessSibling = option("Addressless Sibling", null, null);
        when(salonRepository.findActiveSiblingsBySalonId(sourceSalonId))
                .thenReturn(List.of(addresslessSibling));

        // Act
        List<SiblingSalonOption> result = salonService.getSiblingSalons(sourceSalonId);

        // Assert
        assertThat(result)
                .singleElement()
                .as("a sibling with no structured address must survive untouched — the service "
                        + "neither drops it nor substitutes anything for its null fields")
                .isSameAs(addresslessSibling);
        verify(salonRepository).findActiveSiblingsBySalonId(sourceSalonId);
        verifyNoMoreInteractions(salonRepository, cityRepository);
    }

    /** One projected picker row, as the repository's constructor projection would build it. */
    private SiblingSalonOption option(String name, String street, String buildingNo) {
        return new SiblingSalonOption(UUID.randomUUID(), name, street, buildingNo);
    }
}
