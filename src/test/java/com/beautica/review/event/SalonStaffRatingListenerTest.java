package com.beautica.review.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.master.event.SalonStaffChangedEvent;
import com.beautica.review.service.RatingRecalculationService;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the SECOND salon-rating recalc trigger (mobile Phase 111).
 *
 * <p>Contract mirrored from {@link ReviewEventListenerTest}: an active synchronization context is
 * initialised in {@code setUp} because the listener registers an {@code afterCompletion} callback
 * rather than evicting inline, and the callbacks are fired explicitly so the ordering the
 * production path relies on is exercised rather than assumed.
 */
@DisplayName("SalonStaffRatingListener — unit")
@ExtendWith(MockitoExtension.class)
class SalonStaffRatingListenerTest {

    /**
     * The listener's collaborator is the {@code REQUIRES_NEW} BOUNDARY bean, not the repository:
     * the boundary was moved off {@code onSalonStaffChanged} so the interceptor's commit — and the
     * {@code UnexpectedRollbackException} it raises once Hibernate has marked the transaction
     * rollback-only — happens inside the listener's own try/catch. Mocking it here mocks that
     * proxy, which is what makes
     * {@link #should_notPropagate_when_recalcThrowsDataAccessException} and its
     * {@code UnexpectedRollbackException} sibling able to simulate the real escape path at all.
     */
    @Mock
    private RatingRecalculationService ratingRecalculationService;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private SalonStaffRatingListener listener;

    private final UUID salonId = UUID.randomUUID();

    private ListAppender<ILoggingEvent> listAppender;
    private Logger listenerLogger;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();

        listenerLogger = (Logger) LoggerFactory.getLogger(SalonStaffRatingListener.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        listenerLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        listenerLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @DisplayName("should_recalculateSalonRating_when_staffChanged")
    void should_recalculateSalonRating_when_staffChanged() {
        listener.onSalonStaffChanged(new SalonStaffChangedEvent(salonId));

        verify(ratingRecalculationService).recalculateSalonRating(salonId);
    }

    @Test
    @DisplayName("should_evictSalonDetailAfterCompletion_when_staffChanged")
    void should_evictSalonDetailAfterCompletion_when_staffChanged() {
        org.springframework.cache.Cache salonDetail = new CaffeineCache(
                "salon-detail", Caffeine.newBuilder().build());
        salonDetail.put(salonId, "stale salon with the pre-recalc average");
        org.springframework.cache.Cache reviewPages = new CaffeineCache(
                "reviews-by-salon", Caffeine.newBuilder().build());
        when(cacheManager, salonDetail, reviewPages);

        listener.onSalonStaffChanged(new SalonStaffChangedEvent(salonId));

        assertThat(salonDetail.get(salonId))
                .as("eviction must NOT run inline — a concurrent reader would repopulate the "
                        + "cache with the pre-UPDATE average before this transaction commits")
                .isNotNull();

        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(),
                TransactionSynchronization.STATUS_COMMITTED);

        assertThat(salonDetail.get(salonId))
                .as("recalculateSalonRating wrote salons.avg_rating — the cached Salon entity "
                        + "carrying the old value must be gone once the transaction completes")
                .isNull();
    }

