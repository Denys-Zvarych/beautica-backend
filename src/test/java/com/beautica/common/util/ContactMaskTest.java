package com.beautica.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContactMask — the masked value a security notice shows (B516, AUTH-D19)")
class ContactMaskTest {

    @Test
    @DisplayName("a phone keeps the +380 prefix and its last four digits only")
    void should_maskPhoneMiddle_when_e164() {
        assertThat(ContactMask.phone("+380671234567")).isEqualTo("+380*****4567");
    }

    @Test
    @DisplayName("an email keeps its first character and the domain only")
    void should_maskEmailLocalPart_when_valid() {
        assertThat(ContactMask.email("olena.k@gmail.com")).isEqualTo("o***@gmail.com");
    }

    @Test
    @DisplayName("a missing or malformed value masks to nothing rather than leaking it")
    void should_returnNull_when_valueMissingOrMalformed() {
        assertThat(ContactMask.phone(null)).isNull();
        assertThat(ContactMask.phone("+3806712")).isNull();
        assertThat(ContactMask.email(null)).isNull();
        assertThat(ContactMask.email("@gmail.com")).isNull();
        assertThat(ContactMask.email("olena@")).isNull();
    }

    @Test
    @DisplayName("B520: the invite preview mask shows only the last two digits, grouped like a UA number")
    void should_maskAllButLastTwoDigits_when_invitePhone() {
        assertThat(ContactMask.invitePhone("+380671234545")).isEqualTo("+380 •• ••• •• 45");
    }

    @Test
    @DisplayName("B520: anything but a +380 nine-digit number masks to nothing")
    void should_returnNull_when_invitePhoneMalformed() {
        assertThat(ContactMask.invitePhone(null)).isNull();
        assertThat(ContactMask.invitePhone("+38067123")).isNull();
        assertThat(ContactMask.invitePhone("+48501234567")).isNull();
    }
}
