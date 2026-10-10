package com.beautica.service.service;

import com.beautica.media.service.AfterCommitBlobPurger;
import com.beautica.media.service.R2StorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicePhotoBlobPurger — unit")
class ServicePhotoBlobPurgerTest {

    @Mock
    private R2StorageService r2;

    /**
     * Stand-in for {@code blobPurgeExecutor} that QUEUES submitted tasks instead of running them, so a test
     * can prove the R2 delete is not executed on the committing thread (perf P-M1) and then drain the queue.
     */
    private final Deque<Runnable> submitted = new ArrayDeque<>();
    private final TaskExecutor queueingExecutor = submitted::add;

    private ServicePhotoBlobPurger purger() {
        return new ServicePhotoBlobPurger(
                new AfterCommitBlobPurger(r2, queueingExecutor, new SimpleMeterRegistry()));
    }

    private void drainExecutor() {
        while (!submitted.isEmpty()) {
            submitted.poll().run();
        }
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static final UUID ID = UUID.randomUUID();
    private static final String KEY_A = "services/" + ID + "/1-a.jpg";
    private static final String KEY_B = "services/" + ID + "/2-b.jpg";

    private static ServicePhotoBlobPurger.ServicePhotoBlob blob(UUID id, String key) {
        return new ServicePhotoBlobPurger.ServicePhotoBlob(id, key);
    }

    @Test
    @DisplayName("no blobs -> no R2 call")
    void should_doNothing_when_noBlobs() {
        ServicePhotoBlobPurger purger = purger();

        purger.purgeAfterCommit(List.of());
        purger.purgeAfterCommit((List<ServicePhotoBlobPurger.ServicePhotoBlob>) null);
        purger.purgeAfterCommit(ID, null);
        drainExecutor();

        assertThat(submitted).isEmpty();
        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("without a transaction the purge is SKIPPED (never dispatched) — it must run inside a transaction")
    void should_skipPurge_when_noTransaction() {
        purger().purgeAfterCommit(List.of(blob(ID, KEY_A), blob(ID, KEY_B)));
        drainExecutor();

        assertThat(submitted).isEmpty();
        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("a key with a '..' segment under the own services/<id>/ prefix is skipped; the clean key is deleted")
    void should_skipKey_when_keyContainsTraversal() {
        TransactionSynchronizationManager.initSynchronization();
        String traversal = "services/" + ID + "/../../avatars/" + UUID.randomUUID() + "/a.jpg";
        when(r2.deleteFiles(List.of(KEY_A))).thenReturn(Set.of());

        purger().purgeAfterCommit(List.of(blob(ID, traversal), blob(ID, KEY_A)));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        drainExecutor();

        verify(r2).deleteFiles(List.of(KEY_A));
    }

    @Test
    @DisplayName("inside a transaction the delete runs only after commit, and never on the committing thread")
    void should_deleteOnlyAfterCommit_offCommittingThread_when_transactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        when(r2.deleteFiles(List.of(KEY_A))).thenReturn(Set.of());

        purger().purgeAfterCommit(ID, KEY_A);
        verify(r2, never()).deleteFiles(anyCollection());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(r2, never()).deleteFiles(anyCollection());
        assertThat(submitted).hasSize(1);
        drainExecutor();
        verify(r2).deleteFiles(List.of(KEY_A));
    }

    @Test
    @DisplayName("a rolled-back transaction purges nothing")
    void should_purgeNothing_when_transactionRolledBack() {
        TransactionSynchronizationManager.initSynchronization();

        purger().purgeAfterCommit(ID, KEY_A);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        drainExecutor();

        assertThat(submitted).isEmpty();
        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("an R2 failure after commit never propagates")
    void should_swallow_when_r2Throws() {
        when(r2.deleteFiles(anyCollection())).thenThrow(new IllegalStateException("boom"));

        ServicePhotoBlobPurger purger = purger();
        TransactionSynchronizationManager.initSynchronization();

        assertThatCode(() -> {
            purger.purgeAfterCommit(ID, KEY_A);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            drainExecutor();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a key outside services/<definitionId>/ is skipped, the matching key is still deleted")
    void should_skipMismatchedKey_when_prefixDoesNotMatchOwningDefinition() {
        UUID other = UUID.randomUUID();
        String foreign = "services/" + other + "/1-x.jpg";
        when(r2.deleteFiles(List.of(KEY_A))).thenReturn(Set.of());
        TransactionSynchronizationManager.initSynchronization();

        purger().purgeAfterCommit(List.of(blob(ID, foreign), blob(ID, KEY_A),
                blob(ID, "avatars/" + ID + "/1-x.jpg"), blob(ID, "services/" + ID + "x/1-x.jpg")));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        drainExecutor();

        verify(r2).deleteFiles(List.of(KEY_A));
    }

    @Test
    @DisplayName("when every key mismatches no R2 call is made and the WARN omits the key")
    void should_neverCallR2_andNotLogKey_when_allKeysMismatch() {
        Logger logger = (Logger) LoggerFactory.getLogger(ServicePhotoBlobPurger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String foreign = "services/" + UUID.randomUUID() + "/1-secret.jpg";
        try {
            purger().purgeAfterCommit(ID, foreign);
            drainExecutor();
        } finally {
            logger.detachAppender(appender);
        }

        verifyNoInteractions(r2);
        assertThat(submitted).isEmpty();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain(foreign).doesNotContain("secret");
    }
}
