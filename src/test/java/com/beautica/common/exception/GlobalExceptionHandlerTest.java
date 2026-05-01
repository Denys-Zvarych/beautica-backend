package com.beautica.common.exception;

import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

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

    /**
     * Stub enum used only to satisfy the InvalidFormatException targetType parameter.
     * The actual enum members are irrelevant to this test — only the null-field-name
     * guard in handleMessageNotReadable is exercised.
     */
    private enum SelfRegistrationRoleStub {
        CLIENT, INDEPENDENT_MASTER
    }
}
