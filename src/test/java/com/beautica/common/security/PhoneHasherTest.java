package com.beautica.common.security;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.config.PhoneHashPepperConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PhoneHasher — phase 397 D3")
class PhoneHasherTest {

    private static final String PEPPER = "unit-test-phone-hash-pepper-min-32-chars!";

    private final PhoneHasher hasher = new PhoneHasher(new PhoneHashPepperConfig(PEPPER));

    @ParameterizedTest
    @ValueSource(strings = {"+380501234567", "0501234567", "380501234567", "+38 (050) 123-45-67", "050 123 45 67"})
    void should_hashEqual_when_samePhoneInAnySpelling(String spelling) {
        HashedPhone canonical = hasher.hash("+380501234567");

        HashedPhone result = hasher.hash(spelling);

        assertThat(result.hash()).isEqualTo(canonical.hash());
        assertThat(result.last4()).isEqualTo("4567");
    }

    @Test
    void should_beHmacSha256OfE164WithPepper_when_hashing() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PEPPER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal("+380501234567".getBytes(StandardCharsets.UTF_8));

        byte[] result = hasher.hash("0501234567").hash();

        assertThat(result).hasSize(32).isEqualTo(expected);
    }

    @Test
    void should_produceDifferentHash_when_pepperDiffers() {
        PhoneHasher other = new PhoneHasher(new PhoneHashPepperConfig("a-completely-different-pepper-min-32-chars"));

        byte[] a = hasher.hash("+380501234567").hash();
        byte[] b = other.hash("+380501234567").hash();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_produceDifferentHash_when_phoneDiffers() {
        byte[] a = hasher.hash("+380501234567").hash();

        byte[] b = hasher.hash("+380501234568").hash();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_throw400_when_phoneIsNotUkrainian() {
        assertThatThrownBy(() -> hasher.hash("+12025550123"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid phone format");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-phone", "+12025550123"})
    void should_returnEmpty_when_tryHashGetsUnusablePhone(String raw) {
        assertThat(hasher.tryHash(raw)).isEmpty();
    }

    @Test
    void should_neverPrintDigest_when_toStringCalled() {
        HashedPhone phone = hasher.hash("+380501234567");

        assertThat(phone.toString()).doesNotContain("4567").contains("redacted");
    }

    @Test
    void should_failBoot_when_pepperShorterThan32Chars() {
        assertThatThrownBy(() -> new PhoneHashPepperConfig("a".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phone-hash-pepper")
                .hasMessageContaining("at least 32");
    }

    @Test
    void should_failBoot_when_pepperMissing() {
        assertThatThrownBy(() -> new PhoneHashPepperConfig(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PhoneHashPepperConfig("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_accept_when_pepperIsExactly32Chars() {
        assertThatCode(() -> new PhoneHashPepperConfig("a".repeat(32))).doesNotThrowAnyException();
        assertThat(new PhoneHashPepperConfig("a".repeat(32)).toString()).doesNotContain("aaaa");
    }
}
