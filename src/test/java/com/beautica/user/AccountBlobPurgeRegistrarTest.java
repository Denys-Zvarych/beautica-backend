package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.media.entity.EntityType;
import com.beautica.media.repository.UploaderMediaKey;
import com.beautica.media.service.AccountBlobPointers;
import com.beautica.media.service.MediaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

        registrar.registerAfterCommit(List.of(pointers(userId, "avatars/x")));

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("no synchronization active — the skip is WARN-logged with counts only (no keys)")
    void should_warnWithCountsOnly_when_noSynchronizationActive() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        String mediaKey = "portfolio/salons/" + salonId + "/m.jpg";
        AccountBlobPointers account = new AccountBlobPointers(userId, "avatars/" + userId + "/a.jpg", null,
                List.of(new UploaderMediaKey(userId, mediaKey, EntityType.SALON, salonId)));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(AccountBlobPurgeRegistrar.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            registrar.registerAfterCommit(List.of(account));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).contains("accounts=1").contains("mediaKeyCount=1")
                    .doesNotContain(mediaKey).doesNotContain("avatars/");
        });
        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("the purge does NOT run before commit — registering alone must not invoke MediaService")
    void should_notPurge_beforeCommitFires() {
        TransactionSynchronizationManager.initSynchronization();
        UUID userId = UUID.randomUUID();

        registrar.registerAfterCommit(List.of(pointers(userId, "avatars/x")));

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("afterCommit fires the purge exactly once, with the pre-read pointers passed through verbatim")
    void should_purge_when_afterCommitFires() {
        TransactionSynchronizationManager.initSynchronization();
        AccountBlobPointers account = pointers(UUID.randomUUID(), "avatars/x");

        registrar.registerAfterCommit(List.of(account));
        fireAfterCommit();

        verify(mediaService, times(1)).purgeUserBlobsAfterCommit(List.of(account));
    }

    @Test
    @DisplayName("P-M1: a batch of N accounts registers ONE synchronization and ONE purge call")
    void should_registerSingleSynchronization_when_batchOfAccounts() {
        TransactionSynchronizationManager.initSynchronization();
        List<AccountBlobPointers> accounts = List.of(
                pointers(UUID.randomUUID(), "avatars/a"), pointers(UUID.randomUUID(), "avatars/b"),
                pointers(UUID.randomUUID(), null));

        registrar.registerAfterCommit(accounts);
        fireAfterCommit();

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        verify(mediaService, times(1)).purgeUserBlobsAfterCommit(accounts);
    }

    @Test
    @DisplayName("an empty account list registers nothing")
    void should_registerNothing_when_accountListEmpty() {
        TransactionSynchronizationManager.initSynchronization();

        registrar.registerAfterCommit(List.of());

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    @DisplayName("P-L3: the User/UploaderMediaKey overload captures only scalar pointers (raw, unresolved) — no entity")
    void should_captureScalarPointers_when_registeredFromUserAndRows() {
        TransactionSynchronizationManager.initSynchronization();
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User user = new User("staff@beautica.test", "hash", Role.SALON_ADMIN, "A", "B", null, salonId);
        ReflectionTestUtils.setField(user, "id", userId);
        user.setAvatarR2Key("avatars/" + userId + "/a.jpg");
        user.setAvatarUrl("https://cdn/avatars/" + userId + "/a.jpg");
        UploaderMediaKey row = new UploaderMediaKey(userId, "portfolio/salons/" + salonId + "/m.jpg",
                EntityType.SALON, salonId);

        registrar.registerAfterCommit(user, List.of(row));
        fireAfterCommit();

        verify(mediaService).purgeUserBlobsAfterCommit(List.of(new AccountBlobPointers(userId,
                "avatars/" + userId + "/a.jpg", "https://cdn/avatars/" + userId + "/a.jpg",
                List.of(new UploaderMediaKey(userId, "portfolio/salons/" + salonId + "/m.jpg",
                        EntityType.SALON, salonId)))));
    }

    @Test
    @DisplayName("a rollback (afterCompletion with STATUS_ROLLED_BACK, no afterCommit call) never purges")
    void should_notPurge_whenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        registrar.registerAfterCommit(List.of(pointers(UUID.randomUUID(), "avatars/x")));
        fireAfterCompletionOnly(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("MediaService throwing inside the after-commit callback is swallowed")
    void should_swallowException_when_purgeFailsAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AccountBlobPointers account = pointers(UUID.randomUUID(), null);
        doThrow(new RuntimeException("R2 unreachable")).when(mediaService).purgeUserBlobsAfterCommit(any());

        registrar.registerAfterCommit(List.of(account));

        assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
        verify(mediaService).purgeUserBlobsAfterCommit(List.of(account));
    }

    private static AccountBlobPointers pointers(UUID userId, String avatarKey) {
        return new AccountBlobPointers(userId, avatarKey, null, List.of());
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
