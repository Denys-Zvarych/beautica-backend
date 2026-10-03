package com.beautica.service.service;

import com.beautica.media.service.R2StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

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

    @InjectMocks
    private ServicePhotoBlobPurger purger;

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
        purger.purgeAfterCommit(List.of());
        purger.purgeAfterCommit((List<ServicePhotoBlobPurger.ServicePhotoBlob>) null);
        purger.purgeAfterCommit(ID, null);

        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("without a transaction the keys are deleted immediately")
    void should_deleteImmediately_when_noTransaction() {
        when(r2.deleteFiles(List.of(KEY_A, KEY_B))).thenReturn(Set.of());

        purger.purgeAfterCommit(List.of(blob(ID, KEY_A), blob(ID, KEY_B)));

        verify(r2).deleteFiles(List.of(KEY_A, KEY_B));
    }

    @Test
    @DisplayName("inside a transaction the delete runs only after commit")
    void should_deleteOnlyAfterCommit_when_transactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        when(r2.deleteFiles(List.of(KEY_A))).thenReturn(Set.of());

        purger.purgeAfterCommit(ID, KEY_A);

        verify(r2, never()).deleteFiles(anyCollection());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(r2).deleteFiles(List.of(KEY_A));
    }

    @Test
    @DisplayName("an R2 failure after commit never propagates")
    void should_swallow_when_r2Throws() {
        when(r2.deleteFiles(anyCollection())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> purger.purgeAfterCommit(ID, KEY_A)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a key outside services/<definitionId>/ is skipped, the matching key is still deleted")
    void should_skipMismatchedKey_when_prefixDoesNotMatchOwningDefinition() {
        UUID other = UUID.randomUUID();
        String foreign = "services/" + other + "/1-x.jpg";
        when(r2.deleteFiles(List.of(KEY_A))).thenReturn(Set.of());

        purger.purgeAfterCommit(List.of(blob(ID, foreign), blob(ID, KEY_A),
                blob(ID, "avatars/" + ID + "/1-x.jpg"), blob(ID, "services/" + ID + "x/1-x.jpg")));

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
            purger.purgeAfterCommit(ID, foreign);
        } finally {
            logger.detachAppender(appender);
        }

        verifyNoInteractions(r2);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain(foreign).doesNotContain("secret");
    }
}
