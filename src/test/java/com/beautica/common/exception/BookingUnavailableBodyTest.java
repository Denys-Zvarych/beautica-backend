package com.beautica.common.exception;

import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 398 D3 — the {@code 409 BOOKING_UNAVAILABLE} body: one stable code, a neutral message that differs only by
 * master vs salon wording, nothing that says "blocked", and no cache header (an unblock makes the request succeed).
 */
@DisplayName("BOOKING_UNAVAILABLE body — phase 398")
class BookingUnavailableBodyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("master scope → 409, data.code BOOKING_UNAVAILABLE, master wording, no errors")
    void should_return409WithCode_when_masterScopeRefused() {
        ResponseEntity<ApiResponse<BookingUnavailableResponse>> response =
                handler.handleBookingUnavailable(BookingUnavailableException.forMaster());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isEqualTo(new BookingUnavailableResponse("BOOKING_UNAVAILABLE"));
        assertThat(response.getBody().message()).isEqualTo("Запис до цього майстра зараз недоступний");
        assertThat(response.getBody().errors()).isNull();
        assertThat(response.getHeaders().getCacheControl()).isNull();
    }

    @Test
    @DisplayName("salon scope → same body apart from the salon wording")
    void should_differOnlyByWording_when_salonScopeRefused() {
        JsonNode master = json(handler.handleBookingUnavailable(BookingUnavailableException.forMaster()));

        JsonNode salon = json(handler.handleBookingUnavailable(BookingUnavailableException.forSalon()));

        assertThat(salon.path("message").asText()).isEqualTo("Запис до цього салону зараз недоступний");
        ((com.fasterxml.jackson.databind.node.ObjectNode) salon).set("message", master.path("message"));
        assertThat(salon).isEqualTo(master);
    }

    @Test
    @DisplayName("neither wording nor code reveals a block")
    void should_notRevealBlock_when_serialised() throws Exception {
        String master = objectMapper.writeValueAsString(
                handler.handleBookingUnavailable(BookingUnavailableException.forMaster()).getBody());

        String salon = objectMapper.writeValueAsString(
                handler.handleBookingUnavailable(BookingUnavailableException.forSalon()).getBody());

        assertThat(master + salon).doesNotContainIgnoringCase("block").doesNotContainIgnoringCase("заблок");
    }

    private JsonNode json(ResponseEntity<ApiResponse<BookingUnavailableResponse>> response) {
        return objectMapper.valueToTree(response.getBody());
    }
}
