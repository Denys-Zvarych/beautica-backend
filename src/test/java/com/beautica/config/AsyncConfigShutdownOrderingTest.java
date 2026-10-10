package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the SecurityContext-wrapped pools ({@code emailExecutor}, {@code supportEmailExecutor},
 * {@code pushExecutor}) are shut down by Spring on context close, and that {@code pushTaskPool} is drained
 * BEFORE the real {@link FirebaseConfig#destroy()} runs.
 *
 * <p>Ordering is guaranteed by {@code @DependsOn("firebaseConfig")} on {@code pushTaskPool}. The probe
 * makes its {@link FirebaseConfig} subclass {@code @Lazy} and creates it only after refresh, i.e. in the
 * creation order that would destroy Firebase FIRST were the dependency missing, so the test cannot pass
 * by accident.
 *
 * <p><b>What this pins.</b> (1) The {@code @DependsOn} ordering: {@code pushTaskPool} is a dependent bean
 * of {@code firebaseConfig} and so is destroyed (drained) before {@code FirebaseConfig.destroy()}.
 * (2) With the production config ({@code waitForTasksToCompleteOnShutdown=true}), the drain happens in the
 * pool's {@code destroy()} within {@code awaitTerminationSeconds}: the interrupt-ignoring tasks only finish
 * before Firebase is destroyed if the await is long enough, so an await of 0 turns this test red.
 *
 * <p><b>What this does NOT pin.</b> {@code waitForTasksToCompleteOnShutdown=false} is not guarded: on
 * {@code ContextClosedEvent} the executor's lifecycle stop() is a no-op (it only sets {@code lateShutdown},
 * deferring the real shutdown to {@code destroy()}), and with wait=false {@code destroy()} calls
 * {@code shutdownNow()}, whose interrupts these tasks deliberately ignore — so the tasks would still run to
 * completion and the ordering assertions would still hold.
 */
@DisplayName("AsyncConfig — wrapped pools drain on context close before FirebaseConfig.destroy()")
class AsyncConfigShutdownOrderingTest {

    static final String TASK_DONE = "push-task-done";
    static final String FIREBASE_DESTROY = "firebase-destroy";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Configuration("firebaseConfig")
    @Lazy
    static class ProbeFirebaseConfig extends FirebaseConfig {
        @Override
        public void destroy() {
            EVENTS.add(FIREBASE_DESTROY);
            super.destroy();
        }
    }

    @Configuration
    @Import(AsyncConfig.class)
    static class Probe {
    }

    @Test
    @DisplayName("interrupt-ignoring running + queued push tasks complete before FirebaseConfig.destroy; every pool terminated")
    void should_drainPushTaskBeforeFirebaseDestroy_when_contextClosed() throws Exception {
        EVENTS.clear();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("test");
        context.register(Probe.class);
        context.register(ProbeFirebaseConfig.class);
        context.refresh();
        // @Lazy + fetched only now: unless pushTaskPool @DependsOn("firebaseConfig") forces its creation
        // earlier, firebaseConfig is created AFTER pushTaskPool and so would be destroyed BEFORE it.
        context.getBean("firebaseConfig");
        TaskExecutor push = context.getBean("pushExecutor", TaskExecutor.class);
        assertThat(push).isInstanceOf(DelegatingSecurityContextTaskExecutor.class);
        assertThat(Arrays.asList(context.getBeanFactory().getDependentBeans("firebaseConfig")))
                .as("pushTaskPool must be a dependent of firebaseConfig so it is destroyed (drained) first")
                .contains("pushTaskPool");
        List<ThreadPoolTaskExecutor> pools = List.of(
                context.getBean("emailTaskPool", ThreadPoolTaskExecutor.class),
                context.getBean("supportEmailTaskPool", ThreadPoolTaskExecutor.class),
                context.getBean("pushTaskPool", ThreadPoolTaskExecutor.class));
        // Core size is 4: the first 4 tasks run, the rest queue. With wait=true the executor's lifecycle
        // stop() is a no-op (ContextClosedEvent only sets lateShutdown), so ALL draining — running tasks and
        // the queued tail — happens in the pool's destroy(), bounded by awaitTerminationSeconds.
        int running = 4;
        int total = 10;
        CountDownLatch started = new CountDownLatch(running);
        for (int i = 0; i < total; i++) {
            push.execute(() -> {
                started.countDown();
                long deadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
                while (System.nanoTime() < deadline) {
                    LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
                    Thread.interrupted(); // deliberately ignores interrupts (shutdownNow must not end it early)
                }
                EVENTS.add(TASK_DONE);
            });
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(EVENTS).as("tasks are still running when the context starts closing").isEmpty();

        context.close();

        assertThat(EVENTS)
                .as("every push task (running and queued) must complete before FirebaseConfig.destroy() runs")
                .hasSize(total + 1)
                .endsWith(FIREBASE_DESTROY)
                .containsOnly(TASK_DONE, FIREBASE_DESTROY);
        assertThat(pools)
                .as("every wrapped inner pool shut down by Spring")
                .allSatisfy(p -> assertThat(p.getThreadPoolExecutor().isTerminated()).isTrue());
    }
}
