package com.beautica.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for {@link AsyncConfig}'s {@link
 * org.springframework.security.task.DelegatingSecurityContextTaskExecutor} wrapping of
 * the {@code emailExecutor} and {@code pushExecutor} beans.
 *
 * <p>Asserts that the calling thread's {@link SecurityContextHolder} contents — specifically
 * the {@link Authentication} — propagate to async task threads. Without this propagation any
 * {@code @Async} method reading {@code SecurityContextHolder.getContext().getAuthentication()}
 * would observe {@code null}, silently breaking actor identity for downstream emails and
 * push notifications.
 *
 * <p>This test exists as a regression guard: a future "simplification" PR that unwraps the
 * {@code DelegatingSecurityContextTaskExecutor} (or replaces the bean type with
 * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor}) loses propagation
 * silently — this test fails fast in that scenario.
 *
 * <p><strong>Why two test methods rather than {@code @MethodSource}.</strong> Static
 * {@code @MethodSource} methods cannot access {@code @Autowired} beans, so we use two
 * near-identical test methods, one per executor. Each follows the same arrange/act/assert
 * shape with a {@link CountDownLatch} 5-second timeout to fail fast when propagation breaks.
 *
 * <p><strong>Why {@code @AfterEach SecurityContextHolder.clearContext()}.</strong> The
 * pre-set {@link Authentication} on the calling thread must not leak into other tests that
 * share the same JVM thread (JUnit reuses the same caller thread by default).
 */
@SpringBootTest(
        classes = AsyncConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@DisplayName("AsyncConfig — SecurityContext propagation through wrapped executors")
class AsyncConfigSecurityContextPropagationTest {

    private static final String TEST_PRINCIPAL = "test-user";
    private static final long PROPAGATION_TIMEOUT_SECONDS = 5L;

    @Autowired
    @Qualifier("emailExecutor")
    private TaskExecutor emailExecutor;

    @Autowired
    @Qualifier("pushExecutor")
    private TaskExecutor pushExecutor;

    @AfterEach
    void clearContext() {
        // Prevent the pre-set Authentication from leaking into other tests on the same thread.
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("emailExecutor propagates SecurityContext to async task thread")
    void should_propagateSecurityContext_when_taskSubmittedThroughEmailExecutor() throws Exception {
        assertExecutorPropagatesSecurityContext("emailExecutor", emailExecutor);
    }

    @Test
    @DisplayName("pushExecutor propagates SecurityContext to async task thread")
    void should_propagateSecurityContext_when_taskSubmittedThroughPushExecutor() throws Exception {
        assertExecutorPropagatesSecurityContext("pushExecutor", pushExecutor);
    }

    /**
     * Submits a context-capturing task through {@code executor} and asserts that the worker
     * thread sees the {@link Authentication} pre-set on the calling thread.
     *
     * <p>The {@link CountDownLatch} 5-second timeout fails fast on broken propagation — without
     * a timeout a missing wrapper would simply leave {@code capturedRef} as {@code null} forever.
     *
     * @param name     human-readable executor name (used in failure diagnostics via {@code .as(...)})
     * @param executor the executor under test
     */
    private void assertExecutorPropagatesSecurityContext(String name, TaskExecutor executor)
            throws InterruptedException {
        // Arrange — pre-set authentication on calling thread.
        Authentication auth = new UsernamePasswordAuthenticationToken(TEST_PRINCIPAL, "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Act — submit a task that captures auth from the worker thread.
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Authentication> capturedRef = new AtomicReference<>();
        executor.execute(() -> {
            capturedRef.set(SecurityContextHolder.getContext().getAuthentication());
            done.countDown();
        });

        // Assert — task completed within timeout AND the worker thread observed the same Authentication.
        assertThat(done.await(PROPAGATION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("executor %s task did not complete within %ds", name, PROPAGATION_TIMEOUT_SECONDS)
                .isTrue();
        assertThat(capturedRef.get())
                .as("executor %s did not propagate SecurityContext to worker thread", name)
                .isNotNull()
                .extracting(Authentication::getName)
                .isEqualTo(TEST_PRINCIPAL);
    }
}
