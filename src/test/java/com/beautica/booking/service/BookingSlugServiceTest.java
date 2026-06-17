package com.beautica.booking.service;

import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

        BookingSlugInfoResponse info = service.findBySlug("marija-l-cd34");

        assertThat(info.masterName()).isEqualTo("Марія Левченко");
        assertThat(info.services()).hasSize(1);
        assertThat(info.services().get(0).name()).isEqualTo("Манікюр");
        assertThat(info.services().get(0).priceFrom()).isEqualByComparingTo("350.00");
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

    private static MasterServiceAssignment assignment(String name, int duration, BigDecimal price) {
        ServiceDefinition def = ServiceDefinition.builder()
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
