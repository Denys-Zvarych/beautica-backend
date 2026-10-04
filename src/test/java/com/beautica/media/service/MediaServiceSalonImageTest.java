package com.beautica.media.service;

import com.beautica.common.cache.MasterProfileCacheEvictor;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.repository.MediaRepository;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.entity.SalonImagePointer;
import com.beautica.salon.entity.SalonImageSlot;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.service.ServiceCatalogService;
import com.beautica.service.service.ServicePhotoBlobPurger;
import com.beautica.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 343 TC-10 — {@link MediaService}'s salon logo/cover orchestration with every collaborator mocked:
 * storage gate, single-open sniff, superseded-key purge (stored key first, legacy URL fallback, own-slot prefix
 * only), commit-ack-safe discard of the new blob, and the idempotent empty-slot delete.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MediaService — salon logo/cover (Phase 343)")
class MediaServiceSalonImageTest {

    private static final String CDN = "https://cdn.example/";

    @Mock private R2StorageService r2;
    @Mock private SalonRepository salonRepo;
    @Mock private TransactionTemplate txRead;
    @Mock private TransactionTemplate txWrite;
    @Mock private SalonService salonService;

    private final UUID salonId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final SalonResponse body = mock(SalonResponse.class);
    private MediaService service;

    @BeforeEach
    void setUp() {
        lenient().when(txRead.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        lenient().when(txWrite.execute(any())).thenAnswer(inv -> inSimulatedTransaction(inv.getArgument(0)));
        lenient().when(r2.isEnabled()).thenReturn(true);
        lenient().when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        lenient().when(r2.deleteFiles(anyCollection())).thenReturn(java.util.Set.of());
        lenient().when(r2.extractKeyFromPublicUrl(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return url.startsWith(CDN) ? Optional.of(url.substring(CDN.length())) : Optional.empty();
        });
        service = new MediaService(r2, mock(MediaRepository.class), mock(UserRepository.class), salonRepo,
                mock(MasterRepository.class), Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC),
                txRead, txWrite, mock(CacheManager.class), mock(ServiceRepository.class),
                mock(ServiceCatalogService.class), mock(ServicePhotoBlobPurger.class),
                new AfterCommitBlobPurger(r2, new SyncTaskExecutor(), new SimpleMeterRegistry()),
                mock(UserProfileCacheEvictor.class), mock(MasterProfileCacheEvictor.class), salonService);
    }

    @Test
    @DisplayName("upload: new key under salons/<id>/<slot>/, stored superseded KEY purged after commit")
    void should_uploadUnderSlotPrefixAndPurgeStoredKey_when_logoReplaced() {
        String oldKey = "salons/" + salonId + "/logo/old.jpg";
        stubReplace(new SalonImagePointer(CDN + oldKey, oldKey));

        SalonResponse result = service.uploadSalonImage(ownerId, salonId, SalonImageSlot.LOGO, jpegFile());

        assertThat(result).isSameAs(body);
        ArgumentCaptor<String> newKey = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(newKey.capture(), any(), anyLong(), eq("image/jpeg"));
        assertThat(newKey.getValue()).startsWith("salons/" + salonId + "/logo/").endsWith(".jpg");
        verify(salonService).requireOwnedActiveSalon(ownerId, salonId);
        verify(r2).deleteFiles(List.of(oldKey));
    }

    @Test
    @DisplayName("D6: a legacy superseded URL (no key) is purged by its URL-derived key under the own slot prefix")
    void should_purgeUrlDerivedKey_when_supersededPointerIsLegacy() {
        String legacyKey = "salons/" + salonId + "/cover/legacy.jpg";
        stubReplace(new SalonImagePointer(CDN + legacyKey, null));

        service.uploadSalonImage(ownerId, salonId, SalonImageSlot.COVER, jpegFile());

        verify(r2).deleteFiles(List.of(legacyKey));
    }

