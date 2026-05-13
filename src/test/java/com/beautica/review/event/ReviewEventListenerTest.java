package com.beautica.review.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.review.repository.ReviewRepository;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;

@DisplayName("ReviewEventListener — unit")
@ExtendWith(MockitoExtension.class)
class ReviewEventListenerTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private ReviewEventListener reviewEventListener;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger listenerLogger;

    @BeforeEach
    void setUp() {
        // onReviewCreated calls TransactionSynchronizationManager.registerSynchronization(),
        // which requires an active synchronization context. Initialize it here to mirror
        // the REQUIRES_NEW transactional context the method runs in during production.
        TransactionSynchronizationManager.initSynchronization();

        listenerLogger = (Logger) LoggerFactory.getLogger(ReviewEventListener.class);
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

    // ── onReviewCreated — rating recalculation failure ─────────────────────────

    @Test
    @DisplayName("should_logError_when_recalculateMasterRatingThrows")
    void should_logError_when_recalculateMasterRatingThrows() {
        // Arrange
        UUID masterId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId);

        doThrow(new RuntimeException("simulated DB timeout"))
                .when(reviewRepository).recalculateMasterRating(masterId);
        // cacheManager.getCache() is called inside afterCommit(), which only runs on real TX commit.
        // It is not invoked in this unit test, so no stub is needed.

        // Act
        assertThatNoException()
                .as("exception from recalculateMasterRating must not propagate out of onReviewCreated")
                .isThrownBy(() -> reviewEventListener.onReviewCreated(event));

        // Assert
        assertThat(listAppender.list)
                .as("an ERROR log entry must be emitted when recalculateMasterRating throws")
                .anyMatch(e ->
                        e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains(masterId.toString())
                        && e.getFormattedMessage().contains("RuntimeException")
                        && !e.getFormattedMessage().contains("simulated DB timeout"));
    }

    @Test
    @DisplayName("should_notLogError_when_recalculateMasterRatingSucceeds")
    void should_notLogError_when_recalculateMasterRatingSucceeds() {
        // Arrange
        UUID masterId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId);
        // No stub for reviewRepository.recalculateMasterRating — default Mockito void stub is fine.
        // No stub for cacheManager — afterCommit() only fires on real TX commit.

        // Act + Assert
        assertThatNoException().isThrownBy(() -> reviewEventListener.onReviewCreated(event));

        assertThat(listAppender.list)
                .as("no ERROR log must be emitted on success path")
                .noneMatch(e -> e.getLevel() == Level.ERROR);
    }
}
