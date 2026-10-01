package com.beautica.notification.repository;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 339 audit cycle 2 (P1) — two concurrent upserts of the SAME token by the SAME user must both
 * succeed and leave exactly one row. Before V186, V29's {@code UNIQUE (user_id, token)} was a second
 * unique key that is not the {@code ON CONFLICT (token)} arbiter, so the racing insert could raise
 * {@code unique_violation} instead of resolving through the arbiter. Real committed transactions on
 * real connections (no {@code @DataJpaTest} single-connection rollback), released together by a barrier.
 */
class DeviceTokenConcurrentUpsertIT extends AbstractIntegrationTest {

    private static final int ROUNDS = 25;
    private static final int RACERS = 2;

    @Autowired
    private DeviceTokenRepository repo;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("concurrent same-user upserts of one token both succeed and leave exactly one active row")
    void should_leaveOneRow_when_sameUserUpsertsSameTokenConcurrently() throws Exception {
        User user = userRepository.save(new User(
                "concurrent-token-" + UUID.randomUUID() + "@example.com",
                new BCryptPasswordEncoder(4).encode("test-password"),
                Role.CLIENT, "Anna", "Kovalenko", "+380501111111"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String token = "concurrent-token-" + UUID.randomUUID();
                CyclicBarrier startTogether = new CyclicBarrier(RACERS);
                List<Future<Integer>> racers = new ArrayList<>();
                for (int i = 0; i < RACERS; i++) {
                    racers.add(pool.submit(() -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        return tx.execute(status -> repo.upsertToken(token, user.getId(), "ANDROID"));
                    }));
                }

                for (Future<Integer> racer : racers) {
                    assertThat(racer.get(30, TimeUnit.SECONDS)).as("upsert must write exactly one row").isEqualTo(1);
                }

                Integer rows = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM device_tokens WHERE token = ? AND user_id = ? AND is_active",
                        Integer.class, token, user.getId());
                assertThat(rows).as("one active row per token (round %d)", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("V186: the redundant (user_id, token) unique constraint is gone; user_id index and token-unique index exist")
    void should_haveDroppedRedundantUnique_and_keepSupportingIndexes() {
        Integer userTokenUniques = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                 WHERE c.conrelid = 'device_tokens'::regclass AND c.contype = 'u'
                """, Integer.class);
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'device_tokens'", String.class);

        assertThat(userTokenUniques).as("no unique CONSTRAINT left (token uniqueness is the V185 index)").isZero();
        assertThat(indexes).contains("idx_device_tokens_user_id", "ux_device_tokens_token");
    }
}
