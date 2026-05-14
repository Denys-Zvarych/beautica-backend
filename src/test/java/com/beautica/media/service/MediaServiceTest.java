package com.beautica.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        lenient().when(txWrite.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        // Phase 7.7 — portfolio cache eviction is a no-op in unit tests; the IT suite
        // exercises the real Caffeine cache. lenient() because not every existing test
        // hits a portfolio write path.
        lenient().when(cacheManager.getCache("portfolio")).thenReturn(portfolioCache);
        lenient().when(portfolioCache.evictIfPresent(any())).thenReturn(true);
        service = new MediaService(r2, mediaRepo, userRepo, salonRepo, masterRepo, fixedClock, txRead, txWrite, cacheManager);
    }

    // ---------------------------------------------------------------- magic bytes

    @Test
    @DisplayName("detects JPEG when file starts with FF D8 FF")
    void should_detectJpeg_when_fileStartsWithFfd8ff() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatar.jpg");

        service.uploadAvatar(userId, jpegFile());

        verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/jpeg"));
    }

    @Test
    @DisplayName("detects PNG when file starts with 89 50 4E 47")
    void should_detectPng_when_fileStartsWithPngSignature() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatar.png");

        service.uploadAvatar(userId, pngFile());

        verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/png"));
    }

    @Test
    @DisplayName("detects WebP when file starts with RIFF + WEBP")
    void should_detectWebp_when_fileStartsWithRiffAndWebp() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/avatar.webp");

        service.uploadAvatar(userId, webpFile());

        verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/webp"));
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
        verifyNoInteractions(r2);
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
        verifyNoInteractions(r2);
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
        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("rejects empty files")
    void should_throw400_when_fileIsEmpty() {
        MultipartFile empty = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.uploadAvatar(UUID.randomUUID(), empty))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must not be empty");
        verifyNoInteractions(r2);
    }

    // -------------------------------------------------------------------- avatar

    @Test
    @DisplayName("uploads avatar with valid JPEG and updates user fields")
    void should_uploadAvatar_when_validJpegFile() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
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
    @DisplayName("deletes old avatar from R2 BEFORE uploading the new one")
    void should_deleteOldAvatarFirst_when_userAlreadyHasAvatar() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarR2Key("avatars/" + userId + "/old.jpg");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/new.jpg");

        service.uploadAvatar(userId, jpegFile());

        InOrder inOrder = inOrder(r2);
        inOrder.verify(r2).deleteFile("avatars/" + userId + "/old.jpg");
        inOrder.verify(r2).uploadFile(anyString(), any(), anyLong(), eq("image/jpeg"));
    }

    @Test
    @DisplayName("does not call deleteFile when the user has no existing avatar")
    void should_notCallDeleteFile_when_userHasNoExistingAvatar() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(r2.buildPublicUrl(anyString())).thenReturn("https://r2/new.jpg");

        service.uploadAvatar(userId, jpegFile());

        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("clears avatar fields when deleteAvatar is called")
    void should_clearAvatarFields_when_deleteAvatarCalled() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        user.setAvatarR2Key("avatars/" + userId + "/x.jpg");
        user.setAvatarUrl("https://r2/x.jpg");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        service.deleteAvatar(userId);

        verify(r2).deleteFile("avatars/" + userId + "/x.jpg");
        assertThat(user.getAvatarR2Key()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("does nothing when deleteAvatar is called and no avatar exists")
    void should_doNothing_when_deleteAvatarCalledAndNoAvatarExists() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        service.deleteAvatar(userId);

        verifyNoInteractions(r2);
        verify(userRepo, never()).save(any(User.class));
    }

    @Test
    @DisplayName("throws NotFoundException when user does not exist on deleteAvatar")
    void should_throwNotFound_when_userNotFoundOnDeleteAvatar() {
        // Arrange
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(any(UUID.class))).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.deleteAvatar(userId))
                .isInstanceOf(NotFoundException.class);

        verify(r2, never()).deleteFile(anyString());
    }

    // ----------------------------------------------------------------- portfolio

    @Test
    @DisplayName("uploads portfolio photo for salon when SALON_OWNER uploads")
    void should_uploadPortfolioForSalon_when_salonOwnerUploads() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = Salon.builder().id(salonId).isActive(true).build();
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
    @DisplayName("throws 403 when a CLIENT uploads a portfolio photo")
    void should_throwForbidden_when_clientUploadsPortfolio() {
        assertThatThrownBy(() -> service.uploadPortfolioPhoto(UUID.randomUUID(), Role.CLIENT, jpegFile()))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(r2);
        verifyNoInteractions(mediaRepo);
    }

    @Test
    @DisplayName("deletes portfolio photo when uploader requests deletion")
    void should_deletePortfolioPhoto_when_uploaderRequests() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        User uploader = newUser(actorId);
        MediaFile mf = MediaFile.builder()
                .id(mediaId)
                .uploader(uploader)
                .entityType(EntityType.MASTER)
                .entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO)
                .r2Key("portfolio/independent/x/y.jpg")
                .r2Url("https://r2/y.jpg")
                .build();
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(mf));

        service.deletePortfolioPhoto(actorId, mediaId);

        verify(r2).deleteFile("portfolio/independent/x/y.jpg");
        verify(mediaRepo).deleteById(mediaId);
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
    }

    // ---------------------------------------------- Phase 7.7 — portfolio cache eviction

    @Test
    @DisplayName("evicts the portfolio cache AFTER the write tx commits when uploadPortfolioPhoto succeeds")
    void should_evictPortfolioCache_when_uploadPortfolioPhotoSucceeds() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = Salon.builder().id(salonId).isActive(true).build();
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
        InOrder order = inOrder(txWrite, cacheManager, portfolioCache);
        order.verify(txWrite).execute(any());
        order.verify(cacheManager).getCache("portfolio");
        order.verify(portfolioCache).evictIfPresent(new SimpleKey(EntityType.SALON, salonId));
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
                .r2Key("portfolio/independent/x/y.jpg")
                .r2Url("https://r2/y.jpg")
                .build();
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(mf));

        service.deletePortfolioPhoto(actorId, mediaId);

        // Eviction must happen AFTER the write tx — InOrder over (txWrite, cache) proves it.
        InOrder order = inOrder(txWrite, cacheManager, portfolioCache);
        order.verify(txWrite).execute(any());
        order.verify(cacheManager).getCache("portfolio");
        order.verify(portfolioCache).evictIfPresent(new SimpleKey(EntityType.MASTER, masterEntityId));
    }

    @Test
    @DisplayName("never uses client-supplied filename when building the R2 key")
    void should_neverUseClientFilename_when_buildingR2Key() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
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

    // ----------------------------------------------------------------- SEC-2

    @Test
    @DisplayName("deletes R2 blob BEFORE deleting the media row")
    void should_deleteR2Blob_when_mediaRowIsDeleted() {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        User uploader = newUser(actorId);
        MediaFile mf = MediaFile.builder()
                .id(mediaId)
                .uploader(uploader)
                .entityType(EntityType.MASTER)
                .entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO)
                .r2Key("portfolio/independent/m/file.jpg")
                .r2Url("https://r2/file.jpg")
                .build();
        when(mediaRepo.findById(mediaId)).thenReturn(Optional.of(mf));

        service.deletePortfolioPhoto(actorId, mediaId);

        InOrder inOrder = inOrder(r2, mediaRepo);
        inOrder.verify(r2).deleteFile("portfolio/independent/m/file.jpg");
        inOrder.verify(mediaRepo).deleteById(mediaId);
    }

    @Test
    @DisplayName("purges R2 avatars BEFORE deleting DB rows in deleteByUploader sweep")
    void should_purgeR2Avatars_when_userIsDeletedBeforeCascade() {
        UUID uploaderId = UUID.randomUUID();
        User uploader = newUser(uploaderId);
        MediaFile a = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO).r2Key("k-a").r2Url("u-a").build();
        MediaFile b = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO).r2Key("k-b").r2Url("u-b").build();
        MediaFile c = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO).r2Key("k-c").r2Url("u-c").build();
        List<MediaFile> rows = List.of(a, b, c);
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(rows);

        service.deleteByUploader(uploaderId);

        InOrder inOrder = inOrder(r2, mediaRepo);
        inOrder.verify(r2).deleteFile("k-a");
        inOrder.verify(r2).deleteFile("k-b");
        inOrder.verify(r2).deleteFile("k-c");
        inOrder.verify(mediaRepo).deleteAll(rows);
        verify(r2, times(3)).deleteFile(anyString());
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
    }

    // ---------------------------------------------------- QA MEDIUM #4 — upload rollback

    @Test
    @DisplayName("leaves DB unchanged when R2 upload fails after the old avatar is deleted")
    void should_keepDbUnchanged_when_r2UploadThrowsAfterOldAvatarDeleted() {
        UUID userId = UUID.randomUUID();
        User user = newUser(userId);
        String oldKey = "avatars/" + userId + "/old.jpg";
        user.setAvatarR2Key(oldKey);
        user.setAvatarUrl("https://r2/old.jpg");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "R2 upload failed"))
                .when(r2).uploadFile(anyString(), any(), anyLong(), anyString());

        assertThatThrownBy(() -> service.uploadAvatar(userId, jpegFile()))
                .isInstanceOf(BusinessException.class);

        // SEC-2 ordering: old blob was attempted before the upload that failed.
        verify(r2, times(1)).deleteFile(oldKey);
        // Critically — the write tx (which would have rotated the DB key/url) never ran.
        verify(txWrite, never()).execute(any());
        verify(userRepo, never()).save(any(User.class));
        // The User entity was never mutated in-memory either.
        assertThat(user.getAvatarR2Key()).isEqualTo(oldKey);
        assertThat(user.getAvatarUrl()).isEqualTo("https://r2/old.jpg");
    }

    // ---------------------------------------------------- QA MEDIUM #5 — sweep resilience

    @Test
    @DisplayName("continues the deleteByUploader sweep when one R2 delete fails")
    void should_continueSweep_when_r2DeleteFailsOnOneRow() {
        UUID uploaderId = UUID.randomUUID();
        User uploader = newUser(uploaderId);
        MediaFile a = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO).r2Key("k-a").r2Url("u-a").build();
        MediaFile b = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO).r2Key("k-b").r2Url("u-b").build();
        MediaFile c = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO).r2Key("k-c").r2Url("u-c").build();
        List<MediaFile> rows = List.of(a, b, c);
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(rows);
        // Strict-stubbing mode: "k-a" and "k-c" succeed (explicit no-op), "k-b" throws.
        // Without the explicit doNothing() on the success cases, Mockito's
        // PotentialStubbingProblem would itself be thrown and swallowed by the service's
        // catch — making the test pass for the wrong reason.
        doNothing().when(r2).deleteFile("k-a");
        doThrow(new RuntimeException("transient R2 outage")).when(r2).deleteFile("k-b");
        doNothing().when(r2).deleteFile("k-c");

        // No throw — a single-row failure must not abort the sweep.
        service.deleteByUploader(uploaderId);

        // All three R2 deletes were attempted, in order.
        InOrder inOrder = inOrder(r2);
        inOrder.verify(r2).deleteFile("k-a");
        inOrder.verify(r2).deleteFile("k-b");
        inOrder.verify(r2).deleteFile("k-c");
        verify(r2, times(3)).deleteFile(anyString());
        // The DB batch delete still ran exactly once with the full row set.
        verify(mediaRepo, times(1)).deleteAll(rows);
    }

    // ---------------------------------------------------- MEDIUM-2 — sweep WARN log key redaction

    @Test
    @DisplayName("WARN log omits R2 key and contains '[key omitted]' when deleteByUploader R2 delete fails")
    void should_omitKeyInWarnLog_when_deleteByUploaderFails() {
        // Arrange
        UUID uploaderId = UUID.randomUUID();
        User uploader = newUser(uploaderId);
        String mockedKey = "portfolio/salons/" + UUID.randomUUID() + "/avatar.jpg";
        MediaFile mf = MediaFile.builder()
                .id(UUID.randomUUID())
                .uploader(uploader)
                .entityType(EntityType.SALON)
                .entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO)
                .r2Key(mockedKey)
                .r2Url("https://r2/" + mockedKey)
                .build();
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(List.of(mf));
        doThrow(new RuntimeException("mock delete failure")).when(r2).deleteFile(mockedKey);
        listAppender.list.clear();

        // Act — must not throw even though R2 delete fails
        service.deleteByUploader(uploaderId);

        // Assert
        List<ILoggingEvent> warns = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).as("exactly one WARN emitted when one R2 delete fails in sweep").hasSize(1);
        String formattedMessage = warns.get(0).getFormattedMessage();
        assertThat(formattedMessage)
                .as("WARN log must contain '[key omitted]' sentinel")
                .contains("[key omitted]");
        assertThat(formattedMessage)
                .as("WARN log must not contain the raw R2 key")
                .doesNotContain(mockedKey);
    }

    // ---------------------------------------------- Phase 7.8/7.9 — sweep cache eviction

    @Test
    @DisplayName("evicts portfolio cache for each DISTINCT (entityType, entityId) when deleteByUploader succeeds")
    void should_evictPortfolioCache_for_each_distinctEntity_when_deleteByUploaderSucceeds() {
        UUID uploaderId = UUID.randomUUID();
        User uploader = newUser(uploaderId);
        UUID salonA = UUID.randomUUID();
        UUID masterB = UUID.randomUUID();
        // Two rows on (SALON, salonA) — must collapse to a single eviction call — and one
        // row on (MASTER, masterB). Total: 2 distinct evictions.
        MediaFile a1 = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(salonA)
                .mediaType(MediaType.PORTFOLIO).r2Key("k-a1").r2Url("u-a1").build();
        MediaFile a2 = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(salonA)
                .mediaType(MediaType.PORTFOLIO).r2Key("k-a2").r2Url("u-a2").build();
        MediaFile b1 = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.MASTER).entityId(masterB)
                .mediaType(MediaType.PORTFOLIO).r2Key("k-b1").r2Url("u-b1").build();
        List<MediaFile> rows = List.of(a1, a2, b1);
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(rows);

        service.deleteByUploader(uploaderId);

        // The eviction must happen after the write tx (post-commit by construction).
        InOrder order = inOrder(txWrite, portfolioCache);
        order.verify(txWrite).execute(any());
        // Exactly 2 eviction calls — duplicate (SALON, salonA) collapsed by Set.
        verify(portfolioCache, times(1)).evictIfPresent(new SimpleKey(EntityType.SALON, salonA));
        verify(portfolioCache, times(1)).evictIfPresent(new SimpleKey(EntityType.MASTER, masterB));
        verify(portfolioCache, times(2)).evictIfPresent(any());
    }

    // ------------------------------------------------------------------ helpers

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
}
