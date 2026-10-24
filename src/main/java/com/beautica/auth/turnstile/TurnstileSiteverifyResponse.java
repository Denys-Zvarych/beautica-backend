package com.beautica.auth.turnstile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The subset of Cloudflare's siteverify answer the backend reads (phase 382). Unknown fields
 * ({@code challenge_ts}, {@code cdata}, {@code metadata}, ...) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnstileSiteverifyResponse(
        boolean success,
        String hostname,
        String action,
        @JsonProperty("error-codes") List<String> errorCodes
) {
}
