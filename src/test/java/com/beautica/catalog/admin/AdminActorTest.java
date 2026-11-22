package com.beautica.catalog.admin;

import com.beautica.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AdminActor — X-Admin-Actor header validation")
class AdminActorTest {

    @Test
    @DisplayName("a verified e-mail is accepted, trimmed")
    void should_acceptTrimmedEmail_when_headerIsValid() {
        AdminActor actor = AdminActor.of("  owner@beautica.app ");

        assertThat(actor.email()).isEqualTo("owner@beautica.app");
    }

    @Test
    @DisplayName("an e-mail of exactly 254 chars is accepted, 255 is rejected")
    void should_enforce254Chars_when_emailIsLong() {
        String at254 = "a".repeat(64) + "@" + "b".repeat(185) + ".app";
        String at255 = "a".repeat(64) + "@" + "b".repeat(185) + ".apps";

        assertThat(AdminActor.of(at254).email()).hasSize(254);
        assertThatThrownBy(() -> AdminActor.of(at255)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-an-email", "a@b", "a b@beautica.app", "a@beautica.app\r\nX: y", "\"a\"@beautica.app"})
    @DisplayName("missing, blank or non-email values are a 400 that never echoes the value")
    void should_throw400_when_headerInvalid(String header) {
        assertThatThrownBy(() -> AdminActor.of(header))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Invalid X-Admin-Actor header");
                });
    }
}
