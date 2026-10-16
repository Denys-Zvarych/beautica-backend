package com.beautica.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonEscapedMessageConverter.escape — RFC 8259 string escaping")
class JsonEscapedMessageConverterTest {

    @Test
    @DisplayName("nothing to escape — the same String instance is returned (no allocation)")
    void should_returnSameInstance_when_nothingToEscape() {
        String plain = "Booking created for salon 42 — Київ";

        assertThat(JsonEscapedMessageConverter.escape(plain)).isSameAs(plain);
    }

    @Test
    @DisplayName("quote, backslash and control characters — escaped")
    void should_escape_when_specialCharactersPresent() {
        assertThat(JsonEscapedMessageConverter.escape("a\"b\\c\nd\re\tf\u0001"))
                .isEqualTo("a\\\"b\\\\c\\nd\\re\\tf\\u0001");
    }

    @Test
    @DisplayName("null — empty string")
    void should_returnEmpty_when_null() {
        assertThat(JsonEscapedMessageConverter.escape(null)).isEmpty();
    }
}