    @Test
    @DisplayName("should_logErrorAndNotPropagate_when_recalculateSalonRatingThrows")
    void should_logErrorAndNotPropagate_when_recalculateSalonRatingThrows() {
        doThrow(new RuntimeException("db down"))
                .when(ratingRecalculationService).recalculateSalonRating(salonId);

        assertThatNoException()
                .as("a failed recalc must never roll back the already-committed staff change")
                .isThrownBy(() -> listener.onSalonStaffChanged(new SalonStaffChangedEvent(salonId)));

        assertThat(listAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("recalculateSalonRating failed");
                    assertThat(event.getFormattedMessage())
                            .as("the exception's simple name only — never its message")
                            .contains("RuntimeException")
                            .doesNotContain("db down");
                });
    }

    /**
     * The event forbids a null salon id at construction, so a publisher holding a {@code null}
     * salon (an {@code INDEPENDENT_MASTER} has none) cannot emit one — the guard lives at the
     * event rather than being re-derived in every listener.
     */
    @Test
    @DisplayName("should_rejectNullSalonId_when_constructingEvent")
    void should_rejectNullSalonId_when_constructingEvent() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new SalonStaffChangedEvent(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Finding 3 — {@code catch (Exception)} around a {@code REQUIRES_NEW} call does NOT contain a
     * JPA failure while the boundary is on the listener method itself.
     *
     * <p>The sequence that used to escape: the {@code UPDATE} throws a {@link DataAccessException};
     * Hibernate marks the transaction rollback-only; the {@code catch} swallows the original and
     * the method returns normally; {@code TransactionInterceptor#commitTransactionAfterReturning}
     * then runs OUTSIDE that catch and {@code processCommit} throws
     * {@link UnexpectedRollbackException} into
     * {@code TransactionSynchronizationUtils#invokeAfterCommit}, which has no catch — so an
     * already-COMMITTED master deactivation surfaced to the caller as a 500 with the write
     * persisted.
     *
     * <p>With the boundary on {@code RatingRecalculationService}, the mock stands in for that
     * proxy: whichever of the two exceptions the proxy raises, it is raised across the call inside
     * the try, so the catch contains it. Both are asserted — the underlying data-access failure
     * here, the interceptor's own rollback exception in the sibling below.
     */
    @Test
    @DisplayName("should_notPropagate_when_recalcThrowsDataAccessException")
    void should_notPropagate_when_recalcThrowsDataAccessException() {
        doThrow(new DataIntegrityViolationException("constraint violated"))
                .when(ratingRecalculationService).recalculateSalonRating(salonId);

        assertThatNoException()
                .as("a JPA failure inside the recalc transaction must never turn the "
                        + "already-committed staff change into a 500")
                .isThrownBy(() -> listener.onSalonStaffChanged(new SalonStaffChangedEvent(salonId)));

        assertThat(listAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("recalculateSalonRating failed")
                            .contains("DataIntegrityViolationException")
                            .as("the exception's simple name only — never its message")
                            .doesNotContain("constraint violated");
                });
    }

    /** @see #should_notPropagate_when_recalcThrowsDataAccessException */
    @Test
    @DisplayName("should_notPropagate_when_recalcProxyThrowsUnexpectedRollback")
    void should_notPropagate_when_recalcProxyThrowsUnexpectedRollback() {
        doThrow(new UnexpectedRollbackException("Transaction silently rolled back"))
                .when(ratingRecalculationService).recalculateSalonRating(salonId);

        assertThatNoException()
                .as("the interceptor's own commit failure is raised across the proxy INSIDE the "
                        + "listener's try — that is the whole point of moving the boundary")
                .isThrownBy(() -> listener.onSalonStaffChanged(new SalonStaffChangedEvent(salonId)));

        assertThat(listAppender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("UnexpectedRollbackException"));
    }

    /**
     * Finding 6 — the two evictions must not share one {@code Runnable}.
     *
     * <p>Spring swallows throwables raised from {@code afterCompletion}, which is what stops a
     * CacheManager fault from failing the committed staff change — but a throw from the FIRST call
     * in a shared lambda also strands every later call in it. With one combined {@code Runnable}, a
     * {@code salon-detail} fault left {@code reviews-by-salon} serving pre-recalc pages for the
     * full TTL beside an already-dropped detail entry.
     *
     * <p>Non-vacuity: the review-pages cache is pre-populated with a key the prefix scan MUST
     * match, and {@code salon-detail} is stubbed to throw. Restore the shared lambda and the
     * surviving entry assertion below goes red.
     */
    @Test
    @DisplayName("should_stillEvictReviewPages_when_salonDetailEvictionThrows")
    void should_stillEvictReviewPages_when_salonDetailEvictionThrows() {
        org.springframework.cache.Cache reviewPages = new CaffeineCache(
                "reviews-by-salon", Caffeine.newBuilder().build());
        String pageKey = "salon:" + salonId + ":sort:NEWEST:page:0:size:20";
        reviewPages.put(pageKey, "pre-recalc page");

        org.mockito.Mockito.when(cacheManager.getCache("salon-detail"))
                .thenThrow(new IllegalStateException("cache backend unavailable"));
        org.mockito.Mockito.when(cacheManager.getCache("reviews-by-salon")).thenReturn(reviewPages);

        listener.onSalonStaffChanged(new SalonStaffChangedEvent(salonId));
        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(),
                TransactionSynchronization.STATUS_COMMITTED);

        assertThat(reviewPages.get(pageKey))
                .as("a fault evicting salon-detail must not strand the reviews-by-salon eviction "
                        + "— the two are registered as separate synchronizations")
                .isNull();
    }

    private void when(CacheManager manager,
                      org.springframework.cache.Cache salonDetail,
                      org.springframework.cache.Cache reviewPages) {
        org.mockito.Mockito.when(manager.getCache("salon-detail")).thenReturn(salonDetail);
        org.mockito.Mockito.when(manager.getCache("reviews-by-salon")).thenReturn(reviewPages);
    }
}
