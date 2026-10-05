package com.beautica.service;

import com.beautica.favorite.dto.AddFavoriteRequest;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared HTTP helpers for the bookability ITs (search, profiles, favourites, cache eviction):
 * an asserting GET that unwraps {@code ApiResponse.data}, the id extractor for a
 * {@code PageResponse} body, and the favourite write. One copy instead of one per suite.
 */
final class BookabilityHttp {

    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    BookabilityHttp(TestRestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** Anonymous GET; asserts 200 and returns the {@code data} node. */
    JsonNode get(String url) throws Exception {
        return get(url, null);
    }

    /** GET as {@code token} (null = anonymous); asserts 200 and returns the {@code data} node. */
    JsonNode get(String url, String token) throws Exception {
        HttpEntity<?> entity = token == null ? HttpEntity.EMPTY : new HttpEntity<>(bearer(token));
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).as("GET %s → %s", url, response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    /** {@code POST /favorites} as the client; asserts 200. */
    void addFavorite(String clientToken, FavoriteTargetType type, UUID targetId) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/favorites", HttpMethod.POST,
                new HttpEntity<>(new AddFavoriteRequest(type, targetId), bearer(clientToken)),
                String.class);
        assertThat(response.getStatusCode()).as("favourite %s %s: %s", type, targetId, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    /** The {@code field} of every row of a {@code PageResponse} {@code data} node, in order. */
    static List<String> ids(JsonNode page, String field) {
        List<String> ids = new ArrayList<>();
        page.path("data").forEach(row -> ids.add(row.path(field).asText()));
        return ids;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }
}
