package com.beautica.notification.ics;

import com.beautica.notification.BookingIcsExpectedContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static com.beautica.notification.BookingIcsExpectedContent.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BookingIcsBuilder — phase 496 booking .ics (parity with the website ics.ts)")
class BookingIcsBuilderTest {

    private static final UUID PARITY_ID = UUID.fromString(BookingIcsExpectedContent.PARITY_UID);
    private static final Instant START = Instant.parse("2026-05-20T07:00:00Z");
    private static final Instant END = Instant.parse("2026-05-20T08:30:00Z");
    private static final Instant NOW = Instant.parse("2026-05-01T09:30:15.123Z");
    private static final String ORGANIZER = "noreply@beautica.app";
    private static final String CLIENT_EMAIL = "client@beautica.test";

    private static BookingIcs parityIcs(long sequence) {
        return new BookingIcs(PARITY_ID, START, END, BookingIcsExpectedContent.PARITY_SUMMARY,
                BookingIcsExpectedContent.PARITY_LOCATION, sequence, NOW, ORGANIZER, "Олена Іваненко", CLIENT_EMAIL);
    }

    @Test
    @DisplayName("shared lines are byte-identical to ics.ts: header, UID, UTC stamps, escaped + folded SUMMARY/LOCATION, alarm")
    void should_matchWebsiteGolden_when_sameInputRendered() {
        // Arrange
        BookingIcs ics = parityIcs(42);

        // Act
        String out = BookingIcsBuilder.build(ics, IcsMethod.REQUEST);

        // Assert
        assertThat(out).startsWith(String.join(CRLF, BookingIcsExpectedContent.WEBSITE_HEADER) + CRLF);
        assertThat(out).contains(CRLF + BookingIcsExpectedContent.WEBSITE_UID + CRLF,
                CRLF + BookingIcsExpectedContent.WEBSITE_DTSTAMP + CRLF,
                CRLF + BookingIcsExpectedContent.WEBSITE_DTSTART + CRLF,
                CRLF + BookingIcsExpectedContent.WEBSITE_DTEND + CRLF,
                CRLF + BookingIcsExpectedContent.WEBSITE_SUMMARY + CRLF,
                CRLF + BookingIcsExpectedContent.WEBSITE_LOCATION + CRLF,
                CRLF + BookingIcsExpectedContent.WEBSITE_ALARM + CRLF);
        assertThat(out).endsWith("END:VEVENT" + CRLF + "END:VCALENDAR" + CRLF);
    }

