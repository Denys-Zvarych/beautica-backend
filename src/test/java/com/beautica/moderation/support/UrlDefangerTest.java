package com.beautica.moderation.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UrlDefanger — URLs in user text become non-clickable (phase 414)")
class UrlDefangerTest {

    @Test
    @DisplayName("scheme hxxp(s):// and host dots [.], path kept")
    void should_defangSchemeAndHost_when_urlPresent() {
        assertThat(UrlDefanger.defang("go to https://evil.example.com/login?x=1 now"))
                .isEqualTo("go to hxxps://evil[.]example[.]com/login?x=1 now");
        assertThat(UrlDefanger.defang("HTTP://Evil.COM")).isEqualTo("hxxp://Evil[.]COM");
    }

    @Test
    @DisplayName("bare domains, www. hosts, e-mail addresses and Cyrillic domains lose their clickable dots")
    void should_defangBareHosts_when_noScheme() {
        assertThat(UrlDefanger.defang("see www.evil.com or evil.com.ua")).isEqualTo("see www[.]evil[.]com or evil[.]com[.]ua");
        assertThat(UrlDefanger.defang("mail me: a@b.org")).isEqualTo("mail me: a@b[.]org");
        assertThat(UrlDefanger.defang("сайт.укр")).isEqualTo("сайт[.]укр");
    }

    @Test
    @DisplayName("audit-fix: Unicode full stops (U+3002, U+FF0E, U+FF61) between host labels become [.]")
    void should_defang_when_hostUsesIdeographicFullStop() {
        assertThat(UrlDefanger.defang("evil\u3002com")).isEqualTo("evil[.]com");
        assertThat(UrlDefanger.defang("evil\uFF0Eexample\uFF61org")).isEqualTo("evil[.]example[.]org");
    }

    @Test
    @DisplayName("audit-fix: a punycode TLD (xn--…) as the last label is defanged")
    void should_defang_when_punycodeTld() {
        assertThat(UrlDefanger.defang("see evil.xn--p1ai now")).isEqualTo("see evil[.]xn--p1ai now");
        assertThat(UrlDefanger.defang("https://xn--80ak6aa92e.xn--p1ai/x"))
                .isEqualTo("hxxps://xn--80ak6aa92e[.]xn--p1ai/x");
    }

    @Test
    @DisplayName("audit-fix: U+2024 (one dot leader) between host labels becomes [.]")
    void should_defang_when_hostUsesOneDotLeader() {
        assertThat(UrlDefanger.defang("evil\u2024com")).isEqualTo("evil[.]com");
    }

    @Test
    @DisplayName("audit-fix: bare dotted-quad IPv4 hosts are defanged; version numbers and prices are not")
    void should_defang_when_bareIpv4Host() {
        assertThat(UrlDefanger.defang("go to http://1.2.3.4/x or 10.0.0.1"))
                .isEqualTo("go to hxxp://1[.]2[.]3[.]4/x or 10[.]0[.]0[.]1");
        assertThat(UrlDefanger.defang("v1.2.3 costs 3.50")).isEqualTo("v1.2.3 costs 3.50");
    }

    @Test
    @DisplayName("numbers, abbreviations and plain text are left alone; null stays null")
    void should_keepText_when_noHost() {
        assertThat(UrlDefanger.defang("Ціна 3.5 тис., т.д. Дуже погано.")).isEqualTo("Ціна 3.5 тис., т.д. Дуже погано.");
        assertThat(UrlDefanger.defang(null)).isNull();
    }

    @Test
    @DisplayName("idempotent: defanging twice equals defanging once")
    void should_beIdempotent_when_appliedTwice() {
        String once = UrlDefanger.defang("https://a.b.com/x");

        assertThat(UrlDefanger.defang(once)).isEqualTo(once);
    }
}
