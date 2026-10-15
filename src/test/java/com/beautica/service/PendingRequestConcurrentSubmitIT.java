package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Two concurrent «Надіслати» submits of the same category request / service-type suggestion must
 * leave exactly ONE PENDING row; the loser gets the duplicate error.
 *
 * <p>Deterministic interleaving instead of a probabilistic barrier: submit #1 runs inside a held-open
 * transaction (its INSERT is done but uncommitted), so submit #2's exists-check cannot see it and
 * passes — exactly the check-then-act window. Submit #2 then either blocks on the unique index
 * (detected via {@code pg_stat_activity}) until #1 commits and fails, or — with no unique index —
 * completes and commits a second PENDING row. Real transactions on real connections; the test class
 * itself is not {@code @Transactional}.
 *
 * <p>Service-type suggestions: protected by V197's partial UNIQUE index; the loser is translated to
 * the SAME {@code BusinessException(409, "A matching suggestion is already pending review")} the
 * exists-check throws. Category requests: protected since V64 by {@code uq_platform_categories_name
 * UNIQUE(name)} (any status); the loser is translated to the SAME {@code BusinessException(409,
 * "Category already exists or is pending: <NAME>")} the exists-check throws.
 */
@DisplayName("Pending request/suggestion — concurrent duplicate submit")
class PendingRequestConcurrentSubmitIT extends AbstractIntegrationTest {

    // HAIRDRESSING is seeded APPROVED + active (V74) — passes the suggestion category guard.
    private static final String SUGGESTION_CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED_NAME = "ITRace Hot Towel Shave";
    private static final String CATEGORY_NAME = "ITRACE_NAILART";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long POLL_MILLIS = 100;

    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanRows() {
        // Neither table is in AbstractIntegrationTest.cleanDb(); no FK between them.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE suggested_name = ?", SUGGESTED_NAME);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    @Test
    @DisplayName("concurrent identical suggestions → one PENDING row, loser gets the existing 409")
    void should_keepOnePendingSuggestion_when_identicalSuggestionsSubmittedConcurrently() throws Exception {
        SuggestServiceTypeRequest request =
                new SuggestServiceTypeRequest(SUGGESTED_NAME, SUGGESTION_CATEGORY, "race");

        Throwable loser = raceTwoSubmits(
                () -> serviceCatalogService.suggestServiceType(request, null),
                "insert into service_type_suggestion");

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM service_type_suggestion WHERE status = 'PENDING' "
                        + "AND category_name = ? AND lower(suggested_name) = lower(?)",
                Integer.class, SUGGESTION_CATEGORY, SUGGESTED_NAME);
        assertThat(pending).as("exactly one PENDING suggestion").isEqualTo(1);
        assertThat(loser)
                .as("loser sees the same error as the exists-check")
                .isInstanceOf(BusinessException.class)
                .hasMessage("A matching suggestion is already pending review");
        assertThat(((BusinessException) loser).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(emailService, times(1)).sendServiceTypeSuggestionNotification(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("concurrent identical category requests → one PENDING row, loser gets the existing 409")
    void should_keepOnePendingCategory_when_identicalRequestsSubmittedConcurrently() throws Exception {
        CreateCategoryRequestRequest request = new CreateCategoryRequestRequest(CATEGORY_NAME, "Гонка-арт", null);

        Throwable loser = raceTwoSubmits(
                () -> categoryRequestService.submitRequest(request, null),
                "insert into platform_categories");

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_categories WHERE status = 'PENDING' AND name = ?",
                Integer.class, CATEGORY_NAME);
        assertThat(pending).as("exactly one PENDING category request").isEqualTo(1);
        assertThat(loser)
                .as("loser rejected by uq_platform_categories_name, translated to the exists-check error")
                .isInstanceOf(BusinessException.class)
                .hasMessage("Category already exists or is pending: " + CATEGORY_NAME);
        assertThat(((BusinessException) loser).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(emailService, times(1)).sendCategoryRequestNotification(
                any(), any(), any(), any(), any());
    }

    /**
     * Runs submit #1 in a held-open transaction, fires submit #2 once #1 has inserted, waits until #2
     * is either blocked on a row lock or finished, then commits #1. Returns #2's failure, or fails the
     * test if #2 succeeded (and the caller's row-count assertion would also fail).
     */
    private Throwable raceTwoSubmits(Runnable submit, String insertPrefix) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            submit.run();
            firstInserted.countDown();
            awaitQuietly(releaseFirst);
        }));
        assertThat(firstInserted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("submit #1 inserted").isTrue();

        CompletableFuture<Void> second = CompletableFuture.runAsync(submit);
        awaitBlockedOrDone(second, insertPrefix);
        releaseFirst.countDown();

        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        try {
            second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
        return fail("submit #2 succeeded — a second PENDING row was committed");
    }

    private void awaitBlockedOrDone(CompletableFuture<Void> second, String insertPrefix) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            try {
                second.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException done) {
                return;
            } catch (TimeoutException stillRunning) {
                if (isInsertWaitingOnLock(insertPrefix)) {
                    return;
                }
            }
        }
        fail("submit #2 neither blocked on the unique index nor finished within " + WAIT);
    }

    private boolean isInsertWaitingOnLock(String insertPrefix) {
        Integer waiting = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND lower(query) LIKE ?",
                Integer.class, insertPrefix + "%");
        return waiting != null && waiting > 0;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("submit #1 was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
