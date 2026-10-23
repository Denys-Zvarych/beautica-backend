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
import com.beautica.booking.dto.BookingLinkStatus;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingLinkRow;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 375 — {@link BookingLinkService}: role matrix (D2), kill-switch (D6), URL (D5), bookable (D7/D8).
 * Phase 376 — SALON links: owner (every active salon), admin (bound salon), batched bookability, lazy issue.
 * 2026-10-20 — SALON_OWNER gets SALON links only: no MASTER link, master row never read, master slug never issued.
 */
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
    @Mock private SalonRepository salonRepository;

    private BookingLinksProperties properties;
    private BookingLinkService service;

    @BeforeEach
    void setUp() {
        properties = new BookingLinksProperties();
        properties.setShareEnabled(true);
        properties.setWebBaseUrl("https://beautica.app");
        service = new BookingLinkService(masterRepository, bookingSlugService, scheduleDateMath, properties,
                salonRepository);
        when(scheduleDateMath.today()).thenReturn(TODAY);
    }

    private static Master master(Salon salon, MasterType type) {
        User user = new User("m@beautica.test", "hash", Role.INDEPENDENT_MASTER, "Олена", "Коваль", null);
        return Master.builder().id(MASTER_ID).user(user).salon(salon).masterType(type).isActive(true).build();
    }

    private void givenMaster(Master master, boolean searchBookable) {
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(bookingSlugService.getOrIssueSlugRaceSafe(master)).thenReturn(Optional.of(SLUG));
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
    @DisplayName("salon owner with no active salon → no links; own master row never read, master slug never issued (2026-10-20)")
    void should_returnNoLinksAndNeverReadMaster_when_salonOwnerHasNoActiveSalon() {
        Salon salon = Salon.builder().id(UUID.randomUUID()).isActive(true).build();
        givenMaster(master(salon, MasterType.SALON_OWNER), true);
        when(salonRepository.findBookingLinkRowsByOwnerId(USER_ID)).thenReturn(List.of());

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_OWNER);

        assertThat(response.shareEnabled()).isTrue();
        assertThat(response.links()).isEmpty();
        verifyNoInteractions(masterRepository, bookingSlugService);
        verify(salonRepository, never()).findBookableSalonIds(anyCollection(), any());
    }

    @Test
    @DisplayName("SALON_MASTER → no links, no repository and slug service ever touched (376 D6)")
    void should_returnNoLinksAndIssueNoSlug_when_salonMaster() {
        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_MASTER);

        assertThat(response.shareEnabled()).isTrue();
        assertThat(response.links()).isEmpty();
        verifyNoInteractions(masterRepository, bookingSlugService, salonRepository);
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
        verifyNoInteractions(masterRepository, bookingSlugService, salonRepository);
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
        verifyNoInteractions(masterRepository, bookingSlugService, salonRepository);
    }

    // ── 375 audit F3 — one retry, in a fresh transaction, on a unique-index collision ─────────

    @Test
    @DisplayName("first slug issue hits the unique index → one retry in a fresh call returns the slug")
    void should_retryOnceWithFreshIssue_when_firstIssueHitsUniqueIndex() {
        Master master = master(null, MasterType.INDEPENDENT_MASTER);
        givenMaster(master, true);
        when(bookingSlugService.getOrIssueSlugRaceSafe(master))
                .thenThrow(new DataIntegrityViolationException("ux_masters_booking_slug"))
                .thenReturn(Optional.of(SLUG));

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
    @DisplayName("MASTER link whose master row carries a closed salon → bookable=false without running the search predicate query")
    void should_markNotBookableWithoutQuery_when_salonClosed() {
        // Shared MasterBookability rule; since 2026-10-20 only INDEPENDENT_MASTER reaches the MASTER-link path.
        Salon closed = Salon.builder().id(UUID.randomUUID()).isActive(false).build();
        givenMaster(master(closed, MasterType.INDEPENDENT_MASTER), true);

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

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

    // ── phase 376 — SALON links ─────────────────────────────────────────────────────────────

    private static final UUID SALON_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID SALON_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private static SalonBookingLinkRow salonRow(UUID id, String name, String slug) {
        return new SalonBookingLinkRow() {
            @Override public UUID getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getBookingSlug() { return slug; }
            @Override public Boolean getRevoked() { return false; }
        };
    }

    /** Phase 378 — a salon whose owner revoked the link: no slug, revoke flag set. */
    private static SalonBookingLinkRow revokedSalonRow(UUID id, String name) {
        return new SalonBookingLinkRow() {
            @Override public UUID getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getBookingSlug() { return null; }
            @Override public Boolean getRevoked() { return true; }
        };
    }

    @Test
    @DisplayName("owner with 2 salons → one SALON link per salon, NO MASTER link; ONE salon read + ONE batched bookability read")
    void should_returnOnlySalonLinks_when_ownerHasTwoSalons() {
        givenMaster(master(null, MasterType.SALON_OWNER), true);
        when(salonRepository.findBookingLinkRowsByOwnerId(USER_ID)).thenReturn(List.of(
                salonRow(SALON_A, "Alfa", "alfa-aa11"), salonRow(SALON_B, "Beta", "beta-bb22")));
        when(salonRepository.findBookableSalonIds(List.of(SALON_A, SALON_B), TODAY)).thenReturn(List.of(SALON_B));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_OWNER);

        assertThat(response.links()).containsExactly(
                new BookingLinkResponse(BookingLinkKind.SALON, "alfa-aa11", "https://beautica.app/book/s/alfa-aa11",
                        "Alfa", SALON_A, false),
                new BookingLinkResponse(BookingLinkKind.SALON, "beta-bb22", "https://beautica.app/book/s/beta-bb22",
                        "Beta", SALON_B, true));
        verify(salonRepository, times(1)).findBookingLinkRowsByOwnerId(USER_ID);
        verify(salonRepository, times(1)).findBookableSalonIds(anyCollection(), any());
        verify(bookingSlugService, never()).issueMissingSalonSlugs(anyList());
        verifyNoInteractions(masterRepository);
        verify(bookingSlugService, never()).getOrIssueSlugRaceSafe(any());
    }

    @Test
    @DisplayName("SALON_ADMIN → only its bound salon's SALON link; no master read")
    void should_returnBoundSalonLinkOnly_when_salonAdmin() {
        when(salonRepository.findBookingLinkRowsByAdminUserId(USER_ID))
                .thenReturn(List.of(salonRow(SALON_A, "Alfa", "alfa-aa11")));
        when(salonRepository.findBookableSalonIds(List.of(SALON_A), TODAY)).thenReturn(List.of(SALON_A));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_ADMIN);

        assertThat(response.links()).containsExactly(new BookingLinkResponse(BookingLinkKind.SALON, "alfa-aa11",
                "https://beautica.app/book/s/alfa-aa11", "Alfa", SALON_A, true));
        verifyNoInteractions(masterRepository);
        verify(salonRepository, never()).findBookingLinkRowsByOwnerId(any());
    }

    @Test
    @DisplayName("SALON_ADMIN with no active bound salon → no links, bookability query skipped")
    void should_returnNoLinksAndSkipBookability_when_adminHasNoActiveSalon() {
        when(salonRepository.findBookingLinkRowsByAdminUserId(USER_ID)).thenReturn(List.of());

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_ADMIN);

        assertThat(response.links()).isEmpty();
        verify(salonRepository, never()).findBookableSalonIds(anyCollection(), any());
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER → never reads salons")
    void should_neverReadSalons_when_independentMaster() {
        givenMaster(master(null, MasterType.INDEPENDENT_MASTER), true);

        service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        verifyNoInteractions(salonRepository);
    }

    @Test
    @DisplayName("legacy salon (NULL slug) → issued via the batched race-safe salon issue with the loaded row")
    void should_issueSalonSlug_when_salonSlugNull() {
        SalonBookingLinkRow legacy = salonRow(SALON_A, "Beauty Lab Київ", null);
        when(salonRepository.findBookingLinkRowsByAdminUserId(USER_ID)).thenReturn(List.of(legacy));
        when(bookingSlugService.issueMissingSalonSlugs(List.of(legacy)))
                .thenReturn(Map.of(SALON_A, "beauty-lab-kyiv-ab12"));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_ADMIN);

        assertThat(response.links()).extracting(BookingLinkResponse::slug, BookingLinkResponse::url)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "beauty-lab-kyiv-ab12", "https://beautica.app/book/s/beauty-lab-kyiv-ab12"));
    }

    @Test
    @DisplayName("legacy salon issue hits the unique index → one retry in a fresh call")
    void should_retryOnce_when_salonIssueHitsUniqueIndex() {
        when(salonRepository.findBookingLinkRowsByAdminUserId(USER_ID))
                .thenReturn(List.of(salonRow(SALON_A, "Alfa", null)));
        when(bookingSlugService.issueMissingSalonSlugs(anyList()))
                .thenThrow(new DataIntegrityViolationException("idx_salons_booking_slug"))
                .thenReturn(Map.of(SALON_A, "alfa-cc33"));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_ADMIN);

        assertThat(response.links()).extracting(BookingLinkResponse::slug).containsExactly("alfa-cc33");
        verify(bookingSlugService, times(2)).issueMissingSalonSlugs(anyList());
    }

    @Test
    @DisplayName("legacy salon retry also collides → second violation propagates")
    void should_propagate_when_salonRetryAlsoCollides() {
        when(salonRepository.findBookingLinkRowsByAdminUserId(USER_ID))
                .thenReturn(List.of(salonRow(SALON_A, "Alfa", null)));
        when(bookingSlugService.issueMissingSalonSlugs(anyList()))
                .thenThrow(new DataIntegrityViolationException("first"))
                .thenThrow(new DataIntegrityViolationException("second"));

        assertThatThrownBy(() -> service.getMyLinks(USER_ID, Role.SALON_ADMIN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("second");
        verify(bookingSlugService, times(2)).issueMissingSalonSlugs(anyList());
    }

    @Test
    @DisplayName("owner, 3 salons of which 2 legacy → ONE batched issue call with exactly the 2 legacy rows; stored slug kept (376 audit)")
    void should_issueAllLegacySalonSlugsInOneCall_when_ownerHasSeveralLegacySalons() {
        SalonBookingLinkRow stored = salonRow(SALON_A, "Alfa", "alfa-aa11");
        UUID salonC = UUID.randomUUID();
        SalonBookingLinkRow legacyB = salonRow(SALON_B, "Beta", null);
        SalonBookingLinkRow legacyC = salonRow(salonC, "Gama", null);
        when(salonRepository.findBookingLinkRowsByOwnerId(USER_ID)).thenReturn(List.of(stored, legacyB, legacyC));
        when(salonRepository.findBookableSalonIds(anyCollection(), any())).thenReturn(List.of());
        when(bookingSlugService.issueMissingSalonSlugs(List.of(legacyB, legacyC)))
                .thenReturn(Map.of(SALON_B, "beta-bb22", salonC, "gama-cc33"));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_OWNER);

        assertThat(response.links()).extracting(BookingLinkResponse::slug)
                .containsExactly("alfa-aa11", "beta-bb22", "gama-cc33");
        verify(bookingSlugService, times(1)).issueMissingSalonSlugs(anyList());
    }

    @Test
    @DisplayName("376 audit cycle 2: a legacy salon deleted before the issue (absent from the issued map) → its link omitted, the rest returned")
    void should_omitSalonLink_when_salonDeletedBeforeSlugIssue() {
        SalonBookingLinkRow stored = salonRow(SALON_A, "Alfa", "alfa-aa11");
        SalonBookingLinkRow deleted = salonRow(SALON_B, "Beta", null);
        when(salonRepository.findBookingLinkRowsByAdminUserId(USER_ID)).thenReturn(List.of(stored, deleted));
        when(salonRepository.findBookableSalonIds(anyCollection(), any())).thenReturn(List.of());
        when(bookingSlugService.issueMissingSalonSlugs(List.of(deleted))).thenReturn(Map.of());

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_ADMIN);

        assertThat(response.links()).extracting(BookingLinkResponse::salonId).containsExactly(SALON_A);
    }

    @Test
    @DisplayName("salon URL uses the configured web-base-url and the /book/s/ namespace")
    void should_buildSalonUrlFromConfiguredBase_when_baseUrlOverridden() {
        properties.setWebBaseUrl("https://staging.beautica.app");
        when(salonRepository.findBookingLinkRowsByAdminUserId(USER_ID))
                .thenReturn(List.of(salonRow(SALON_A, "Alfa", "alfa-aa11")));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_ADMIN);

        assertThat(response.links().get(0).url()).isEqualTo("https://staging.beautica.app/book/s/alfa-aa11");
    }

    // ── phase 378 — REVOKED links (D4/D15) ──────────────────────────────────────────────────

    @Test
    @DisplayName("phase 378 D15: revoked master link → status REVOKED, slug/url null, not bookable, no bookability read")
    void should_returnRevokedMasterLink_when_issueReportsRevoked() {
        Master master = master(null, MasterType.INDEPENDENT_MASTER);
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));
        when(bookingSlugService.getOrIssueSlugRaceSafe(master)).thenReturn(Optional.empty());

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.links()).singleElement().satisfies(link -> {
            assertThat(link.kind()).isEqualTo(BookingLinkKind.MASTER);
            assertThat(link.status()).isEqualTo(BookingLinkStatus.REVOKED);
            assertThat(link.slug()).isNull();
            assertThat(link.url()).isNull();
            assertThat(link.displayName()).isEqualTo("Олена Коваль");
            assertThat(link.bookable()).isFalse();
        });
        verify(masterRepository, never()).findSearchBookability(any(), any());
    }

    @Test
    @DisplayName("378 audit perf LOW-1: revoked flag came with the master read → REVOKED without the issue transaction")
    void should_answerRevokedWithoutIssueTransaction_when_readCarriesRevokedFlag() {
        User user = new User("m@beautica.test", "hash", Role.INDEPENDENT_MASTER, "Олена", "Коваль", null);
        Master master = Master.builder().id(MASTER_ID).user(user).masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true).bookingLinkRevoked(true).build();
        when(masterRepository.findActiveByUserIdWithUserAndSalon(USER_ID)).thenReturn(Optional.of(master));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.INDEPENDENT_MASTER);

        assertThat(response.links()).singleElement()
                .satisfies(link -> assertThat(link.status()).isEqualTo(BookingLinkStatus.REVOKED));
        verifyNoInteractions(bookingSlugService);
        verify(masterRepository, never()).findSearchBookability(any(), any());
    }

    @Test
    @DisplayName("phase 378 D4/D15: a revoked salon is listed REVOKED in name order and never handed to the lazy issue")
    void should_listRevokedSalonWithoutIssuing_when_salonRevoked() {
        SalonBookingLinkRow revoked = revokedSalonRow(SALON_A, "Alfa");
        SalonBookingLinkRow active = salonRow(SALON_B, "Beta", "beta-bb22");
        when(salonRepository.findBookingLinkRowsByOwnerId(USER_ID)).thenReturn(List.of(revoked, active));
        when(salonRepository.findBookableSalonIds(anyCollection(), any())).thenReturn(List.of(SALON_A, SALON_B));

        BookingLinksResponse response = service.getMyLinks(USER_ID, Role.SALON_OWNER);

        assertThat(response.links()).extracting(BookingLinkResponse::status, BookingLinkResponse::slug,
                        BookingLinkResponse::url, BookingLinkResponse::salonId, BookingLinkResponse::bookable)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BookingLinkStatus.REVOKED, null, null, SALON_A, false),
                        org.assertj.core.groups.Tuple.tuple(BookingLinkStatus.ACTIVE, "beta-bb22",
                                "https://beautica.app/book/s/beta-bb22", SALON_B, true));
        verify(bookingSlugService, never()).issueMissingSalonSlugs(anyList());
    }
}
