package com.beautica.notification.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 491 — {@link RequestDecisionEvent#parse}: the drain's reader of a {@code REQUEST_DECIDED} payload. A bad
 * row must fail on its own retry path with a message that names the key only, never a payload value.
 */
@DisplayName("RequestDecisionEvent.parse — REQUEST_DECIDED payload reader")
class RequestDecisionEventTest {

    private static Map<String, String> payload(String kind, String requestId, String approved, String recipient) {
        Map<String, String> map = new HashMap<>();
        map.put(NotificationOutboxService.REQUEST_KIND, kind);
        map.put(NotificationOutboxService.REQUEST_ID, requestId);
        map.put(NotificationOutboxService.REQUEST_APPROVED, approved);
        map.put(NotificationOutboxService.RECIPIENT_USER_ID, recipient);
        return map;
    }

    @Test
    @DisplayName("a well-formed reject payload parses to kind, id, approved=false and the recipient")
    void should_parseAllFields_when_payloadWellFormed() {
        UUID recipient = UUID.randomUUID();

        RequestDecisionEvent event = RequestDecisionEvent.parse(
                payload("CATEGORY_REQUEST", "42", "false", recipient.toString()));

        assertThat(event).isEqualTo(new RequestDecisionEvent(RequestKind.CATEGORY_REQUEST, "42", false, recipient));
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"kind", "requestId", "approved", "recipientUserId"})
    @DisplayName("a missing key is refused, naming the key")
    void should_throwNamingKey_when_keyMissing(String key) {
        Map<String, String> map = payload("CATEGORY_REQUEST", "42", "true", UUID.randomUUID().toString());
        map.remove(key);

        assertThatThrownBy(() -> RequestDecisionEvent.parse(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REQUEST_DECIDED payload is missing " + key);
    }

    @Test
    @DisplayName("a blank value counts as missing")
    void should_throw_when_valueBlank() {
        assertThatThrownBy(() -> RequestDecisionEvent.parse(
                payload("CATEGORY_REQUEST", "  ", "true", UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REQUEST_DECIDED payload is missing requestId");
    }

    @Test
    @DisplayName("an approved flag other than true/false is refused (no silent Boolean.parseBoolean → false)")
    void should_throw_when_approvedFlagUnreadable() {
        assertThatThrownBy(() -> RequestDecisionEvent.parse(
                payload("CATEGORY_REQUEST", "42", "yes", UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REQUEST_DECIDED payload has an unreadable approved flag");
    }

    @Test
    @DisplayName("an unknown kind is refused without echoing the value")
    void should_throwWithoutEchoingValue_when_kindUnknown() {
        assertThatThrownBy(() -> RequestDecisionEvent.parse(
                payload("SECRET_KIND_VALUE", "42", "true", UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REQUEST_DECIDED payload has an unreadable kind or recipient")
                .message().doesNotContain("SECRET_KIND_VALUE");
    }

    @Test
    @DisplayName("a non-UUID recipient is refused without echoing the value")
    void should_throwWithoutEchoingValue_when_recipientNotUuid() {
        assertThatThrownBy(() -> RequestDecisionEvent.parse(
                payload("SERVICE_TYPE_SUGGESTION", "42", "true", "someone@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REQUEST_DECIDED payload has an unreadable kind or recipient")
                .message().doesNotContain("someone@example.com");
    }
}
