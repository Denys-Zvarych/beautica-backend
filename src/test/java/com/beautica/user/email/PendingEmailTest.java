package com.beautica.user.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PendingEmail — the code hash never serialises (B516, Anti-Bug §I)")
class PendingEmailTest {

    @Test
    @DisplayName("serialising a pending email never writes its code hash")
    void should_omitCodeHash_when_serialised() throws Exception {
        PendingEmail row = PendingEmail.forUser(UUID.randomUUID());
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        row.issue("olena@beautica.test", "c".repeat(64), now, now.plusSeconds(900));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        String json = mapper.writeValueAsString(row);

        assertThat(json).doesNotContain("codeHash").doesNotContain("c".repeat(64));
    }
}
