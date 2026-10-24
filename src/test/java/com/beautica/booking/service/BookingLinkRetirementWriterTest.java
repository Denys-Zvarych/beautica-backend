package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.repository.RetiredBookingSlugRepository;
import com.beautica.common.cache.MasterProfileCacheEvictor;
import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.common.exception.ConflictException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 378 — {@link BookingLinkRetirementWriter}: the D11 order (lock → generate → CAS → tombstone →
 * eviction), CAS 409 with no tombstone, idempotent revoke without a write, and "no live slug → no tombstone".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingLinkRetirementWriter — unit (phase 378)")
class BookingLinkRetirementWriterTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MASTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALON_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final String OLD = "olena-koval-x7k2";
    private static final String NEW = "olena-koval-p3qd";
    private static final String SALON_OLD = "alfa-x7k2";
    private static final String MEMBER_SLUG = "anna-b-aa11";

    private static String masterHash(String slug) {
        return RetiredSlugHasher.hash(BookingLinkKind.MASTER, slug);
    }

    private static String salonHash(String slug) {
        return RetiredSlugHasher.hash(BookingLinkKind.SALON, slug);
    }

    @Mock private MasterRepository masterRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private RetiredBookingSlugRepository retiredRepository;
    @Mock private BookingSlugService bookingSlugService;
    @Mock private MasterProfileCacheEvictor masterProfileCacheEvictor;
    @Mock private SalonBookingInfoCacheEvictor salonBookingInfoCacheEvictor;

    private BookingLinkRetirementWriter writer;
    private Master master;

    @BeforeEach
    void setUp() {
        writer = new BookingLinkRetirementWriter(masterRepository, salonRepository, retiredRepository,
                bookingSlugService, masterProfileCacheEvictor, salonBookingInfoCacheEvictor,
                Clock.fixed(NOW, ZoneOffset.UTC));
        User user = new User("m@beautica.test", "hash", Role.INDEPENDENT_MASTER, "Олена", "Коваль", null);
        master = Master.builder().id(MASTER_ID).user(user).masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true).bookingSlug(OLD).build();
    }

    private static MasterRepository.BookingLinkState state(String slug, boolean revoked) {
        return new MasterRepository.BookingLinkState() {
            @Override public String getBookingSlug() { return slug; }
            @Override public Boolean getRevoked() { return revoked; }
        };
    }

    @Test
    @DisplayName("master regenerate: lock old slug → locked generation → CAS → tombstone REGENERATED → evict old key")
    void should_runD11Order_when_regeneratingMaster() {
        when(bookingSlugService.generateMasterSlugUnderLock("Олена", "Коваль", OLD)).thenReturn(NEW);
        when(masterRepository.replaceBookingSlug(MASTER_ID, OLD, NEW)).thenReturn(1);

        String issued = writer.regenerateMaster(master, OLD, ACTOR);

        assertThat(issued).isEqualTo(NEW);
        InOrder order = inOrder(salonRepository, bookingSlugService, masterRepository, retiredRepository,
                masterProfileCacheEvictor);
        order.verify(salonRepository).lockBookingSlugCandidates(new String[] {OLD});
        order.verify(bookingSlugService).generateMasterSlugUnderLock("Олена", "Коваль", OLD);
        order.verify(masterRepository).replaceBookingSlug(MASTER_ID, OLD, NEW);
        order.verify(retiredRepository).insertMasterTombstone(masterHash(OLD), MASTER_ID, ACTOR, "REGENERATED", NOW);
        order.verify(masterProfileCacheEvictor).evictBookingSlugInfoBySlugAfterCommit(OLD);
    }

    @Test
    @DisplayName("D7: CAS matches no row → 409, no tombstone, no eviction")
    void should_throwConflictWithoutTombstone_when_casMisses() {
        when(bookingSlugService.generateMasterSlugUnderLock(any(), any(), any())).thenReturn(NEW);
        when(masterRepository.replaceBookingSlug(MASTER_ID, OLD, NEW)).thenReturn(0);

        assertThatThrownBy(() -> writer.regenerateMaster(master, OLD, ACTOR)).isInstanceOf(ConflictException.class);

        verifyNoInteractions(retiredRepository, masterProfileCacheEvictor);
    }

    @Test
    @DisplayName("regenerate from a REVOKED link (currentSlug null): CAS token \"\", no lock on old text, no tombstone")
    void should_issueWithoutTombstone_when_regeneratingFromRevoked() {
        when(bookingSlugService.generateMasterSlugUnderLock(any(), any(), any())).thenReturn(NEW);
        when(masterRepository.replaceBookingSlug(MASTER_ID, "", NEW)).thenReturn(1);

        writer.regenerateMaster(master, null, ACTOR);

        verify(salonRepository, never()).lockBookingSlugCandidates(any());
        verifyNoInteractions(retiredRepository, masterProfileCacheEvictor);
    }

    @Test
    @DisplayName("master revoke: CAS stamps revoked_at with the app clock, tombstone REVOKED, old key evicted")
    void should_tombstoneAndEvict_when_revokingMaster() {
        when(masterRepository.revokeBookingSlug(MASTER_ID, OLD, NOW)).thenReturn(1);

        writer.revokeMaster(MASTER_ID, OLD, ACTOR);

        verify(salonRepository).lockBookingSlugCandidates(new String[] {OLD});
        verify(retiredRepository).insertMasterTombstone(masterHash(OLD), MASTER_ID, ACTOR, "REVOKED", NOW);
        verify(masterProfileCacheEvictor).evictBookingSlugInfoBySlugAfterCommit(OLD);
    }

    @Test
    @DisplayName("D8: revoke on an already-revoked link (currentSlug null) → no write, no tombstone")
    void should_doNothing_when_revokingAlreadyRevokedMaster() {
        when(masterRepository.findBookingLinkStateById(MASTER_ID)).thenReturn(Optional.of(state(null, true)));

        writer.revokeMaster(MASTER_ID, null, ACTOR);

        verify(masterRepository, never()).revokeBookingSlug(any(), anyString(), any());
        verifyNoInteractions(retiredRepository, masterProfileCacheEvictor);
    }

    @Test
    @DisplayName("revoke of a never-issued legacy link (null slug, not revoked) → flag set, no tombstone")
    void should_setFlagWithoutTombstone_when_revokingLegacyMaster() {
        when(masterRepository.findBookingLinkStateById(MASTER_ID)).thenReturn(Optional.of(state(null, false)));
        when(masterRepository.revokeBookingSlug(MASTER_ID, "", NOW)).thenReturn(1);

        writer.revokeMaster(MASTER_ID, null, ACTOR);

        verifyNoInteractions(retiredRepository);
    }

    @Test
    @DisplayName("salon regenerate: salon generator, CAS, SALON tombstone, salon page (value + old key) + member pages evicted")
    void should_tombstoneAndEvictPages_when_regeneratingSalon() {
        when(masterRepository.findActiveMemberBookingSlugsBySalonId(SALON_ID)).thenReturn(List.of(MEMBER_SLUG));
        when(bookingSlugService.generateSalonSlug("Alfa", SALON_OLD)).thenReturn("alfa-p3qd");
        when(salonRepository.replaceBookingSlug(SALON_ID, SALON_OLD, "alfa-p3qd")).thenReturn(1);

        String issued = writer.regenerateSalon(SALON_ID, "Alfa", SALON_OLD, ACTOR);

        assertThat(issued).isEqualTo("alfa-p3qd");
        verify(retiredRepository).insertSalonTombstone(salonHash(SALON_OLD), SALON_ID, ACTOR, "REGENERATED", NOW);
        verify(salonBookingInfoCacheEvictor).evictSalonAfterCommit(SALON_ID);
        verify(salonBookingInfoCacheEvictor).evictSlugAfterCommit(SALON_OLD);
        verify(masterProfileCacheEvictor).evictBookingSlugInfoBySlugsAfterCommit(List.of(MEMBER_SLUG));
    }

    @Test
    @DisplayName("378 audit security LOW: salon revoke evicts the retired slug's salon-booking-slug-info key after commit")
    void should_evictOldSalonSlugKey_when_salonRevokeCommits() {
        when(masterRepository.findActiveMemberBookingSlugsBySalonId(SALON_ID)).thenReturn(List.of(MEMBER_SLUG));
        when(salonRepository.revokeBookingSlug(SALON_ID, SALON_OLD, NOW)).thenReturn(1);

        writer.revokeSalon(SALON_ID, SALON_OLD, ACTOR);

        verify(retiredRepository).insertSalonTombstone(salonHash(SALON_OLD), SALON_ID, ACTOR, "REVOKED", NOW);
        verify(salonBookingInfoCacheEvictor).evictSlugAfterCommit(SALON_OLD);
        verify(salonBookingInfoCacheEvictor).evictSalonAfterCommit(SALON_ID);
        verify(masterProfileCacheEvictor).evictBookingSlugInfoBySlugsAfterCommit(List.of(MEMBER_SLUG));
    }

    @Test
    @DisplayName("378 audit perf LOW-2: member slugs are read in ONE query BEFORE the slug lock and the CAS UPDATE")
    void should_readMemberSlugsBeforeLocks_when_regeneratingSalon() {
        when(masterRepository.findActiveMemberBookingSlugsBySalonId(SALON_ID)).thenReturn(List.of(MEMBER_SLUG));
        when(bookingSlugService.generateSalonSlug("Alfa", SALON_OLD)).thenReturn("alfa-p3qd");
        when(salonRepository.replaceBookingSlug(SALON_ID, SALON_OLD, "alfa-p3qd")).thenReturn(1);

        writer.regenerateSalon(SALON_ID, "Alfa", SALON_OLD, ACTOR);

        InOrder order = inOrder(masterRepository, salonRepository);
        order.verify(masterRepository).findActiveMemberBookingSlugsBySalonId(SALON_ID);
        order.verify(salonRepository).lockBookingSlugCandidates(new String[] {SALON_OLD});
        order.verify(salonRepository).replaceBookingSlug(SALON_ID, SALON_OLD, "alfa-p3qd");
        verify(masterRepository, never()).findIdsBySalonIdAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("378 audit security MEDIUM: the tombstone gets SHA-256(kind:slug) hex — the plaintext slug is never written")
    void should_storeNoPlaintextSlug_when_tombstoneWritten() {
        when(masterRepository.revokeBookingSlug(MASTER_ID, OLD, NOW)).thenReturn(1);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);

        writer.revokeMaster(MASTER_ID, OLD, ACTOR);

        verify(retiredRepository).insertMasterTombstone(stored.capture(), any(), any(), any(), any());
        assertThat(stored.getValue())
                .matches("^[0-9a-f]{64}$")
                .isEqualTo(RetiredSlugHasher.hash(BookingLinkKind.MASTER, OLD))
                .doesNotContain(OLD)
                .doesNotContain("olena");
    }

    @Test
    @DisplayName("salon revoke CAS misses → 409, no tombstone, no eviction")
    void should_throwConflict_when_salonRevokeCasMisses() {
        when(salonRepository.revokeBookingSlug(SALON_ID, SALON_OLD, NOW)).thenReturn(0);

        assertThatThrownBy(() -> writer.revokeSalon(SALON_ID, SALON_OLD, ACTOR)).isInstanceOf(ConflictException.class);

        verifyNoInteractions(retiredRepository, salonBookingInfoCacheEvictor);
    }
}
