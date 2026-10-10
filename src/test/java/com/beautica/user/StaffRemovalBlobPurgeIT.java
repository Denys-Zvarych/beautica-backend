package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import com.beautica.common.exception.NotFoundException;
import com.beautica.media.entity.EntityType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.repository.UploaderMediaKey;
import com.beautica.media.service.MediaService;
import com.beautica.media.service.R2StorageService;
import com.beautica.salon.service.SalonService;
import com.beautica.salon.entity.Salon;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.beautica.support.R2DeleteLedger.purgedKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

/**
 * H1 — owner-initiated staff removal ({@code removeMaster}, {@code removeAdmin}, salon delete) must purge the
 * removed user's avatar and {@code media_files} blobs from R2 after commit; a control user's blobs must not be
 * touched, and the self-delete path (which registers its own sweep) must not double-purge.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Staff removal — R2 avatar + media purge (H1)")
class StaffRemovalBlobPurgeIT extends AbstractIntegrationTest {

    @Autowired private SalonService salonService;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private MediaService mediaService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private R2StorageService r2;

    /** Spy (real repository underneath) — used ONLY by the S-L2 race test to park the disposal mid-flight. */
    @SpyBean private MediaRepository mediaRepository;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        reset(r2);
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + Role.SALON_OWNER.name()))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("lockAvatarPointersByIdIn declares its 'users' query space: an unrelated dirty entity is NOT auto-flushed (P-L3)")
    void should_notAutoFlushUnrelatedDirtyEntity_when_lockingAvatarPointers() {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Boolean stillDirty = tx.execute(status -> {
            Salon managed = entityManager.find(Salon.class, salon.salonId());
            managed.setName(managed.getName() + " dirty");
            List<UserAvatarPointers> locked = userRepository.lockAvatarPointersByIdIn(List.of(salon.masterUserId()));
            boolean dirty = entityManager.unwrap(Session.class).isDirty();
            status.setRollbackOnly();
            return locked.size() == 1 && dirty;
        });

        assertThat(stillDirty).isTrue();
    }

    @Test
    @DisplayName("removeMaster purges the removed master's avatar + media keys, never a control user's")
    void should_purgeRemovedMasterBlobs_when_removeMaster() {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        Keys removed = seedBlobs(salon.masterUserId(), salon.salonId());
        UUID controlId = csd.createSalonAdmin(salon);
        Keys control = seedBlobs(controlId, salon.salonId());

        salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());

        assertThat(purgedKeys(r2)).contains(removed.avatar(), removed.media()).doesNotContain(control.avatar(), control.media());
    }

    @Test
    @DisplayName("removeAdmin purges the removed admin's avatar + media keys, never a control user's")
    void should_purgeRemovedAdminBlobs_when_removeAdmin() {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID adminId = csd.createSalonAdmin(salon);
        Keys removed = seedBlobs(adminId, salon.salonId());
        Keys control = seedBlobs(salon.masterUserId(), salon.salonId());

        salonService.removeAdmin(salon.ownerId(), salon.salonId(), adminId);

        assertThat(purgedKeys(r2)).contains(removed.avatar(), removed.media()).doesNotContain(control.avatar(), control.media());
    }

    @Test
    @DisplayName("salon delete (deleteSalonStaff cascade) purges every staff member's avatar + media keys, "
            + "never another salon's staff")
    void should_purgeAllStaffBlobs_when_salonDeleted() {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID adminId = csd.createSalonAdmin(salon);
        Keys master = seedBlobs(salon.masterUserId(), salon.salonId());
        Keys admin = seedBlobs(adminId, salon.salonId());
        ClientSelfDeleteTestFixtures.Salon other = csd.createSalon();
        Keys control = seedBlobs(other.masterUserId(), other.salonId());

        salonService.deactivateSalon(salon.ownerId(), salon.salonId());

        assertThat(purgedKeys(r2)).contains(master.avatar(), master.media(), admin.avatar(), admin.media())
                .doesNotContain(control.avatar(), control.media());
    }

    @Test
    @DisplayName("a staff self-delete purges the avatar exactly once — dispose() does not double-register")
    void should_purgeAvatarExactlyOnce_when_staffSelfDeletes() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID adminId = csd.createSalonAdmin(salon);
        Keys keys = seedBlobs(adminId, salon.salonId());
        String token = fixtures.tokenFor(emailOf(adminId));

        ResponseEntity<Void> response = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).filteredOn(keys.avatar()::equals).hasSize(1);
        assertThat(purgedKeys(r2)).filteredOn(keys.media()::equals).hasSize(1);
    }

    /**
     * S-L2 race: the removed master replaces their avatar while the owner's removeMaster is BETWEEN its avatar
     * pointer read and the users delete. The disposal is parked inside its media pre-read (which runs right
     * after the pointer read), the replace is released, and the disposal resumes once the replace has either
     * finished or is observably waiting on a row lock (pg_stat_activity — no sleeps). With the FOR UPDATE
     * pointer read the replace blocks, then finds no user and discards its NEW blob; without it the replace
     * commits and the disposal purges only the stale old key, orphaning the new blob.
     */
    @Test
    @DisplayName("S-L2: an avatar replace racing removeMaster never orphans the new blob (row-locked pointer read)")
    void should_purgeOrDiscardNewAvatar_when_replaceRacesRemoveMaster() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        Keys old = seedBlobs(salon.masterUserId(), salon.salonId());
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        List<String> uploaded = new CopyOnWriteArrayList<>();
        doAnswer(inv -> uploaded.add(inv.getArgument(0))).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        CountDownLatch disposalParked = new CountDownLatch(1);
        CountDownLatch resumeDisposal = new CountDownLatch(1);
        String disposalThread = "race-disposal";
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals(disposalThread)) {
                disposalParked.countDown();
                assertThat(resumeDisposal.await(60, TimeUnit.SECONDS)).isTrue();
            }
            // Spied Spring Data proxies cannot callRealMethod(); answer with the same projection via JDBC.
            return jdbcTemplate.query("SELECT uploader_id, r2_key, entity_type, entity_id FROM media_files "
                            + "WHERE uploader_id = ?",
                    (rs, i) -> new UploaderMediaKey(rs.getObject(1, UUID.class), rs.getString(2),
                            EntityType.valueOf(rs.getString(3)), rs.getObject(4, UUID.class)),
                    salon.masterUserId());
        }).when(mediaRepository).findMediaKeysByUploaderIdIn(anyCollection());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "race-pool"));
        Future<?> disposal;
        Future<?> replace;
        try {
            disposal = pool.submit(() -> {
                Thread.currentThread().setName(disposalThread);
                SecurityContextHolder.getContext().setAuthentication(auth);
                salonService.removeMaster(salon.ownerId(), salon.salonId(), salon.masterId());
                return null;
            });
            assertThat(disposalParked.await(60, TimeUnit.SECONDS)).isTrue();
            replace = pool.submit(() -> mediaService.uploadAvatar(salon.masterUserId(), jpeg()));
            awaitDoneOrLockWaiting(replace);
            resumeDisposal.countDown();
            disposal.get(60, TimeUnit.SECONDS);
        } finally {
            resumeDisposal.countDown();
            pool.shutdown();
        }
        Throwable replaceOutcome = outcomeOf(replace);

        assertThat(uploaded).hasSize(1);
        String newKey = uploaded.get(0);
        assertThat(purgedKeys(r2)).as("the superseded AND the new avatar blob are both deleted — nothing orphaned")
                .contains(old.avatar(), newKey);
        assertThat(replaceOutcome).as("the replace blocked on the row lock and then found the account gone")
                .isInstanceOf(NotFoundException.class);
    }

    /** Returns once {@code task} finished or any backend is waiting on a lock; bounded, no sleeps. */
    private void awaitDoneOrLockWaiting(Future<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (task.isDone() || lockWaiters() > 0) {
                return;
            }
            try {
                task.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException ignored) {
                // poll again — the bounded get() is the wait, not a sleep
            }
        }
        throw new AssertionError("replace neither finished nor waited on a lock within 60s");
    }

    private int lockWaiters() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Integer.class);
        return n == null ? 0 : n;
    }

    private static Throwable outcomeOf(Future<?> task) throws InterruptedException, TimeoutException {
        try {
            task.get(60, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
    }

    private static MockMultipartFile jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return new MockMultipartFile("file", "a.jpg", "image/jpeg", bytes);
    }

    private record Keys(String avatar, String media) {}

    private Keys seedBlobs(UUID userId, UUID salonId) {
        String avatar = "avatars/" + userId + "/a-" + UUID.randomUUID() + ".jpg";
        String media = "portfolio/salons/" + salonId + "/m-" + UUID.randomUUID() + ".jpg";
        jdbcTemplate.update("UPDATE users SET avatar_r2_key = ?, avatar_url = ? WHERE id = ?",
                avatar, "https://cdn.example/" + avatar, userId);
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                        + "created_at, updated_at) VALUES (?, ?, 'SALON', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                UUID.randomUUID(), userId, salonId, media, "https://cdn.example/" + media);
        return new Keys(avatar, media);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
