package com.beautica.user;

import com.beautica.media.entity.MediaFile;
import com.beautica.media.service.MediaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Closes the unit-pin gap the phase-301 build-verifier flagged: {@code
 * ClientAccountDeletionServiceTest}'s R2-purge test carries a comment claiming "the actual
 * after-commit TransactionSynchronization mechanics now live inside AccountBlobPurgeRegistrar
 * itself" — implying its own after-commit mechanics are pinned there. They were not, until this
 * class. No real Spring transaction is bootstrapped here (a pure Mockito unit test): the {@link
 * TransactionSynchronizationManager} static registry is driven directly, exactly the way this
 * class's own production code drives it, so the after-commit-only contract is provable without a
 * {@code @SpringBootTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountBlobPurgeRegistrar.registerAfterCommit — unit")
class AccountBlobPurgeRegistrarTest {

    @Mock
    private MediaService mediaService;

    private AccountBlobPurgeRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new AccountBlobPurgeRegistrar(mediaService);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization(); // defensive — never leak state
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("no synchronization active — a defensive no-op, MediaService is never touched")
    void should_doNothing_when_noSynchronizationActive() {
        UUID userId = UUID.randomUUID();

        registrar.registerAfterCommit(userId, "avatars/x", List.of());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("the purge does NOT run before commit — registering alone must not invoke "
            + "MediaService")
    void should_notPurge_beforeCommitFires() {
        TransactionSynchronizationManager.initSynchronization();
        UUID userId = UUID.randomUUID();

        registrar.registerAfterCommit(userId, "avatars/x", List.of());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("afterCommit fires the purge exactly once, with the pre-read avatar key and media "
            + "rows passed through verbatim")
    void should_purge_when_afterCommitFires() {
        TransactionSynchronizationManager.initSynchronization();
        UUID userId = UUID.randomUUID();
        MediaFile portfolioRow = MediaFile.builder().id(UUID.randomUUID()).build();
        List<MediaFile> rows = List.of(portfolioRow);

        registrar.registerAfterCommit(userId, "avatars/x", rows);
        fireAfterCommit();

        verify(mediaService, times(1)).purgeUserBlobsAfterCommit(userId, "avatars/x", rows);
    }

    @Test
    @DisplayName("a rollback (afterCompletion with STATUS_ROLLED_BACK, no afterCommit call) never "
            + "purges — R2 objects must not be swept for a transaction that never committed")
    void should_notPurge_whenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        UUID userId = UUID.randomUUID();

        registrar.registerAfterCommit(userId, "avatars/x", List.of());
        fireAfterCompletionOnly(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("MediaService throwing inside the after-commit callback is swallowed — a purge "
            + "failure must never surface as an unhandled exception from a completed transaction")
    void should_swallowException_when_purgeFailsAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        UUID userId = UUID.randomUUID();
        doThrow(new RuntimeException("R2 unreachable"))
                .when(mediaService).purgeUserBlobsAfterCommit(any(), any(), any());

        registrar.registerAfterCommit(userId, null, List.of());

        assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
        verify(mediaService).purgeUserBlobsAfterCommit(userId, null, List.of());
    }

    @Test
    @DisplayName("a null avatarR2Key and an empty media-rows list are passed through as-is, never "
            + "substituted or defaulted")
    void should_passThroughNullAvatarKeyAndEmptyRows() {
        TransactionSynchronizationManager.initSynchronization();
        UUID userId = UUID.randomUUID();

        registrar.registerAfterCommit(userId, null, List.of());
        fireAfterCommit();

        verify(mediaService, times(1)).purgeUserBlobsAfterCommit(userId, null, List.of());
    }

    private void fireAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    private void fireAfterCompletionOnly(int status) {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(status);
        }
    }
}
