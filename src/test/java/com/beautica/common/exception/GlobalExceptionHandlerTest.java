package com.beautica.common.exception;

import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler — unit")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
    @DisplayName("Should return 404 when NotFoundException is thrown")
    void should_return404_when_notFoundExceptionThrown() {
        // Arrange
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
                .as("message must be non-blank")
                .isNotBlank();
    }

    @Test
    @DisplayName("Should return 403 when ForbiddenException is thrown")
    void should_return403_when_forbiddenExceptionThrown() {
        // Arrange
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
