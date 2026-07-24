package com.beautica.config;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal test-only controller used by {@link JsonStreamReadConstraintsTest} to exercise
 * the global Jackson {@code StreamReadConstraints} cap on the real MVC request-parsing
 * {@code ObjectMapper}. It has a single free-text {@code note} field so an oversized JSON
 * string token can be posted and observed to fail at the parse phase (400), independent
 * of any feature controller's collaborators.
 */
@RestController
@Validated
public class JsonCapEchoTestController {

    static final String ECHO_URL = "/test-only/json-cap/echo";
    static final String RAW_URL = "/test-only/json-cap/raw";

    record EchoRequest(@NotBlank String note) {}

    @PostMapping(ECHO_URL)
    String echo(@RequestBody EchoRequest body) {
        return body.note();
    }

    /**
     * Binds an arbitrary JSON tree so structurally-valid-but-deeply-nested or
     * large-number payloads can reach the controller (200) when they are <em>within</em>
     * the parser caps. Used by the boundary "no false rejection" cases, which must not be
     * masked by {@code @NotBlank}/{@code EchoRequest} binding constraints that fire after
     * a successful parse.
     */
    @PostMapping(RAW_URL)
    String raw(@RequestBody JsonNode body) {
        return body.toString();
    }
}
