package com.beautica.media.service;

import com.beautica.common.cache.MasterProfileCacheEvictor;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.repository.MediaRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.service.ServiceCatalogService;
import com.beautica.service.service.ServicePhotoBlobPurger;
import com.beautica.user.User;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 417 — pins the {@code MediaService#buildKey} invariant that {@code R2StorageService.IMMUTABLE_CACHE_CONTROL}
 * depends on: with a FIXED clock (same epoch-millis) and the SAME bytes uploaded twice through the public upload
 * API, the two keys still differ and keep the {@code <prefix><millis>-<uuid>.<ext>} shape.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MediaService — upload key uniqueness invariant (Phase 417)")
class MediaServiceKeyUniquenessTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);

    @Mock private R2StorageService r2;
    @Mock private MediaRepository mediaRepo;
    @Mock private UserRepository userRepo;
    @Mock private SalonRepository salonRepo;
    @Mock private MasterRepository masterRepo;
    @Mock private TransactionTemplate txRead;
    @Mock private TransactionTemplate txWrite;
    @Mock private CacheManager cacheManager;
    @Mock private ServiceRepository serviceRepo;
    @Mock private ServiceCatalogService serviceCatalogService;
    @Mock private AuthorizationService authz;
    @Mock private ServicePhotoBlobPurger servicePhotoBlobPurger;
    @Mock private UserProfileCacheEvictor userProfileCacheEvictor;
    @Mock private MasterProfileCacheEvictor masterProfileCacheEvictor;

    private MediaService service;

    @BeforeEach
    void setUp() {
        lenient().when(txRead.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().when(txWrite.execute(any())).thenAnswer(inv -> inSimulatedTransaction(inv.getArgument(0)));
        lenient().when(r2.isEnabled()).thenReturn(true);
        lenient().when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://media.example/" + inv.getArgument(0));
        service = new MediaService(r2, mediaRepo, userRepo, salonRepo, masterRepo, FIXED_CLOCK, txRead, txWrite,
                cacheManager, serviceRepo, serviceCatalogService, authz, servicePhotoBlobPurger,
                new AfterCommitBlobPurger(r2, new SyncTaskExecutor(), new SimpleMeterRegistry()),
                userProfileCacheEvictor, masterProfileCacheEvictor);
    }

    @Test
    @DisplayName("same bytes uploaded twice at the same instant → two distinct, well-formed keys")
    void should_generateDistinctWellFormedKeys_when_sameBytesUploadedTwiceAtSameInstant() {
        UUID userId = UUID.randomUUID();
        when(userRepo.existsById(userId)).thenReturn(true);
        when(userRepo.findByIdForUpdate(userId)).thenReturn(Optional.of(newUser(userId)));
        byte[] sameBytes = jpegBytes();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

        service.uploadAvatar(userId, new MockMultipartFile("file", "a.jpg", "image/jpeg", sameBytes));
        service.uploadAvatar(userId, new MockMultipartFile("file", "a.jpg", "image/jpeg", sameBytes));

        verify(r2, times(2)).uploadFile(keys.capture(), any(), anyLong(), anyString());
        List<String> uploaded = keys.getAllValues();
        assertThat(uploaded).hasSize(2).doesNotHaveDuplicates();
        Pattern shape = Pattern.compile(
                "^" + Pattern.quote("avatars/" + userId + "/") + "\\d+-[0-9a-f-]{36}\\.(jpg|png|webp)$");
        assertThat(uploaded).allSatisfy(key -> assertThat(key).matches(shape));
    }

    private static byte[] jpegBytes() {
        byte[] bytes = new byte[12];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static User newUser(UUID id) {
        try {
            var ctor = User.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            User u = ctor.newInstance();
            var idField = findField(User.class, "id");
            idField.setAccessible(true);
            idField.set(u, id);
            return u;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate User", e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** A real transaction boundary: synchronization active inside, afterCommit hooks fire on success. */
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
