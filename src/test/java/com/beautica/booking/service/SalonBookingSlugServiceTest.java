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
    @InjectMocks private SalonBookingSlugService service;

    private final UUID salonId = UUID.randomUUID();

    private SalonBookingPageRow salonRow(int reviewCount, BigDecimal avgRating) {
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
}
