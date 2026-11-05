package com.beautica.notification.telegram.link;

import com.beautica.common.security.PhoneHasher;
import com.beautica.config.PhoneHashPepperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 409 — fixed D2 period, the erase's lock order, the D1 lock-before-check order, the backstop's prefix-collision
 * spare and keyset paging, and the batch cap shared by the D2/D4 purges.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramLinkRetentionService — D1 lock order, backstop spare + paging, batch caps, D2/D3 (phase 409)")
class TelegramLinkRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T03:27:00Z");

    @Mock private TelegramLinkRepository linkRepository;
    @Mock private TelegramLinkTokenRepository tokenRepository;

    @Mock private Function<Collection<String>, Collection<String>> phonesWithOpenBookings;

    private static final String PHONE_A = "+380501234567";
    private static final String PHONE_B = "+380671112233";
    private static final Instant LINKED_BEFORE = Instant.parse("2026-10-30T03:51:00Z");

    private PhoneHasher hasher;
    private TelegramLinkRetentionService service;

    @BeforeEach
    void setUp() {
        hasher = new PhoneHasher(new PhoneHashPepperConfig("unit-test-pepper-unit-test-pepper-0123456789"));
        service = new TelegramLinkRetentionService(linkRepository, tokenRepository, hasher);
    }

    private byte[] hash(String phone) {
        return hasher.hash(phone).hash();
    }

    private record Key(UUID id, byte[] phoneHash) implements TelegramLinkRepository.LinkKey {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public byte[] getPhoneHash() {
            return phoneHash;
        }
    }

    private static byte[] hashWithPrefix(long prefix, long tail) {
        return ByteBuffer.allocate(32).putLong(prefix).putLong(tail).array();
    }

    @Test
    @DisplayName("D2 deletes BLOCKED links changed more than exactly 12 calendar months before now")
    void should_useFixedTwelveMonthCutoff_when_purgingBlocked() {
        service.purgeBlockedLinks(NOW, 500, 200);

        verify(linkRepository).deleteBlockedBatchBefore(Instant.parse("2025-10-31T03:27:00Z"), 500);
        assertThat(TelegramLinkRetentionService.BLOCKED_RETENTION_MONTHS).isEqualTo(12);
    }

    @Test
    @DisplayName("D3 erase: per-phone issue lock, then tokens, then the link")
    void should_lockIssueThenDeleteTokensThenLink_when_erasing() {
        service.eraseForPhone("+380501234567");

        InOrder order = inOrder(tokenRepository, linkRepository);
        order.verify(tokenRepository).acquirePhoneIssueLock(any());
        order.verify(tokenRepository).deleteAllByPhoneHash(any());
        order.verify(linkRepository).deleteByPhone(any());
    }

    @Test
    @DisplayName("D1: every linked phone's retention lock is taken BEFORE the still-booked check, then the delete")
    void should_lockEveryLinkedPhoneBeforeCheckingBookings_when_unlinkingFreedPhones() {
        when(linkRepository.findLinkedPhoneHashes(anyCollection())).thenReturn(List.of(hash(PHONE_A), hash(PHONE_B)));
        when(phonesWithOpenBookings.apply(anyCollection())).thenReturn(List.of());
        when(linkRepository.deleteByPhones(anyCollection())).thenReturn(2);

        int deleted = service.unlinkPhonesWithoutOpenBookings(List.of(PHONE_A, PHONE_B), phonesWithOpenBookings);

        assertThat(deleted).isEqualTo(2);
        InOrder order = inOrder(linkRepository, phonesWithOpenBookings);
        order.verify(linkRepository, times(2)).acquirePhoneRetentionLock(any());
        order.verify(phonesWithOpenBookings).apply(anyCollection());
        order.verify(linkRepository).deleteByPhones(anyCollection());
    }

    @Test
    @DisplayName("D1: retention locks are taken in ascending hash-hex order (no lock-order deadlock between instances)")
    void should_lockInHashOrder_when_unlinkingSeveralPhones() {
        when(linkRepository.findLinkedPhoneHashes(anyCollection())).thenReturn(List.of(hash(PHONE_A), hash(PHONE_B)));
        when(phonesWithOpenBookings.apply(anyCollection())).thenReturn(List.of(PHONE_A, PHONE_B));

        service.unlinkPhonesWithoutOpenBookings(List.of(PHONE_A, PHONE_B), phonesWithOpenBookings);

        ArgumentCaptor<byte[]> locked = ArgumentCaptor.forClass(byte[].class);
        verify(linkRepository, times(2)).acquirePhoneRetentionLock(locked.capture());
        List<String> lockedHex = locked.getAllValues().stream().map(HexFormat.of()::formatHex).toList();
        assertThat(lockedHex).as("lock order, actual=%s", lockedHex).isSorted().hasSize(2);
    }

    @Test
    @DisplayName("D1: a phone the check reports as still booked keeps its link; only the freed phone's hash is deleted")
    void should_deleteOnlyFreedPhone_when_otherPhoneStillBooked() {
        when(linkRepository.findLinkedPhoneHashes(anyCollection())).thenReturn(List.of(hash(PHONE_A), hash(PHONE_B)));
        when(phonesWithOpenBookings.apply(anyCollection())).thenReturn(List.of(PHONE_A));
        when(linkRepository.deleteByPhones(anyCollection())).thenReturn(1);

        service.unlinkPhonesWithoutOpenBookings(List.of(PHONE_A, PHONE_B), phonesWithOpenBookings);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<byte[]>> deleted = ArgumentCaptor.forClass(Collection.class);
        verify(linkRepository).deleteByPhones(deleted.capture());
        assertThat(deleted.getValue()).singleElement().isEqualTo(hash(PHONE_B));
    }

    @Test
    @DisplayName("D1: every linked phone still booked → no delete statement at all")
    void should_notDelete_when_everyLinkedPhoneStillBooked() {
        when(linkRepository.findLinkedPhoneHashes(anyCollection())).thenReturn(List.of(hash(PHONE_A)));
        when(phonesWithOpenBookings.apply(anyCollection())).thenReturn(List.of(PHONE_A));

        int deleted = service.unlinkPhonesWithoutOpenBookings(List.of(PHONE_A), phonesWithOpenBookings);

        assertThat(deleted).isZero();
        verify(linkRepository, never()).deleteByPhones(anyCollection());
    }

    @Test
    @DisplayName("D1: no freed phone has a link → no lock, no booking check, no delete")
    void should_skipLockAndCheck_when_noFreedPhoneIsLinked() {
        when(linkRepository.findLinkedPhoneHashes(anyCollection())).thenReturn(List.of());

        int deleted = service.unlinkPhonesWithoutOpenBookings(List.of(PHONE_A), phonesWithOpenBookings);

        assertThat(deleted).isZero();
        verify(linkRepository, never()).acquirePhoneRetentionLock(any());
        verify(linkRepository, never()).deleteByPhones(anyCollection());
        verifyNoInteractions(phonesWithOpenBookings);
    }

    @Test
    @DisplayName("D1: un-hashable phones only (garbage in the batch) → no repository call at all")
    void should_touchNothing_when_noPhoneIsHashable() {
        int deleted = service.unlinkPhonesWithoutOpenBookings(List.of("not-a-phone", " "), phonesWithOpenBookings);

        assertThat(deleted).isZero();
        verifyNoInteractions(linkRepository, phonesWithOpenBookings);
    }

    @Test
    @DisplayName("backstop: a link whose hash shares the 8-byte prefix of an open phone is spared; a true orphan goes")
    void should_spareLink_when_hashCollidesOnPrefixWithOpenPhone() {
        PhoneHashPrefixes open = new PhoneHashPrefixes();
        open.add(hashWithPrefix(42L, 1L));
        UUID collided = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        when(linkRepository.findLinkKeysPage(any(), eq(LINKED_BEFORE), anyInt())).thenReturn(List.of(
                new Key(collided, hashWithPrefix(42L, 2L)), new Key(orphan, hashWithPrefix(43L, 0L))));
        when(linkRepository.deleteOrphansByIds(anyCollection(), eq(LINKED_BEFORE))).thenReturn(1);

        int deleted = service.deleteOrphanLinks(open, LINKED_BEFORE, 100);

        assertThat(deleted).isEqualTo(1);
        verify(linkRepository).deleteOrphansByIds(List.of(orphan), LINKED_BEFORE);
    }

    @Test
    @DisplayName("backstop: every link of the page belongs to an open phone → no delete statement")
    void should_notDelete_when_everyLinkHasOpenPhone() {
        PhoneHashPrefixes open = new PhoneHashPrefixes();
        open.add(hashWithPrefix(7L, 0L));
        when(linkRepository.findLinkKeysPage(any(), eq(LINKED_BEFORE), anyInt()))
                .thenReturn(List.of(new Key(UUID.randomUUID(), hashWithPrefix(7L, 9L))));

        int deleted = service.deleteOrphanLinks(open, LINKED_BEFORE, 100);

        assertThat(deleted).isZero();
        verify(linkRepository, never()).deleteOrphansByIds(anyCollection(), any());
    }

    @Test
    @DisplayName("backstop: a full page continues from its last id (keyset starts at the all-zero uuid); a short page ends")
    void should_continueFromLastId_when_linkPageIsFull() {
        List<TelegramLinkRepository.LinkKey> fullPage = new ArrayList<>(IntStream
                .range(0, TelegramLinkRetentionService.ORPHAN_PAGE_SIZE)
                .mapToObj(i -> (TelegramLinkRepository.LinkKey) new Key(new UUID(0L, i + 1L), hashWithPrefix(i, 0L)))
                .toList());
        UUID lastOfFirstPage = fullPage.get(fullPage.size() - 1).getId();
        PhoneHashPrefixes open = new PhoneHashPrefixes();
        open.add(hashWithPrefix(-1L, 0L));
        when(linkRepository.findLinkKeysPage(new UUID(0L, 0L), LINKED_BEFORE, TelegramLinkRetentionService.ORPHAN_PAGE_SIZE))
                .thenReturn(fullPage);
        when(linkRepository.findLinkKeysPage(lastOfFirstPage, LINKED_BEFORE, TelegramLinkRetentionService.ORPHAN_PAGE_SIZE))
                .thenReturn(List.of());
        when(linkRepository.deleteOrphansByIds(anyCollection(), eq(LINKED_BEFORE)))
                .thenReturn(TelegramLinkRetentionService.ORPHAN_PAGE_SIZE);

        int deleted = service.deleteOrphanLinks(open, LINKED_BEFORE, 100);

        assertThat(deleted).isEqualTo(TelegramLinkRetentionService.ORPHAN_PAGE_SIZE);
        verify(linkRepository, times(2)).findLinkKeysPage(any(), eq(LINKED_BEFORE), anyInt());
    }

    @Test
    @DisplayName("backstop: maxPages caps one run even when every page is full")
    void should_stopAtMaxPages_when_everyLinkPageIsFull() {
        List<TelegramLinkRepository.LinkKey> fullPage = IntStream
                .range(0, TelegramLinkRetentionService.ORPHAN_PAGE_SIZE)
                .mapToObj(i -> (TelegramLinkRepository.LinkKey) new Key(UUID.randomUUID(), hashWithPrefix(i, 0L)))
                .toList();
        PhoneHashPrefixes open = new PhoneHashPrefixes();
        open.add(hashWithPrefix(-1L, 0L));
        when(linkRepository.findLinkKeysPage(any(), eq(LINKED_BEFORE), anyInt())).thenReturn(fullPage);

        service.deleteOrphanLinks(open, LINKED_BEFORE, 3);

        verify(linkRepository, times(3)).findLinkKeysPage(any(), eq(LINKED_BEFORE), anyInt());
    }

    @Test
    @DisplayName("D4 batch cap: every batch full → exactly maxBatches statements, total = maxBatches × batchSize")
    void should_stopAtMaxBatches_when_everyTokenBatchIsFull() {
        Instant cutoff = NOW.minusSeconds(86_400);
        when(tokenRepository.deleteExpiredBatchBefore(cutoff, 1_000)).thenReturn(1_000);

        int deleted = service.purgeExpiredTokens(cutoff, 1_000, 100);

        assertThat(deleted).isEqualTo(100_000);
        verify(tokenRepository, times(100)).deleteExpiredBatchBefore(cutoff, 1_000);
    }

    @Test
    @DisplayName("D4 batch cap: a short batch ends the run early (the backlog is drained)")
    void should_stopEarly_when_tokenBatchIsShort() {
        Instant cutoff = NOW.minusSeconds(86_400);
        when(tokenRepository.deleteExpiredBatchBefore(cutoff, 1_000)).thenReturn(1_000, 1_000, 7);

        int deleted = service.purgeExpiredTokens(cutoff, 1_000, 100);

        assertThat(deleted).isEqualTo(2_007);
        verify(tokenRepository, times(3)).deleteExpiredBatchBefore(cutoff, 1_000);
    }
}
