package com.beautica.booking.service;

import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.BookingSlugSalonRef;
import com.beautica.booking.dto.CategoryRef;
import com.beautica.review.dto.SalonReviewSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingLinkRow;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BookingSlugService}: transliteration correctness,
 * uniqueness/retry behaviour, lazy get-or-create, and slug resolution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingSlugService")
class BookingSlugServiceTest {

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private MasterServiceRepository masterServiceRepository;

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private com.beautica.media.service.MediaService mediaService;

    @Mock
    private com.beautica.review.service.ReviewService reviewService;

    @Mock
    private com.beautica.master.service.ScheduleDateMath scheduleDateMath;

    @InjectMocks
    private BookingSlugService service;

    // ── generateSlug — transliteration ──────────────────────────────────────

    @Test
    @DisplayName("should_transliterateCyrillicToAsciiKmu2010_when_generatingSlug")
    void should_transliterateCyrillicToAsciiKmu2010_when_generatingSlug() {
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.generateSlug("Олександра", "Жучок");

        assertThat(slug).matches("^oleksandra-zhuchok-[a-z0-9]{4}$");
    }

    @Test
    @DisplayName("should_lowercaseHyphenateAndKeepAscii_when_nameIsLatin")
    void should_lowercaseHyphenateAndKeepAscii_when_nameIsLatin() {
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.generateSlug("Anna Maria", "O'Neil");

        assertThat(slug).matches("^anna-maria-o-neil-[a-z0-9]{4}$");
        assertThat(slug).isLowerCase();
    }

    @Test
    @DisplayName("should_appendExactlyFourRandomAlphanumerics_when_generatingSlug")
    void should_appendExactlyFourRandomAlphanumerics_when_generatingSlug() {
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.generateSlug("Іван", "Петренко");

        String suffix = slug.substring(slug.lastIndexOf('-') + 1);
        assertThat(suffix).hasSize(4).matches("[a-z0-9]{4}");
    }

    @Test
    @DisplayName("should_fallBackToMasterBase_when_nameTransliteratesToNothing")
    void should_fallBackToMasterBase_when_nameTransliteratesToNothing() {
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.generateSlug("", "");

        assertThat(slug).matches("^master-[a-z0-9]{4}$");
    }

    @Test
    @DisplayName("should_capTotalLengthAtSixty_when_nameIsVeryLong")
    void should_capTotalLengthAtSixty_when_nameIsVeryLong() {
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.generateSlug("a".repeat(100), "b".repeat(100));

        assertThat(slug.length()).isLessThanOrEqualTo(60);
        assertThat(slug).matches("^[a-z0-9][a-z0-9\\-]*[a-z0-9]$");
    }

    // ── generateSlug — uniqueness / retry ───────────────────────────────────

    @Test
    @DisplayName("should_retryUntilUnique_when_firstCandidatesCollide")
    void should_retryUntilUnique_when_firstCandidatesCollide() {
        when(masterRepository.existsByBookingSlug(anyString()))
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(false);

        String slug = service.generateSlug("Ivan", "Test");

        assertThat(slug).matches("^ivan-test-[a-z0-9]{4}$");
        verify(masterRepository, times(3)).existsByBookingSlug(anyString());
    }

    @Test
    @DisplayName("should_throwConflict_when_allFiveAttemptsCollide")
    void should_throwConflict_when_allFiveAttemptsCollide() {
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.generateSlug("Ivan", "Test"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unique booking slug");

        verify(masterRepository, times(5)).existsByBookingSlug(anyString());
    }

    // ── getOrCreateSlug — lazy get-or-create ────────────────────────────────

    @Test
    @DisplayName("should_returnExistingSlug_when_alreadyPresent")
    void should_returnExistingSlug_when_alreadyPresent() {
        UUID masterId = UUID.randomUUID();
        Master master = masterWithSlug("anna-existing-ab12");
        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));

        String slug = service.getOrCreateSlug(masterId);

