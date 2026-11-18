package com.beautica.common.util;

import com.beautica.common.exception.IdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UaMobilePhone — Ukrainian mobile normaliser (B514, AUTH-D15)")
class UaMobilePhoneTest {

    @ParameterizedTest
    @ValueSource(strings = {"+380501234567", "380501234567", "0501234567", "+38 (050) 123-45-67",
            "00380501234567", "501234567"})
    @DisplayName("should_normaliseToE164_when_uaMobileInAnySpelling")
    void should_normaliseToE164_when_uaMobileInAnySpelling(String raw) {
        String phone = UaMobilePhone.require(raw);

        assertThat(phone).isEqualTo("+380501234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+380671234567", "+380731234567", "+380991234567", "+380771234567", "+380391234567"})
    @DisplayName("should_accept_when_operatorCodeIsMobile")
    void should_accept_when_operatorCodeIsMobile(String raw) {
        assertThat(UaMobilePhone.parse(raw)).contains(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+380441234567", "0441234567", "+380321234567", "+380561234567"})
    @DisplayName("should_reject_when_uaLandline")
    void should_reject_when_uaLandline(String raw) {
        assertThat(UaMobilePhone.parse(raw)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"+48501234567", "+14155552671", "+3805012345", "abc", "+0501234567"})
    @DisplayName("should_reject_when_notUkrainianOrMalformed")
    void should_reject_when_notUkrainianOrMalformed(String raw) {
        assertThat(UaMobilePhone.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("should_throwPhoneNotUaMobile_when_requireGetsLandline")
    void should_throwPhoneNotUaMobile_when_requireGetsLandline() {
        assertThatThrownBy(() -> UaMobilePhone.require("+380441234567"))
                .isInstanceOfSatisfying(IdentityException.class, e ->
                        assertThat(e.getCode()).isEqualTo(IdentityException.Code.PHONE_NOT_UA_MOBILE))
                .hasMessageNotContaining("441234567");
    }
}