    @Test
    @DisplayName("every line ends with CRLF and no physical line exceeds 75 UTF-8 octets; no bare LF or CR")
    void should_useCrlfAndFoldAt75Octets_when_rendered() {
        // Arrange
        BookingIcs ics = parityIcs(1);

        // Act
        String out = BookingIcsBuilder.build(ics, IcsMethod.REQUEST);

        // Assert
        assertThat(out.replace(CRLF, "")).doesNotContain("\n").doesNotContain("\r");
        assertThat(Arrays.stream(out.split(CRLF)))
                .allSatisfy(line -> assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75));
    }

    @Test
    @DisplayName("folding never splits a multi-byte code point: a 2-octet Cyrillic letter at the 75-octet edge moves whole")
    void should_moveWholeCodePoint_when_foldBoundaryFallsInsideIt() {
        // Arrange — 74 ASCII octets, then «Ж» (2 octets) would end at octet 76
        String line = "A".repeat(74) + "Ж" + "B";

        // Act
        String folded = BookingIcsBuilder.fold(line);

        // Assert
        assertThat(folded).isEqualTo("A".repeat(74) + CRLF + " ЖB");
    }

    @Test
    @DisplayName("a 4-octet code point (emoji) is never split across a fold")
    void should_keepSurrogatePairTogether_when_folding() {
        // Arrange
        String line = "A".repeat(73) + "😀" + "B";

        // Act
        String folded = BookingIcsBuilder.fold(line);

        // Assert
        assertThat(folded).isEqualTo("A".repeat(73) + CRLF + " 😀B");
    }

    @Test
    @DisplayName("TEXT escaping: backslash, ; and , escaped; every line break becomes \\n; other controls dropped")
    void should_escapeLikeWebsite_when_specialCharactersPresent() {
        // Act
        String escaped = BookingIcsBuilder.escape("a\\b;c,d\re\nf\r\ng h\u0085i\u0000j\u0007k\u009Fl\tm");

        // Assert
        assertThat(escaped).isEqualTo("a\\\\b\\;c\\,d\\ne\\nf\\ng\\nh\\ni" + "jkl\tm");
    }

    @Test
    @DisplayName("REQUEST: METHOD:REQUEST, STATUS:CONFIRMED, SEQUENCE, organizer + attendee, alarm")
    void should_renderRequestFields_when_methodRequest() {
        // Act
        String out = BookingIcsBuilder.build(parityIcs(7), IcsMethod.REQUEST);

        // Assert
        assertThat(BookingIcsExpectedContent.lines(out)).contains(BookingIcsExpectedContent.METHOD_REQUEST,
                BookingIcsExpectedContent.STATUS_CONFIRMED, "SEQUENCE:7", BookingIcsExpectedContent.VALARM,
                BookingIcsExpectedContent.ORGANIZER_PREFIX + ORGANIZER,
                "ATTENDEE;CN=\"Олена Іваненко\";RSVP=FALSE:mailto:" + CLIENT_EMAIL);
    }

    @Test
    @DisplayName("CANCEL: METHOD:CANCEL, STATUS:CANCELLED, same UID, no alarm")
    void should_renderCancelFields_when_methodCancel() {
        // Act
        String out = BookingIcsBuilder.build(parityIcs(8), IcsMethod.CANCEL);

        // Assert
        assertThat(BookingIcsExpectedContent.lines(out)).contains(BookingIcsExpectedContent.METHOD_CANCEL,
                BookingIcsExpectedContent.STATUS_CANCELLED, BookingIcsExpectedContent.uidLine(PARITY_ID))
                .doesNotContain(BookingIcsExpectedContent.METHOD_REQUEST, BookingIcsExpectedContent.STATUS_CONFIRMED,
                        BookingIcsExpectedContent.VALARM);
    }

    @Test
    @DisplayName("SEQUENCE = seconds since 2026-01-01T00:00Z of updatedAt: rises with each change, 0 before the epoch / null")
    void should_deriveMonotonicSequence_when_updatedAtAdvances() {
        // Act
        long atEpoch = BookingIcs.sequenceOf(Instant.parse("2026-01-01T00:00:00Z"));
        long later = BookingIcs.sequenceOf(Instant.parse("2026-01-01T00:01:40Z"));
        long laterStill = BookingIcs.sequenceOf(Instant.parse("2026-01-01T00:01:41Z"));

        // Assert
        assertThat(atEpoch).isZero();
        assertThat(later).isEqualTo(100);
        assertThat(laterStill).isGreaterThan(later);
        assertThat(BookingIcs.sequenceOf(Instant.parse("2025-12-31T23:59:59Z"))).isZero();
        assertThat(BookingIcs.sequenceOf(null)).isZero();
    }

    @Test
    @DisplayName("an attendee name with a quote, control chars or line break cannot break out of the CN parameter")
    void should_stripQuotesAndControls_when_attendeeNameHostile() {
        // Arrange
        BookingIcs ics = new BookingIcs(PARITY_ID, START, END, "s", null, 0, NOW, ORGANIZER,
                "Ol\"ena\r\nX-INJECT:1", "a\r\nb@beautica.test");

        // Act
        String out = BookingIcsBuilder.build(ics, IcsMethod.REQUEST);

        // Assert
        assertThat(BookingIcsExpectedContent.lines(out))
                .contains("ATTENDEE;CN=\"OlenaX-INJECT:1\";RSVP=FALSE:mailto:ab@beautica.test")
                .noneMatch(l -> l.startsWith("X-INJECT"));
    }

    @Test
    @DisplayName("no LOCATION line when the address is absent; no DESCRIPTION outside the alarm (no notes, no phone)")
    void should_omitLocationAndDescription_when_noAddress() {
        // Arrange
        BookingIcs ics = new BookingIcs(PARITY_ID, START, END, "Манікюр", null, 0, NOW, ORGANIZER, "Олена",
                CLIENT_EMAIL);

        // Act
        String out = BookingIcsBuilder.build(ics, IcsMethod.CANCEL);

        // Assert
        assertThat(BookingIcsExpectedContent.property(out, "LOCATION")).isEmpty();
        assertThat(BookingIcsExpectedContent.property(out, "DESCRIPTION")).isEmpty();
        assertThat(BookingIcsExpectedContent.property(out, "TEL")).isEmpty();
    }
}
