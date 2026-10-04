package com.beautica.media.service;

import com.beautica.TestConstants;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.common.cache.MasterProfileCacheEvictor;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.master.repository.MasterCacheKeys;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaFileKey;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.repository.UploaderMediaKey;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
// SimpleKey import removed — cache keys are now plain Strings (portfolioCacheKey contract)
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MediaService}. Pure Mockito — no Spring context.
 *
 * <p>Magic-byte fixtures are crafted with exact 12-byte payloads via {@link MockMultipartFile};
 * SVG and other unsupported bytes are rejected with HTTP 400.
 *
 * <p>SEC-1 (uploader ownership) and SEC-2 (R2-before-DB delete ordering) are both covered
 * explicitly — see {@code should_deleteR2Blob_when_mediaRowIsDeleted} and
 * {@code should_purgeR2Avatars_when_userIsDeletedBeforeCascade}.
 *
 * <p><b>TransactionTemplate mocking.</b> The Perf MEDIUM #1 / #2 refactor moved every DB
 * mutation behind a {@link TransactionTemplate}; this fixture stubs both the read and the
 * write template to invoke their callback synchronously, so every existing test continues
 * to observe the same {@code userRepo} / {@code mediaRepo} interactions without
 * rewriting assertions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MediaService — unit")
class MediaServiceTest {

