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
// SimpleKey import removed — cache keys are now plain Strings (portfolioCacheKey contract)
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                .r2Key("portfolio/independent/x/y.jpg")
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

        // Batched (Phase 268 perf follow-up): one deleteFiles(...) call for every row's key,
        // still strictly BEFORE the DB batch delete.
        InOrder inOrder = inOrder(r2, mediaRepo);
        inOrder.verify(r2).deleteFiles(List.of("k-a", "k-b", "k-c"));
        inOrder.verify(mediaRepo).deleteAll(rows);
    }

    // ------------------------------------- avatar blob coverage in the deleteByUploader sweep

    @Test
    @DisplayName("purges the users.avatar_r2_key blob and nulls both avatar columns in the deleteByUploader sweep")
    void should_purgeAvatarBlob_when_deleteByUploaderSweeps() {
        UUID uploaderId = UUID.randomUUID();
        User uploader = newUser(uploaderId);
        uploader.setAvatarR2Key("avatars/" + uploaderId + "/photo.jpg");
        uploader.setAvatarUrl("https://r2/avatars/" + uploaderId + "/photo.jpg");
        MediaFile portfolio = MediaFile.builder().id(UUID.randomUUID()).uploader(uploader)
                .entityType(EntityType.SALON).entityId(UUID.randomUUID())
                .mediaType(MediaType.PORTFOLIO).r2Key("k-a").r2Url("u-a").build();
        when(userRepo.findById(uploaderId)).thenReturn(Optional.of(uploader));
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(List.of(portfolio));

        service.deleteByUploader(uploaderId);

        // The avatar blob lives on the users row, not in media_files, and is swept separately
        // (sweepAvatar, single-key) from the batched media_files row sweep (sweepBlobs) — a
        // media-rows-only sweep would leave the avatar publicly retrievable after the account
        // is deleted.
        verify(r2).deleteFile("avatars/" + uploaderId + "/photo.jpg");
        verify(r2).deleteFiles(List.of("k-a"));
        assertThat(uploader.getAvatarR2Key()).isNull();
        assertThat(uploader.getAvatarUrl()).isNull();
        verify(userRepo).save(uploader);
    }

    @Test
    @DisplayName("still purges the avatar blob when the uploader owns no media_files rows")
    void should_purgeAvatarBlob_when_uploaderHasNoMediaRows() {
        // Regression guard: the sweep's `rows.isEmpty()` short-circuit must not skip the
        // avatar. A CLIENT with a profile photo and no portfolio is exactly this case.
        UUID uploaderId = UUID.randomUUID();
        User uploader = newUser(uploaderId);
        uploader.setAvatarR2Key("avatars/" + uploaderId + "/photo.jpg");
        uploader.setAvatarUrl("https://r2/avatars/" + uploaderId + "/photo.jpg");
        when(userRepo.findById(uploaderId)).thenReturn(Optional.of(uploader));
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(List.of());

        service.deleteByUploader(uploaderId);

        verify(r2).deleteFile("avatars/" + uploaderId + "/photo.jpg");
        assertThat(uploader.getAvatarR2Key()).isNull();
        assertThat(uploader.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("does not touch R2 in the deleteByUploader sweep when the uploader has no avatar")
    void should_skipAvatarSweep_when_uploaderHasNoAvatar() {
        UUID uploaderId = UUID.randomUUID();
        when(userRepo.findById(uploaderId)).thenReturn(Optional.of(newUser(uploaderId)));
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(List.of());

        service.deleteByUploader(uploaderId);

        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("continues the deleteByUploader sweep and redacts the key when the avatar R2 delete fails")
    void should_continueSweep_when_avatarR2DeleteFails() {
        UUID uploaderId = UUID.randomUUID();
        User uploader = newUser(uploaderId);
        String avatarKey = "avatars/" + uploaderId + "/photo.jpg";
        uploader.setAvatarR2Key(avatarKey);
        uploader.setAvatarUrl("https://r2/" + avatarKey);
        when(userRepo.findById(uploaderId)).thenReturn(Optional.of(uploader));
        when(mediaRepo.findByUploaderId(uploaderId)).thenReturn(List.of());
        doThrow(new RuntimeException("transient R2 outage")).when(r2).deleteFile(avatarKey);
        listAppender.list.clear();

        // Best-effort, mirroring the per-row policy: no throw, DB pointer dropped anyway.
        service.deleteByUploader(uploaderId);

        assertThat(uploader.getAvatarR2Key()).isNull();
        List<ILoggingEvent> warns = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
                .contains("[key omitted]")
                .doesNotContain(avatarKey);
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
        // Batched (Phase 268 perf follow-up): one deleteFiles(...) call carrying all three keys;
        // R2 reports "k-b" as a per-key failure in the returned set (S3 DeleteObjects semantics —
        // it does not throw for a partial batch failure).
        when(r2.deleteFiles(List.of("k-a", "k-b", "k-c"))).thenReturn(Set.of("k-b"));

        // No throw — a single-key failure must not abort the sweep.
        service.deleteByUploader(uploaderId);

        verify(r2, times(1)).deleteFiles(List.of("k-a", "k-b", "k-c"));
        // The DB batch delete still ran exactly once with the full row set, despite one key failing.
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
        // Batched (Phase 268 perf follow-up): R2 reports the key as failed in the returned set.
        when(r2.deleteFiles(List.of(mockedKey))).thenReturn(Set.of(mockedKey));
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
        // Cache keys are plain Strings: entityType.name() + '_' + entityId (portfolioCacheKey contract).
        verify(portfolioCache, times(1)).evictIfPresent(EntityType.SALON.name() + "_" + salonA);
        verify(portfolioCache, times(1)).evictIfPresent(EntityType.MASTER.name() + "_" + masterB);
        verify(portfolioCache, times(2)).evictIfPresent(any());
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

    // ------------------------------------------------------- Phase 268 D3 — deleteBySalon sweep

    @Test
    @DisplayName("case 6 — deletes every SALON portfolio row's R2 blob and the row itself")
    void should_deleteSalonPortfolioPhotos_when_deleteBySalonCalled() {
        UUID salonId = UUID.randomUUID();
        MediaFile p1 = MediaFile.builder().id(UUID.randomUUID())
                .entityType(EntityType.SALON).entityId(salonId)
                .mediaType(MediaType.PORTFOLIO).r2Key("p-1").r2Url("u-1").build();
        MediaFile p2 = MediaFile.builder().id(UUID.randomUUID())
                .entityType(EntityType.SALON).entityId(salonId)
                .mediaType(MediaType.PORTFOLIO).r2Key("p-2").r2Url("u-2").build();
        List<MediaFile> rows = List.of(p1, p2);

        service.deleteBySalon(salonId, null, null, rows);

        // Batched (Phase 268 perf follow-up): one deleteFiles(...) call carrying both keys.
        verify(r2).deleteFiles(List.of("p-1", "p-2"));
        verify(mediaRepo).deleteAll(rows);
    }

    @Test
    @DisplayName("case 7 — deletes BOTH the avatar and cover R2 blobs, even with zero portfolio rows "
            + "(must not short-circuit on rows.isEmpty())")
    void should_deleteAvatarAndCoverBlobs_when_deleteBySalonCalledWithNoPortfolioRows() {
        UUID salonId = UUID.randomUUID();
        String avatarUrl = "https://pub.r2.dev/portfolio/salons/" + salonId + "/avatar.jpg";
        String coverUrl = "https://pub.r2.dev/portfolio/salons/" + salonId + "/cover.jpg";
        when(r2.extractKeyFromPublicUrl(avatarUrl))
                .thenReturn(Optional.of("portfolio/salons/" + salonId + "/avatar.jpg"));
        when(r2.extractKeyFromPublicUrl(coverUrl))
                .thenReturn(Optional.of("portfolio/salons/" + salonId + "/cover.jpg"));

        service.deleteBySalon(salonId, avatarUrl, coverUrl, List.of());

        // Batched (Phase 268 perf follow-up): both extra keys in one deleteFiles(...) call.
        verify(r2).deleteFiles(List.of(
                "portfolio/salons/" + salonId + "/avatar.jpg",
                "portfolio/salons/" + salonId + "/cover.jpg"));
        // No portfolio rows to delete — the DB write tx must never be invoked for an empty list.
        verify(mediaRepo, never()).deleteAll(anyList());
    }

    @Test
    @DisplayName("case 9 — a foreign/malformed image URL yields NO deleteFile call for it (D2 safety "
            + "guard); the portfolio rows are still swept")
    void should_skipDelete_when_imageUrlLacksConfiguredPrefix() {
        UUID salonId = UUID.randomUUID();
        String foreignUrl = "https://evil.example.com/not-ours.jpg";
        when(r2.extractKeyFromPublicUrl(foreignUrl)).thenReturn(Optional.empty());
        MediaFile portfolio = MediaFile.builder().id(UUID.randomUUID())
                .entityType(EntityType.SALON).entityId(salonId)
                .mediaType(MediaType.PORTFOLIO).r2Key("p-1").r2Url("u-1").build();

        service.deleteBySalon(salonId, foreignUrl, null, List.of(portfolio));

        // The batched call never carries anything derived from the foreign URL — only the
        // legitimate portfolio row's key.
        verify(r2).deleteFiles(List.of("p-1"));
    }

    @Test
    @DisplayName("case 11 — one R2 delete failing (the avatar) does not abort the sweep: the cover "
            + "and the portfolio row are still deleted, and the DB batch delete still runs")
    void should_continueSweep_when_oneR2DeleteThrowsDuringDeleteBySalon() {
        UUID salonId = UUID.randomUUID();
        String avatarUrl = "https://pub.r2.dev/avatar.jpg";
        String coverUrl = "https://pub.r2.dev/cover.jpg";
        when(r2.extractKeyFromPublicUrl(avatarUrl)).thenReturn(Optional.of("avatar-key"));
        when(r2.extractKeyFromPublicUrl(coverUrl)).thenReturn(Optional.of("cover-key"));
        MediaFile portfolio = MediaFile.builder().id(UUID.randomUUID())
                .entityType(EntityType.SALON).entityId(salonId)
                .mediaType(MediaType.PORTFOLIO).r2Key("p-1").r2Url("u-1").build();
        // Batched (Phase 268 perf follow-up): one deleteFiles(...) call carrying all three keys;
        // R2 reports only "avatar-key" as a per-key failure in the returned set.
        when(r2.deleteFiles(List.of("avatar-key", "cover-key", "p-1"))).thenReturn(Set.of("avatar-key"));

        // No throw — the failing avatar delete must not abort the rest of the sweep.
        service.deleteBySalon(salonId, avatarUrl, coverUrl, List.of(portfolio));

        verify(r2).deleteFiles(List.of("avatar-key", "cover-key", "p-1"));
        verify(mediaRepo).deleteAll(List.of(portfolio));
    }

    @Test
    @DisplayName("case 12 — the sweep completes with NO thrown exception even when every single "
            + "R2 delete fails, so a salon deletion never rolls back on an R2 outage")
    void should_completeWithoutThrowing_when_everyR2DeleteFailsDuringDeleteBySalon() {
        UUID salonId = UUID.randomUUID();
        String avatarUrl = "https://pub.r2.dev/avatar.jpg";
        when(r2.extractKeyFromPublicUrl(avatarUrl)).thenReturn(Optional.of("avatar-key"));
        MediaFile portfolio = MediaFile.builder().id(UUID.randomUUID())
                .entityType(EntityType.SALON).entityId(salonId)
                .mediaType(MediaType.PORTFOLIO).r2Key("p-1").r2Url("u-1").build();
        // Batched (Phase 268 perf follow-up): r2.deleteFiles never throws for a delete failure —
        // it reports every requested key back as failed in the returned set (S3 DeleteObjects
        // semantics), which is exactly "every single R2 delete fails" in the new contract.
        when(r2.deleteFiles(List.of("avatar-key", "p-1"))).thenReturn(Set.of("avatar-key", "p-1"));

        assertThatCode(() -> service.deleteBySalon(salonId, avatarUrl, null, List.of(portfolio)))
                .as("R2 being entirely down must never propagate out of the sweep")
                .doesNotThrowAnyException();

        // The DB pointer's row is still dropped — D4's accepted-orphan policy.
        verify(mediaRepo).deleteAll(List.of(portfolio));
    }

    @Test
    @DisplayName("case 13 — WARN log omits the R2 key and contains '[key omitted]' when an avatar/cover "
            + "delete fails during deleteBySalon")
    void should_notLogR2Key_when_deleteBySalonAvatarDeleteFails() {
        UUID salonId = UUID.randomUUID();
        String avatarUrl = "https://pub.r2.dev/avatar.jpg";
        String rawKey = "portfolio/salons/" + salonId + "/avatar.jpg";
        when(r2.extractKeyFromPublicUrl(avatarUrl)).thenReturn(Optional.of(rawKey));
        // Batched (Phase 268 perf follow-up): R2 reports the key as failed in the returned set.
        when(r2.deleteFiles(List.of(rawKey))).thenReturn(Set.of(rawKey));
        listAppender.list.clear();

        service.deleteBySalon(salonId, avatarUrl, null, List.of());

        List<ILoggingEvent> warns = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).hasSize(1);
        String formattedMessage = warns.get(0).getFormattedMessage();
        assertThat(formattedMessage).contains("[key omitted]");
        assertThat(formattedMessage).doesNotContain(rawKey);
        assertThat(formattedMessage).doesNotContain(avatarUrl);
    }

    @Test
    @DisplayName("evicts the salon's portfolio cache entry after deleteBySalon, even when there were "
            + "zero portfolio rows to derive the entity from")
    void should_evictSalonPortfolioCache_when_deleteBySalonCalledWithNoRows() {
        UUID salonId = UUID.randomUUID();
        String avatarUrl = "https://pub.r2.dev/avatar.jpg";
        when(r2.extractKeyFromPublicUrl(avatarUrl)).thenReturn(Optional.of("avatar-key"));

        service.deleteBySalon(salonId, avatarUrl, null, List.of());

        verify(portfolioCache).evictIfPresent(EntityType.SALON.name() + "_" + salonId);
    }

    @Test
    @DisplayName("D4 pin, dedicated to deleteBySalon — R2 batch delete happens strictly BEFORE the "
            + "DB pointer drop, proven independently of the shared deleteByUploader InOrder assertion")
    void should_deleteR2BlobsBeforeDbRows_when_deleteBySalonCalled() {
        UUID salonId = UUID.randomUUID();
        String avatarUrl = "https://pub.r2.dev/avatar.jpg";
        String coverUrl = "https://pub.r2.dev/cover.jpg";
        when(r2.extractKeyFromPublicUrl(avatarUrl)).thenReturn(Optional.of("avatar-key"));
        when(r2.extractKeyFromPublicUrl(coverUrl)).thenReturn(Optional.of("cover-key"));
        MediaFile portfolio = MediaFile.builder().id(UUID.randomUUID())
                .entityType(EntityType.SALON).entityId(salonId)
                .mediaType(MediaType.PORTFOLIO).r2Key("p-1").r2Url("u-1").build();

        service.deleteBySalon(salonId, avatarUrl, coverUrl, List.of(portfolio));

        // D4: R2-first-then-DB. sweepBlobs is shared with deleteByUploader (pinned separately by
        // should_purgeR2Avatars_when_userIsDeletedBeforeCascade), but this pins the ordering by
        // name for the SALON path specifically, so a future divergence between the two callers is
        // caught even if one of them stops sharing sweepBlobs.
        InOrder inOrder = inOrder(r2, mediaRepo);
        inOrder.verify(r2).deleteFiles(List.of("avatar-key", "cover-key", "p-1"));
        inOrder.verify(mediaRepo).deleteAll(List.of(portfolio));
    }

    @Test
    @DisplayName("is a true no-op — no R2 delete, no DB call — when there is nothing to sweep at all")
    void should_doNothing_when_deleteBySalonCalledWithNothingToSweep() {
        UUID salonId = UUID.randomUUID();

        service.deleteBySalon(salonId, null, null, List.of());

        // extractKeyFromPublicUrl(null) is still called for both URL params (it handles null
        // gracefully and resolves no key) — the no-op guarantee is that NOTHING is ever handed to
        // R2, and the DB batch delete never runs.
        verify(r2, never()).deleteFile(anyString());
        verify(r2, never()).deleteFiles(anyList());
        verify(mediaRepo, never()).deleteAll(anyList());
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
