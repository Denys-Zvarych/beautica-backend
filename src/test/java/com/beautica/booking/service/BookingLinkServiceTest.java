package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Phase 375 — {@link BookingLinkService}: role matrix (D2), kill-switch (D6), URL (D5), bookable (D7/D8). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BookingLinkService")
class BookingLinkServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MASTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final String SLUG = "olena-koval-x7k2";

    @Mock private MasterRepository masterRepository;
    @Mock private BookingSlugService bookingSlugService;
    @Mock private ScheduleDateMath scheduleDateMath;

    private BookingLinksProperties properties;
    private BookingLinkService service;

    @BeforeEach
    void setUp() {
        properties = new BookingLinksProperties();
        properties.setShareEnabled(true);
        properties.setWebBaseUrl("https://beautica.app");
        service = new BookingLinkService(masterRepository, bookingSlugService, scheduleDateMath, properties);
        when(scheduleDateMath.today()).thenReturn(TODAY);
    }

    private static Master master(Salon salon, MasterType type) {
        User user = new User("m@beautica.test", "hash", Role.INDEPENDENT_MASTER, "Олена", "Коваль", null);
        return Master.builder().id(MASTER_ID).user(user).salon(salon).masterType(type).isActive(true).build();
    }

    private void givenMaster(Master master, boolean searchBookable) {
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(bookingSlugService.getOrIssueSlugRaceSafe(master)).thenReturn(SLUG);
        MasterRepository.SearchBookabilityRow row = new MasterRepository.SearchBookabilityRow() {
            @Override public UUID getMasterId() { return MASTER_ID; }
            @Override public UUID getSalonId() { return null; }
            @Override public Boolean getBookable() { return searchBookable; }
        };
        when(masterRepository.findSearchBookability(List.of(MASTER_ID), TODAY)).thenReturn(List.of(row));
    }

    @Test
    @DisplayName("independent master → one MASTER link with absolute https URL, full name, no salonId, bookable")
    void should_returnMasterLinkWithAbsoluteUrl_when_independentMaster() {
        givenMaster(master(null, MasterType.INDEPENDENT_MASTER), true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.shareEnabled()).isTrue();
        assertThat(response.links()).containsExactly(new BookingLinkResponse(BookingLinkKind.MASTER, SLUG,
                "https://beautica.app/book/" + SLUG, "Олена Коваль", null, true));
    }

    @Test
    @DisplayName("salon owner → own MASTER link only, salonId null")
    void should_returnOwnMasterLink_when_salonOwner() {
        Salon salon = Salon.builder().id(UUID.randomUUID()).isActive(true).build();
        givenMaster(master(salon, MasterType.SALON_OWNER), true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_OWNER);

        assertThat(response.links()).extracting(BookingLinkResponse::kind).containsExactly(BookingLinkKind.MASTER);
        assertThat(response.links().get(0).salonId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_ADMIN", "SALON_MASTER"})
    @DisplayName("SALON_ADMIN / SALON_MASTER → no links, repository and slug service never touched")
    void should_returnNoLinksAndIssueNoSlug_when_roleHasNoMasterLink(Role role) {
        BookingLinksResponse response = service.getMyLinks(USER_ID, role);

        assertThat(response.shareEnabled()).isTrue();
        assertThat(response.links()).isEmpty();
        verifyNoInteractions(masterRepository, bookingSlugService);
    }

    /**
     * 375 audit (LOW security) — the controller's {@code @PreAuthorize} is the single CLIENT gate
     * (pinned by BookingLinkControllerTest / BookingLinkIT → 403). Should a CLIENT ever reach the
     * service, the {@code ownsMasterLink} allow-list still hands it nothing and touches no data.
     */
    @Test
    @DisplayName("CLIENT reaching the service (defence in depth) → no links, no data touched")
    void should_returnNoLinksAndTouchNoData_when_clientReachesService() {
        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.CLIENT);

        assertThat(response.links()).isEmpty();
        verifyNoInteractions(masterRepository, bookingSlugService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INDEPENDENT_MASTER", "SALON_OWNER", "SALON_ADMIN", "SALON_MASTER"})
    @DisplayName("kill-switch off → shareEnabled=false, empty links, no slug issued, no read")
    void should_returnDisabledAndNeverIssueSlug_when_shareSwitchedOff(Role role) {
        properties.setShareEnabled(false);
        givenMaster(master(null, MasterType.INDEPENDENT_MASTER), true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, role);

        assertThat(response.shareEnabled()).isFalse();
        assertThat(response.links()).isEmpty();
        verify(bookingSlugService, never()).getOrIssueSlugRaceSafe(any());
        verify(bookingSlugService, never()).getOrCreateSlug(any());
        verify(masterRepository, never()).findActiveByUserIdWithUserAndSalon(any());
        verifyNoInteractions(masterRepository, bookingSlugService);
    }

    // ── 375 audit F3 — one retry, in a fresh transaction, on a unique-index collision ─────────

    @Test
    @DisplayName("first slug issue hits the unique index → one retry in a fresh call returns the slug")
    void should_retryOnceWithFreshIssue_when_firstIssueHitsUniqueIndex() {
        Master master = master(null, MasterType.INDEPENDENT_MASTER);
        givenMaster(master, true);
        when(bookingSlugService.getOrIssueSlugRaceSafe(master))
                .thenThrow(new DataIntegrityViolationException("ux_masters_booking_slug"))
                .thenReturn(SLUG);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.links()).extracting(BookingLinkResponse::slug).containsExactly(SLUG);
        verify(bookingSlugService, times(2)).getOrIssueSlugRaceSafe(master);
    }

    @Test
    @DisplayName("slug already stored → returned as loaded, issue transaction never opened")
    void should_returnLoadedSlugWithoutIssueTransaction_when_slugAlreadyStored() {
        Master master = Master.builder().id(MASTER_ID)
                .user(new User("m@beautica.test", "hash", Role.INDEPENDENT_MASTER, "Олена", "Коваль", null))
                .masterType(MasterType.INDEPENDENT_MASTER).isActive(true).bookingSlug(SLUG).build();
        givenMaster(master, true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.links()).extracting(BookingLinkResponse::slug).containsExactly(SLUG);
        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("retry also hits the unique index → second violation propagates, exactly two attempts")
    void should_propagate_when_retryAlsoHitsUniqueIndex() {
        Master master = master(null, MasterType.INDEPENDENT_MASTER);
        givenMaster(master, true);
        when(bookingSlugService.getOrIssueSlugRaceSafe(master))
                .thenThrow(new DataIntegrityViolationException("first"))
                .thenThrow(new DataIntegrityViolationException("second"));

        assertThatThrownBy(() -> service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("second");

        verify(bookingSlugService, times(2)).getOrIssueSlugRaceSafe(master);
    }

    @Test
    @DisplayName("URL built from configured web-base-url, not a hard-coded domain")
    void should_buildUrlFromConfiguredWebBaseUrl_when_baseUrlOverridden() {
        properties.setWebBaseUrl("https://staging.beautica.app");
        givenMaster(master(null, MasterType.INDEPENDENT_MASTER), true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.links().get(0).url()).isEqualTo("https://staging.beautica.app/book/" + SLUG);
    }

    @Test
    @DisplayName("no active master row → link omitted, no slug issued")
    void should_omitLink_when_noActiveMasterRow() {
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.empty());

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.shareEnabled()).isTrue();
        assertThat(response.links()).isEmpty();
        verify(bookingSlugService, never()).getOrIssueSlugRaceSafe(any());
    }

    @Test
    @DisplayName("shared search bookability predicate false → bookable=false")
    void should_markNotBookable_when_sharedSearchPredicateFails() {
        givenMaster(master(null, MasterType.INDEPENDENT_MASTER), false);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.links().get(0).bookable()).isFalse();
    }

    @Test
    @DisplayName("closed salon → bookable=false without running the search predicate query")
    void should_markNotBookableWithoutQuery_when_salonClosed() {
        Salon closed = Salon.builder().id(UUID.randomUUID()).isActive(false).build();
        givenMaster(master(closed, MasterType.SALON_OWNER), true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_OWNER);

        assertThat(response.links().get(0).bookable()).isFalse();
        verify(masterRepository, never()).findSearchBookability(anyCollection(), any());
    }

    @Test
    @DisplayName("master without a last name → displayName is the first name, no trailing space")
    void should_useFirstNameOnly_when_lastNameMissing() {
        User user = new User("m@beautica.test", "hash", Role.INDEPENDENT_MASTER, "Олена", null, null);
        Master master = Master.builder().id(MASTER_ID).user(user)
                .masterType(MasterType.INDEPENDENT_MASTER).isActive(true).build();
        givenMaster(master, true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.links()).extracting(BookingLinkResponse::displayName).containsExactly("Олена");
    }
}