    @Test
    @DisplayName("TC-10: a legacy superseded URL whose key fails the prefix check (other salon / other slot / "
            + "foreign host) is NOT purged")
    void should_notPurge_when_legacyKeyFailsPrefixCheck() {
        for (String url : List.of(
                CDN + "salons/" + UUID.randomUUID() + "/logo/x.jpg",
                CDN + "salons/" + salonId + "/cover/x.jpg",
                "https://evil.example/salons/" + salonId + "/logo/x.jpg")) {
            stubReplace(new SalonImagePointer(url, null));

            service.uploadSalonImage(ownerId, salonId, SalonImageSlot.LOGO, jpegFile());
        }

        verify(r2, never()).deleteFiles(anyCollection());
        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("TC-10: a commit failure discards the NEW blob when the row does not reference it")
    void should_discardNewBlob_when_lockedWriteFails() {
        when(salonService.replaceSalonImageLocked(any(), any(), any(), any(), any()))
                .thenThrow(new TransactionSystemException("commit failed"));
        Salon unchanged = Salon.builder().id(salonId).avatarR2Key("salons/" + salonId + "/logo/old.jpg").build();
        when(salonRepo.findById(salonId)).thenReturn(Optional.of(unchanged));

        assertThatThrownBy(() -> service.uploadSalonImage(ownerId, salonId, SalonImageSlot.LOGO, jpegFile()))
                .isInstanceOf(TransactionSystemException.class);

        ArgumentCaptor<String> newKey = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(newKey.capture(), any(), anyLong(), anyString());
        verify(r2).deleteFile(newKey.getValue());
    }

    @Test
    @DisplayName("commit-ack failure that DID commit (row references the new key) keeps the new blob")
    void should_keepNewBlob_when_failedWriteActuallyCommitted() {
        ArgumentCaptor<String> newKey = ArgumentCaptor.forClass(String.class);
        when(salonService.replaceSalonImageLocked(any(), any(), any(), any(), newKey.capture()))
                .thenThrow(new TransactionSystemException("ack lost"));
        when(salonRepo.findById(salonId)).thenAnswer(inv -> Optional.of(
                Salon.builder().id(salonId).coverR2Key(newKey.getValue()).build()));

        assertThatThrownBy(() -> service.uploadSalonImage(ownerId, salonId, SalonImageSlot.COVER, jpegFile()))
                .isInstanceOf(TransactionSystemException.class);

        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("TC-10: DELETE on an empty slot → no R2 call")
    void should_makeNoR2Call_when_deletingEmptySlot() {
        when(salonService.clearSalonImageLocked(ownerId, salonId, SalonImageSlot.COVER))
                .thenReturn(SalonImagePointer.EMPTY);

        service.deleteSalonImage(ownerId, salonId, SalonImageSlot.COVER);

        verify(r2, never()).deleteFiles(anyCollection());
        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("DELETE on a set slot purges the cleared stored key after commit")
    void should_purgeClearedKey_when_deletingSetSlot() {
        String key = "salons/" + salonId + "/cover/c.jpg";
        when(salonService.clearSalonImageLocked(ownerId, salonId, SalonImageSlot.COVER))
                .thenReturn(new SalonImagePointer(CDN + key, key));

        service.deleteSalonImage(ownerId, salonId, SalonImageSlot.COVER);

        verify(r2).deleteFiles(List.of(key));
    }

    @Test
    @DisplayName("storage off: POST and DELETE → 503 before ANY salon read or write (D7)")
    void should_return503BeforeAnyDbWork_when_storageDisabled() {
        when(r2.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.uploadSalonImage(ownerId, salonId, SalonImageSlot.LOGO, jpegFile()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> service.deleteSalonImage(ownerId, salonId, SalonImageSlot.LOGO))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(salonService, salonRepo, txWrite);
    }

    @Test
    @DisplayName("a read-gate refusal (403/404) stops the flow before the R2 upload")
    void should_notUpload_when_readGateRefuses() {
        doThrow(new com.beautica.common.exception.ForbiddenException("Access denied"))
                .when(salonService).requireOwnedActiveSalon(ownerId, salonId);

        assertThatThrownBy(() -> service.uploadSalonImage(ownerId, salonId, SalonImageSlot.LOGO, jpegFile()))
                .isInstanceOf(com.beautica.common.exception.ForbiddenException.class);

        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("D8: resolveSalonImageKey prefers the stored key and rejects a foreign one; URL is only the fallback")
    void should_preferStoredKey_when_resolvingSalonImageKey() {
        String key = "salons/" + salonId + "/logo/k.jpg";

        assertThat(service.resolveSalonImageKey(salonId, key, "https://elsewhere/x.jpg")).isEqualTo(key);
        assertThat(service.resolveSalonImageKey(salonId, "salons/" + UUID.randomUUID() + "/logo/k.jpg", CDN + key))
                .as("a foreign stored key is never replaced by the URL").isNull();
        assertThat(service.resolveSalonImageKey(salonId, null, CDN + key)).isEqualTo(key);
    }

    private void stubReplace(SalonImagePointer superseded) {
        when(salonService.replaceSalonImageLocked(eq(ownerId), eq(salonId), any(), anyString(), anyString()))
                .thenReturn(new SalonService.SalonImageWrite(body, superseded));
    }

    private static MockMultipartFile jpegFile() {
        byte[] bytes = new byte[12];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return new MockMultipartFile("file", "img.jpg", "image/jpeg", bytes);
    }

    private static Object inSimulatedTransaction(TransactionCallback<?> cb) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Object result = cb.doInTransaction(mock(TransactionStatus.class));
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            return result;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