        assertThat(slug).isEqualTo("anna-existing-ab12");
        verify(masterRepository, never()).existsByBookingSlug(anyString());
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_generateAssignToManagedEntityAndReturn_when_slugIsNull")
    void should_generateAssignToManagedEntityAndReturn_when_slugIsNull() {
        UUID masterId = UUID.randomUUID();
        Master master = masterWithUser("Олена", "Коваль", null);
        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.getOrCreateSlug(masterId);

        // master is loaded via findById (managed in-transaction), so the slug is flushed
        // by Hibernate dirty-checking on commit — no explicit save() call.
        assertThat(slug).matches("^olena-koval-[a-z0-9]{4}$");
        assertThat(master.getBookingSlug()).isEqualTo(slug);
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_masterDoesNotExist")
    void should_throwNotFound_when_masterDoesNotExist() {
        UUID masterId = UUID.randomUUID();
        when(masterRepository.findById(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrCreateSlug(masterId))
                .isInstanceOf(NotFoundException.class);
    }

    // ── getOrIssueSlugRaceSafe — phase 375 lazy issue on /booking-links/mine ──

    @Test
    @DisplayName("race-safe issue, slug already present → returned, no UPDATE")
    void should_returnExistingSlugWithoutWriting_when_raceSafeIssueAndSlugPresent() {
        Master master = masterWithSlug("anna-existing-ab12");

        String slug = service.getOrIssueSlugRaceSafe(master);

        assertThat(slug).isEqualTo("anna-existing-ab12");
        verify(masterRepository, never()).assignBookingSlugIfAbsent(any(), anyString());
    }

    @Test
    @DisplayName("race-safe issue, conditional UPDATE wins → generated slug returned, no re-read")
    void should_returnGeneratedSlug_when_conditionalUpdateWins() {
        Master master = masterWithUser("Олена", "Коваль", null);
        setId(master, UUID.randomUUID());
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);
        when(masterRepository.assignBookingSlugIfAbsent(any(), anyString())).thenReturn(1);

        String slug = service.getOrIssueSlugRaceSafe(master);

        assertThat(slug).matches("^olena-koval-[a-z0-9]{4}$");
        verify(masterRepository).assignBookingSlugIfAbsent(master.getId(), slug);
        verify(masterRepository, never()).findBookingSlugById(any());
    }

    @Test
    @DisplayName("race-safe issue, concurrent call won → stored slug re-read and returned")
    void should_reReadAndReturnStoredSlug_when_concurrentCallIssuedFirst() {
        Master master = masterWithUser("Олена", "Коваль", null);
        setId(master, UUID.randomUUID());
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);
        when(masterRepository.assignBookingSlugIfAbsent(any(), anyString())).thenReturn(0);
        when(masterRepository.findBookingSlugById(master.getId())).thenReturn(Optional.of("olena-koval-win1"));

        String slug = service.getOrIssueSlugRaceSafe(master);

        assertThat(slug).isEqualTo("olena-koval-win1");
    }

    @Test
    @DisplayName("race-safe issue, UPDATE matched nothing and row gone → NotFoundException")
    void should_throwNotFound_when_raceSafeReReadFindsNoRow() {
        Master master = masterWithUser("Олена", "Коваль", null);
        setId(master, UUID.randomUUID());
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);
        when(masterRepository.assignBookingSlugIfAbsent(any(), anyString())).thenReturn(0);
        when(masterRepository.findBookingSlugById(master.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrIssueSlugRaceSafe(master))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Master not found");
    }

    // ── phase 376 — salon slugs (shared generator, salon namespace) ────────