    @Mock private R2StorageService r2;
    @Mock private MediaRepository mediaRepo;
    @Mock private UserRepository userRepo;
    @Mock private SalonRepository salonRepo;
    @Mock private MasterRepository masterRepo;
    @Mock private TransactionTemplate txRead;
    @Mock private TransactionTemplate txWrite;
    @Mock private CacheManager cacheManager;
    @Mock private Cache portfolioCache;
    @Mock private com.beautica.service.repository.ServiceRepository serviceRepo;
    @Mock private com.beautica.service.service.ServiceCatalogService serviceCatalogService;
    @Mock private com.beautica.service.service.ServicePhotoBlobPurger servicePhotoBlobPurger;
    @Mock private com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;
    @Mock private com.beautica.common.cache.MasterProfileCacheEvictor masterProfileCacheEvictor;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC);

    private MediaService service;

    // ── Logback ListAppender wiring ───────────────────────────────────────────

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachListAppender() {
        Logger mediaLogger = (Logger) LoggerFactory.getLogger(MediaService.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        mediaLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        Logger mediaLogger = (Logger) LoggerFactory.getLogger(MediaService.class);
        mediaLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @BeforeEach
    void setUp() {
        // Both templates simply invoke their callback inline — the real propagation /
        // read-only flags are exercised by integration tests, not this unit suite.
        lenient().when(txRead.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        // txWrite simulates a REAL transaction boundary: synchronization is active inside the callback and the
        // registered afterCommit hooks fire on success (never on a throw) — AfterCommitBlobPurger SKIPS a purge
        // registered with no active synchronization, exactly as in production (item 7).
        lenient().when(txWrite.execute(any())).thenAnswer(inv -> inSimulatedTransaction(inv.getArgument(0)));
        // uploadAvatar's 404 read-gate is an existence probe (item 5); the unknown-user case overrides this.
        lenient().when(userRepo.existsById(any(UUID.class))).thenReturn(true);
        // Phase 7.7 — portfolio cache eviction is a no-op in unit tests; the IT suite
        // exercises the real Caffeine cache. lenient() because not every existing test
        // hits a portfolio write path.
        lenient().when(r2.isEnabled()).thenReturn(true);
        lenient().when(cacheManager.getCache("portfolio")).thenReturn(portfolioCache);
        lenient().when(portfolioCache.evictIfPresent(any())).thenReturn(true);
        service = new MediaService(r2, mediaRepo, userRepo, salonRepo, masterRepo, fixedClock, txRead, txWrite, cacheManager,
                serviceRepo, serviceCatalogService, servicePhotoBlobPurger,
                new AfterCommitBlobPurger(r2, new SyncTaskExecutor(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                userProfileCacheEvictor, masterProfileCacheEvictor);
    }

    private static final UUID MASTER_ID_FOR_PURGE = UUID.randomUUID();

    /** The storage-enabled probe ({@code isEnabled}) is allowed; any blob write/delete is not. */
    private void verifyNoStorageWrites() {
        verify(r2, never()).uploadFile(any(), any(), anyLong(), any());
        verify(r2, never()).deleteFile(any());
        verify(r2, never()).deleteFiles(any());
        verify(r2, never()).buildPublicUrl(any());
    }

    @Test
    @DisplayName("uploadAvatar 404s on an unknown user via the existence probe — no entity load, no R2 upload")
    void should_throwNotFound_when_uploadAvatarForUnknownUser() {
        UUID userId = UUID.randomUUID();
        when(userRepo.existsById(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.uploadAvatar(userId, jpegFile()))
                .isInstanceOf(NotFoundException.class);

        verify(userRepo, never()).findById(any(UUID.class));
        verifyNoStorageWrites();
    }

    // ------------------------------------------------- storage disabled (phase 341)

    @Test
    @DisplayName("uploadAvatar throws 503 and never touches the user row or R2 when storage is disabled")
    void uploadAvatar_whenStorageDisabled_throws503_andDoesNotTouchUserRow() {
        when(r2.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.uploadAvatar(UUID.randomUUID(), jpegFile()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage()).isEqualTo("Media storage is not configured");
                });

        verifyNoInteractions(userRepo, mediaRepo);
        verify(r2, never()).deleteFile(any());
        verify(r2, never()).uploadFile(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("uploadPortfolioPhoto throws 503 and never touches repositories or R2 when storage is disabled")
    void uploadPortfolioPhoto_whenStorageDisabled_throws503_andDoesNotTouchRows() {
        when(r2.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.uploadPortfolioPhoto(UUID.randomUUID(), Role.SALON_OWNER, jpegFile()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verifyNoInteractions(userRepo, mediaRepo, salonRepo, masterRepo);
        verify(r2, never()).deleteFile(any());
        verify(r2, never()).uploadFile(any(), any(), anyLong(), any());
    }

    // ---------------------------------------------------------------- magic bytes

    @Test
    @DisplayName("detects JPEG when file starts with FF D8 FF")
    void should_detectJpeg_when_fileStartsWithFfd8ff() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatar.jpg");

        service.uploadAvatar(userId, jpegFile());

        verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/jpeg"));
    }

    @Test
    @DisplayName("detects PNG when file starts with 89 50 4E 47")
    void should_detectPng_when_fileStartsWithPngSignature() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatar.png");

        service.uploadAvatar(userId, pngFile());

        verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/png"));
    }

    @Test
    @DisplayName("detects WebP when file starts with RIFF + WEBP")
    void should_detectWebp_when_fileStartsWithRiffAndWebp() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatar.webp");

        service.uploadAvatar(userId, webpFile());

        verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/webp"));
    }

    @Test
    @DisplayName("opens the upload stream exactly once and hands R2 the full payload from byte 0")
    void should_openStreamOnce_andUploadFullPayload_when_avatarUploaded() throws Exception {
        UUID userId = UUID.randomUUID();
        User u247 = newUser(userId);
        when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(u247));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatar.jpg");
        byte[] payload = new byte[64];
        payload[0] = (byte) 0xFF;
        payload[1] = (byte) 0xD8;
        payload[2] = (byte) 0xFF;
        payload[40] = 7;
        MultipartFile file = org.mockito.Mockito.spy(
                new MockMultipartFile("file", "a.jpg", "image/jpeg", payload));
        java.util.concurrent.atomic.AtomicReference<byte[]> uploaded = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            uploaded.set(((java.io.InputStream) inv.getArgument(1)).readAllBytes());
            return null;
        }).when(r2).uploadFile(anyString(), any(), anyLong(), eq("image/jpeg"));

        service.uploadAvatar(userId, file);

        verify(file, times(1)).getInputStream();
        assertThat(uploaded.get()).isEqualTo(payload);
    }

    @Test
    @DisplayName("opens the upload stream exactly once on the portfolio path too")
    void should_openStreamOnce_when_portfolioUploadRejectedAfterSniff() throws Exception {
        MultipartFile file = org.mockito.Mockito.spy(
                new MockMultipartFile("file", "a.svg", "image/svg+xml", "<svg xmlns='x'/>".getBytes()));

        assertThatThrownBy(() -> service.uploadPortfolioPhoto(UUID.randomUUID(), Role.SALON_OWNER, file))
                .isInstanceOf(BusinessException.class);

        verify(file, times(1)).getInputStream();
        verify(r2, never()).uploadFile(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("rejects unknown magic bytes (SVG content)")
    void should_throw400_when_fileMagicBytesAreUnrecognized() {
        MultipartFile svg = new MockMultipartFile(
                "file", "icon.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.uploadAvatar(UUID.randomUUID(), svg))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported image format");
        verifyNoStorageWrites();
    }

    @Test
    @DisplayName("rejects spoofed Content-Type (JPEG header + SVG content)")
    void should_throw400_when_clientSendsJpegMimeButSvgContent() {
        MultipartFile spoofed = new MockMultipartFile(
                "file", "evil.jpg", "image/jpeg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.uploadAvatar(UUID.randomUUID(), spoofed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported image format");
        verifyNoStorageWrites();
    }

    @Test
    @DisplayName("rejects files larger than 5 MB")
    void should_throw400_when_fileSizeExceeds5MB() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF;
        MultipartFile huge = new MockMultipartFile("file", "huge.jpg", "image/jpeg", bytes) {
            @Override public long getSize() { return 5L * 1024L * 1024L + 1L; }
            @Override public boolean isEmpty() { return false; }
        };

        assertThatThrownBy(() -> service.uploadAvatar(UUID.randomUUID(), huge))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5 MB");
        verifyNoStorageWrites();
    }

    @Test
    @DisplayName("rejects empty files")
    void should_throw400_when_fileIsEmpty() {
        MultipartFile empty = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.uploadAvatar(UUID.randomUUID(), empty))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must not be empty");
        verifyNoStorageWrites();
    }

    // -------------------------------------------------------------------- avatar

    @Test
    @DisplayName("uploads avatar with valid JPEG and updates user fields")
    void should_uploadAvatar_when_validJpegFile() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatars/u/file.jpg");

        AvatarResponse response = service.uploadAvatar(userId, jpegFile());

        // Detected MIME — not the client header — is passed downstream.
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(keyCaptor.capture(), any(), anyLong(), eq("image/jpeg"));
        assertThat(keyCaptor.getValue())
                .startsWith("avatars/" + userId + "/")
                .endsWith(".jpg");
        assertThat(user.getAvatarR2Key()).isEqualTo(keyCaptor.getValue());
        assertThat(user.getAvatarUrl()).isEqualTo("https://r2/avatars/u/file.jpg");
        assertThat(response.avatarUrl()).isEqualTo("https://r2/avatars/u/file.jpg");
    }

    @Test
    @DisplayName("uploads the NEW avatar first and deletes the superseded one only after the DB write")
    void should_uploadNewFirstThenDeleteOld_when_userAlreadyHasAvatar() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarR2Key("avatars/" + userId + "/old.jpg");
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/new.jpg");

        service.uploadAvatar(userId, jpegFile());

        // The locked write step (findByIdForUpdate on the MANAGED user — no save(), dirty checking flushes it)
        // runs after the upload; the superseded blob is purged only after that write commits.
        InOrder inOrder = inOrder(r2, userRepo);
        inOrder.verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/jpeg"));
        inOrder.verify(userRepo).findByIdForUpdate(userId);
        inOrder.verify(r2).deleteFiles(List.of("avatars/" + userId + "/old.jpg"));
        assertThat(user.getAvatarR2Key()).startsWith("avatars/" + userId + "/").isNotEqualTo("avatars/" + userId + "/old.jpg");
        verify(userRepo, never()).save(any(User.class));
    }

    @Test
    @DisplayName("never deletes an avatar key outside the user's own avatars/<id>/ prefix")
    void should_notDeleteForeignKey_when_storedKeyIsOutsideOwnPrefix() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarR2Key("avatars/" + UUID.randomUUID() + "/other.jpg");
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/new.jpg");

        service.uploadAvatar(userId, jpegFile());

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyCollection());
    }

    @Test
    @DisplayName("replace of a legacy avatar (url only) deletes the URL-derived key")
    void should_deleteUrlDerivedKey_when_legacyAvatarReplaced() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarUrl("https://cdn/avatars/" + userId + "/legacy.jpg");
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.extractKeyFromPublicUrl(user.getAvatarUrl()))
                .thenReturn(Optional.of("avatars/" + userId + "/legacy.jpg"));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/new.jpg");

        service.uploadAvatar(userId, jpegFile());

        verify(r2).deleteFiles(List.of("avatars/" + userId + "/legacy.jpg"));
    }

    // ---- Phase 344: GET /users/me carries avatarUrl, so every avatar write evicts user-profile

    @Test
    @DisplayName("uploadAvatar evicts the caller's user-profile cache entry (Phase 344)")
    void should_evictUserProfileCache_when_avatarUploaded() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatars/u/file.jpg");

        service.uploadAvatar(userId, jpegFile());

        verify(userProfileCacheEvictor).evictAfterCommit(userId);
    }

    @Test
    @DisplayName("deleteAvatar evicts the caller's user-profile cache entry when an avatar was cleared (Phase 344)")
    void should_evictUserProfileCache_when_avatarDeleted() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarR2Key("avatars/" + userId + "/a.jpg");
        user.setAvatarUrl("https://cdn/avatars/" + userId + "/a.jpg");
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        service.deleteAvatar(userId);

        verify(userProfileCacheEvictor).evictAfterCommit(userId);
    }

    @Test
    @DisplayName("uploadAvatar by a master evicts the master-profile caches under the row's masterId and slug (344 c1)")
    void should_evictMasterProfileCaches_when_masterUploadsAvatar() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        lenient().when(userRepo.findByIdForUpdate(userId))
                .thenReturn(Optional.of(newUser(userId, Role.INDEPENDENT_MASTER)));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatars/u/file.jpg");
        when(masterRepo.findCacheKeysByUserId(userId))
                .thenReturn(Optional.of(new MasterCacheKeys(masterId, userId, "olena-k-ab12")));

        service.uploadAvatar(userId, jpegFile());

        verify(masterProfileCacheEvictor)
                .evictAfterCommit(userId, masterId, "olena-k-ab12", Role.INDEPENDENT_MASTER);
    }

    @Test
    @DisplayName("deleteAvatar by a master evicts the master-profile caches under the row's masterId and slug (344 c1)")
    void should_evictMasterProfileCaches_when_masterDeletesAvatar() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = newUser(userId, Role.SALON_MASTER);
        user.setAvatarR2Key("avatars/" + userId + "/a.jpg");
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(masterRepo.findCacheKeysByUserId(userId))
                .thenReturn(Optional.of(new MasterCacheKeys(masterId, userId, "olena-k-ab12")));

        service.deleteAvatar(userId);

        verify(masterProfileCacheEvictor).evictAfterCommit(userId, masterId, "olena-k-ab12", Role.SALON_MASTER);
    }

    @Test
    @DisplayName("uploadAvatar by a user with no masters row evicts only user-profile (344 c1)")
    void should_notEvictMasterProfileCaches_when_userHasNoMasterRow() {
        UUID userId = UUID.randomUUID();
        lenient().when(userRepo.findByIdForUpdate(userId))
                .thenReturn(Optional.of(newUser(userId, Role.SALON_OWNER)));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatars/u/file.jpg");
        when(masterRepo.findCacheKeysByUserId(userId)).thenReturn(Optional.empty());

        service.uploadAvatar(userId, jpegFile());

        verify(userProfileCacheEvictor).evictAfterCommit(userId);
        verifyNoInteractions(masterProfileCacheEvictor);
    }

    @Test
    @DisplayName("deleteAvatar with no avatar set resolves no master keys and evicts no master cache (344 c1)")
    void should_notEvictMasterProfileCaches_when_noAvatarToDelete() {
        UUID userId = UUID.randomUUID();
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(newUser(userId)));

        service.deleteAvatar(userId);

        verify(masterRepo, never()).findCacheKeysByUserId(any());
        verifyNoInteractions(masterProfileCacheEvictor);
    }

    @Test
    @DisplayName("a throwing blob-purge after-commit callback cannot skip the cache evictions — they are registered first (344 c1)")
    void should_stillEvictCaches_when_blobPurgeCallbackThrows() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        User user = newUser(userId, Role.INDEPENDENT_MASTER);
        user.setAvatarR2Key("avatars/" + userId + "/old.jpg");
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(masterRepo.findCacheKeysByUserId(userId))
                .thenReturn(Optional.of(new MasterCacheKeys(masterId, userId, "slug-ab12")));
        Cache profileCache = mock(Cache.class);
        Cache detailCache = mock(Cache.class);
        // lenient: the evictors also look up caches this test does not observe (null → skipped).
        lenient().when(cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE)).thenReturn(profileCache);
        lenient().when(cacheManager.getCache(MasterProfileCacheEvictor.MASTER_DETAIL_CACHE)).thenReturn(detailCache);
        AfterCommitBlobPurger throwingPurger = mock(AfterCommitBlobPurger.class);
        doAnswer(inv -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalStateException("simulated purge-dispatch failure");
                }
            });
            return null;
        }).when(throwingPurger).purgeAfterCommit(anyCollection(), anyString());
        MediaService withThrowingPurge = new MediaService(r2, mediaRepo, userRepo, salonRepo, masterRepo, fixedClock,
                txRead, txWrite, cacheManager, serviceRepo, serviceCatalogService, servicePhotoBlobPurger,
                throwingPurger, new UserProfileCacheEvictor(cacheManager), new MasterProfileCacheEvictor(cacheManager));

        assertThatThrownBy(() -> withThrowingPurge.deleteAvatar(userId))
                .isInstanceOf(IllegalStateException.class);

        verify(profileCache).evict(userId);
        verify(detailCache).evict(masterId);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"CLIENT", "SALON_ADMIN"})
    @DisplayName("avatar write by a role that can never own a masters row skips the master-key lookup (344 c2)")
    void should_skipMasterKeyLookup_when_roleCannotOwnMasterRow(Role role) {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId, role);
        user.setAvatarR2Key("avatars/" + userId + "/a.jpg");
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatars/u/file.jpg");

        service.uploadAvatar(userId, jpegFile());
        service.deleteAvatar(userId);

        verify(userProfileCacheEvictor, times(2)).evictAfterCommit(userId);
        verify(masterRepo, never()).findCacheKeysByUserId(any());
        verifyNoInteractions(masterProfileCacheEvictor);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"INDEPENDENT_MASTER", "SALON_MASTER", "SALON_OWNER"})
    @DisplayName("avatar write by a role that can own a masters row (owner-as-master included) resolves the master keys (344 c2)")
    void should_lookUpMasterKeys_when_roleCanOwnMasterRow(Role role) {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(newUser(userId, role)));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatars/u/file.jpg");
        when(masterRepo.findCacheKeysByUserId(userId))
                .thenReturn(Optional.of(new MasterCacheKeys(masterId, userId, "slug-ab12")));

        service.uploadAvatar(userId, jpegFile());

        verify(masterProfileCacheEvictor).evictAfterCommit(userId, masterId, "slug-ab12", role);
    }

    @ParameterizedTest(name = "{0} clears search = {1}")
    @CsvSource({"INDEPENDENT_MASTER,true", "SALON_MASTER,false", "SALON_OWNER,false"})
    @DisplayName("avatar write clears search:masters:* only for an INDEPENDENT_MASTER, per-key evicts for all (344 c2)")
    void should_clearSearchCachesOnlyForIndependentMaster_when_masterUploadsAvatar(Role role, boolean cleared) {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(newUser(userId, role)));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatars/u/file.jpg");
        when(masterRepo.findCacheKeysByUserId(userId))
                .thenReturn(Optional.of(new MasterCacheKeys(masterId, userId, "slug-ab12")));
        Cache detailCache = mock(Cache.class);
        Cache browse = mock(Cache.class);
        Cache query = mock(Cache.class);
        // lenient: the evictors also look up caches this test does not observe (null → skipped).
        lenient().when(cacheManager.getCache(MasterProfileCacheEvictor.MASTER_DETAIL_CACHE)).thenReturn(detailCache);
        lenient().when(cacheManager.getCache(SearchCacheNames.MASTERS_BROWSE)).thenReturn(browse);
        lenient().when(cacheManager.getCache(SearchCacheNames.MASTERS_QUERY)).thenReturn(query);
        MediaService withRealEvictors = new MediaService(r2, mediaRepo, userRepo, salonRepo, masterRepo, fixedClock,
                txRead, txWrite, cacheManager, serviceRepo, serviceCatalogService, servicePhotoBlobPurger,
                new AfterCommitBlobPurger(r2, new SyncTaskExecutor(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new UserProfileCacheEvictor(cacheManager), new MasterProfileCacheEvictor(cacheManager));

        withRealEvictors.uploadAvatar(userId, jpegFile());

        verify(detailCache).evict(masterId);
        verify(browse, times(cleared ? 1 : 0)).clear();
        verify(query, times(cleared ? 1 : 0)).clear();
    }

    @Test
    @DisplayName("deleteAvatar with no avatar set is a no-op and evicts nothing (Phase 344)")
    void should_notEvictUserProfileCache_when_noAvatarToDelete() {
        UUID userId = UUID.randomUUID();
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(newUser(userId)));

        service.deleteAvatar(userId);

        verify(userProfileCacheEvictor, never()).evictAfterCommit(any());
    }

    @Test
    @DisplayName("deleteAvatar of a legacy avatar deletes the derived key and nulls both pointers")
    void should_clearBothPointersAndDeleteDerivedKey_when_legacyAvatarDeleted() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarUrl("https://cdn/avatars/" + userId + "/legacy.jpg");
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.extractKeyFromPublicUrl(user.getAvatarUrl()))
                .thenReturn(Optional.of("avatars/" + userId + "/legacy.jpg"));

        service.deleteAvatar(userId);

        verify(r2).deleteFiles(List.of("avatars/" + userId + "/legacy.jpg"));
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.getAvatarR2Key()).isNull();
    }

    @Test
    @DisplayName("deleteAvatar of a legacy avatar whose URL is foreign nulls pointers but deletes nothing")
    void should_notDeleteAnything_when_legacyUrlIsForeign() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarUrl("https://evil.example/avatars/" + userId + "/x.jpg");
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.extractKeyFromPublicUrl(anyString())).thenReturn(Optional.empty());

        service.deleteAvatar(userId);

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyCollection());
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("deleteAvatar with a url-derived key outside the user's prefix deletes nothing")
    void should_notDeleteAnything_when_derivedKeyOutsideOwnPrefix() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarUrl("https://cdn/avatars/" + UUID.randomUUID() + "/x.jpg");
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.extractKeyFromPublicUrl(anyString())).thenReturn(Optional.of("services/abc/x.jpg"));

        service.deleteAvatar(userId);

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyCollection());
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("does not call deleteFile when the user has no existing avatar")
    void should_notCallDeleteFile_when_userHasNoExistingAvatar() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/new.jpg");

        service.uploadAvatar(userId, jpegFile());

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyCollection());
    }

    @Test
    @DisplayName("clears avatar fields when deleteAvatar is called")
    void should_clearAvatarFields_when_deleteAvatarCalled() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarR2Key("avatars/" + userId + "/x.jpg");
        user.setAvatarUrl("https://r2/x.jpg");
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        service.deleteAvatar(userId);

        verify(r2).deleteFiles(List.of("avatars/" + userId + "/x.jpg"));
        assertThat(user.getAvatarR2Key()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("does nothing when deleteAvatar is called and no avatar exists")
    void should_doNothing_when_deleteAvatarCalledAndNoAvatarExists() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        service.deleteAvatar(userId);

        verifyNoStorageWrites();
        verify(userRepo, never()).save(any(User.class));
    }

    @Test
    @DisplayName("throws NotFoundException when user does not exist on deleteAvatar")
    void should_throwNotFound_when_userNotFoundOnDeleteAvatar() {
        // Arrange
        UUID userId = UUID.randomUUID();
        lenient().when(userRepo.findById(any(UUID.class))).thenReturn(Optional.empty());
        lenient().when(userRepo.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.deleteAvatar(userId))
                .isInstanceOf(NotFoundException.class);

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyCollection());
    }

    // ----------------------------------------------------------------- portfolio

    @Test
    @DisplayName("uploads portfolio photo for salon when SALON_OWNER uploads")
    void should_uploadPortfolioForSalon_when_salonOwnerUploads() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID).id(salonId).isActive(true).build();
        when(salonRepo.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(any(UUID.class)))
                .thenReturn(Optional.of(salon));
        when(userRepo.getReferenceById(actorId)).thenReturn(newUser(actorId));
        when(mediaRepo.save(any(MediaFile.class))).thenAnswer(inv -> {
            MediaFile mf = inv.getArgument(0);
            setField(mf, "id", UUID.randomUUID());
            return mf;
        });
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/p.jpg");

        MediaFileResponse response = service.uploadPortfolioPhoto(actorId, Role.SALON_OWNER, jpegFile());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(keyCaptor.capture(), any(), anyLong(), eq("image/jpeg"));
        assertThat(keyCaptor.getValue())
                .startsWith("portfolio/salons/" + salonId + "/")
                .endsWith(".jpg");
        assertThat(response.entityType()).isEqualTo(EntityType.SALON);
        assertThat(response.entityId()).isEqualTo(salonId);
        assertThat(response.mediaType()).isEqualTo(MediaType.PORTFOLIO);
    }

    @Test
    @DisplayName("uploads portfolio photo for master when INDEPENDENT_MASTER uploads")
    void should_uploadPortfolioForMaster_when_independentMasterUploads() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = Master.builder().id(masterId).build();
        when(masterRepo.findByUserId(actorId)).thenReturn(Optional.of(master));
        when(userRepo.getReferenceById(actorId)).thenReturn(newUser(actorId));
        when(mediaRepo.save(any(MediaFile.class))).thenAnswer(inv -> {
            MediaFile mf = inv.getArgument(0);
            setField(mf, "id", UUID.randomUUID());
            return mf;
        });
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/p.jpg");

        MediaFileResponse response = service.uploadPortfolioPhoto(actorId, Role.INDEPENDENT_MASTER, jpegFile());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(keyCaptor.capture(), any(), anyLong(), eq("image/jpeg"));
        assertThat(keyCaptor.getValue())
                .startsWith("portfolio/independent/" + masterId + "/")
                .endsWith(".jpg");
        assertThat(response.entityType()).isEqualTo(EntityType.MASTER);
        assertThat(response.entityId()).isEqualTo(masterId);
    }

    @Test
    @DisplayName("throws ForbiddenException when SALON_OWNER has no active salon on uploadPortfolioPhoto")
    void should_throwForbidden_when_salonOwnerHasNoActiveSalon() {
        // Arrange
        UUID actorId = UUID.randomUUID();
        when(salonRepo.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(any(UUID.class)))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.uploadPortfolioPhoto(actorId, Role.SALON_OWNER, jpegFile()))
                .isInstanceOf(ForbiddenException.class);

        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("throws ForbiddenException when INDEPENDENT_MASTER has no master profile on uploadPortfolioPhoto")
    void should_throwForbidden_when_independentMasterHasNoProfile() {
        // Symmetric empty branch to should_throwForbidden_when_salonOwnerHasNoActiveSalon:
        // resolvePortfolioTarget() looks up the master profile for an INDEPENDENT_MASTER and
        // throws ForbiddenException("No master profile found for user") when absent. Only the
        // happy path (Optional.of(master)) was previously stubbed, so this empty branch was
        // never exercised — a regression that dropped the orElseThrow would have gone unnoticed.
        UUID actorId = UUID.randomUUID();
        when(masterRepo.findByUserId(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadPortfolioPhoto(actorId, Role.INDEPENDENT_MASTER, jpegFile()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("No master profile found");

        // The R2 upload runs only AFTER the target resolves — it must never be reached.
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
        verifyNoInteractions(mediaRepo);
    }

    @Test
    @DisplayName("throws 403 when a CLIENT uploads a portfolio photo")
    void should_throwForbidden_when_clientUploadsPortfolio() {
        assertThatThrownBy(() -> service.uploadPortfolioPhoto(UUID.randomUUID(), Role.CLIENT, jpegFile()))
                .isInstanceOf(ForbiddenException.class);

        verifyNoStorageWrites();
        verifyNoInteractions(mediaRepo);
    }

    @Test
    @DisplayName("deletes portfolio photo when uploader requests deletion — row deleted, own key purged after commit")
    void should_deletePortfolioPhoto_when_uploaderRequests() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        String ownKey = "portfolio/independent/" + masterId + "/y.jpg";
        MediaFile row = portfolioRow(mediaId, actorId, EntityType.MASTER, masterId, ownKey);
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(row));

        service.deletePortfolioPhoto(actorId, mediaId);

        verify(mediaRepo).delete(row);
        verify(r2).deleteFiles(List.of(ownKey));
        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("SEC-N1: a SALON row's key under its OWN portfolio root reaches R2 — only AFTER the row delete")
    void should_deleteR2Blob_when_salonPortfolioKeyInsideOwnPrefix() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        String ownKey = "portfolio/salons/" + salonId + "/p.jpg";
        MediaFile row = portfolioRow(mediaId, actorId, EntityType.SALON, salonId, ownKey);
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(row));

        service.deletePortfolioPhoto(actorId, mediaId);

        InOrder order = inOrder(mediaRepo, r2);
        order.verify(mediaRepo).delete(row);
        order.verify(r2).deleteFiles(List.of(ownKey));
    }

    @Test
    @DisplayName("SEC-N1: a key outside the row's own portfolio root never reaches R2 — the row is still deleted")
    void should_skipR2Delete_when_portfolioKeyOutsideOwnPrefix() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        // Three corrupted shapes: another salon's object, a user avatar, and a traversal under the own root.
        List<String> foreignKeys = List.of(
                "portfolio/salons/" + UUID.randomUUID() + "/p.jpg",
                "avatars/" + UUID.randomUUID() + "/a.jpg",
                "portfolio/salons/" + salonId + "/../../avatars/x/a.jpg");
        for (String foreignKey : foreignKeys) {
            reset(r2, mediaRepo);
            MediaFile row = portfolioRow(mediaId, actorId, EntityType.SALON, salonId, foreignKey);
            when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(row));

            service.deletePortfolioPhoto(actorId, mediaId);

            verify(r2, never()).deleteFile(anyString());
            verify(r2, never()).deleteFiles(anyCollection());
            verify(mediaRepo).delete(row);
        }
    }

    @Test
    @DisplayName("M1: the row delete fails (rollback) — R2 is never touched")
    void should_notTouchR2_when_portfolioRowDeleteFails() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        MediaFile row = portfolioRow(mediaId, actorId, EntityType.SALON, salonId,
                "portfolio/salons/" + salonId + "/p.jpg");
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(row));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("forced"))
                .when(mediaRepo).delete(row);

        assertThatThrownBy(() -> service.deletePortfolioPhoto(actorId, mediaId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyCollection());
        verify(portfolioCache, never()).evictIfPresent(any());
    }

    @Test
    @DisplayName("M1: the purge is registered inside the write tx and dispatched only when it commits")
    void should_notPurgeBeforeCommit_when_portfolioPhotoDeleted() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        String ownKey = "portfolio/salons/" + salonId + "/p.jpg";
        MediaFile row = portfolioRow(mediaId, actorId, EntityType.SALON, salonId, ownKey);
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(row));
        List<Boolean> r2CalledBeforeCommit = new java.util.ArrayList<>();
        doAnswer(inv -> {
            TransactionSynchronizationManager.initSynchronization();
            try {
                Object result = ((TransactionCallback<?>) inv.getArgument(0))
                        .doInTransaction(mock(TransactionStatus.class));
                r2CalledBeforeCommit.add(!org.mockito.Mockito.mockingDetails(r2).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().startsWith("delete")).toList().isEmpty());
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit);
                return result;
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }).when(txWrite).execute(any());

        service.deletePortfolioPhoto(actorId, mediaId);

        assertThat(r2CalledBeforeCommit).containsExactly(false);
        verify(r2).deleteFiles(List.of(ownKey));
    }

    @Test
    @DisplayName("throws 403 when a non-uploader tries to delete a portfolio photo")
    void should_throwForbidden_when_nonUploaderDeletesPortfolioPhoto() {
        UUID actorId = UUID.randomUUID();
        UUID uploaderId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        MediaFile mf = MediaFile.builder()
                .id(mediaId)
                .uploader(newUser(uploaderId))
                .r2Key("k.jpg")
                .r2Url("u")
                .entityType(EntityType.MASTER)
                .entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO)
                .build();
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(mf));

        assertThatThrownBy(() -> service.deletePortfolioPhoto(actorId, mediaId))
                .isInstanceOf(ForbiddenException.class);

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyCollection());
        verify(mediaRepo, never()).deleteById(any(UUID.class));
        verify(mediaRepo, never()).delete(any(MediaFile.class));
    }

    @Test
    @DisplayName("throws 404 when the portfolio photo is not found")
    void should_throwNotFound_when_portfolioPhotoNotFound() {
        UUID mediaId = UUID.randomUUID();
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePortfolioPhoto(UUID.randomUUID(), mediaId))
                .isInstanceOf(NotFoundException.class);

        verifyNoStorageWrites();
    }

    // ---------------------------------------------- Phase 7.7 — portfolio cache eviction

    @Test
    @DisplayName("evicts the portfolio cache AFTER the write tx commits when uploadPortfolioPhoto succeeds")
    void should_evictPortfolioCache_when_uploadPortfolioPhotoSucceeds() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID).id(salonId).isActive(true).build();
        when(salonRepo.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(any(UUID.class)))
                .thenReturn(Optional.of(salon));
        when(userRepo.getReferenceById(actorId)).thenReturn(newUser(actorId));
        when(mediaRepo.save(any(MediaFile.class))).thenAnswer(inv -> {
            MediaFile mf = inv.getArgument(0);
            setField(mf, "id", UUID.randomUUID());
            return mf;
        });
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/p.jpg");

        service.uploadPortfolioPhoto(actorId, Role.SALON_OWNER, jpegFile());

        // Eviction must happen AFTER the write tx — InOrder over (txWrite, cache) proves it.
        // Cache key is a plain String: entityType.name() + '_' + entityId (portfolioCacheKey contract).
        InOrder order = inOrder(txWrite, cacheManager, portfolioCache);
        order.verify(txWrite).execute(any());
        order.verify(cacheManager).getCache("portfolio");
        order.verify(portfolioCache).evictIfPresent(EntityType.SALON.name() + "_" + salonId);
    }

    @Test
    @DisplayName("evicts the portfolio cache AFTER the write tx commits when deletePortfolioPhoto succeeds")
    void should_evictPortfolioCache_when_deletePortfolioPhotoSucceeds() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID masterEntityId = UUID.randomUUID();
        MediaFile mf = MediaFile.builder()
                .id(mediaId)
                .uploader(newUser(actorId))
                .entityType(EntityType.MASTER)
                .entityId(masterEntityId)
                .mediaType(MediaType.PORTFOLIO)
                .r2Key("portfolio/independent/" + masterEntityId + "/y.jpg")
                .r2Url("https://r2/y.jpg")
                .build();
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(mf));

        service.deletePortfolioPhoto(actorId, mediaId);

        // Eviction must happen AFTER the write tx — InOrder over (txWrite, cache) proves it.
        // Cache key is a plain String: entityType.name() + '_' + entityId (portfolioCacheKey contract).
        InOrder order = inOrder(txWrite, cacheManager, portfolioCache);
        order.verify(txWrite).execute(any());
        order.verify(cacheManager).getCache("portfolio");
        order.verify(portfolioCache).evictIfPresent(EntityType.MASTER.name() + "_" + masterEntityId);
    }

    @Test
    @DisplayName("never uses client-supplied filename when building the R2 key")
    void should_neverUseClientFilename_when_buildingR2Key() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/x.jpg");

        // Filename packed with path-traversal + double-extension attacks.
        MultipartFile attack = new MockMultipartFile(
                "file",
                "../../../etc/passwd.php.jpg",
                "image/jpeg",
                jpegBytes()
        );

        service.uploadAvatar(userId, attack);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(keyCaptor.capture(), any(), anyLong(), eq("image/jpeg"));
        String key = keyCaptor.getValue();
        assertThat(key).doesNotContain("..");
        assertThat(key).doesNotContain("passwd");
        assertThat(key).doesNotContain(".php");
        assertThat(key).startsWith("avatars/" + userId + "/").endsWith(".jpg");
    }

    // ---------------------------------------------------- QA MEDIUM #3 — getPortfolio

    @Test
    @DisplayName("returns empty list when there is no media for the entity")
    void should_returnEmptyList_when_noMediaForEntity() {
        UUID entityId = UUID.randomUUID();
        when(mediaRepo.findByEntityTypeAndEntityId(EntityType.SALON, entityId))
                .thenReturn(List.of());

        List<MediaFileResponse> result = service.getPortfolio(EntityType.SALON, entityId);

        assertThat(result).isEmpty();
        verifyNoMoreInteractions(mediaRepo);
        // LOW-2: Non-paginated overload is intentionally uncached (PERF-M3 security fix) to prevent
        // List<MediaFileResponse> vs Page<MediaFileResponse> cache-slot collision.
        verify(cacheManager, never()).getCache(anyString());
    }

    @Test
    @DisplayName("returns every media item when the entity has a portfolio")
    void should_returnAllMedia_when_entityHasPortfolioItems() {
        UUID entityId = UUID.randomUUID();
        // NOTE: uploader is intentionally NOT stubbed — if MediaFileResponse.from ever
        // started touching getUploader() this test would NPE, proving the LAZY association
        // remains untouched.
        MediaFile mockA = mock(MediaFile.class);
        MediaFile mockB = mock(MediaFile.class);
        MediaFile mockC = mock(MediaFile.class);
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();
        when(mockA.getId()).thenReturn(idA);
        when(mockB.getId()).thenReturn(idB);
        when(mockC.getId()).thenReturn(idC);
        // The remaining MediaFile getters are stubbed only because MediaFileResponse.from
        // reads them; LAZY uploader stays untouched and would NPE if the mapper called it.
        when(mockA.getEntityType()).thenReturn(EntityType.MASTER);
        when(mockB.getEntityType()).thenReturn(EntityType.MASTER);
        when(mockC.getEntityType()).thenReturn(EntityType.MASTER);
        when(mockA.getEntityId()).thenReturn(entityId);
        when(mockB.getEntityId()).thenReturn(entityId);
        when(mockC.getEntityId()).thenReturn(entityId);
        when(mockA.getMediaType()).thenReturn(MediaType.PORTFOLIO);
        when(mockB.getMediaType()).thenReturn(MediaType.PORTFOLIO);
        when(mockC.getMediaType()).thenReturn(MediaType.PORTFOLIO);
        when(mockA.getR2Url()).thenReturn("https://r2/a.jpg");
        when(mockB.getR2Url()).thenReturn("https://r2/b.jpg");
        when(mockC.getR2Url()).thenReturn("https://r2/c.jpg");
        when(mediaRepo.findByEntityTypeAndEntityId(EntityType.MASTER, entityId))
                .thenReturn(List.of(mockA, mockB, mockC));

        List<MediaFileResponse> result = service.getPortfolio(EntityType.MASTER, entityId);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(MediaFileResponse::id).containsExactly(idA, idB, idC);
        verify(mockA, never()).getUploader();
        verify(mockB, never()).getUploader();
        verify(mockC, never()).getUploader();
        // LOW-2: Non-paginated overload is intentionally uncached (PERF-M3 security fix) to prevent
        // List<MediaFileResponse> vs Page<MediaFileResponse> cache-slot collision.
        verify(cacheManager, never()).getCache(anyString());
    }

    // ---------------------------------------------------- QA MEDIUM #4 — upload rollback

    @Test
    @DisplayName("leaves DB unchanged and the OLD blob intact when the R2 upload fails")
    void should_keepOldAvatarIntact_when_r2UploadThrows() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        String oldKey = "avatars/" + userId + "/old.jpg";
        user.setAvatarR2Key(oldKey);
        user.setAvatarUrl("https://r2/old.jpg");
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "R2 upload failed"))
                .when(r2).uploadFile(anyString(), any(), anyLong(), anyString());

        assertThatThrownBy(() -> service.uploadAvatar(userId, jpegFile()))
                .isInstanceOf(BusinessException.class);

        verify(r2, never()).deleteFile(anyString());
        verify(txWrite, never()).execute(any());
        verify(userRepo, never()).save(any(User.class));
        assertThat(user.getAvatarR2Key()).isEqualTo(oldKey);
        assertThat(user.getAvatarUrl()).isEqualTo("https://r2/old.jpg");
    }

    @Test
    @DisplayName("deletes the NEW blob (not the old) when the DB write fails and the row does not reference it")
    void should_discardNewBlobAndKeepOld_when_dbWriteFails() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        String oldKey = "avatars/" + userId + "/old.jpg";
        user.setAvatarR2Key(oldKey);
        // A fresh read after the rolled-back write still sees the OLD key (the locked entity above is rolled back).
        User committedView = newUser(userId);
        committedView.setAvatarR2Key(oldKey);
        lenient().when(userRepo.findById(userId)).thenReturn(Optional.of(committedView));
        // The write tx fails (no save() any more — the locked load is the write step's DB round-trip).
        when(userRepo.findByIdForUpdate(userId)).thenThrow(new IllegalStateException("db down"));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/new.jpg");

        assertThatThrownBy(() -> service.uploadAvatar(userId, jpegFile()))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(uploaded.capture(), any(), anyLong(), anyString());
        verify(r2).deleteFile(uploaded.getValue());
        verify(r2, never()).deleteFile(oldKey);
    }

    // ------------------------------------------------ paginated getPortfolio — sort override

    @Test
    @DisplayName("enforces createdAt DESC sort regardless of the PageRequest sort supplied by the caller")
    void should_enforceCreatedAtDescSort_when_callerSuppliesArbitrarySort() {
        UUID entityId = UUID.randomUUID();
        // Caller-supplied sort that is deliberately different from the expected createdAt DESC.
        PageRequest callerPageRequest = PageRequest.of(0, 10, Sort.by(Direction.ASC, "r2Key"));
        when(mediaRepo.findByEntityTypeAndEntityId(eq(EntityType.MASTER), eq(entityId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getPortfolio(EntityType.MASTER, entityId, callerPageRequest);

        // Capture the Pageable that actually reached the repository and verify the sort
        // was overridden to createdAt DESC — never the caller-supplied r2Key ASC.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(mediaRepo).findByEntityTypeAndEntityId(eq(EntityType.MASTER), eq(entityId), pageableCaptor.capture());
        Sort capturedSort = pageableCaptor.getValue().getSort();
        assertThat(capturedSort.getOrderFor("createdAt")).isNotNull();
        assertThat(capturedSort.getOrderFor("createdAt").getDirection()).isEqualTo(Direction.DESC);
        assertThat(capturedSort.getOrderFor("r2Key")).isNull();
    }

    @Test
    @DisplayName("preserves caller page number and page size while overriding the sort")
    void should_preservePageNumberAndSize_when_overridingSort() {
        UUID entityId = UUID.randomUUID();
        PageRequest callerPageRequest = PageRequest.of(2, 25, Sort.by(Direction.ASC, "r2Key"));
        when(mediaRepo.findByEntityTypeAndEntityId(eq(EntityType.SALON), eq(entityId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getPortfolio(EntityType.SALON, entityId, callerPageRequest);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(mediaRepo).findByEntityTypeAndEntityId(eq(EntityType.SALON), eq(entityId), pageableCaptor.capture());
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(2);
        assertThat(captured.getPageSize()).isEqualTo(25);
    }

    // ------------------------------------------------- S-M1 — salon image own-prefix guard

    @Test
    @DisplayName("S-M1: a salon image KEY outside salons/<salonId>/ (another salon, a user avatar, traversal) handed "
            + "to deleteBySalon is NOT deleted — counts-only WARN; the portfolio rows are still swept")
    void should_skipDelete_when_salonImageKeyOutsideSalonPrefix() {
        UUID salonId = UUID.randomUUID();
        String otherSalonKey = "salons/" + UUID.randomUUID() + "/logo/x.jpg";
        String userAvatarKey = "avatars/" + UUID.randomUUID() + "/a.jpg";
        String traversalKey = "salons/" + salonId + "/../avatars/u/a.jpg";
        MediaFileKey portfolio = salonMediaKey(salonId, "portfolio/salons/" + salonId + "/p-1");
        listAppender.list.clear();

        service.deleteBySalon(salonId, List.of(otherSalonKey, userAvatarKey, traversalKey), List.of(portfolio));

        verify(r2).deleteFiles(List.of("portfolio/salons/" + salonId + "/p-1"));
        assertThat(listAppender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("skippedCount=3")
                        .doesNotContain("logo/x.jpg").doesNotContain("/a.jpg"));
    }

    @Test
    @DisplayName("S-M1: resolveSalonImageKey rejects a URL resolving outside salons/<salonId>/ with a WARN that "
            + "omits the URL")
    void should_returnNullAndWarn_when_salonImageUrlOutsideSalonPrefix() {
        UUID salonId = UUID.randomUUID();
        String otherSalonUrl = "https://pub.r2.dev/salons/" + UUID.randomUUID() + "/logo/x.jpg";
        when(r2.extractKeyFromPublicUrl(otherSalonUrl))
                .thenReturn(Optional.of(otherSalonUrl.substring("https://pub.r2.dev/".length())));
        listAppender.list.clear();

        String key = service.resolveSalonImageKey(salonId, otherSalonUrl);

        assertThat(key).isNull();
        assertThat(listAppender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("Salon image purge skipped")
                        .doesNotContain("logo/x.jpg"));
    }

    @Test
    @DisplayName("S-M1: resolveSalonImageKey returns the key for a URL under the salon's own prefix")
    void should_returnKey_when_salonImageUrlUnderOwnPrefix() {
        UUID salonId = UUID.randomUUID();
        String logoKey = "salons/" + salonId + "/logo/l.jpg";
        when(r2.extractKeyFromPublicUrl("https://pub.r2.dev/" + logoKey)).thenReturn(Optional.of(logoKey));

        assertThat(service.resolveSalonImageKey(salonId, "https://pub.r2.dev/" + logoKey)).isEqualTo(logoKey);
    }

    @Test
    @DisplayName("S-M1: logo + cover keys under the salon's own salons/<salonId>/ prefix ARE deleted")
    void should_deleteSalonImageKeys_when_keysUnderOwnSalonPrefix() {
        UUID salonId = UUID.randomUUID();
        String logoKey = "salons/" + salonId + "/logo/l.jpg";
        String coverKey = "salons/" + salonId + "/cover/c.jpg";

        service.deleteBySalon(salonId, List.of(logoKey, coverKey), List.of());

        verify(r2).deleteFiles(List.of(logoKey, coverKey));
    }

    @Test
    @DisplayName("S-M1: resolveSalonImageKey rejects traversal under the own prefix and a null URL")
    void should_returnNull_when_salonImageKeyHasTraversalOrUrlIsNull() {
        UUID salonId = UUID.randomUUID();
        String url = "https://pub.r2.dev/x";
        when(r2.extractKeyFromPublicUrl(url)).thenReturn(Optional.of("salons/" + salonId + "/../avatars/u/a.jpg"));

        assertThat(service.resolveSalonImageKey(salonId, url)).isNull();
        assertThat(service.resolveSalonImageKey(salonId, null)).isNull();
    }

    // --------------------------------------------- account purge (S-L1, P-M1, P-M2 hand-off)

    @Test
    @DisplayName("S-L1: an account avatar key outside avatars/<userId>/ is skipped (WARN, key omitted); "
            + "media keys are still purged")
    void should_skipAvatarKey_when_accountAvatarOutsideOwnPrefix() {
        UUID userId = UUID.randomUUID();
        String foreignKey = "avatars/" + UUID.randomUUID() + "/x.jpg";
        UploaderMediaKey media = new UploaderMediaKey(userId, "portfolio/independent/" + MASTER_ID_FOR_PURGE + "/m.jpg",
                EntityType.MASTER, MASTER_ID_FOR_PURGE);
        listAppender.list.clear();

        service.purgeUserBlobsAfterCommit(List.of(new AccountBlobPointers(userId, foreignKey, null, List.of(media))));

        verify(r2).deleteFiles(List.of("portfolio/independent/" + MASTER_ID_FOR_PURGE + "/m.jpg"));
        assertThat(listAppender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("[omitted]").doesNotContain(foreignKey));
    }

    @Test
    @DisplayName("P-M1: N accounts -> ONE deleteFiles call with every verified avatar (incl. legacy url-derived) "
            + "and media key; each distinct portfolio cache entry evicted once")
    void should_purgeAllAccountsInOneBatch_when_multipleAccounts() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(r2.extractKeyFromPublicUrl("https://cdn/avatars/" + b + "/legacy.jpg"))
                .thenReturn(Optional.of("avatars/" + b + "/legacy.jpg"));
        UploaderMediaKey ma = new UploaderMediaKey(a, "portfolio/salons/" + salonId + "/a.jpg", EntityType.SALON, salonId);
        UploaderMediaKey mb = new UploaderMediaKey(b, "portfolio/salons/" + salonId + "/b.jpg", EntityType.SALON, salonId);

        service.purgeUserBlobsAfterCommit(List.of(
                new AccountBlobPointers(a, "avatars/" + a + "/a.jpg", null, List.of(ma)),
                new AccountBlobPointers(b, null, "https://cdn/avatars/" + b + "/legacy.jpg", List.of(mb))));

        verify(r2, times(1)).deleteFiles(List.of(
                "avatars/" + a + "/a.jpg", "portfolio/salons/" + salonId + "/a.jpg", "avatars/" + b + "/legacy.jpg", "portfolio/salons/" + salonId + "/b.jpg"));
        verify(portfolioCache, times(1)).evictIfPresent(EntityType.SALON.name() + "_" + salonId);
    }

    // ----------------------------------- S-L5 — media_files key own-portfolio-prefix guard

    @Test
    @DisplayName("S-L5: account purge skips a media key outside its row's portfolio/<kind>/<entityId>/ prefix "
            + "(another entity, wrong kind, traversal, USER entity) — WARN with a count only")
    void should_skipMediaKey_when_outsideEntityPortfolioPrefix() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        String own = "portfolio/salons/" + salonId + "/1-own.jpg";
        List<UploaderMediaKey> media = List.of(
                new UploaderMediaKey(userId, own, EntityType.SALON, salonId),
                new UploaderMediaKey(userId, "portfolio/salons/" + UUID.randomUUID() + "/1-foreign.jpg",
                        EntityType.SALON, salonId),
                new UploaderMediaKey(userId, "portfolio/independent/" + salonId + "/1-kind.jpg",
                        EntityType.SALON, salonId),
                new UploaderMediaKey(userId, "portfolio/independent/" + masterId + "/../x/1-trav.jpg",
                        EntityType.MASTER, masterId),
                new UploaderMediaKey(userId, "avatars/" + userId + "/1-user.jpg", EntityType.USER, userId));
        listAppender.list.clear();

        service.purgeUserBlobsAfterCommit(List.of(new AccountBlobPointers(userId, null, null, media)));

        verify(r2).deleteFiles(List.of(own));
        assertThat(listAppender.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("skippedCount=4")
                        .doesNotContain("foreign").doesNotContain("1-kind").doesNotContain("1-user"));
    }

    @Test
    @DisplayName("S-L5: salon sweep deletes only rows under portfolio/salons/<salonId>/; a mismatched row's "
            + "blob is skipped but the row itself is still removed from the DB")
    void should_skipMediaKey_when_salonSweepRowOutsideEntityPortfolioPrefix() {
        UUID salonId = UUID.randomUUID();
        String own = "portfolio/salons/" + salonId + "/1-own.jpg";
        List<MediaFileKey> rows = List.of(
                salonMediaKey(salonId, own),
                salonMediaKey(salonId, "avatars/" + UUID.randomUUID() + "/a.jpg"));

        service.deleteBySalon(salonId, List.of(), rows);

        verify(r2).deleteFiles(List.of(own));
        verify(mediaRepo).deleteAllByIdInBatch(keyIdsOf(rows));
    }

    @Test
    @DisplayName("S-L5: keys under their own SALON / MASTER portfolio prefix are accepted")
    void should_acceptMediaKey_when_underOwnEntityPortfolioPrefix() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        String salonKey = "portfolio/salons/" + salonId + "/1-s.jpg";
        String masterKey = "portfolio/independent/" + masterId + "/1-m.jpg";

        service.purgeUserBlobsAfterCommit(List.of(new AccountBlobPointers(userId, null, null, List.of(
                new UploaderMediaKey(userId, salonKey, EntityType.SALON, salonId),
                new UploaderMediaKey(userId, masterKey, EntityType.MASTER, masterId)))));

        verify(r2).deleteFiles(List.of(salonKey, masterKey));
    }

    @Test
    @DisplayName("account purge with nothing to delete makes no R2 call")
    void should_notCallR2_when_accountHasNoBlobs() {
        service.purgeUserBlobsAfterCommit(List.of(new AccountBlobPointers(UUID.randomUUID(), null, null, List.of())));

        verify(r2, never()).deleteFiles(anyCollection());
    }

    // ------------------------------------------------------- Phase 268 D3 — deleteBySalon sweep

    @Test
    @DisplayName("case 6 — deletes every SALON portfolio row's R2 blob and the row itself")
    void should_deleteSalonPortfolioPhotos_when_deleteBySalonCalled() {
        UUID salonId = UUID.randomUUID();
        List<MediaFileKey> rows = List.of(
                salonMediaKey(salonId, "portfolio/salons/" + salonId + "/p-1"),
                salonMediaKey(salonId, "portfolio/salons/" + salonId + "/p-2"));

        service.deleteBySalon(salonId, List.of(), rows);

        // Batched (Phase 268 perf follow-up): one deleteFiles(...) call carrying both keys.
        verify(r2).deleteFiles(List.of("portfolio/salons/" + salonId + "/p-1", "portfolio/salons/" + salonId + "/p-2"));
        verify(mediaRepo).deleteAllByIdInBatch(keyIdsOf(rows));
    }

    @Test
    @DisplayName("case 7 — deletes BOTH the logo and cover R2 blobs, even with zero portfolio rows "
            + "(must not short-circuit on rows.isEmpty())")
    void should_deleteAvatarAndCoverBlobs_when_deleteBySalonCalledWithNoPortfolioRows() {
        UUID salonId = UUID.randomUUID();
        String logoKey = "salons/" + salonId + "/logo/avatar.jpg";
        String coverKey = "salons/" + salonId + "/cover/cover.jpg";

        service.deleteBySalon(salonId, List.of(logoKey, coverKey), List.of());

        // Batched (Phase 268 perf follow-up): both extra keys in one deleteFiles(...) call.
        verify(r2).deleteFiles(List.of(logoKey, coverKey));
        // No portfolio rows to delete — the DB write tx must never be invoked for an empty list.
        verify(mediaRepo, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    @DisplayName("case 11 — one R2 delete failing (the logo) does not abort the sweep: the cover "
            + "and the portfolio row are still deleted, and the DB batch delete still runs")
    void should_continueSweep_when_oneR2DeleteThrowsDuringDeleteBySalon() {
        UUID salonId = UUID.randomUUID();
        String logoKey = "salons/" + salonId + "/logo/a.jpg";
        String coverKey = "salons/" + salonId + "/cover/c.jpg";
        String portfolioKey = "portfolio/salons/" + salonId + "/p-1";
        MediaFileKey portfolio = salonMediaKey(salonId, portfolioKey);
        // R2 reports only the logo as a per-key failure in the returned set.
        when(r2.deleteFiles(List.of(logoKey, coverKey, portfolioKey))).thenReturn(Set.of(logoKey));

        // No throw — the failing logo delete must not abort the rest of the sweep.
        service.deleteBySalon(salonId, List.of(logoKey, coverKey), List.of(portfolio));

        verify(r2).deleteFiles(List.of(logoKey, coverKey, portfolioKey));
        verify(mediaRepo).deleteAllByIdInBatch(keyIdsOf(List.of(portfolio)));
    }

    @Test
    @DisplayName("case 12 — the sweep completes with NO thrown exception even when every single "
            + "R2 delete fails, so a salon deletion never rolls back on an R2 outage")
    void should_completeWithoutThrowing_when_everyR2DeleteFailsDuringDeleteBySalon() {
        UUID salonId = UUID.randomUUID();
        String logoKey = "salons/" + salonId + "/logo/a.jpg";
        String portfolioKey = "portfolio/salons/" + salonId + "/p-1";
        MediaFileKey portfolio = salonMediaKey(salonId, portfolioKey);
        // r2.deleteFiles never throws for a delete failure — it reports every requested key back as failed.
        when(r2.deleteFiles(List.of(logoKey, portfolioKey))).thenReturn(Set.of(logoKey, portfolioKey));

        assertThatCode(() -> service.deleteBySalon(salonId, List.of(logoKey), List.of(portfolio)))
                .as("R2 being entirely down must never propagate out of the sweep")
                .doesNotThrowAnyException();

        // The DB pointer's row is still dropped — D4's accepted-orphan policy.
        verify(mediaRepo).deleteAllByIdInBatch(keyIdsOf(List.of(portfolio)));
    }

    @Test
    @DisplayName("case 13 — WARN log omits the R2 key and contains '[key omitted]' when a logo/cover "
            + "delete fails during deleteBySalon")
    void should_notLogR2Key_when_deleteBySalonAvatarDeleteFails() {
        UUID salonId = UUID.randomUUID();
        String rawKey = "salons/" + salonId + "/logo/avatar.jpg";
        when(r2.deleteFiles(List.of(rawKey))).thenReturn(Set.of(rawKey));
        listAppender.list.clear();

        service.deleteBySalon(salonId, List.of(rawKey), List.of());

        List<ILoggingEvent> warns = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).hasSize(1);
        String formattedMessage = warns.get(0).getFormattedMessage();
        assertThat(formattedMessage).contains("[key omitted]");
        assertThat(formattedMessage).doesNotContain(rawKey);
    }

    @Test
    @DisplayName("evicts the salon's portfolio cache entry after deleteBySalon, even when there were "
            + "zero portfolio rows to derive the entity from")
    void should_evictSalonPortfolioCache_when_deleteBySalonCalledWithNoRows() {
        UUID salonId = UUID.randomUUID();

        service.deleteBySalon(salonId, List.of("salons/" + salonId + "/logo/a.jpg"), List.of());

        verify(portfolioCache).evictIfPresent(EntityType.SALON.name() + "_" + salonId);
    }

    @Test
    @DisplayName("D4 pin, dedicated to deleteBySalon — R2 batch delete happens strictly BEFORE the "
            + "DB row drop (the salon is already gone; no live pointer to protect)")
    void should_deleteR2BlobsBeforeDbRows_when_deleteBySalonCalled() {
        UUID salonId = UUID.randomUUID();
        String logoKey = "salons/" + salonId + "/logo/a.jpg";
        String coverKey = "salons/" + salonId + "/cover/c.jpg";
        String portfolioKey = "portfolio/salons/" + salonId + "/p-1";
        MediaFileKey portfolio = salonMediaKey(salonId, portfolioKey);

        service.deleteBySalon(salonId, List.of(logoKey, coverKey), List.of(portfolio));

        InOrder inOrder = inOrder(r2, mediaRepo);
        inOrder.verify(r2).deleteFiles(List.of(logoKey, coverKey, portfolioKey));
        inOrder.verify(mediaRepo).deleteAllByIdInBatch(keyIdsOf(List.of(portfolio)));
    }

    @Test
    @DisplayName("is a true no-op — no R2 delete, no DB call — when there is nothing to sweep at all")
    void should_doNothing_when_deleteBySalonCalledWithNothingToSweep() {
        UUID salonId = UUID.randomUUID();

        service.deleteBySalon(salonId, List.of(), List.of());

        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyList());
        verify(mediaRepo, never()).deleteAllByIdInBatch(anyList());
    }

    // ------------------------------------------------------------------ helpers

    private static User newUser(UUID id, Role role) {
        User u = newUser(id);
        setField(u, "role", role);
        return u;
    }

    private static User newUser(UUID id) {
        try {
            var ctor = User.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            User u = ctor.newInstance();
            setField(u, "id", id);
            return u;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate User", e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set field " + name, e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cur = type;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ------------------------------------------------------ service photo (phase 342)

    private static com.beautica.service.entity.ServiceDefinition definition(
            UUID id, String photoUrl, String photoR2Key) {
        return definition(id, photoUrl, photoR2Key, true);
    }

    private static com.beautica.service.entity.ServiceDefinition definition(
            UUID id, String photoUrl, String photoR2Key, boolean active) {
        return com.beautica.service.entity.ServiceDefinition.builder()
                .id(id)
                .ownerType(com.beautica.service.entity.OwnerType.INDEPENDENT_MASTER)
                .ownerId(UUID.randomUUID())
                .name("Manicure")
                .baseDurationMinutes(60)
                .photoUrl(photoUrl)
                .photoR2Key(photoR2Key)
                .priceType(com.beautica.service.entity.PriceType.FIXED)
                .basePrice(new java.math.BigDecimal("100.00"))
                .isActive(active)
                .build();
    }

    /** Read-gate passes and the locked read returns {@code def}. */
    private void givenActiveLockedDefinition(UUID id, com.beautica.service.entity.ServiceDefinition def) {
        lenient().when(serviceRepo.findIdIfDefinitionAndOwnerActive(id)).thenReturn(Optional.of(id));
        lenient().when(serviceRepo.findByIdForUpdate(id)).thenReturn(Optional.of(def));
        lenient().when(serviceRepo.save(def)).thenReturn(def);
        lenient().when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.test/" + inv.getArgument(0));
    }

    private String capturedUploadedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(key.capture(), any(), anyLong(), anyString());
        return key.getValue();
    }

    @Test
    @DisplayName("uploadServicePhoto stores key + url on the definition and evicts caches post-commit")
    void uploadServicePhoto_setsKeyAndUrl_andEvictsCaches() {
        UUID id = UUID.randomUUID();
        var def = definition(id, null, null);
        givenActiveLockedDefinition(id, def);

        var response = service.uploadServicePhoto(id, jpegFile());

        assertThat(def.getPhotoR2Key()).startsWith("services/" + id + "/").endsWith(".jpg");
        assertThat(def.getPhotoUrl()).isEqualTo("https://cdn.test/" + def.getPhotoR2Key());
        assertThat(response.photoUrl()).isEqualTo(def.getPhotoUrl());
        verify(r2, never()).deleteFile(any());
        verify(servicePhotoBlobPurger).purgeAfterCommit(id, null);
        verify(serviceCatalogService).evictServicePhotoCaches(id, def.getOwnerType(), def.getOwnerId());
    }

    @Test
    @DisplayName("uploadServicePhoto on replace uploads the NEW blob first, then hands the superseded KEY to the purger")
    void uploadServicePhoto_onReplace_uploadsThenPurgesSupersededKey() {
        UUID id = UUID.randomUUID();
        String oldKey = "services/" + id + "/1-old.jpg";
        var def = definition(id, "https://cdn.test/" + oldKey, oldKey);
        givenActiveLockedDefinition(id, def);

        service.uploadServicePhoto(id, jpegFile());

        var order = inOrder(r2, serviceRepo, servicePhotoBlobPurger);
        order.verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/jpeg"));
        order.verify(serviceRepo).findByIdForUpdate(id);
        order.verify(servicePhotoBlobPurger).purgeAfterCommit(id, oldKey);
        verify(r2, never()).deleteFile(any());
        assertThat(def.getPhotoR2Key()).isNotEqualTo(oldKey);
    }

    @Test
    @DisplayName("uploadServicePhoto on a legacy row (url, no key) purges nothing")
    void uploadServicePhoto_onLegacyRow_purgesNothing() {
        UUID id = UUID.randomUUID();
        var def = definition(id, "https://legacy.test/p.jpg", null);
        givenActiveLockedDefinition(id, def);

        service.uploadServicePhoto(id, jpegFile());

        verify(r2, never()).deleteFile(any());
        verify(servicePhotoBlobPurger).purgeAfterCommit(id, null);
        verify(r2).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("uploadServicePhoto when the R2 upload fails keeps the old photo and pointer intact")
    void uploadServicePhoto_whenR2UploadFails_keepsOldPhoto() {
        UUID id = UUID.randomUUID();
        String oldKey = "services/" + id + "/1-old.jpg";
        String oldUrl = "https://cdn.test/" + oldKey;
        var def = definition(id, oldUrl, oldKey);
        givenActiveLockedDefinition(id, def);
        doThrow(new IllegalStateException("r2 down")).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());

        assertThatThrownBy(() -> service.uploadServicePhoto(id, jpegFile()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(def.getPhotoR2Key()).isEqualTo(oldKey);
        assertThat(def.getPhotoUrl()).isEqualTo(oldUrl);
        verify(r2, never()).deleteFile(any());
        verify(serviceRepo, never()).save(any());
        verifyNoInteractions(servicePhotoBlobPurger, serviceCatalogService);
    }

    @Test
    @DisplayName("uploadServicePhoto when the DB write fails after the upload deletes the NEW blob and keeps the old one")
    void uploadServicePhoto_whenDbWriteFails_deletesNewBlob() {
        UUID id = UUID.randomUUID();
        String oldKey = "services/" + id + "/1-old.jpg";
        var def = definition(id, "https://cdn.test/" + oldKey, oldKey);
        givenActiveLockedDefinition(id, def);
        when(serviceRepo.save(def)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.uploadServicePhoto(id, jpegFile()))
                .isInstanceOf(IllegalStateException.class);

        String uploadedKey = capturedUploadedKey();
        verify(r2).deleteFile(uploadedKey);
        verify(r2, never()).deleteFile(oldKey);
        verifyNoInteractions(servicePhotoBlobPurger, serviceCatalogService);
    }

    @Test
    @DisplayName("uploadServicePhoto when the failure surfaces AFTER the commit applied retains the NEW blob")
    void should_retainNewBlob_when_failureSurfacesAfterCommitApplied() {
        UUID id = UUID.randomUUID();
        String oldKey = "services/" + id + "/1-old.jpg";
        var def = definition(id, "https://cdn.test/" + oldKey, oldKey);
        givenActiveLockedDefinition(id, def);
        // Commit-ack ambiguity: the callback ran (row now holds the new key) but the commit
        // acknowledgement failed — the exception reaches the service although the write is durable.
        doAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            cb.doInTransaction(mock(TransactionStatus.class));
            throw new IllegalStateException("commit ack lost");
        }).when(txWrite).execute(any());
        when(serviceRepo.findById(id)).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> service.uploadServicePhoto(id, jpegFile()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(def.getPhotoR2Key()).isEqualTo(capturedUploadedKey());
        verify(r2, never()).deleteFile(any());
    }

    @Test
    @DisplayName("uploadServicePhoto when the commit state cannot be re-read retains the NEW blob")
    void should_retainNewBlob_when_commitStateCannotBeVerified() {
        UUID id = UUID.randomUUID();
        String oldKey = "services/" + id + "/1-old.jpg";
        var def = definition(id, "https://cdn.test/" + oldKey, oldKey);
        givenActiveLockedDefinition(id, def);
        when(serviceRepo.save(def)).thenThrow(new IllegalStateException("db down"));
        when(serviceRepo.findById(id)).thenThrow(new IllegalStateException("db still down"));

        assertThatThrownBy(() -> service.uploadServicePhoto(id, jpegFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");

        verify(serviceRepo).findById(id);
        verify(r2, never()).deleteFile(any());
    }

    @Test
    @DisplayName("uploadServicePhoto when the definition went inactive inside the lock deletes the NEW blob and throws 404")
    void uploadServicePhoto_whenDeactivatedInsideLock_deletesNewBlobAndThrowsNotFound() {
        UUID id = UUID.randomUUID();
        String oldKey = "services/" + id + "/1-old.jpg";
        var def = definition(id, "https://cdn.test/" + oldKey, oldKey, false);
        givenActiveLockedDefinition(id, def);

        assertThatThrownBy(() -> service.uploadServicePhoto(id, jpegFile()))
                .isInstanceOf(NotFoundException.class);

        String uploadedKey = capturedUploadedKey();
        verify(r2).deleteFile(uploadedKey);
        assertThat(def.getPhotoR2Key()).isEqualTo(oldKey);
        verify(serviceRepo, never()).save(any());
        verifyNoInteractions(servicePhotoBlobPurger, serviceCatalogService);
    }

    @Test
    @DisplayName("uploadServicePhoto twice (concurrent replace): each superseded blob is purged, none orphaned")
    void uploadServicePhoto_concurrentReplace_purgesEverySupersededBlob() {
        UUID id = UUID.randomUUID();
        String originalKey = "services/" + id + "/1-orig.jpg";
        var def = definition(id, "https://cdn.test/" + originalKey, originalKey);
        givenActiveLockedDefinition(id, def);

        // Both uploads pass the read-gate and upload before either write; the row lock then serialises the
        // writes, so the second one reads the CURRENT key (the first writer's), not the original.
        service.uploadServicePhoto(id, jpegFile());
        String firstKey = def.getPhotoR2Key();
        service.uploadServicePhoto(id, jpegFile());
        String secondKey = def.getPhotoR2Key();

        assertThat(firstKey).isNotEqualTo(originalKey);
        assertThat(secondKey).isNotIn(originalKey, firstKey);
        var order = inOrder(servicePhotoBlobPurger);
        order.verify(servicePhotoBlobPurger).purgeAfterCommit(id, originalKey);
        order.verify(servicePhotoBlobPurger).purgeAfterCommit(id, firstKey);
        verify(r2, never()).deleteFile(any());
    }

    @Test
    @DisplayName("uploadServicePhoto throws 503 before any repo read or R2 delete when storage is disabled")
    void uploadServicePhoto_whenStorageDisabled_throws503BeforeAnyDelete() {
        when(r2.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.uploadServicePhoto(UUID.randomUUID(), jpegFile()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verifyNoInteractions(serviceRepo, serviceCatalogService, servicePhotoBlobPurger);
        verifyNoStorageWrites();
    }

    @Test
    @DisplayName("uploadServicePhoto throws NotFound for an unknown/deactivated definition or salon and writes nothing to R2")
    void uploadServicePhoto_inactiveOrUnknownDefinition_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(serviceRepo.findIdIfDefinitionAndOwnerActive(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadServicePhoto(id, jpegFile()))
                .isInstanceOf(NotFoundException.class);

        verifyNoStorageWrites();
        verify(serviceRepo, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("uploadServicePhoto rejects an unsupported format with 400 before touching the repo")
    void uploadServicePhoto_rejectsSvg() {
        var svg = new MockMultipartFile("file", "a.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.uploadServicePhoto(UUID.randomUUID(), svg))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(serviceRepo);
        verifyNoStorageWrites();
    }

    @Test
    @DisplayName("deleteServicePhoto clears the DB pointers first, then hands the key to the purger and evicts caches")
    void deleteServicePhoto_clearsPointersThenPurgesKey() {
        UUID id = UUID.randomUUID();
        String key = "services/" + id + "/1-a.jpg";
        var def = definition(id, "https://cdn.test/" + key, key);
        givenActiveLockedDefinition(id, def);

        service.deleteServicePhoto(id);

        assertThat(def.getPhotoR2Key()).isNull();
        assertThat(def.getPhotoUrl()).isNull();
        var order = inOrder(serviceRepo, servicePhotoBlobPurger);
        order.verify(serviceRepo).save(def);
        order.verify(servicePhotoBlobPurger).purgeAfterCommit(id, key);
        verify(r2, never()).deleteFile(any());
        verify(serviceCatalogService).evictServicePhotoCaches(id, def.getOwnerType(), def.getOwnerId());
    }

    @Test
    @DisplayName("deleteServicePhoto on a legacy row (url, no key) clears photo_url with no R2 call")
    void deleteServicePhoto_legacyRow_clearsUrlWithoutR2() {
        UUID id = UUID.randomUUID();
        var def = definition(id, "https://legacy.test/p.jpg", null);
        givenActiveLockedDefinition(id, def);

        service.deleteServicePhoto(id);

        assertThat(def.getPhotoUrl()).isNull();
        verify(serviceRepo).save(def);
        verifyNoStorageWrites();
        verify(servicePhotoBlobPurger).purgeAfterCommit(id, null);
        verify(serviceCatalogService).evictServicePhotoCaches(id, def.getOwnerType(), def.getOwnerId());
    }

    @Test
    @DisplayName("deleteServicePhoto with no photo at all is a no-op (no R2 call, no write, no eviction)")
    void deleteServicePhoto_noPhoto_isNoOp() {
        UUID id = UUID.randomUUID();
        givenActiveLockedDefinition(id, definition(id, null, null));

        service.deleteServicePhoto(id);

        verifyNoStorageWrites();
        verify(serviceRepo, never()).save(any());
        verifyNoInteractions(serviceCatalogService, servicePhotoBlobPurger);
    }

    @Test
    @DisplayName("deleteServicePhoto throws NotFound for a deactivated definition/salon and changes nothing")
    void deleteServicePhoto_inactive_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(serviceRepo.findIdIfDefinitionAndOwnerActive(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteServicePhoto(id)).isInstanceOf(NotFoundException.class);

        verify(serviceRepo, never()).save(any());
        verifyNoInteractions(serviceCatalogService, servicePhotoBlobPurger);
    }

    @Test
    @DisplayName("deleteServicePhoto when the definition went inactive inside the lock throws NotFound and changes nothing")
    void deleteServicePhoto_deactivatedInsideLock_throwsNotFound() {
        UUID id = UUID.randomUUID();
        String key = "services/" + id + "/1-a.jpg";
        var def = definition(id, "https://cdn.test/" + key, key, false);
        givenActiveLockedDefinition(id, def);

        assertThatThrownBy(() -> service.deleteServicePhoto(id)).isInstanceOf(NotFoundException.class);

        assertThat(def.getPhotoR2Key()).isEqualTo(key);
        verifyNoInteractions(servicePhotoBlobPurger, serviceCatalogService);
    }

    private static MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "img.jpg", "image/jpeg", jpegBytes());
    }

    private static byte[] jpegBytes() {
        byte[] bytes = new byte[12];
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static MockMultipartFile pngFile() {
        byte[] bytes = new byte[12];
        bytes[0] = (byte) 0x89; bytes[1] = 0x50; bytes[2] = 0x4E; bytes[3] = 0x47;
        return new MockMultipartFile("file", "img.png", "image/png", bytes);
    }

    private static MockMultipartFile webpFile() {
        byte[] bytes = new byte[12];
        bytes[0] = 0x52; bytes[1] = 0x49; bytes[2] = 0x46; bytes[3] = 0x46;
        bytes[8] = 0x57; bytes[9] = 0x45; bytes[10] = 0x42; bytes[11] = 0x50;
        return new MockMultipartFile("file", "img.webp", "image/webp", bytes);
    }

    /** PERF-1: the sweep deletes by id in ONE batch statement — the ids the verifications expect. */
    private static MediaFileKey salonMediaKey(UUID salonId, String key) {
        return new MediaFileKey(UUID.randomUUID(), key, EntityType.SALON, salonId);
    }

    private static List<UUID> keyIdsOf(List<MediaFileKey> rows) {
        return rows.stream().map(MediaFileKey::id).toList();
    }

    private static MediaFile portfolioRow(UUID mediaId, UUID uploaderId, EntityType entityType, UUID entityId,
                                          String key) {
        return MediaFile.builder()
                .id(mediaId)
                .uploader(newUser(uploaderId))
                .entityType(entityType)
                .entityId(entityId)
                .mediaType(MediaType.PORTFOLIO)
                .r2Key(key)
                .r2Url("https://r2/" + key)
                .build();
    }

    /**
     * Runs {@code cb} the way {@code TransactionTemplate} would around a commit: synchronization active during
     * the callback, every registered {@code afterCommit} fired only when it returns normally, synchronization
     * cleared either way. Joins an outer simulated transaction instead of nesting one.
     */
    private static Object inSimulatedTransaction(TransactionCallback<?> cb) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            return cb.doInTransaction(mock(TransactionStatus.class));
        }
        TransactionSynchronizationManager.initSynchronization();
        try {
            Object result = cb.doInTransaction(mock(TransactionStatus.class));
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            return result;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
