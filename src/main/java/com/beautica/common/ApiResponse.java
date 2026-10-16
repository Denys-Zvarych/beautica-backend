package com.beautica.common;

import com.beautica.common.web.RequestIdFilter;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Standard response envelope for every endpoint.
 *
 * @param success whether the request succeeded
 * @param data    the success payload (null on error responses)
 * @param message human-readable message (null on plain success responses)
 * @param errors  per-field validation errors as {@code field → message}. Present
 *                only on validation failures (HTTP 400 from
 *                {@code MethodArgumentNotValidException}); {@code null} — and
 *                therefore omitted from the JSON — for every other response. The
 *                mobile client reads this top-level {@code errors} key in
 *                {@code ErrorMapperInterceptor._extractFieldErrors} to surface
 *                inline field errors on the edit screen.
 * @param requestId the request's {@code X-Request-Id} (Phase 371, D5). Filled on error envelopes
 *                  ({@code success == false}) from the logging MDC set by {@link RequestIdFilter};
 *                  {@code null} — and therefore omitted from the JSON — on success envelopes and
 *                  outside a request. Additive field: no existing key changes.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,

        // Scoped to this component only — existing responses keep their exact wire
        // shape (data:null / message:null stay present). The errors map is omitted
        // entirely from the JSON unless a validation handler populates it, so no
        // non-validation response gains a spurious "errors": null key.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<String, String> errors,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        String requestId
) {

    public ApiResponse(boolean success, T data, String message) {
        this(success, data, message, null);
    }

    /** Every error envelope (including direct {@code new ApiResponse<>(false, …)} callers) carries the request ID. */
    public ApiResponse(boolean success, T data, String message, Map<String, String> errors) {
        this(success, data, message, errors, success ? null : MDC.get(RequestIdFilter.MDC_KEY));
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message);
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, null, message);
    }

    /**
     * Error envelope carrying a per-field validation map under the top-level
     * {@code errors} key. Used by the validation handlers so the mobile client
     * can render field-level errors while {@code message} stays a generic,
     * non-leaking sentinel.
     */
    public static ApiResponse<Void> validationError(String message, Map<String, String> errors) {
        return new ApiResponse<>(false, null, message, errors);
    }
}