    @Test
    @DisplayName("salon «Beauty Lab Київ» → beauty-lab-kyiv-xxxx, probed against the SALON namespace only")
    void should_transliterateSalonNameAndProbeSalonNamespace_when_generatingSalonSlug() {
        when(salonRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.generateSalonSlug("Beauty Lab Київ");

        assertThat(slug).matches("^beauty-lab-kyiv-[a-z0-9]{4}$");
        verify(salonRepository).existsByBookingSlug(slug);
        verify(masterRepository, never()).existsByBookingSlug(anyString());
    }

    @Test
    @DisplayName("salon name with no slug-able chars → 'salon' fallback base")
    void should_useSalonFallbackBase_when_salonNameHasNoSlugChars() {
        when(salonRepository.existsByBookingSlug(anyString())).thenReturn(false);

        String slug = service.generateSalonSlug("✦ ✦");

        assertThat(slug).matches("^salon-[a-z0-9]{4}$");
    }

    @Test
    @DisplayName("salon slug collides on every attempt → CONFLICT after exactly 5 probes")
    void should_throwConflict_when_salonSlugAttemptsExhausted() {
        when(salonRepository.existsByBookingSlug(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.generateSalonSlug("Beauty Lab"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Could not allocate a unique booking slug")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        verify(salonRepository, times(5)).existsByBookingSlug(anyString());
    }

    @Test
    @DisplayName("createSalon path: each candidate's advisory lock is taken BEFORE its probe; a taken one is redrawn under a fresh lock (376 audit)")
    void should_lockCandidateBeforeProbingAndRedraw_when_salonCandidateTaken() {
        when(salonRepository.existsByBookingSlug(anyString())).thenReturn(true, false);

        String slug = service.generateSalonSlug("Beauty Lab");

        ArgumentCaptor<String> probed = ArgumentCaptor.forClass(String.class);
        verify(salonRepository, times(2)).existsByBookingSlug(probed.capture());
        String taken = probed.getAllValues().get(0);
        InOrder order = inOrder(salonRepository);
        order.verify(salonRepository).lockBookingSlugCandidates(new String[] {taken});
        order.verify(salonRepository).existsByBookingSlug(taken);
        order.verify(salonRepository).lockBookingSlugCandidates(new String[] {slug});
        order.verify(salonRepository).existsByBookingSlug(slug);
        assertThat(slug).isEqualTo(probed.getAllValues().get(1)).startsWith("beauty-lab-");
    }

    private static SalonRepository.SalonSlugRow slugRow(UUID id, String slug) {
        return new SalonRepository.SalonSlugRow() {
            @Override public UUID getId() { return id; }
            @Override public String getBookingSlug() { return slug; }
        };
    }

    private static SalonBookingLinkRow salonRow(UUID id, String name) {
        return new SalonBookingLinkRow() {
            @Override public UUID getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getBookingSlug() { return null; }
        };
    }

    @Test
    @DisplayName("batched salon issue → rows locked, candidates locked then probed ONCE, ONE UPDATE for all, no re-read")
    void should_issueAllSalonSlugsWithOneLockProbeAndUpdate_when_salonsStillMissing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(salonRepository.lockSalonIdsWithoutBookingSlug(List.of(a, b, c))).thenReturn(List.of(a, b, c));
        when(salonRepository.findExistingBookingSlugs(anyCollection())).thenReturn(List.of());

        Map<UUID, String> slugs = service.issueMissingSalonSlugs(
                List.of(salonRow(a, "Alfa"), salonRow(b, "Beta Київ"), salonRow(c, "Alfa")));

        assertThat(slugs.get(a)).matches("^alfa-[a-z0-9]{4}$");
        assertThat(slugs.get(b)).matches("^beta-kyiv-[a-z0-9]{4}$");
        assertThat(slugs.get(c)).matches("^alfa-[a-z0-9]{4}$").isNotEqualTo(slugs.get(a));
        InOrder order = inOrder(salonRepository);
        order.verify(salonRepository).lockSalonIdsWithoutBookingSlug(List.of(a, b, c));
        order.verify(salonRepository, atLeastOnce()).lockBookingSlugCandidates(any());
        order.verify(salonRepository, atLeastOnce()).findExistingBookingSlugs(anyCollection());
        ArgumentCaptor<UUID[]> ids = ArgumentCaptor.captor();
        ArgumentCaptor<String[]> written = ArgumentCaptor.captor();
        order.verify(salonRepository).assignBookingSlugsIfAbsent(ids.capture(), written.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(a, b, c);
        for (int i = 0; i < ids.getValue().length; i++) {
            assertThat(written.getValue()[i]).isEqualTo(slugs.get(ids.getValue()[i]));
        }
        verify(salonRepository, never()).findBookingSlugsByIds(any());
        verify(salonRepository, never()).existsByBookingSlug(anyString());
    }

    @Test
    @DisplayName("batched salon issue → candidates locked in sorted order, before the probe")
    void should_lockCandidatesSorted_when_issuingBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(salonRepository.lockSalonIdsWithoutBookingSlug(List.of(a, b))).thenReturn(List.of(a, b));
        when(salonRepository.findExistingBookingSlugs(anyCollection())).thenReturn(List.of());

        service.issueMissingSalonSlugs(List.of(salonRow(a, "Zeta"), salonRow(b, "Alfa")));

        ArgumentCaptor<String[]> locked = ArgumentCaptor.captor();
        verify(salonRepository).lockBookingSlugCandidates(locked.capture());
        assertThat(locked.getValue()).hasSize(2).isSorted();
    }

    @Test
    @DisplayName("batched salon issue, a candidate already stored → only that salon redrawn in a second lock+probe round")
    void should_redrawOnlyTakenCandidate_when_batchCandidateAlreadyStored() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(salonRepository.lockSalonIdsWithoutBookingSlug(List.of(a, b))).thenReturn(List.of(a, b));
        List<Collection<String>> probes = new ArrayList<>();
        when(salonRepository.findExistingBookingSlugs(anyCollection())).thenAnswer(inv -> {
            Collection<String> candidates = inv.getArgument(0);
            probes.add(List.copyOf(candidates));
            // First round: Alfa's candidate is taken; every later round is free.
            return probes.size() == 1
                    ? candidates.stream().filter(slug -> slug.startsWith("alfa-")).toList()
                    : List.of();
        });

        Map<UUID, String> slugs = service.issueMissingSalonSlugs(List.of(salonRow(a, "Alfa"), salonRow(b, "Beta")));

        assertThat(probes).hasSize(2);
        assertThat(probes.get(1)).containsExactly(slugs.get(a));
        assertThat(probes.get(0)).doesNotContain(slugs.get(a)).contains(slugs.get(b));
        verify(salonRepository, times(2)).lockBookingSlugCandidates(any());
    }

    @Test
    @DisplayName("batched salon issue, every round collides → CONFLICT after 5 rounds, nothing written")
    void should_throwConflictAndWriteNothing_when_batchAttemptsExhausted() {
        UUID a = UUID.randomUUID();
        when(salonRepository.lockSalonIdsWithoutBookingSlug(List.of(a))).thenReturn(List.of(a));
        when(salonRepository.findExistingBookingSlugs(anyCollection()))
                .thenAnswer(inv -> List.copyOf((Collection<String>) inv.getArgument(0)));

        assertThatThrownBy(() -> service.issueMissingSalonSlugs(List.of(salonRow(a, "Alfa"))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Could not allocate a unique booking slug");
        verify(salonRepository, times(5)).findExistingBookingSlugs(anyCollection());
        verify(salonRepository, never()).assignBookingSlugsIfAbsent(any(), any());
    }

    @Test
    @DisplayName("batched salon issue, a concurrent call issued one salon first → that salon's stored slug re-read, only the other written")
    void should_reReadConcurrentlyIssuedSlug_when_rowNoLongerMissingAtLock() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(salonRepository.lockSalonIdsWithoutBookingSlug(List.of(a, b))).thenReturn(List.of(b));
        when(salonRepository.findExistingBookingSlugs(anyCollection())).thenReturn(List.of());
        when(salonRepository.findBookingSlugsByIds(new UUID[] {a})).thenReturn(List.of(slugRow(a, "alfa-win1")));

        Map<UUID, String> slugs = service.issueMissingSalonSlugs(List.of(salonRow(a, "Alfa"), salonRow(b, "Beta")));

        assertThat(slugs.get(a)).isEqualTo("alfa-win1");
        assertThat(slugs.get(b)).matches("^beta-[a-z0-9]{4}$");
        verify(salonRepository).assignBookingSlugsIfAbsent(new UUID[] {b}, new String[] {slugs.get(b)});
    }

    @Test
    @DisplayName("batched salon issue, every row issued concurrently → no candidate lock, probe or UPDATE, ONE batched re-read")
    void should_onlyReRead_when_noRowMissingAtLock() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(salonRepository.lockSalonIdsWithoutBookingSlug(List.of(a, b))).thenReturn(List.of());
        when(salonRepository.findBookingSlugsByIds(new UUID[] {a, b}))
                .thenReturn(List.of(slugRow(a, "alfa-win1"), slugRow(b, "beta-win2")));

        Map<UUID, String> slugs = service.issueMissingSalonSlugs(List.of(salonRow(a, "Alfa"), salonRow(b, "Beta")));

        assertThat(slugs).containsOnly(Map.entry(a, "alfa-win1"), Map.entry(b, "beta-win2"));
        verify(salonRepository, times(1)).findBookingSlugsByIds(any());
        verify(salonRepository, never()).lockBookingSlugCandidates(any());
        verify(salonRepository, never()).findExistingBookingSlugs(anyCollection());
        verify(salonRepository, never()).assignBookingSlugsIfAbsent(any(), any());
    }

    @Test
    @DisplayName("376 audit cycle 2: batched salon issue, a salon deleted before the lock → omitted from the result, no 404")
    void should_omitSalon_when_salonRowGoneOnReRead() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(salonRepository.lockSalonIdsWithoutBookingSlug(List.of(a, b))).thenReturn(List.of(b));
        when(salonRepository.findExistingBookingSlugs(anyCollection())).thenReturn(List.of());
        when(salonRepository.findBookingSlugsByIds(new UUID[] {a})).thenReturn(List.of());

        Map<UUID, String> slugs = service.issueMissingSalonSlugs(List.of(salonRow(a, "Alfa"), salonRow(b, "Beta")));

        assertThat(slugs).containsOnlyKeys(b);
        assertThat(slugs.get(b)).matches("^beta-[a-z0-9]{4}$");
    }

    @Test
    @DisplayName("master generation never probes the salon namespace (D2 — per-table uniqueness)")
    void should_notProbeSalonNamespace_when_generatingMasterSlug() {
        when(masterRepository.existsByBookingSlug(anyString())).thenReturn(false);

        service.generateSlug("Олена", "Коваль");

        verify(salonRepository, never()).existsByBookingSlug(anyString());
    }

    // ── findBySlug ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnInfoWithComposedNameAndServices_when_slugExists")
    void should_returnInfoWithComposedNameAndServices_when_slugExists() {
        UUID masterId = UUID.randomUUID();
        Master master = masterWithUser("Марія", "Левченко", "marija-l-cd34");
        setId(master, masterId);
        when(masterRepository.findByBookingSlugWithUser("marija-l-cd34"))
                .thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(any(), any()))
                .thenReturn(List.of(assignment("Манікюр", 60, new BigDecimal("350.00"))));
        when(scheduleDateMath.today()).thenReturn(TODAY);
        when(mediaService.getLatestPortfolio(any(com.beautica.media.entity.EntityType.class), any(), anyInt()))
                .thenReturn(List.of());
        when(reviewService.getLatestReviewsForMaster(any(), anyInt())).thenReturn(List.of());
        when(reviewService.getMasterReviewSummary(any())).thenReturn(summary(distribution(0, 0, 0, 0, 0)));

        BookingSlugInfoResponse info = service.findBySlug("marija-l-cd34");

        assertThat(info.masterName()).isEqualTo("Марія Левченко");
        assertThat(info.services()).hasSize(1);
        assertThat(info.services().get(0).name()).isEqualTo("Манікюр");
        assertThat(info.services().get(0).priceFrom()).isEqualByComparingTo("350.00");
    }

