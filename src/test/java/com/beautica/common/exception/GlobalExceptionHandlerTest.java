package com.beautica.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

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
    @DisplayName("handleForbidden — internal message is emitted at DEBUG level so ops can triage without client exposure")
    void should_emitDebugLog_when_forbiddenExceptionThrown() {
        // Arrange — internal message that must appear in the server log but NOT in the response body
        String internalMessage = "Master 550e8400-e29b-41d4-a716-446655440000 does not own salon abc";
        var ex = new ForbiddenException(internalMessage);
        listAppender.list.clear();

        // Act
        handler.handleForbidden(ex);

        // Assert — exactly one DEBUG event was emitted containing the internal message
        List<ILoggingEvent> debugEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .toList();
        assertThat(debugEvents)
                .as("handleForbidden must emit exactly one DEBUG log for server-side triage")
                .hasSize(1);
        assertThat(debugEvents.get(0).getFormattedMessage())
                .as("DEBUG log must contain the original internal message for ops visibility")
                .contains(internalMessage);
    }

    @Test
    @DisplayName("Should return 409 when BusinessException with CONFLICT status is thrown")
    void should_return409_when_conflictExceptionThrown() {
        // Arrange
        var ex = new BusinessException(HttpStatus.CONFLICT, "Slot not available");

        // Act
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 409")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().success())
                .as("success must be false")
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
                .as("message must contain the constraint violation text")
                .isNotBlank()
                .contains("must not be blank");
    }

    @Test
    @DisplayName("Should return 400 when MissingServletRequestPartException is thrown")
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
                .as("message must reference the missing part name")
                .contains("file");
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
