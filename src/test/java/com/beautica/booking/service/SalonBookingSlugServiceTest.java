package com.beautica.booking.service;

import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.MasterRepository.SalonBookingPageMasterRow;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingPageRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalonBookingSlugService — public salon booking page (phase 377)")
class SalonBookingSlugServiceTest {

    private static final String SLUG = "salon-ab12";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Mock private SalonRepository salonRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private ScheduleDateMath scheduleDateMath;
    @Mock private com.beautica.media.service.MediaService mediaService;
    @Mock private com.beautica.service.service.ServiceCatalogService serviceCatalogService;
    @Mock private com.beautica.review.service.ReviewService reviewService;
    @Mock private RetiredBookingSlugGuard retiredBookingSlugGuard;
    @InjectMocks private SalonBookingSlugService service;

    /** Phase 384: the four tab blocks default to "nothing to show" (lenient: not every test reads them). */
    private void emptyTabs() {
        org.mockito.Mockito.lenient().when(mediaService.getLatestPortfolio(any(), any(), anyInt())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(serviceCatalogService.getSalonCatalogSnapshot(any()))
                .thenReturn(com.beautica.service.dto.SalonCatalogSnapshot.EMPTY);
        org.mockito.Mockito.lenient().when(reviewService.getSalonReviewSummary(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.beautica.review.dto.SalonReviewSummaryResponse(null, 0, List.of(
                        new com.beautica.common.RatingBucket(5, 0), new com.beautica.common.RatingBucket(4, 0),
                        new com.beautica.common.RatingBucket(3, 0), new com.beautica.common.RatingBucket(2, 0),
                        new com.beautica.common.RatingBucket(1, 0))));
        org.mockito.Mockito.lenient().when(reviewService.getLatestReviewsForSalon(any(), anyInt())).thenReturn(List.of());
    }

    private final UUID salonId = UUID.randomUUID();

    private SalonBookingPageRow salonRow(int reviewCount, BigDecimal avgRating) {
        emptyTabs();
        when(scheduleDateMath.today()).thenReturn(TODAY);
        SalonBookingPageRow row = mock(SalonBookingPageRow.class);
        when(row.getId()).thenReturn(salonId);
        when(row.getName()).thenReturn("Salon");
        when(row.getCity()).thenReturn("Вінниця");
        when(row.getAvatarUrl()).thenReturn("https://cdn.beautica.test/logo.jpg");
        when(row.getCoverImageUrl()).thenReturn("https://cdn.beautica.test/cover.jpg");
        when(row.getReviewCount()).thenReturn(reviewCount);
        if (reviewCount > 0) {
            when(row.getAvgRating()).thenReturn(avgRating);
        }
        when(salonRepository.findBookingPageRowBySlug(SLUG)).thenReturn(Optional.of(row));
        return row;
    }

    private static SalonBookingPageMasterRow masterRow(String slug, String first, String last) {
        SalonBookingPageMasterRow row = mock(SalonBookingPageMasterRow.class);
        when(row.getBookingSlug()).thenReturn(slug);
        when(row.getFirstName()).thenReturn(first);
        when(row.getLastName()).thenReturn(last);
        when(row.getAvatarUrl()).thenReturn(null);
        when(row.getProfessionalTitle()).thenReturn("Майстер");
        org.mockito.Mockito.lenient().when(row.getReviewCount()).thenReturn(0);
        return row;
    }

    @Test
    @DisplayName("unknown or inactive slug → NotFoundException, no master query")
    void should_throwNotFound_when_slugDoesNotResolve() {
        when(salonRepository.findBookingPageRowBySlug(SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findBySlug(SLUG))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Booking page not found");

        verify(masterRepository, never()).findBookingPageMastersBySalonId(any(), any(), anyInt());
        verify(retiredBookingSlugGuard).throwGoneIfRetired(com.beautica.booking.dto.BookingLinkKind.SALON, SLUG);
    }

    @Test
    @DisplayName("phase 379: miss + SALON tombstone → GoneException (410), no master query")
    void should_throwGone_when_salonSlugRetired() {
        when(salonRepository.findBookingPageRowBySlug(SLUG)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(com.beautica.common.exception.GoneException.bookingLinkRetired())
                .when(retiredBookingSlugGuard).throwGoneIfRetired(com.beautica.booking.dto.BookingLinkKind.SALON, SLUG);

        assertThatThrownBy(() -> service.findBySlug(SLUG))
                .isInstanceOf(com.beautica.common.exception.GoneException.class);
        verify(masterRepository, never()).findBookingPageMastersBySalonId(any(), any(), anyInt());
    }

    @Test
    @DisplayName("no bookable master → bookable=false, masters empty, salon fields mapped, avgRating null at 0 reviews")
    void should_returnNotBookable_when_noBookableMasters() {
        salonRow(0, null);
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of());

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.bookable()).isFalse();
        assertThat(page.masters()).isEmpty();
        assertThat(page.salonId()).isEqualTo(salonId);
        assertThat(page.city()).isEqualTo("Вінниця");
        assertThat(page.coverImageUrl()).isEqualTo("https://cdn.beautica.test/cover.jpg");
        assertThat(page.avgRating()).isNull();
        assertThat(page.reviewCount()).isZero();
    }

    @Test
    @DisplayName("phase 380 D7: region, street, buildingNo and updatedAt are appended from the salon row")
    void should_appendAddressAndUpdatedAt_when_streetPresent() {
        SalonBookingPageRow row = salonRow(0, null);
        when(row.getRegion()).thenReturn("Київська область");
        when(row.getStreet()).thenReturn("вул. Хрещатик");
        when(row.getBuildingNo()).thenReturn("12");
        when(row.getUpdatedAt()).thenReturn(java.time.Instant.parse("2026-10-15T08:00:00Z"));
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of());

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.region()).isEqualTo("Київська область");
        assertThat(page.street()).isEqualTo("вул. Хрещатик");
        assertThat(page.buildingNo()).isEqualTo("12");
        assertThat(page.updatedAt()).isEqualTo(java.time.Instant.parse("2026-10-15T08:00:00Z"));
    }

