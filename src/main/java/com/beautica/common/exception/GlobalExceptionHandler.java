package com.beautica.common.exception;

import com.beautica.auth.dto.EmailAlreadyRegisteredResponse;
import com.beautica.auth.dto.EmailNotVerifiedResponse;
import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(VerificationException.class)
    public ResponseEntity<ApiResponse<VerificationErrorResponse>> handleVerification(VerificationException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, new VerificationErrorResponse(ex.getCode().name()), "Verification failed"));
    }

    /**
     * Honest 409 for duplicate-email registration.
     *
     * <p>{@code AuthService} throws {@link EmailAlreadyRegisteredException} on every
     * duplicate-email registration in every profile. The previous anti-enumeration
     * silent-200 branch was removed because it created an undebuggable "we sent a
     * code, but it never comes" footgun; the per-IP rate limit on {@code /auth/*}
     * bounds the enumeration surface.
     *
     * <p>Must be declared BEFORE / alongside {@link #handleBusiness} so the structured
     * {@link EmailAlreadyRegisteredResponse} body (with the {@code EMAIL_ALREADY_REGISTERED}
     * code) is emitted instead of the generic conflict message.
     */
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiResponse<EmailAlreadyRegisteredResponse>> handleEmailAlreadyRegistered(
            EmailAlreadyRegisteredException ex) {
        log.debug("Registration rejected — {}", ex.getClass().getSimpleName());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(false,
                        new EmailAlreadyRegisteredResponse(EmailAlreadyRegisteredException.ERROR_CODE),
                        "Email already registered"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.debug("Business exception: {}", ex.getMessage());
        String clientMessage = switch (ex.getStatus()) {
            case CONFLICT -> "Request could not be completed due to a conflict";
            case BAD_REQUEST -> "Invalid request";
            // 422 carries deliberate, user-facing domain copy (e.g. the Ukrainian
            // cancel-window-closed message in Phase 13.4) that the client must display
            // verbatim. These messages are non-sensitive product strings — no enum
            // constants, SQL, IDs, or bound values — so echoing ex.getMessage() here is
            // safe and intended, unlike the genericised CONFLICT/BAD_REQUEST branches.
            case UNPROCESSABLE_ENTITY -> ex.getMessage();
            default -> "Request could not be completed";
        };
        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(clientMessage));
    }

    @ExceptionHandler(ResendThrottledException.class)
    public ResponseEntity<ApiResponse<Void>> handleResendThrottled(ResendThrottledException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiResponse.error("Please wait before requesting another code"));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        // Return a generic message — do not echo ex.getMessage() which may reveal internal
        // data model structure (e.g. "Salon not found for owner", "Master not found for user").
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Resource not found"));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        // Log the exception type only — never ex.getMessage() which may carry internal UUIDs
        // or user data if a future dev writes new ForbiddenException("Master " + userId + " ...").
        // getSimpleName() is always the constant "ForbiddenException"; no PII risk (Anti-Bug § I).
        log.debug("Forbidden access denied [{}]", ex.getClass().getSimpleName());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied"));
    }

    /**
     * Method-security denial (a {@code @PreAuthorize}/{@code @PostAuthorize} check failed),
     * surfaced by Spring's {@code AuthorizationManagerBeforeMethodInterceptor}. Without an
     * explicit WARN line here the only trace is Spring's opaque
     * {@code Resolved [AuthorizationDeniedException: Access Denied]}, which carries no path
     * or principal and makes 403s impossible to triage.
     *
     * <p>Logs HTTP method + request URI + the principal's authorities + the non-PII subject
     * (the user id held in {@link Authentication#getDetails()}, set by
     * {@code JwtAuthenticationFilter}). Never logs the email ({@code getName()}), the JWT,
     * or the query string — only the role/authorities, path, and user id (§I / Security rules).
     * The response body is unchanged: 403 for an authenticated caller, 401 for an
     * unauthenticated one.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(
            AuthorizationDeniedException ex, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isUnauthenticated = auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken;

        log.warn("Access denied: {} {} for authorities={} (subject={})",
                request.getMethod(),
                request.getRequestURI(),
                isUnauthenticated || auth == null ? "[none]" : auth.getAuthorities(),
                subjectOf(auth, isUnauthenticated));

        if (isUnauthenticated) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Authentication required"));
        }
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied"));
    }

    /**
     * Returns a non-PII principal identifier for diagnostic logging: the user id (UUID)
     * stored in {@link Authentication#getDetails()} by {@code JwtAuthenticationFilter}.
     * Never returns {@code auth.getName()} — that is the email (PII). Falls back to
     * {@code "[anonymous]"} / {@code "[unknown]"} rather than risk leaking anything else.
     */
    private static String subjectOf(Authentication auth, boolean isUnauthenticated) {
        if (isUnauthenticated || auth == null) {
            return "[anonymous]";
        }
        return auth.getDetails() instanceof UUID userId ? userId.toString() : "[unknown]";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        // Top-level message stays a generic sentinel; the per-field detail lives in the
        // `errors` map (field → message) so the mobile client can surface inline field
        // errors (ErrorMapperInterceptor._extractFieldErrors reads the top-level `errors`
        // key). Echoing the Bean Validation message for a known request-body field name is
        // safe: the DTO field names are part of the public API contract. The §A/§N leak
        // concern is enum constants, SQL fragments, and bound-value disclosure — those come
        // from HttpMessageNotReadableException / DataIntegrityViolationException, not from a
        // developer-authored @Size/@Pattern message on a declared field.
        var fieldErrors = ex.getBindingResult().getFieldErrors();
        log.debug("Validation failed: {}", fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; ")));

        Map<String, String> errors = fieldErrors.stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        // Two violations on the same field: keep the first, deterministic.
                        (first, second) -> first,
                        LinkedHashMap::new));

        String message = "Validation failed — check request parameters";
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.validationError(message, errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        // Detail logged at DEBUG only. Unlike @RequestBody validation, the property path here
        // includes the controller method + parameter name (e.g. "search.q") which is internal
        // API surface — so the `errors` map is keyed by the LEAF property name only (e.g. "q"),
        // and the bound values from ex.getMessage() are never echoed to the caller (§A/§N).
        log.debug("Constraint violation: {}", ex.getMessage());

        Map<String, String> errors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> leafPropertyName(v.getPropertyPath().toString()),
                        v -> v.getMessage() != null ? v.getMessage() : "Invalid value",
                        (first, second) -> first,
                        LinkedHashMap::new));

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.validationError("Validation failed — check request parameters", errors));
    }

    /**
     * Returns the last segment of a Bean Validation property path so the client sees the
     * caller-facing parameter name ({@code "q"}) rather than the internal
     * {@code methodName.paramName} path that {@link ConstraintViolationException} carries.
     */
    private static String leafPropertyName(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        // Single static message — distinguishing "duplicate" from "foreign key" in the
        // HTTP body is an enumeration oracle (reveals which constraint fired). The real
        // cause is logged at DEBUG only, never echoed to the caller (§I/§N).
        log.debug("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Request conflicts with existing data"));
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ApiResponse<Void>> handleJwt(JwtException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Invalid or expired token"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String safeMessage;

        if (cause instanceof InvalidFormatException ife
                && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {
            String rawName = ife.getPath().isEmpty() ? null
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            String fieldName = (rawName != null) ? rawName : "a field";
            safeMessage = "Invalid value for field '" + fieldName + "': not a recognised option";
        } else {
            safeMessage = "Request body is malformed or missing required fields";
        }

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(safeMessage));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        // Parameter name is not echoed — it discloses internal controller parameter names
        // on permitAll endpoints (Anti-Bug §I/§N).
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error("A required query parameter is missing"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error("Content-Type not supported. Use application/json"));
    }

    /**
     * Multipart upload endpoints (e.g. {@code POST /api/v1/media/avatar}) throw this
     * when the caller omits the required {@code file} part. Without an explicit mapping
     * the generic {@code Exception} fallback turns the missing-part error into a 500.
     * Closes the Anti-Bug Playbook § N gap for multipart endpoints.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        // Static message — the part name comes from server-side annotations today, but could
        // be attacker-influenced in future endpoints, so it is not interpolated anywhere
        // (neither the HTTP body nor the log). The client only needs to know to send
        // multipart/form-data with a 'file' field; the part name adds no diagnostic value (§I).
        log.debug("Missing required multipart request part");
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error("Required file part is missing"));
    }

    /**
     * Handles type-conversion failures for {@code @RequestParam} values.
     *
     * <p>When a caller passes a malformed value for a typed request parameter (e.g.
     * {@code ?filterMasterId=not-a-uuid}), Spring throws
     * {@link MethodArgumentTypeMismatchException} before the controller method is invoked.
     * Without this handler the exception falls through to {@link #handleGeneric}, producing
     * HTTP 500 and an "Unhandled exception" log entry. The correct status is 400.
     *
     * <p>{@link MethodArgumentTypeMismatchException#getName()} returns the parameter name
     * defined in the controller source (e.g. {@code "filterMasterId"}) — it is
     * framework-controlled, not user-supplied, so interpolating it into the response is safe.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName() != null ? ex.getName() : "parameter";
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error("Invalid value for parameter '" + paramName + "'"));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ApiResponse<EmailNotVerifiedResponse>> handleEmailNotVerified(
            EmailNotVerifiedException ex) {
        // No PII at any log level — log only the non-PII exception marker.
        // The email is still returned in the response body by design (the
        // authenticating account owner needs it to route to the verify screen).
        log.debug("Login rejected — {}", ex.getClass().getSimpleName());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ApiResponse<>(false,
                        new EmailNotVerifiedResponse("EMAIL_NOT_VERIFIED", ex.getEmail()),
                        "Email not verified"));
    }

    /**
     * Handles Spring MVC 6.x {@link NoResourceFoundException}, thrown when a request
     * targets a static resource path that does not exist (e.g. {@code /swagger-ui/index.html}
     * in production where Swagger is disabled). Without this handler the generic
     * {@link #handleGeneric} catch-all fires, logging at ERROR and returning HTTP 500.
     * The correct status is 404 and the log noise belongs at DEBUG.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("Static resource not found: {}", ex.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Resource not found"));
    }

    /**
     * Thrown by the multipart resolver when an uploaded request body exceeds the
     * configured {@code spring.servlet.multipart.max-*-size} (5 MB). Without this
     * mapping the generic {@code Exception} fallback turns an oversized upload into
     * a 500. The correct status is 413 Payload Too Large. The static message does
     * not echo the configured limit (no internal-config disclosure, §I).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.debug("Upload rejected — request body exceeds the multipart size limit");
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("Upload exceeds the maximum allowed size"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
