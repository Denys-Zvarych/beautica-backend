package com.beautica.common;

import com.beautica.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiResponse.requestId — Phase 371 D5 (additive)")
class ApiResponseRequestIdTest {

    private static final String ID = "req-12345678";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("error(...) and validationError(...) carry the MDC request ID")
    void should_fillRequestId_when_errorFactoriesUsedInsideARequest() {
        MDC.put(RequestIdFilter.MDC_KEY, ID);

        ApiResponse<Void> error = ApiResponse.error("Not found");
        ApiResponse<Void> validation = ApiResponse.validationError("Validation failed", Map.of("name", "required"));

        assertThat(error.requestId()).isEqualTo(ID);
        assertThat(validation.requestId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("direct new ApiResponse<>(false, data, message) — carries the request ID too")
    void should_fillRequestId_when_errorBuiltWithConstructor() {
        MDC.put(RequestIdFilter.MDC_KEY, ID);

        ApiResponse<String> error = new ApiResponse<>(false, "CODE", "Verification failed");

        assertThat(error.requestId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("ok(...) never carries the request ID, even inside a request")
    void should_leaveRequestIdNull_when_success() {
        MDC.put(RequestIdFilter.MDC_KEY, ID);

        assertThat(ApiResponse.ok("x").requestId()).isNull();
        assertThat(ApiResponse.ok("x", "done").requestId()).isNull();
    }

    @Test
    @DisplayName("JSON: requestId present on errors; absent (no null key) on success and outside a request")
    void should_serializeRequestIdOnlyWhenSet() throws Exception {
        String outside = objectMapper.writeValueAsString(ApiResponse.error("Nope"));
        MDC.put(RequestIdFilter.MDC_KEY, ID);
        String error = objectMapper.writeValueAsString(ApiResponse.error("Nope"));
        String ok = objectMapper.writeValueAsString(ApiResponse.ok("x"));

        assertThat(outside).isEqualTo("{\"success\":false,\"data\":null,\"message\":\"Nope\"}");
        assertThat(error).isEqualTo("{\"success\":false,\"data\":null,\"message\":\"Nope\",\"requestId\":\"" + ID + "\"}");
        assertThat(ok).isEqualTo("{\"success\":true,\"data\":\"x\",\"message\":null}");
    }
}
