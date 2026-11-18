package com.beautica.user.phone;

import com.beautica.common.exception.IdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PhoneChangeGuard — profile writes never change the phone (B516, CLI-04)")
class PhoneChangeGuardTest {

    @ParameterizedTest(name = "stored={0} requested={1}")
    @CsvSource(nullValues = "NULL", value = {
            "+380671234567, NULL",
            "+380671234567, '   '",
            "+380671234567, +380671234567",
            "+380671234567, 067 123 45 67",
            "+380671234567, +38 (067) 123-45-67",
            "NULL, NULL",
            "free text, free text"})
    @DisplayName("should_passSilently_when_valueAbsentOrSameNumber")
    void should_passSilently_when_valueAbsentOrSameNumber(String stored, String requested) {
        assertThatCode(() -> PhoneChangeGuard.requireUnchanged(stored, requested)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "stored={0} requested={1}")
    @CsvSource(nullValues = "NULL", value = {
            "+380671234567, +380501234567",
            "NULL, +380671234567",
            "free text, other text"})
    @DisplayName("should_throwPhoneChangeRequiresOtp_when_numberDiffers")
    void should_throwPhoneChangeRequiresOtp_when_numberDiffers(String stored, String requested) {
        assertThatThrownBy(() -> PhoneChangeGuard.requireUnchanged(stored, requested))
                .isInstanceOf(IdentityException.class)
                .hasMessage(IdentityException.Code.PHONE_CHANGE_REQUIRES_OTP.clientMessage());
    }

    @Test
    @DisplayName("should_notTreatTwoUnparseableValuesAsEqual_when_bothAreGarbage")
    void should_notTreatTwoUnparseableValuesAsEqual_when_bothAreGarbage() {
        assertThatThrownBy(() -> PhoneChangeGuard.requireUnchanged("abc", "xyz"))
                .isInstanceOf(IdentityException.class);
    }
}