    @Test
    @DisplayName("phase 380 D7: blank street falls back to the legacy address")
    void should_fallBackToLegacyAddress_when_streetBlank() {
        SalonBookingPageRow row = salonRow(0, null);
        when(row.getStreet()).thenReturn("  ");
        when(row.getAddress()).thenReturn("вул. Старої Адреси, 5");
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of());

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.street()).isEqualTo("вул. Старої Адреси, 5");
    }

    @Test
    @DisplayName("bookable masters → bookable=true, stored slugs used verbatim, names composed")
    void should_mapMasters_when_slugsStored() {
        salonRow(4, new BigDecimal("4.75"));
        List<SalonBookingPageMasterRow> rows = List.of(masterRow("anna-b-aa11", "Anna", "Bila"),
                masterRow("olha-ch-bb22", " Olha ", null));
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(rows);

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.bookable()).isTrue();
        assertThat(page.avgRating()).isEqualByComparingTo("4.75");
        assertThat(page.masters()).extracting("masterSlug").containsExactly("anna-b-aa11", "olha-ch-bb22");
        assertThat(page.masters()).extracting("name").containsExactly("Anna Bila", "Olha");
    }

    @Test
    @DisplayName("read path never issues a slug — the repository batch alone supplies every masterSlug (phase 377 audit)")
    void should_neverLoadOrWriteMasterEntity_when_buildingPage() {
        salonRow(0, null);
        List<SalonBookingPageMasterRow> rows = List.of(masterRow("anna-b-aa11", "Anna", "Bila"));
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(rows);

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.masters()).extracting("masterSlug").containsExactly("anna-b-aa11");
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
        verify(masterRepository, never()).save(any());
    }

    // ── phase 384: tab blocks by delegation ──────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnEmptyListsNotNull_when_salonHasNoPortfolioReviewsOrServices")
    void should_returnEmptyListsNotNull_when_salonHasNoPortfolioReviewsOrServices() {
        salonRow(0, null);
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of());

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.portfolio()).isNotNull().isEmpty();
        assertThat(page.serviceCategories()).isNotNull().isEmpty();
        assertThat(page.latestReviews()).isNotNull().isEmpty();
        assertThat(page.ratingDistribution()).hasSize(5);
    }

    @Test
    @DisplayName("should_useSharedCapsAndNewestSort_when_tabsLoaded")
    void should_useSharedCapsAndNewestSort_when_tabsLoaded() {
        salonRow(0, null);
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of());

        service.findBySlug(SLUG);

        verify(mediaService).getLatestPortfolio(com.beautica.media.entity.EntityType.SALON, salonId,
                BookingSlugService.PORTFOLIO_LIMIT);
        verify(reviewService).getLatestReviewsForSalon(salonId, BookingSlugService.LATEST_REVIEWS_LIMIT);
        verify(reviewService, never()).getSalonReviews(any(), any(), any());
        assertThat(BookingSlugService.PORTFOLIO_LIMIT).as("shared with the master page (380)").isEqualTo(12);
        assertThat(BookingSlugService.LATEST_REVIEWS_LIMIT).isEqualTo(5);
    }

    @Test
    @DisplayName("should_returnTheDelegatedPayloadsUnchanged_and_aboutFromTheRow")
    void should_returnTheDelegatedPayloadsUnchanged_and_aboutFromTheRow() {
        SalonBookingPageRow row = salonRow(0, null);
        when(row.getDescription()).thenReturn("Студія краси");
        when(row.getLocationNote()).thenReturn("Другий вхід");
        var group = new com.beautica.service.dto.SalonServiceCategoryGroup("NAILS", "Нігті", 0, List.of());
        when(serviceCatalogService.getSalonCatalogSnapshot(salonId)).thenReturn(new com.beautica.service.dto.SalonCatalogSnapshot(
                new com.beautica.service.dto.SalonServiceCatalogResponse(List.of(group)), java.util.Map.of()));
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of());

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.description()).isEqualTo("Студія краси");
        assertThat(page.locationNote()).isEqualTo("Другий вхід");
        assertThat(page.serviceCategories()).containsExactly(group);
    }

    @Test
    @DisplayName("masters carry the app's rating pair: avgRating + reviewCount, avgRating null while the master has no reviews")
    void should_carryRatingPair_when_mastersListed() {
        salonRow(0, null);
        SalonBookingPageMasterRow rated = masterRow("anna-b-aa11", "Anna", "Bila");
        when(rated.getReviewCount()).thenReturn(12);
        when(rated.getAvgRating()).thenReturn(new BigDecimal("4.75"));
        SalonBookingPageMasterRow unrated = masterRow("olha-ch-bb22", "Olha", "Chorna");
        org.mockito.Mockito.lenient().when(unrated.getAvgRating()).thenReturn(new BigDecimal("0.00"));
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of(rated, unrated));

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.masters()).extracting("reviewCount").containsExactly(12, 0);
        assertThat(page.masters().get(0).avgRating()).isEqualByComparingTo("4.75");
        assertThat(page.masters().get(1).avgRating()).as("stored 0.00 never reaches the wire").isNull();
    }

    // ── phase 391: serviceCoverage ───────────────────────────────────────────────────────────

    private static com.beautica.service.dto.ServiceDefinitionResponse serviceWithId(UUID id) {
        var sd = mock(com.beautica.service.dto.ServiceDefinitionResponse.class);
        when(sd.id()).thenReturn(id);
        return sd;
    }

    @Test
    @DisplayName("phase 391: serviceCoverage — one entry per catalogue service in order; performers in ROSTER order; "
            + "a non-roster performer dropped; a service without a performer gets []; roster carries masterId")
    void should_buildCoverageInCatalogueAndRosterOrder_when_catalogueAndPerformersPresent() {
        salonRow(0, null);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID outsideRoster = UUID.randomUUID();
        SalonBookingPageMasterRow firstRow = masterRow("first-m-aa11", "Anna", "Bila");
        when(firstRow.getId()).thenReturn(first);
        SalonBookingPageMasterRow secondRow = masterRow(null, "Olha", "Chorna");
        when(secondRow.getId()).thenReturn(second);
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of(firstRow, secondRow));
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        var sd1 = serviceWithId(s1);
        var sd2 = serviceWithId(s2);
        var sd3 = serviceWithId(s3);
        var catalogue = new com.beautica.service.dto.SalonServiceCatalogResponse(List.of(
                new com.beautica.service.dto.SalonServiceCategoryGroup("NAILS", "Нігті", 2, List.of(sd1, sd2)),
                new com.beautica.service.dto.SalonServiceCategoryGroup("HAIR", "Волосся", 1, List.of(sd3))));
        UUID a1 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        when(serviceCatalogService.getSalonCatalogSnapshot(salonId)).thenReturn(
                new com.beautica.service.dto.SalonCatalogSnapshot(catalogue, java.util.Map.of(
                s1, List.of(new com.beautica.booking.dto.SalonServiceMasterRef(second, b1),
                        new com.beautica.booking.dto.SalonServiceMasterRef(outsideRoster, UUID.randomUUID()),
                        new com.beautica.booking.dto.SalonServiceMasterRef(first, a1)),
                s2, List.of(new com.beautica.booking.dto.SalonServiceMasterRef(first, a2)))));

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.masters()).extracting("masterId").containsExactly(first, second);
        assertThat(page.masters().get(1).masterSlug()).as("slug-less master listed with null slug").isNull();
        assertThat(page.serviceCoverage()).extracting("serviceId").containsExactly(s1, s2, s3);
        assertThat(page.serviceCoverage().get(0).masters()).containsExactly(
                new com.beautica.booking.dto.SalonServiceMasterRef(first, a1),
                new com.beautica.booking.dto.SalonServiceMasterRef(second, b1));
        assertThat(page.serviceCoverage().get(1).masters())
                .containsExactly(new com.beautica.booking.dto.SalonServiceMasterRef(first, a2));
        assertThat(page.serviceCoverage().get(2).masters()).isEmpty();
    }

    @Test
    @DisplayName("phase 391 audit: catalogue and coverage come from ONE snapshot call — the app catalogue read "
            + "(a second batch on a cold miss) is never made; empty catalogue → empty coverage")
    void should_readOneSnapshotOnly_when_catalogueEmpty() {
        salonRow(0, null);
        when(masterRepository.findBookingPageMastersBySalonId(salonId, TODAY, SalonBookingSlugService.MAX_MASTERS))
                .thenReturn(List.of());

        SalonBookingSlugInfoResponse page = service.findBySlug(SLUG);

        assertThat(page.serviceCoverage()).isEmpty();
        org.mockito.Mockito.verify(serviceCatalogService).getSalonCatalogSnapshot(salonId);
        org.mockito.Mockito.verifyNoMoreInteractions(serviceCatalogService);
    }
}