    // ── findBySlug — phase 380 enrichment ───────────────────────────────────

    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 10, 20);

    private Master enrichedMaster(UUID masterId) {
        Master master = masterWithUser("Олена", "Коваль", "olena-k-ab12");
        setId(master, masterId);
        master.setMasterType(com.beautica.master.entity.MasterType.INDEPENDENT_MASTER);
        master.setReviewCount(37);
        master.setAvgRating(new BigDecimal("4.80"));
        master.getUser().setProfessionalTitle("Майстер манікюру");
        master.getUser().setCity("Львів");
        master.getUser().setRegion("Львівська область");
        when(masterRepository.findByBookingSlugWithUser("olena-k-ab12")).thenReturn(Optional.of(master));
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(any(), any()))
                .thenReturn(List.of(assignment("Манікюр", 60, new BigDecimal("350.00"))));
        org.mockito.Mockito.lenient().when(masterServiceRepository.findActiveCategoriesByMasterId(masterId)).thenReturn(List.of());
        when(scheduleDateMath.today()).thenReturn(TODAY);
        org.mockito.Mockito.lenient().when(reviewService.getMasterReviewSummary(masterId))
                .thenReturn(summary(distribution(0, 0, 0, 0, 0)));
        org.mockito.Mockito.lenient().when(mediaService.getLatestPortfolio(eq(com.beautica.media.entity.EntityType.MASTER), eq(masterId), anyInt()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(reviewService.getLatestReviewsForMaster(eq(masterId), anyInt()))
                .thenReturn(List.of());
        return master;
    }

    @Test
    @DisplayName("should_mapEveryEnrichedField_when_slugExists")
    void should_mapEveryEnrichedField_when_slugExists() {
        UUID masterId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        enrichedMaster(masterId);
        var category = org.mockito.Mockito.mock(com.beautica.service.entity.CatalogCategory.class);
        when(category.getId()).thenReturn(categoryId);
        when(category.getNameUk()).thenReturn("Манікюр");
        when(masterServiceRepository.findActiveCategoriesByMasterId(masterId))
                .thenReturn(java.util.Collections.singletonList(new Object[] {category, "NAILS"}));
        var photo = new com.beautica.media.dto.MediaFileResponse(UUID.randomUUID(),
                com.beautica.media.entity.EntityType.MASTER, masterId,
                com.beautica.media.entity.MediaType.PORTFOLIO, "https://media.beautica.app/p.webp", null);
        when(mediaService.getLatestPortfolio(eq(com.beautica.media.entity.EntityType.MASTER), eq(masterId), anyInt()))
                .thenReturn(List.of(photo));
        when(masterRepository.isBookableById(masterId, TODAY)).thenReturn(true);

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.masterId()).isEqualTo(masterId);
        assertThat(info.masterType()).isEqualTo(com.beautica.master.entity.MasterType.INDEPENDENT_MASTER);
        assertThat(info.professionalTitle()).isEqualTo("Майстер манікюру");
        assertThat(info.city()).isEqualTo("Львів");
        assertThat(info.region()).isEqualTo("Львівська область");
        assertThat(info.avgRating()).isEqualByComparingTo("4.80");
        assertThat(info.reviewCount()).isEqualTo(37);
        assertThat(info.categories()).containsExactly(new CategoryRef(categoryId, "Манікюр", "NAILS"));
        assertThat(info.portfolio()).containsExactly(photo);
        assertThat(info.bookable()).isTrue();
        assertThat(info.salon()).isNull();
    }

    @Test
    @DisplayName("should_returnNullAvgRating_when_reviewCountIsZero")
    void should_returnNullAvgRating_when_reviewCountIsZero() {
        UUID masterId = UUID.randomUUID();
        Master master = enrichedMaster(masterId);
        master.setReviewCount(0);
        master.setAvgRating(BigDecimal.ZERO);

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.avgRating()).isNull();
        assertThat(info.reviewCount()).isZero();
    }

    @Test
    @DisplayName("should_returnSalonRef_when_masterBelongsToSalon")
    void should_returnSalonRef_when_masterBelongsToSalon() {
        UUID masterId = UUID.randomUUID();
        Master master = enrichedMaster(masterId);
        var salon = new com.beautica.salon.entity.Salon();
        salon.setName("Studio Velvet");
        org.springframework.test.util.ReflectionTestUtils.setField(salon, "bookingSlug", "studio-velvet-a1b2");
        master.setSalon(salon);

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.salon()).isEqualTo(new BookingSlugSalonRef("Studio Velvet", "studio-velvet-a1b2"));
    }

    @Test
    @DisplayName("should_reportNotBookable_when_repositorySaysNotBookable")
    void should_reportNotBookable_when_repositorySaysNotBookable() {
        UUID masterId = UUID.randomUUID();
        enrichedMaster(masterId);
        when(masterRepository.isBookableById(masterId, TODAY)).thenReturn(false);

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.bookable()).isFalse();
    }

    // ── phase 383: app parity ────────────────────────────────────────────────

    @Test
    @DisplayName("should_setCategoryOnEveryService_when_infoBuilt")
    void should_setCategoryOnEveryService_when_infoBuilt() {
        UUID masterId = UUID.randomUUID();
        enrichedMaster(masterId);
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(any(), any())).thenReturn(List.of(
                assignment("Манікюр", 60, new BigDecimal("350.00"), "NAILS"),
                assignment("Стрижка", 45, new BigDecimal("200.00"), "HAIR")));

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.services()).extracting("category").containsExactly("NAILS", "HAIR");
    }

    @Test
    @DisplayName("should_returnZeroFilledDistribution_when_noReviews")
    void should_returnZeroFilledDistribution_when_noReviews() {
        UUID masterId = UUID.randomUUID();
        Master master = enrichedMaster(masterId);
        master.setReviewCount(0);

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.ratingDistribution()).containsExactlyElementsOf(distribution(0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("should_delegateDistributionToReviewSummary_when_infoBuilt")
    void should_delegateDistributionToReviewSummary_when_infoBuilt() {
        UUID masterId = UUID.randomUUID();
        enrichedMaster(masterId);
        when(reviewService.getMasterReviewSummary(masterId)).thenReturn(summary(distribution(12, 3, 0, 0, 1)));

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.ratingDistribution()).containsExactlyElementsOf(distribution(12, 3, 0, 0, 1));
    }

    @Test
    @DisplayName("should_exposeAddress_when_independentMaster")
    void should_exposeAddress_when_independentMaster() {
        UUID masterId = UUID.randomUUID();
        Master master = enrichedMaster(masterId);
        master.getUser().setStreet("вул. Хрещатик");
        master.getUser().setBuildingNo("22");
        master.getUser().setLocationNote("2 поверх");

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.street()).isEqualTo("вул. Хрещатик");
        assertThat(info.buildingNo()).isEqualTo("22");
        assertThat(info.locationNote()).isEqualTo("2 поверх");
    }

    @Test
    @DisplayName("should_nullAddress_when_salonMaster")
    void should_nullAddress_when_salonMaster() {
        UUID masterId = UUID.randomUUID();
        Master master = enrichedMaster(masterId);
        master.setMasterType(com.beautica.master.entity.MasterType.SALON_MASTER);
        master.getUser().setStreet("вул. Хрещатик");
        master.getUser().setBuildingNo("22");
        master.getUser().setLocationNote("2 поверх");

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.street()).isNull();
        assertThat(info.buildingNo()).isNull();
        assertThat(info.locationNote()).isNull();
    }

    @Test
    @DisplayName("should_clampPageSizeTo20AndDelegate_when_reviewsRequested")
    void should_clampPageSizeTo20AndDelegate_when_reviewsRequested() {
        UUID masterId = UUID.randomUUID();
        when(masterRepository.findActiveReviewTargetByBookingSlug("olena-k-ab12")).thenReturn(Optional.of(target(masterId, 500)));
        when(reviewService.getReviewsForMaster(eq(masterId), eq(SalonReviewSort.HIGHEST), any(PageRequest.class)))
                .thenReturn(Page.empty());

        service.reviewsForSlug("olena-k-ab12", SalonReviewSort.HIGHEST, PageRequest.of(2, 500));

        verify(reviewService).getReviewsForMaster(masterId, SalonReviewSort.HIGHEST, PageRequest.of(2, 20));
    }

    @Test
    @DisplayName("should_clampPageSizeTo20AndDelegate_when_salonReviewsRequested")
    void should_clampPageSizeTo20AndDelegate_when_salonReviewsRequested() {
        UUID salonId = UUID.randomUUID();
        when(salonRepository.findActiveReviewTargetByBookingSlug("studio-a1b2")).thenReturn(Optional.of(target(salonId, 500)));
        when(reviewService.getSalonReviews(eq(salonId), eq(SalonReviewSort.LOWEST), any(PageRequest.class)))
                .thenReturn(Page.empty());

        service.salonReviewsForSlug("studio-a1b2", SalonReviewSort.LOWEST, PageRequest.of(1, 999));

        verify(reviewService).getSalonReviews(salonId, SalonReviewSort.LOWEST, PageRequest.of(1, 20));
    }

    @Test
    @DisplayName("should_throwNotFound_when_salonReviewsSlugUnknownOrInactive")
    void should_throwNotFound_when_salonReviewsSlugUnknownOrInactive() {
        when(salonRepository.findActiveReviewTargetByBookingSlug("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.salonReviewsForSlug("nope", SalonReviewSort.NEWEST, PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
        verify(reviewService, org.mockito.Mockito.never()).getSalonReviews(any(), any(), any());
    }

    @Test
    @DisplayName("should_reject400_when_publicReviewsPageAboveCap - master and salon, before any lookup")
    void should_reject400_when_publicReviewsPageAboveCap() {
        assertThatThrownBy(() -> service.reviewsForSlug("olena-k-ab12", SalonReviewSort.NEWEST,
                PageRequest.of(BookingSlugService.MAX_PUBLIC_REVIEWS_PAGE + 1, 20)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.salonReviewsForSlug("studio-a1b2", SalonReviewSort.NEWEST,
                PageRequest.of(BookingSlugService.MAX_PUBLIC_REVIEWS_PAGE + 1, 20)))
                .isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(masterRepository, salonRepository, reviewService);
    }

    @Test
    @DisplayName("should_notCacheEmptyDeepPage_when_pageBeyondLastPage - the cached review method is never reached")
    void should_notCacheEmptyDeepPage_when_pageBeyondLastPage() {
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(masterRepository.findActiveReviewTargetByBookingSlug("olena-k-ab12"))
                .thenReturn(Optional.of(target(masterId, 25)));
        when(salonRepository.findActiveReviewTargetByBookingSlug("studio-a1b2"))
                .thenReturn(Optional.of(target(salonId, 25)));

        var master = service.reviewsForSlug("olena-k-ab12", SalonReviewSort.NEWEST, PageRequest.of(2, 20));
        var salon = service.salonReviewsForSlug("studio-a1b2", SalonReviewSort.NEWEST, PageRequest.of(5, 20));

        assertThat(master.getContent()).isEmpty();
        assertThat(master.getTotalElements()).isEqualTo(25);
        assertThat(salon.getContent()).isEmpty();
        assertThat(salon.getTotalElements()).isEqualTo(25);
        verify(reviewService, org.mockito.Mockito.never()).getReviewsForMaster(any(), any(), any());
        verify(reviewService, org.mockito.Mockito.never()).getSalonReviews(any(), any(), any());
    }

    @Test
    @DisplayName("the last page that still holds data (offset < reviewCount) is served through the review service")
    void should_delegate_when_pageStartsBeforeReviewCount() {
        UUID masterId = UUID.randomUUID();
        when(masterRepository.findActiveReviewTargetByBookingSlug("olena-k-ab12"))
                .thenReturn(Optional.of(target(masterId, 25)));
        when(reviewService.getReviewsForMaster(eq(masterId), eq(SalonReviewSort.NEWEST), any(PageRequest.class)))
                .thenReturn(Page.empty());

        service.reviewsForSlug("olena-k-ab12", SalonReviewSort.NEWEST, PageRequest.of(1, 20));

        verify(reviewService).getReviewsForMaster(masterId, SalonReviewSort.NEWEST, PageRequest.of(1, 20));
    }

    @Test
    @DisplayName("should_throwNotFound_when_reviewsSlugUnknownOrInactive")
    void should_throwNotFound_when_reviewsSlugUnknownOrInactive() {
        when(masterRepository.findActiveReviewTargetByBookingSlug("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reviewsForSlug("nope", SalonReviewSort.NEWEST, PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
        verify(reviewService, org.mockito.Mockito.never()).getReviewsForMaster(any(), any(), any());
    }

    @Test
    @DisplayName("should_returnEmptyListsNotNull_when_nothingToShow")
    void should_returnEmptyListsNotNull_when_nothingToShow() {
        UUID masterId = UUID.randomUUID();
        enrichedMaster(masterId);
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(any(), any())).thenReturn(List.of());

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.services()).isEmpty();
        assertThat(info.categories()).isEmpty();
        assertThat(info.portfolio()).isEmpty();
        assertThat(info.latestReviews()).isEmpty();
    }

    @Test
    @DisplayName("should_requestPortfolio12AndNewest5Reviews_when_slugExists")
    void should_requestPortfolio12AndNewest5Reviews_when_slugExists() {
        UUID masterId = UUID.randomUUID();
        enrichedMaster(masterId);

        service.findBySlug("olena-k-ab12");

        verify(mediaService).getLatestPortfolio(com.beautica.media.entity.EntityType.MASTER, masterId, 12);
        verify(mediaService, org.mockito.Mockito.never()).getPortfolio(any(), any(), any(PageRequest.class));
        verify(reviewService).getLatestReviewsForMaster(masterId, 5);
        verify(reviewService, org.mockito.Mockito.never()).getReviewsForMaster(any(), any(), any());
    }

    @Test
    @DisplayName("should_returnLatestOfMasterAndUserUpdatedAt_when_bothPresent")
    void should_returnLatestOfMasterAndUserUpdatedAt_when_bothPresent() {
        UUID masterId = UUID.randomUUID();
        Master master = enrichedMaster(masterId);
        java.time.Instant older = java.time.Instant.parse("2026-10-01T00:00:00Z");
        java.time.Instant newer = java.time.Instant.parse("2026-10-18T09:12:44Z");
        org.springframework.test.util.ReflectionTestUtils.setField(master, "updatedAt", older);
        org.springframework.test.util.ReflectionTestUtils.setField(master.getUser(), "updatedAt", newer);

        BookingSlugInfoResponse info = service.findBySlug("olena-k-ab12");

        assertThat(info.updatedAt()).isEqualTo(newer);
    }

    @Test
    @DisplayName("should_throwNotFound_when_slugUnknown")
    void should_throwNotFound_when_slugUnknown() {
        when(masterRepository.findByBookingSlugWithUser("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findBySlug("nope"))
                .isInstanceOf(NotFoundException.class);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Master masterWithSlug(String slug) {
        Master m = new Master();
        m.setBookingSlug(slug);
        return m;
    }

    private static Master masterWithUser(String first, String last, String slug) {
        User user = new User("u@example.com", "hash",
                com.beautica.auth.Role.INDEPENDENT_MASTER, first, last, "+380671234567");
        user.setAvatarUrl("https://cdn.example/a.jpg");
        user.setBio("bio");
        Master m = new Master();
        m.setUser(user);
        m.setBookingSlug(slug);
        return m;
    }

    private static void setId(Master m, UUID id) {
        m.setId(id);
    }

    private static com.beautica.common.ReviewTarget target(UUID id, int reviewCount) {
        return new com.beautica.common.ReviewTarget() {
            @Override public UUID getId() { return id; }
            @Override public int getReviewCount() { return reviewCount; }
        };
    }

    private static List<com.beautica.common.RatingBucket> distribution(long five, long four, long three, long two,
                                                                      long one) {
        return List.of(new com.beautica.common.RatingBucket(5, five), new com.beautica.common.RatingBucket(4, four),
                new com.beautica.common.RatingBucket(3, three), new com.beautica.common.RatingBucket(2, two),
                new com.beautica.common.RatingBucket(1, one));
    }

    private static com.beautica.review.dto.MasterReviewSummaryResponse summary(
            List<com.beautica.common.RatingBucket> distribution) {
        return new com.beautica.review.dto.MasterReviewSummaryResponse(null, 0, distribution);
    }

    private static MasterServiceAssignment assignment(String name, int duration, BigDecimal price) {
        return assignment(name, duration, price, null);
    }

    private static MasterServiceAssignment assignment(String name, int duration, BigDecimal price, String category) {
        ServiceDefinition def = ServiceDefinition.builder()
                .category(category)
                .name(name)
                .baseDurationMinutes(duration)
                .basePrice(price)
                .build();
        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .serviceDefinition(def)
                .isActive(true)
                .build();
    }
}
