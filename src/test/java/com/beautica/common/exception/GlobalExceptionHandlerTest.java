package com.beautica.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.auth.Role;
import com.beautica.auth.dto.EmailAlreadyRegisteredResponse;
import com.beautica.auth.dto.EmailNotVerifiedResponse;
import com.beautica.booking.dto.ClientBookingConflictResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.ApiResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GlobalExceptionHandler — unit")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── Logback ListAppender wiring ───────────────────────────────────────────
    // Attached to the GlobalExceptionHandler logger so tests can assert
    // that DEBUG-level messages are emitted without leaking to the client.

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachListAppender() {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        // Force DEBUG so the appender receives events regardless of the active log profile.
        // CI runs with -PtestLogLevel=INFO which would suppress debug() calls otherwise.
        handlerLogger.setLevel(Level.DEBUG);
        listAppender = new ListAppender<>();
        listAppender.start();
        handlerLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLogger.detachAppender(listAppender);
        handlerLogger.setLevel(null);
        listAppender.stop();
        // handleAuthorizationDenied reads SecurityContextHolder — always clear it so a
        // populated context never bleeds into the next test (isolation, no order coupling).
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should return safe message when enum error occurs on array element")
    void should_returnSafeMessage_when_enumErrorOnArrayElement() {
        // Arrange — build an InvalidFormatException whose path contains an index-based
        // Reference (getFieldName() == null), simulating an error at e.g. roles[0].
        InvalidFormatException ife = new InvalidFormatException(
                "Cannot deserialise value",
                "INVALID_ENUM_VALUE",
                SelfRegistrationRoleStub.class
        );
        // Index-based reference: getFieldName() returns null for these.
        ife.prependPath(new Object(), 0);

        @SuppressWarnings("deprecation")
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", ife
        );

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleMessageNotReadable(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        String message = response.getBody().message();

        assertThat(message)
                .as("message must not contain the string 'null' as a field name")
                .doesNotContain("'null'");

        assertThat(message)
                .as("message must use the fallback field name 'a field'")
                .contains("a field");

        assertThat(message)
                .as("message must use the exact safe wording")
                .isEqualTo("Invalid value for field 'a field': not a recognised option");
    }

    @Test
    @DisplayName("Should not leak exception message when unhandled exception is thrown")
    void should_notLeakExceptionMessage_when_unhandledExceptionThrown() {
        // Arrange
        var ex = new RuntimeException("internal DB password is abc123");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleGeneric(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 500 for an unhandled exception")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        String message = response.getBody().message();

        assertThat(message)
                .as("response must not leak the sensitive token 'abc123'")
                .doesNotContain("abc123");

        assertThat(message)
                .as("response must not leak the phrase 'DB password'")
                .doesNotContain("DB password");

        assertThat(message)
                .as("response must be exactly the generic safe message")
                .isEqualTo("An unexpected error occurred");
    }

    @Test
    @DisplayName("Should return 404 with generic message when NotFoundException is thrown")
    void should_return404_when_notFoundExceptionThrown() {
        // Arrange — internal message "Master not found" must NOT be echoed in the response
        // (it leaks internal data model structure e.g. "Salon not found for owner").
        var ex = new NotFoundException("Master not found");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleNotFound(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 404")
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the generic safe string — never ex.getMessage()")
                .isEqualTo("Resource not found");

        assertThat(response.getBody().message())
                .as("message must NOT leak the internal exception text")
                .doesNotContain("Master not found");
    }

    @Test
    @DisplayName("Should return 403 with generic message when ForbiddenException is thrown")
    void should_return403_when_forbiddenExceptionThrown() {
        // Arrange — use an internal message that must NOT be echoed to the client
        var ex = new ForbiddenException("Access denied");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleForbidden(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("response body must be the generic 'Access denied' string")
                .isEqualTo("Access denied");
    }

    @Test
    @DisplayName("Should not leak internal UUID when ForbiddenException carries master UUID")
    void should_notLeakUuid_when_forbiddenExceptionContainsMasterUuid() {
        // Arrange — simulates ForbiddenException("Master " + uuid + " does not own ...") pattern
        var internalUuid = "550e8400-e29b-41d4-a716-446655440000";
        var ex = new ForbiddenException("Master " + internalUuid + " does not own this booking");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleForbidden(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(response.getBody().message())
                .as("response must not leak the UUID from the internal exception message")
                .doesNotContain(internalUuid)
                .isEqualTo("Access denied");
    }

    @Test
    @DisplayName("handleForbidden — emits static DEBUG marker without leaking ex.getMessage() PII")
    void should_emitDebugLog_when_forbiddenExceptionThrown() {
        // Arrange — the message intentionally contains PII-like content to verify it is NEVER logged.
        // Anti-Bug Playbook § I: a future dev may write new ForbiddenException("Master " + uuid + "...")
        // so the log line must use the static class name, not ex.getMessage().
        String piiMessage = "Master 550e8400-e29b-41d4-a716-446655440000 does not own salon abc";
        var ex = new ForbiddenException(piiMessage);
        listAppender.list.clear();

        // Act
        handler.handleForbidden(ex);

        // Assert — exactly one DEBUG event was emitted
        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleForbidden must emit exactly one DEBUG log for server-side triage")
                .hasSize(1);

        String logLine = debugEvents.get(0).getFormattedMessage();
        // Static marker — class name only, never the message content
        assertThat(logLine)
                .as("DEBUG log must contain the static class-name marker")
                .contains("ForbiddenException");
        // PII guard — message text must not appear in the log (Anti-Bug § I)
        assertThat(logLine)
                .as("DEBUG log must NOT echo ex.getMessage() which may carry PII")
                .doesNotContain(piiMessage);
    }

    // ── AuthorizationDeniedException — enriched 403 WARN (method-security path) ────
    // Regression guard for the access-denied logging enrichment: when a wrong-role
    // principal is denied, the WARN line MUST carry the HTTP method + request URI +
    // authorities (so a future 403 is self-diagnosing) and the non-PII subject (the
    // user id from Authentication.getDetails()), while the 403/401 body stays unchanged.
    //
    // Against the PRE-enrichment code (which logged nothing — Spring's opaque
    // "Resolved [AuthorizationDeniedException: Access Denied]" carried no path/principal)
    // every "log must contain method/uri/authorities" assertion below FAILS, because no
    // WARN event is emitted by the handler at all. That is the regression these pin.

    /** A wrong-role authentication: CLIENT principal carrying the user id in getDetails(). */
    private static UsernamePasswordAuthenticationToken clientAuth(UUID userId, String email) {
        var auth = new UsernamePasswordAuthenticationToken(
                email, "N/A", List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
        auth.setDetails(userId); // mirrors JwtAuthenticationFilter.setDetails(userId)
        return auth;
    }

    private static AuthorizationDeniedException accessDenied() {
        return new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));
    }

    @Test
    @DisplayName("handleAuthorizationDenied — 403 unchanged body + WARN carries method, URI, authorities, non-PII subject")
    void should_logEnrichedWarnAndReturn403_when_clientDeniedByMethodSecurity() {
        // Arrange — a CLIENT principal denied at a SALON_MASTER/INDEPENDENT_MASTER-only
        // endpoint (e.g. GET /api/v1/masters/me). The email is PII and must NEVER be logged;
        // the user id (getDetails()) is the non-PII subject that MUST be logged.
        UUID userId = UUID.randomUUID();
        String email = "pii-canary-deny-9c1f@beautica.test";
        SecurityContextHolder.getContext().setAuthentication(clientAuth(userId, email));

        var request = new MockHttpServletRequest("GET", "/api/v1/masters/me");
        request.setQueryString("secretToken=leak-me"); // query string must NOT be logged
        listAppender.list.clear();

        // Act
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAuthorizationDenied(accessDenied(), request);

        // Assert — HTTP contract unchanged: 403 with the generic "Access denied" body
        assertThat(response.getStatusCode())
                .as("an authenticated wrong-role caller must get 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();
        assertThat(response.getBody().message())
                .as("body must remain the generic 'Access denied' string")
                .isEqualTo("Access denied");

        // Assert — exactly one WARN event was emitted (pre-enrichment: zero → FAILS here)
        List<ILoggingEvent> warnEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents)
                .as("handleAuthorizationDenied must emit exactly one enriched WARN for triage")
                .hasSize(1);

        String logLine = warnEvents.get(0).getFormattedMessage();
        // The three diagnostic anchors that make a 403 self-diagnosing.
        assertThat(logLine)
                .as("WARN must carry the HTTP method so the denied request is identifiable")
                .contains("GET");
        assertThat(logLine)
                .as("WARN must carry the request URI so the denied endpoint is identifiable")
                .contains("/api/v1/masters/me");
        assertThat(logLine)
                .as("WARN must carry the principal's authorities so the missing role is obvious")
                .contains("ROLE_CLIENT");
        // Non-PII subject present (the user id), PII (email) absent.
        assertThat(logLine)
                .as("WARN must carry the non-PII subject (user id from getDetails())")
                .contains(userId.toString());

        // PII / secret guard — neither the email nor the query string may appear anywhere.
        assertThat(logLine)
                .as("WARN must NOT log the email (PII) — getName() is the email")
                .doesNotContain(email);
        assertThat(logLine)
                .as("WARN must NOT log the query string (may carry tokens/secrets)")
                .doesNotContain("secretToken")
                .doesNotContain("leak-me");

        // No JWT-shaped substring may leak into the log (three dot-separated base64 segments).
        boolean jwtShaped = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(".*[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}.*"));
        assertThat(jwtShaped)
                .as("no log event may contain a JWT-shaped token")
                .isFalse();
    }

    @Test
    @DisplayName("handleAuthorizationDenied — 401 + WARN authorities=[none], subject=[anonymous] for an unauthenticated caller")
    void should_logEnrichedWarnAndReturn401_when_unauthenticatedCallerDenied() {
        // Arrange — an anonymous principal (filter-chain / pre-auth denial). The body must
        // be 401 "Authentication required" (NOT 403), and the WARN must still carry the
        // method + URI but redact authorities/subject to safe placeholders.
        var anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        var request = new MockHttpServletRequest("DELETE", "/api/v1/masters/me");
        listAppender.list.clear();

        // Act
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAuthorizationDenied(accessDenied(), request);

        // Assert — unauthenticated callers get 401, not 403
        assertThat(response.getStatusCode())
                .as("an unauthenticated caller must get 401, not 403")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message())
                .as("401 body must be the generic 'Authentication required' string")
                .isEqualTo("Authentication required");

        // Assert — WARN still carries method + URI, with redacted principal fields
        List<ILoggingEvent> warnEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents)
                .as("an unauthenticated denial must still emit exactly one WARN for triage")
                .hasSize(1);

        String logLine = warnEvents.get(0).getFormattedMessage();
        assertThat(logLine)
                .as("WARN must carry the HTTP method even for anonymous denials")
                .contains("DELETE");
        assertThat(logLine)
                .as("WARN must carry the request URI even for anonymous denials")
                .contains("/api/v1/masters/me");
        assertThat(logLine)
                .as("authorities must be redacted to [none] for an unauthenticated caller")
                .contains("[none]");
        assertThat(logLine)
                .as("subject must be redacted to [anonymous] — the real ROLE_ANONYMOUS authority must not leak")
                .contains("[anonymous]")
                .doesNotContain("ROLE_ANONYMOUS");
    }

    @Test
    @DisplayName("Should return 409 with generic message and NOT echo internal message when CONFLICT BusinessException")
    void should_return409_when_conflictExceptionThrown() {
        // Arrange — internal message must NOT appear in the response body (Finding 1 fix)
        var ex = new BusinessException(HttpStatus.CONFLICT, "User already holds a master profile of a different type");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 409")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("response must use the generic conflict message — never echo internal state")
                .isEqualTo("Request could not be completed due to a conflict");

        assertThat(response.getBody().message())
                .as("internal master-type detail must not reach the caller")
                .doesNotContain("master profile");
    }

    @Test
    @DisplayName("Should return 400 with generic message and NOT echo internal message when BAD_REQUEST BusinessException")
    void should_return400_with_genericMessage_when_badRequestBusinessException() {
        // Arrange
        var ex = new BusinessException(HttpStatus.BAD_REQUEST, "Salon is not active");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().message())
                .as("message must be the generic 'Invalid request' string")
                .isEqualTo("Invalid request");

        assertThat(response.getBody().message())
                .as("internal salon-state detail must not reach the caller")
                .doesNotContain("Salon is not active");
    }

    @Test
    @DisplayName("Should return 422 and SURFACE the user-facing message when UNPROCESSABLE_ENTITY BusinessException")
    void should_return422_with_message_when_unprocessableEntityBusinessException() {
        // Arrange — 422 carries deliberate, user-facing domain copy (Phase 13.4 cancel
        // window). Unlike CONFLICT/BAD_REQUEST, this message must reach the client verbatim.
        var ex = new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Скасування недоступне — менше ніж 2 год до запису");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 422")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(response.getBody().message())
                .as("422 user-facing copy must be surfaced verbatim")
                .isEqualTo("Скасування недоступне — менше ніж 2 год до запису");
    }

    @Test
    @DisplayName("handleBusiness — internal message is emitted at DEBUG level for server-side triage")
    void should_emitDebugLog_when_businessExceptionThrown() {
        // Arrange
        String internalMessage = "Owner master profile already exists in a different salon";
        var ex = new ConflictException(internalMessage);
        listAppender.list.clear();

        // Act
        handler.handleBusiness(ex);

        // Assert — exactly one DEBUG event containing the internal message
        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleBusiness must emit exactly one DEBUG log for server-side triage")
                .hasSize(1);
        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must contain the original internal message")
                .contains(internalMessage);
    }

    @Test
    @DisplayName("handleDataIntegrityViolation — returns 409 with static message and never leaks the DB constraint cause")
    void should_return409StaticMessage_when_dataIntegrityViolationThrown() {
        // Arrange — a DataIntegrityViolationException whose most-specific cause carries a
        // leaky DB constraint name (the kind Postgres emits: "duplicate key value violates
        // unique constraint \"uq_users_email\""). The handler must collapse every variant —
        // unique violation, FK violation, NOT-NULL violation — to one static body so the
        // client cannot use the message as an enumeration oracle (which constraint fired),
        // and the constraint/column/value detail must never reach the caller (§I/§N).
        String leakyCause = "duplicate key value violates unique constraint \"uq_users_email\"";
        var ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException(leakyCause));

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(ex);

        // Assert — HTTP contract
        assertThat(response.getStatusCode())
                .as("data-integrity violation must map to 409")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the single static conflict string")
                .isEqualTo("Request conflicts with existing data");

        // Assert — no leak of the underlying constraint detail in the response body
        assertThat(response.getBody().message())
                .as("response must NOT leak the constraint name 'uq_users_email'")
                .doesNotContain("uq_users_email");

        assertThat(response.getBody().message())
                .as("response must NOT leak the raw DB error phrase")
                .doesNotContain("duplicate key")
                .doesNotContain("unique constraint");
    }

    @Test
    @DisplayName("handleDataIntegrityViolation — emits the real cause only at DEBUG, never in the response")
    void should_logCauseAtDebugOnly_when_dataIntegrityViolationThrown() {
        // Arrange — the leaky cause must surface in the DEBUG log for ops triage but never
        // in the client body. This pins the §I/§N split: detail for the server, silence for
        // the caller.
        String leakyCause = "insert or update on table \"bookings\" violates foreign key "
                + "constraint \"fk_bookings_master\"";
        var ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException(leakyCause));
        listAppender.list.clear();

        // Act
        handler.handleDataIntegrityViolation(ex);

        // Assert — exactly one DEBUG event carrying the real cause for server-side triage
        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleDataIntegrityViolation must emit exactly one DEBUG log for triage")
                .hasSize(1);
        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must contain the real cause for ops visibility")
                .contains("fk_bookings_master");

        // And no event at any level may have leaked the cause anywhere other than DEBUG —
        // i.e. there is no ERROR/WARN/INFO event carrying it (would imply over-logging).
        boolean nonDebugLeak = listAppender.list.stream()
                .filter(e -> e.getLevel() != Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("fk_bookings_master"));
        assertThat(nonDebugLeak)
                .as("the cause must appear ONLY at DEBUG, not at any louder level")
                .isFalse();
    }

    @Test
    @DisplayName("Should return 400 with non-blank message when @Valid constraint violation occurs")
    void should_return400_with_nonBlankMessage_when_validationFails() throws NoSuchMethodException {
        // Arrange — build a MethodArgumentNotValidException with one field error
        MethodParameter param = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod", String.class), 0);
        var bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "email", "must not be blank"));
        var ex = new MethodArgumentNotValidException(param, bindingResult);

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the generic sentinel — constraint text must not be echoed to clients")
                .isEqualTo("Validation failed — check request parameters");
    }

    @Test
    @DisplayName("handleValidation — populates top-level errors map (field → message) for the mobile client")
    void should_populateErrorsMap_when_validationFails() throws NoSuchMethodException {
        // Arrange — two field errors so the map shape is exercised; the mobile
        // ErrorMapperInterceptor reads this top-level `errors` key to render inline errors.
        MethodParameter param = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod", String.class), 0);
        var bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "instagram",
                "Instagram must be a handle (e.g. @username) or a full instagram.com URL"));
        bindingResult.addError(new FieldError("target", "bio", "Bio must not exceed 2000 characters"));
        var ex = new MethodArgumentNotValidException(param, bindingResult);

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        // Assert
        assertThat(response.getBody().errors())
                .as("errors map must carry the per-field messages keyed by field name")
                .containsEntry("instagram",
                        "Instagram must be a handle (e.g. @username) or a full instagram.com URL")
                .containsEntry("bio", "Bio must not exceed 2000 characters");

        assertThat(response.getBody().message())
                .as("top-level message must remain the generic sentinel")
                .isEqualTo("Validation failed — check request parameters");
    }

    @Test
    @DisplayName("Should return 400 with static message when MissingServletRequestPartException is thrown")
    void should_return400_when_missingRequestPart() {
        // Arrange — multipart endpoint called without the required 'file' part
        var ex = new MissingServletRequestPartException("file");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingPart(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 400 for missing multipart part")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the static string — part name must not be interpolated")
                .isEqualTo("Required file part is missing");
    }

    @Test
    @DisplayName("Should not echo attacker-controlled part name in response")
    void should_notEchoPartName_when_partNameIsAttackerControlled() {
        // Arrange — simulate an attacker-supplied part name that should never reach the response
        var ex = new MissingServletRequestPartException("<script>alert(1)</script>");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingPart(ex);

        // Assert — the attacker string must not appear anywhere in the message
        assertThat(response.getBody().message())
                .as("attacker-controlled part name must not be echoed in the response body")
                .doesNotContain("<script>")
                .isEqualTo("Required file part is missing");
    }

    @Test
    @DisplayName("should return 400 with safe param name when UUID request param is malformed")
    void should_return400_with_safe_param_name_when_UUID_param_is_malformed() {
        // Arrange — mock avoids the complex MethodParameter constructor setup
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("filterMasterId");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 400 for a malformed request parameter")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must contain the safe parameter name")
                .contains("filterMasterId");

        assertThat(response.getBody().message())
                .as("message must not echo any user-supplied value")
                .doesNotContain("not-a-uuid");
    }

    @Test
    @DisplayName("Should NOT log the email at any level when handling EmailNotVerifiedException (PII)")
    void should_notLogEmail_when_handlingEmailNotVerified() {
        // Arrange — a recognisably-unique email so a leak is unambiguous.
        String email = "pii-leak-canary-7f3a@beautica.test";
        var ex = new EmailNotVerifiedException(email);

        // Act
        ResponseEntity<ApiResponse<EmailNotVerifiedResponse>> response =
                handler.handleEmailNotVerified(ex);

        // Assert — status + body contract preserved (email returned BY DESIGN
        // in the body so the account owner can route to the verify screen).
        assertThat(response.getStatusCode())
                .as("unverified login must map to 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().data().email())
                .as("email is intentionally returned in the response body")
                .isEqualTo(email);

        // The log MUST NOT contain the email at any level (the §I regression).
        boolean leaked = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains(email));
        assertThat(leaked)
                .as("no log event may contain the email address — PII at any level")
                .isFalse();

        // A non-PII marker IS logged for server-side triage.
        boolean markerLogged = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("EmailNotVerifiedException"));
        assertThat(markerLogged)
                .as("a non-PII exception marker should still be logged at DEBUG")
                .isTrue();
    }

    @Test
    @DisplayName("Should return 409 with EMAIL_ALREADY_REGISTERED code when EmailAlreadyRegisteredException is thrown")
    void should_return409WithEmailAlreadyRegisteredCode_when_emailAlreadyRegisteredExceptionThrown() {
        // Arrange — the toggle-on branch of AuthService.register throws this exception.
        // The handler must emit the structured EmailAlreadyRegisteredResponse body
        // (with the EMAIL_ALREADY_REGISTERED code) instead of letting handleBusiness
        // catch it and return the generic "Request could not be completed due to a conflict".
        var ex = new EmailAlreadyRegisteredException();

        // Act
        ResponseEntity<ApiResponse<EmailAlreadyRegisteredResponse>> response =
                handler.handleEmailAlreadyRegistered(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("duplicate-email disclosure must map to 409")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        // Reference the constant — a rename of EmailAlreadyRegisteredException.ERROR_CODE
        // must fail this test, not silently break the mobile client's routing logic.
        assertThat(response.getBody().data().code())
                .as("code must be the stable EMAIL_ALREADY_REGISTERED constant")
                .isEqualTo(EmailAlreadyRegisteredException.ERROR_CODE);

        assertThat(response.getBody().message())
                .as("message must be the stable wire-contract string the mobile client may surface")
                .isEqualTo("Email already registered");
    }

    @Test
    @DisplayName("handleEmailAlreadyRegistered — emits DEBUG log marker without leaking email PII")
    void should_emitDebugLog_when_emailAlreadyRegisteredExceptionThrown() {
        // Arrange — the exception itself carries no email field, but this test guards
        // against future changes that add a payload field (mirrors the PII regression
        // suite for EmailNotVerifiedException).
        var ex = new EmailAlreadyRegisteredException();
        listAppender.list.clear();

        // Act
        handler.handleEmailAlreadyRegistered(ex);

        // Assert — exactly one DEBUG event was emitted carrying the non-PII class marker
        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleEmailAlreadyRegistered must emit exactly one DEBUG log for server-side triage")
                .hasSize(1);
        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must contain the non-PII exception class marker")
                .contains("EmailAlreadyRegisteredException");

        // No log event at any level may contain something that looks like an email
        // address. The exception is email-less today, but this assertion guards a
        // future maintainer who adds an email payload field from leaking PII (§I).
        boolean emailShapedLogged = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(".*\\S+@\\S+.*"));
        assertThat(emailShapedLogged)
                .as("no log event may contain an email-shaped substring — PII at any level")
                .isFalse();
    }

    /**
     * Minimal, fully-hydrated {@link Booking} fixture used to construct a
     * {@link ClientBookingConflictException} — mirrors the fixture style in
     * {@code BookingServiceTest}. No lazy fields are touched by the exception constructor
     * beyond master.user and masterService.serviceDefinition, both set here.
     */
    private Booking buildConflictingBooking(UUID id) {
        User masterUser = new User(
                "master@example.com", "hash", Role.INDEPENDENT_MASTER, "Olena", "Kovalenko", "+380501234567");
        Master master = Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();
        ServiceDefinition serviceDef = ServiceDefinition.builder()
                .name("Manicure")
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .baseDurationMinutes(90)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDef)
                .isActive(true)
                .build();
        User client = new User(
                "client@example.com", "hash", Role.CLIENT, "Client", "Test", "+380631234567");
        Booking booking = Booking.builder()
                .client(client)
                .master(master)
                .masterService(msa)
                .status(BookingStatus.CONFIRMED)
                .startsAt(OffsetDateTime.parse("2026-07-15T14:00:00+03:00"))
                .endsAt(OffsetDateTime.parse("2026-07-15T15:30:00+03:00"))
                .priceAtBooking(new BigDecimal("500.00"))
                .durationMinutesAtBooking(90)
                .bufferMinutesAtBooking(0)
                .build();
        ReflectionTestUtils.setField(booking, "id", id);
        return booking;
    }

    @Test
    @DisplayName("Should return 409 with CLIENT_BOOKING_CONFLICT code and conflicting-booking details "
            + "when ClientBookingConflictException is thrown")
    void should_return409WithClientBookingConflictCode_when_clientBookingConflictExceptionThrown() {
        // Arrange — BookingService throws this when the client already holds an overlapping
        // PENDING/CONFIRMED booking with a different master/salon. The handler must emit the
        // structured ClientBookingConflictResponse body (code + conflict details) instead of
        // letting handleBusiness catch it and return the generic master-busy conflict message.
        UUID conflictingBookingId = UUID.randomUUID();
        Booking conflicting = buildConflictingBooking(conflictingBookingId);
        var ex = new ClientBookingConflictException(conflicting);

        // Act
        ResponseEntity<ApiResponse<ClientBookingConflictResponse>> response =
                handler.handleClientBookingConflict(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("a client double-booking themselves must map to 409")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        // Reference the constant — a rename of ClientBookingConflictException.ERROR_CODE must
        // fail this test, not silently break the mobile client's routing between this and the
        // generic "Slot not available" (master-busy) 409.
        assertThat(response.getBody().data().code())
                .as("code must be the stable CLIENT_BOOKING_CONFLICT constant")
                .isEqualTo(ClientBookingConflictException.ERROR_CODE);

        assertThat(response.getBody().data().conflictingBookingId()).isEqualTo(conflictingBookingId);
        assertThat(response.getBody().data().serviceName()).isEqualTo("Manicure");
        assertThat(response.getBody().data().masterName()).isEqualTo("Olena Kovalenko");
        assertThat(response.getBody().data().startsAt()).isNotNull();
        assertThat(response.getBody().data().endsAt()).isNotNull();

        assertThat(response.getBody().message())
                .as("message must be the stable wire-contract string")
                .isEqualTo("Client already has an overlapping booking");
    }

    @Test
    @DisplayName("handleClientBookingConflict — emits DEBUG log marker only")
    void should_emitDebugLog_when_clientBookingConflictExceptionThrown() {
        Booking conflicting = buildConflictingBooking(UUID.randomUUID());
        var ex = new ClientBookingConflictException(conflicting);
        listAppender.list.clear();

        handler.handleClientBookingConflict(ex);

        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleClientBookingConflict must emit exactly one DEBUG log for server-side triage")
                .hasSize(1);
        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must contain the non-PII exception class marker")
                .contains("ClientBookingConflictException");
    }

    // ── handleBookingElapsed (track 24.x read-only-after-elapse) ───────────────

    @Test
    @DisplayName("handleBookingElapsed — 409 with BOOKING_ALREADY_ELAPSED code, success=false, and the stable wire message")
    void should_return409WithBookingAlreadyElapsedCode_when_bookingElapsedExceptionThrown() {
        // BookingService throws this when a CLIENT cancels/reschedules a booking whose window has
        // already fully elapsed. The dedicated handler must emit the structured BookingElapsedResponse
        // (data.code) rather than letting handleBusiness genericise it into the shared "conflict"
        // message the mobile app also uses for master-busy / slot-not-available 409s.
        var ex = new com.beautica.common.exception.BookingElapsedException();

        ResponseEntity<ApiResponse<com.beautica.booking.dto.BookingElapsedResponse>> response =
                handler.handleBookingElapsed(ex);

        assertThat(response.getStatusCode())
                .as("a client mutation on an elapsed booking must map to 409")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();
        // Reference the constant — a rename must fail this test, not silently break mobile routing
        // between this and the generic 409s.
        assertThat(response.getBody().data().code())
                .as("data.code must be the stable BOOKING_ALREADY_ELAPSED constant")
                .isEqualTo(com.beautica.common.exception.BookingElapsedException.ERROR_CODE);
        assertThat(response.getBody().data().code()).isEqualTo("BOOKING_ALREADY_ELAPSED");
        assertThat(response.getBody().message())
                .as("message must be the stable wire-contract string")
                .isEqualTo("This booking's time has already passed");
    }

    @Test
    @DisplayName("handleBookingElapsed — emits DEBUG log marker only (non-PII exception class)")
    void should_emitDebugLog_when_bookingElapsedExceptionThrown() {
        var ex = new com.beautica.common.exception.BookingElapsedException();
        listAppender.list.clear();

        handler.handleBookingElapsed(ex);

        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleBookingElapsed must emit exactly one DEBUG log for server-side triage")
                .hasSize(1);
        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must contain the non-PII exception class marker")
                .contains("BookingElapsedException");
    }

    @Test
    @DisplayName("should return 400 with generic message when ConstraintViolationException is thrown")
    void should_return400_withGenericMessage_when_constraintViolationExceptionThrown() {
        // Arrange — construct a ConstraintViolationException with a real ConstraintViolation
        // whose property path is "size" and whose message discloses bound limits ("size must be
        // between 1 and 200").  Neither the path name nor the bound values may reach the client
        // (Anti-Bug §A/§N).
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("size");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("size must be between 1 and 200");

        ConstraintViolationException ex =
                new ConstraintViolationException(java.util.Set.of(violation));

        listAppender.list.clear();

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleConstraintViolation(ex);

        // Assert — HTTP contract
        assertThat(response.getStatusCode())
                .as("status must be 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the generic sentinel — no constraint detail leaked to client")
                .isEqualTo("Validation failed — check request parameters");

        // Assert — no detail leak in the response body
        assertThat(response.getBody().message())
                .as("message must NOT contain the field name 'size'")
                .doesNotContain("size");

        assertThat(response.getBody().message())
                .as("message must NOT contain the bound value '200'")
                .doesNotContain("200");

        // Assert — DEBUG log contains the detail so ops can triage without client exposure
        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleConstraintViolation must emit at least one DEBUG log for server-side triage")
                .isNotEmpty();
        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must contain the constraint violation detail for ops visibility")
                .contains("size must be between 1 and 200");
    }

    @Test
    @DisplayName("should return 400 with generic message when MissingServletRequestParameterException is thrown")
    void should_return400_withGenericMessage_when_missingServletRequestParameterExceptionThrown() {
        // Arrange — simulates a caller omitting the required ?page= query parameter.
        // The parameter name ("page") and type ("Integer") must NOT appear in the response
        // body — they disclose internal controller parameter names on permitAll endpoints
        // (Anti-Bug §I/§N).
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("page", "Integer");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingParam(ex);

        // Assert — HTTP contract
        assertThat(response.getStatusCode())
                .as("status must be 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the generic sentinel")
                .isEqualTo("A required query parameter is missing");

        // Assert — no detail leak in the response body
        assertThat(response.getBody().message())
                .as("message must NOT contain the parameter name 'page'")
                .doesNotContain("page");

        assertThat(response.getBody().message())
                .as("message must NOT contain the parameter type 'Integer'")
                .doesNotContain("Integer");
    }

    @Test
    @DisplayName("handleNoResourceFound — returns 404 with generic message when static resource is not found")
    void should_return404_when_noResourceFoundException() {
        // Arrange — Spring MVC 6.x throws this when a static resource path does not exist
        // (e.g. /swagger-ui/index.html when Swagger is disabled in production).
        // The exception should NOT be logged at ERROR; DEBUG is sufficient.
        var ex = new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET,
                "/swagger-ui/index.html");

        listAppender.list.clear();

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFound(ex);

        // Assert — HTTP contract
        assertThat(response.getStatusCode())
                .as("status must be 404 for a missing static resource")
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the generic safe string")
                .isEqualTo("Resource not found");

        // Assert — logging contract: DEBUG level (not ERROR)
        List<ILoggingEvent> allEvents = listAppender.list;
        List<ILoggingEvent> errorEvents = allEvents.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertThat(errorEvents)
                .as("handleNoResourceFound must NOT emit any ERROR logs")
                .isEmpty();

        List<ILoggingEvent> debugEvents = allEvents.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleNoResourceFound must emit exactly one DEBUG log")
                .hasSize(1);

        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must reference the resource path")
                .contains("/swagger-ui/index.html");
    }

    // ── handlePessimisticLockingFailure (Phase 19.4 — 55P03 lock_timeout → 409; widened cycle-2
    //    audit finding 2 — 40P01 deadlock_detected → the SAME 409, not a 500) ──────────
    //
    // CannotAcquireLockException is Spring's SQLState translation of Postgres 55P03
    // (lock_not_available), raised when a lock wait exceeds the 3s lock_timeout fused into
    // BookingRepository.acquireClientAdvisoryLockWithTimeout / acquireAdvisoryLockWithTimeout /
    // AppointmentRepository.lockHeaderIfConfirmed / lockHeaderRegardlessOfStatus.
    // DeadlockLoserDataAccessException is Spring's translation of Postgres 40P01
    // (deadlock_detected) — a SIBLING of CannotAcquireLockException under the shared
    // PessimisticLockingFailureException superclass, NOT a subtype of it, so a handler scoped to
    // ONLY CannotAcquireLockException never matched a real deadlock — it fell through to the
    // generic handleGeneric(Exception) fallback and surfaced as a bare 500. These tests pin the
    // 409 mapping for BOTH exception shapes via the shared superclass handler.

    @Test
    @DisplayName("handlePessimisticLockingFailure — returns 409 with the standard conflict message when a "
            + "lock wait exceeds lock_timeout")
    void should_return409_when_cannotAcquireLockExceptionThrown() {
        // Arrange — mirrors the real translation path: Hibernate/Spring wraps the Postgres
        // 55P03 SQLState into CannotAcquireLockException with a driver-shaped cause message
        // that must never reach the client.
        var ex = new CannotAcquireLockException(
                "could not execute statement",
                new RuntimeException("ERROR: canceling statement due to lock timeout"));

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handlePessimisticLockingFailure(ex);

        // Assert — same "try again" semantics as the existing master/client-busy 409s
        assertThat(response.getStatusCode())
                .as("a lock_timeout wait must map to 409, not the 500 the generic handler would produce")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().success())
                .as("success must be false")
                .isFalse();

        assertThat(response.getBody().message())
                .as("message must be the shared static conflict string — same wording as handleBusiness's "
                        + "CONFLICT branch, so the mobile client treats both identically")
                .isEqualTo("Request could not be completed due to a conflict");
    }

    @Test
    @DisplayName("handlePessimisticLockingFailure — returns the SAME 409 for DeadlockLoserDataAccessException "
            + "— cycle-2 audit finding 2, defensive coverage: this exception is a SIBLING of "
            + "CannotAcquireLockException, not a subtype, so a handler scoped to only that subtype would miss "
            + "it. A real reproduced 40P01 deadlock in THIS codebase actually surfaces as "
            + "CannotAcquireLockException (Hibernate's LockAcquisitionException classification covers the "
            + "whole lock/deadlock SQLSTATE class, and HibernateJpaDialect maps it there uniformly — verified "
            + "empirically) — DeadlockLoserDataAccessException is a latent-gap guard against a plain-JDBC "
            + "translation path this JPA-only codebase does not currently exercise, not today's live path")
    void should_return409_when_deadlockLoserExceptionThrown() {
        // Arrange — a DeadlockLoserDataAccessException shaped as Spring's plain-JDBC translator would
        // produce for Postgres 40P01 (not this codebase's live JPA path today — see DisplayName).
        var ex = new DeadlockLoserDataAccessException(
                "could not execute statement",
                new RuntimeException("ERROR: deadlock detected"));

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handlePessimisticLockingFailure(ex);

        // Assert — identical "try again" semantics to the lock-timeout case, not a 500
        assertThat(response.getStatusCode())
                .as("a genuine deadlock must map to 409, not the 500 the generic handler would produce")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message())
                .isEqualTo("Request could not be completed due to a conflict");
    }

    @Test
    @DisplayName("handlePessimisticLockingFailure — never echoes the SQL state, driver cause, or lock detail "
            + "in the response body")
    void should_notLeakLockDetail_when_cannotAcquireLockExceptionThrown() {
        // Arrange — a cause message shaped like the real Postgres 55P03 error text, which
        // names the lock class and the timed-out statement — must never reach the client.
        String leakyCause = "ERROR: canceling statement due to lock timeout — "
                + "pg_advisory_xact_lock(hashtextextended(...))";
        var ex = new CannotAcquireLockException("could not execute statement", new RuntimeException(leakyCause));

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handlePessimisticLockingFailure(ex);

        // Assert — no internal detail leaks into the HTTP body
        assertThat(response.getBody().message())
                .as("response must not leak the lock-wait cause text")
                .doesNotContain("pg_advisory_xact_lock")
                .doesNotContain("lock timeout")
                .doesNotContain("hashtextextended");
    }

    @Test
    @DisplayName("handlePessimisticLockingFailure — emits the real cause only at DEBUG, never at a louder level")
    void should_logCauseAtDebugOnly_when_cannotAcquireLockExceptionThrown() {
        // Arrange
        var ex = new CannotAcquireLockException("could not execute statement",
                new RuntimeException("55P03 lock_not_available"));
        listAppender.list.clear();

        // Act
        handler.handlePessimisticLockingFailure(ex);

        // Assert — exactly one DEBUG event, no louder-level event at all (this handler never
        // needs ERROR/WARN — a bounded lock-wait timeout is an expected, self-healing condition)
        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handlePessimisticLockingFailure must emit exactly one DEBUG log for server-side triage")
                .hasSize(1);

        boolean anyLouderLevel = listAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR);
        assertThat(anyLouderLevel)
                .as("a bounded lock-wait timeout must never be logged at WARN/ERROR")
                .isFalse();
    }

    /**
     * Dummy target method whose sole purpose is to supply a MethodParameter
     * for constructing MethodArgumentNotValidException in tests.
     * Never invoked at runtime.
     */
    @SuppressWarnings("unused")
    private void dummyMethod(@NotNull String value) {}

    /**
     * Stub enum used only to satisfy the InvalidFormatException targetType parameter.
     * The actual enum members are irrelevant to this test — only the null-field-name
     * guard in handleMessageNotReadable is exercised.
     */
    private enum SelfRegistrationRoleStub {
        CLIENT, INDEPENDENT_MASTER
    }
}
