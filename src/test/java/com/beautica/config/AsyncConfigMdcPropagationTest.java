package com.beautica.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AsyncConfig MDC TaskDecorator — Phase 371 D5")
class AsyncConfigMdcPropagationTest {

    private final TaskDecorator decorator = AsyncConfig.mdcPropagatingDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("captures the submitter's MDC at decoration time and exposes it to the task")
    void should_propagateSubmitterMdc_when_taskRuns() throws Exception {
        MDC.put("requestId", "req-12345678");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> seen.set(MDC.get("requestId")));
        MDC.clear();

        Thread worker = Thread.ofVirtual().start(decorated);
        worker.join();

        assertThat(seen.get()).isEqualTo("req-12345678");
    }

    @Test
    @DisplayName("restores the worker's own MDC afterwards (no leak into the next pooled task)")
    void should_restoreWorkerMdc_when_taskCompletes() {
        MDC.put("requestId", "submitter-0001");
        Runnable decorated = decorator.decorate(() -> { });
        MDC.put("requestId", "worker-00000001");

        decorated.run();

        assertThat(MDC.get("requestId")).isEqualTo("worker-00000001");
    }

    @Test
    @DisplayName("empty submitter MDC — task sees none and the worker MDC is cleared back")
    void should_runWithEmptyMdc_when_submitterHadNone() {
        Runnable decorated = decorator.decorate(() -> assertThat(MDC.get("requestId")).isNull());
        MDC.put("requestId", "stale-00000001");

        decorated.run();

        assertThat(MDC.get("requestId")).isEqualTo("stale-00000001");
    }

    @Test
    @DisplayName("Sentry scope — a pooled task runs in a scope forked from the request's; its tag never reaches the root")
    void should_forkSentryScope_when_taskRuns() throws Exception {
        io.sentry.Sentry.init(options -> {
            options.setDsn("http://k@localhost:1/1");
            options.setSendClientReports(false);
        });
        try {
            AtomicReference<String> seen = new AtomicReference<>();
            // Mirrors SentrySpringFilter: the request runs in its own forked scopes.
            try (io.sentry.ISentryLifecycleToken ignored =
                         io.sentry.Sentry.forkedScopes("test-request").makeCurrent()) {
                io.sentry.Sentry.configureScope(scope -> scope.setTag("request_id", "submitter-0001"));
                Runnable decorated = AsyncConfig.taskContextDecorator().decorate(() ->
                        io.sentry.Sentry.configureScope(scope -> {
                            seen.set(scope.getTags().get("request_id"));
                            scope.setTag("task_tag", "leak?");
                        }));
                Thread worker = Thread.ofVirtual().start(decorated);
                worker.join();
            }

            AtomicReference<String> leaked = new AtomicReference<>();
            Thread rootReader = Thread.ofVirtual().start(() ->
                    io.sentry.Sentry.configureScope(scope -> leaked.set(scope.getTags().get("task_tag"))));
            rootReader.join();
            assertThat(seen.get()).isEqualTo("submitter-0001");
            assertThat(leaked.get()).isNull();
        } finally {
            io.sentry.Sentry.close();
        }
    }
}
