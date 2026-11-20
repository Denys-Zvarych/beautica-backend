package com.beautica.client.provider.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientCardResponse")
class ClientCardResponseTest {

    @Test
    @DisplayName("toString masks the phone and never prints the raw number or the name")
    void should_notLogRawPhone_when_toStringCalled() {
        var card = new ClientCardResponse(ClientKind.REGISTERED, UUID.randomUUID(), null, "Петро Сидоренко", null,
                "+380671234567", false, null, null, null, null, new ClientCardResponse.Stats(1, 1, 0));

        String text = card.toString();

        assertThat(text).doesNotContain("671234567").doesNotContain("+380671234567").doesNotContain("Сидоренко");
        assertThat(text).contains("+380***");
    }

    @Test
    @DisplayName("toString prints null for an absent phone")
    void should_printNullPhone_when_phoneAbsent() {
        var card = new ClientCardResponse(ClientKind.GUEST, null, UUID.randomUUID(), null, null, null, false, null,
                null, null, null, new ClientCardResponse.Stats(0, 0, 0));

        assertThat(card.toString()).contains("phone=null");
    }
}
