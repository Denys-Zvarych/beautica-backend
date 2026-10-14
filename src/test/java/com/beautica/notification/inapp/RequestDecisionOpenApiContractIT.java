package com.beautica.notification.inapp;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 372 — pins the ADDITIVE OpenAPI shape of the feed contract: four new {@code type} values, one
 * new {@code TargetKind}, two new NULLABLE {@code NotificationParams} fields — and nothing existing
 * removed. (Never commit a spec dumped from a test context; this only asserts the shape.)
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — phase 372 feed contract is additive")
class RequestDecisionOpenApiContractIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    private JsonNode schemas() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("components").path("schemas");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    @DisplayName("NotificationResponse.type lists the 4 new decision types and keeps the 10 existing ones")
    void should_listFourNewTypes_and_keepTheTenExisting() throws Exception {
        List<String> types = texts(schemas().path("NotificationResponse").path("properties").path("type").path("enum"));

        assertThat(types).contains(
                "BOOKING_CREATED", "BOOKING_CANCELLED_BY_CLIENT", "BOOKING_DECLINED", "BOOKING_NOT_COMPLETED",
                "BOOKING_RESCHEDULED", "REVIEW_REQUESTED", "BOOKING_CANCELLED_SALON_CLOSED",
                "BOOKING_CANCELLED_MASTER_REMOVED", "REVIEW_RECEIVED", "INVITE_ACCEPTED",
                "CATEGORY_REQUEST_APPROVED", "CATEGORY_REQUEST_REJECTED",
                "SERVICE_TYPE_SUGGESTION_APPROVED", "SERVICE_TYPE_SUGGESTION_REJECTED")
                .hasSize(14);
    }

    @Test
    @DisplayName("NotificationTarget.kind adds SERVICE_CATALOG and keeps the existing kinds")
    void should_listServiceCatalogKind_and_keepExistingKinds() throws Exception {
        List<String> kinds = texts(schemas().path("NotificationTarget").path("properties").path("kind").path("enum"));

        assertThat(kinds).containsExactlyInAnyOrder(
                "BOOKING", "BOOKING_REVIEW", "SALON_TEAM", "SERVICE_CATALOG", "NONE");
    }

    @Test
    @DisplayName("NotificationParams adds nullable categoryName + decisionReason and keeps the 8 existing params")
    void should_addTwoNullableStringParams_and_keepTheEightExisting() throws Exception {
        JsonNode properties = schemas().path("NotificationParams").path("properties");

        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);

        assertThat(names).contains("counterpartName", "serviceName", "serviceCount", "startsAt", "salonName",
                "masterName", "subjectName", "subjectRole", "categoryName", "decisionReason");
        for (String field : List.of("categoryName", "decisionReason")) {
            JsonNode node = properties.path(field);
            assertThat(texts(node.path("type"))).as("%s is a nullable string", field)
                    .containsExactlyInAnyOrder("string", "null");
        }
        assertThat(schemas().path("NotificationParams").path("required"))
                .as("the new fields must not be required").extracting(JsonNode::asText)
                .doesNotContain("categoryName", "decisionReason");
    }
}
