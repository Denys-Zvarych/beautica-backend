package com.beautica.notification.telegram.link;

import com.beautica.common.security.PhoneHasher;
import com.beautica.config.PhoneHashPepperConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 409 audit-fix cycle 2 — an over-budget phone scan deletes nothing and is counted; a scan that completes inside
 * the budget (even on its last page) deletes against the full open-phone set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramLinkOrphanBackstop — phone budget and keyset scan (phase 409)")
class TelegramLinkOrphanBackstopTest {

    @Mock private OpenGuestPhoneSource phoneSource;
    @Mock private TelegramLinkRetentionService retentionService;

    private static final Instant NOW = Instant.parse("2026-11-01T03:51:00Z");
    private static final String OPEN_PHONE = "+380501234567";

    private PhoneHasher hasher;
    private SimpleMeterRegistry registry;
    private TelegramLinkOrphanBackstop backstop;

    @BeforeEach
    void setUp() {
        hasher = new PhoneHasher(new PhoneHashPepperConfig("unit-test-pepper-unit-test-pepper-0123456789"));
        registry = new SimpleMeterRegistry();
        backstop = new TelegramLinkOrphanBackstop(phoneSource, retentionService, hasher, registry);
    }

    private static List<String> fullPageEndingWith(String last) {
        List<String> page = new ArrayList<>(IntStream.range(0, TelegramLinkOrphanBackstop.PHONE_PAGE_SIZE - 1)
                .mapToObj(i -> "not-a-phone-" + i).toList());
        page.add(last);
        return page;
    }

    @Test
    @DisplayName("every page full for the whole budget → no delete, skipped counter = 1")
    void should_countSkipAndDeleteNothing_when_phoneBudgetExceeded() {
        List<String> fullPage = IntStream.range(0, TelegramLinkOrphanBackstop.PHONE_PAGE_SIZE)
                .mapToObj(i -> "not-a-phone-" + i).toList();
        when(phoneSource.openGuestPhonesPage(anyString(), anyInt())).thenReturn(fullPage);

        int deleted = backstop.deleteOrphanLinks(NOW);

        assertThat(deleted).isZero();
        assertThat(registry.counter(TelegramLinkOrphanBackstop.SKIPPED_METRIC).count()).isEqualTo(1.0);
        verifyNoInteractions(retentionService);
    }

    @Test
    @DisplayName("scan finishing on the LAST budget page (page 200 short) still deletes, against the full phone set")
    void should_deleteAgainstFullSet_when_scanEndsOnLastBudgetPage() {
        List<String> fullPage = fullPageEndingWith("zz-last");
        // pages 1..199 full, page 200 short and carrying the one real open phone
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < TelegramLinkOrphanBackstop.MAX_PHONE_PAGES - 1; i++) {
            pages.add(fullPage);
        }
        pages.add(List.of(OPEN_PHONE));
        int[] call = {0};
        when(phoneSource.openGuestPhonesPage(anyString(), anyInt())).thenAnswer(inv -> pages.get(call[0]++));
        when(retentionService.deleteOrphanLinks(any(), any(), anyInt())).thenReturn(4);

        int deleted = backstop.deleteOrphanLinks(NOW);

        ArgumentCaptor<PhoneHashPrefixes> open = ArgumentCaptor.forClass(PhoneHashPrefixes.class);
        verify(retentionService).deleteOrphanLinks(open.capture(), eq(NOW.minus(Duration.ofDays(1))),
                eq(TelegramLinkOrphanBackstop.MAX_LINK_PAGES));
        assertThat(deleted).isEqualTo(4);
        assertThat(open.getValue().contains(hasher.hash(OPEN_PHONE).hash())).isTrue();
        assertThat(registry.counter(TelegramLinkOrphanBackstop.SKIPPED_METRIC).count()).isZero();
    }

    @Test
    @DisplayName("keyset: the first page starts after \"\", the next after the previous page's last phone")
    void should_continueAfterLastPhone_when_phonePageIsFull() {
        List<String> firstPage = fullPageEndingWith(OPEN_PHONE);
        when(phoneSource.openGuestPhonesPage("", TelegramLinkOrphanBackstop.PHONE_PAGE_SIZE)).thenReturn(firstPage);
        when(phoneSource.openGuestPhonesPage(OPEN_PHONE, TelegramLinkOrphanBackstop.PHONE_PAGE_SIZE))
                .thenReturn(List.of());

        backstop.deleteOrphanLinks(NOW);

        verify(phoneSource, times(2)).openGuestPhonesPage(anyString(), anyInt());
        verify(retentionService).deleteOrphanLinks(any(), eq(NOW.minus(Duration.ofDays(1))),
                eq(TelegramLinkOrphanBackstop.MAX_LINK_PAGES));
    }
}
