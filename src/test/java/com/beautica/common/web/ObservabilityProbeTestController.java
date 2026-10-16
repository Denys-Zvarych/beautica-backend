package com.beautica.common.web;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test-only endpoints for {@code RequestIdIT} and {@code SentryCaptureIT} (Phase 371). Under
 * {@code /api/v1/internal/**}, so {@code InternalApiKeyFilter} still demands {@code X-Internal-Key}; the JWT
 * filter skips the prefix, so a Bearer header is carried without being parsed.
 */
@RestController
@RequestMapping(ObservabilityProbeTestController.BASE)
public class ObservabilityProbeTestController {

    static final String BASE = "/api/v1/internal/test-only/observability";
    static final String ASYNC_LOG_LINE = "observability-async-probe";

    private static final Logger log = LoggerFactory.getLogger(ObservabilityProbeTestController.class);

    private final TaskExecutor pushExecutor;

    public ObservabilityProbeTestController(@Qualifier("pushExecutor") TaskExecutor pushExecutor) {
        this.pushExecutor = pushExecutor;
    }

    @GetMapping("/ok")
    public ApiResponse<String> ok() {
        return ApiResponse.ok("ok");
    }

    @GetMapping("/forbidden")
    public ApiResponse<String> forbidden() {
        throw new ForbiddenException("Probe forbidden");
    }

    @GetMapping("/boom")
    public ApiResponse<String> boom() {
        throw new IllegalStateException("probe failure");
    }

    /** Message deliberately echoes the query phone, so the live scrubber must redact it. */
    @PostMapping("/boom")
    public ApiResponse<String> boomWithBody(@RequestParam("phone") String phone, @RequestBody Map<String, Object> body) {
        throw new IllegalStateException("probe failure for " + phone + " with " + body.size() + " field(s)");
    }

    /** Bean-validated body: a blank {@code name} trips {@code MethodArgumentNotValidException} → {@code ApiResponse.validationError}. */
    @PostMapping("/validated")
    public ApiResponse<String> validated(@Valid @RequestBody ValidatedProbe body) {
        return ApiResponse.ok(body.name());
    }

    public record ValidatedProbe(@NotBlank String name) { }

    /** Logs from a real pooled executor thread (MDC TaskDecorator under test) and waits for it. */
    @GetMapping("/async")
    public ApiResponse<String> async() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        pushExecutor.execute(() -> {
            log.info(ASYNC_LOG_LINE);
            done.complete(null);
        });
        done.get(10, TimeUnit.SECONDS);
        return ApiResponse.ok("ok");
    }
}
