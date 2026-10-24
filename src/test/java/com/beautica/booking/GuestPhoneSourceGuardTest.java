package com.beautica.booking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 386 HARD RULE as a structural gate: a registered user's phone must never reach a provider through
 * {@code BookingDetailResponse}. The guest phone has exactly one legal source - {@code booking.getGuestPhone()} - so
 * the DTO file must not read a phone from any user/account type. This is a source scan (no ArchUnit on the
 * classpath): any {@code getPhone(} / {@code getPhoneNumber(} call in the file, or a {@code .getClient()} chained into
 * one, fails the build. It scans the CODE only (comments stripped), so the javadoc may still name the rule.
 */
@DisplayName("GuestPhoneSourceGuard - BookingDetailResponse never reads a user's phone (phase 386 hard rule)")
class GuestPhoneSourceGuardTest {

    private static final Path DTO = Path.of("src/main/java/com/beautica/booking/dto/BookingDetailResponse.java");
    private static final Pattern USER_PHONE_READ = Pattern.compile("\\bget(Phone|PhoneNumber)\\s*\\(");
    private static final Pattern CLIENT_PHONE_CHAIN = Pattern.compile("getClient\\s*\\(\\s*\\)\\s*\\.\\s*get\\w*Phone\\w*");

    /** Drops block comments (javadoc) and line comments so only executable code is scanned. */
    static String codeOnly(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String dtoCode() throws IOException {
        return codeOnly(Files.readString(DTO));
    }

    @Test
    @DisplayName("should_notReadAnyPhoneFromAUser_when_scanningBookingDetailResponse")
    void should_notReadAnyPhoneFromAUser_when_scanningBookingDetailResponse() throws IOException {
        String code = dtoCode();

        assertThat(USER_PHONE_READ.matcher(code).find())
                .as("BookingDetailResponse must not call getPhone()/getPhoneNumber() on any user type")
                .isFalse();
        assertThat(CLIENT_PHONE_CHAIN.matcher(code).find())
                .as("BookingDetailResponse must not chain getClient() into a phone getter")
                .isFalse();
    }

    @Test
    @DisplayName("should_sourceGuestPhoneOnlyFromTheBookingWhenThereIsNoClient_when_scanningTheWither")
    void should_sourceGuestPhoneOnlyFromTheBookingWhenThereIsNoClient_when_scanningTheWither() throws IOException {
        String code = dtoCode().replaceAll("\\s+", " ");

        assertThat(code).contains("booking.getClient() == null ? booking.getGuestPhone() : null");
        assertThat(List.of(code.split("getGuestPhone")).size())
                .as("exactly one read of getGuestPhone() in the DTO").isEqualTo(2);
    }

    @Test
    @DisplayName("the scanner itself flags a phone read and ignores comments (guard against a vacuous pass)")
    void should_flagPhoneReadsAndIgnoreComments_when_scannerRunOnSyntheticSources() {
        String leaking = "String p = booking.getClient().getPhoneNumber();";
        String commentOnly = "/** never calls getPhone() */ // getPhoneNumber() is forbidden\nint x = 1;";

        assertThat(USER_PHONE_READ.matcher(codeOnly(leaking)).find()).isTrue();
        assertThat(CLIENT_PHONE_CHAIN.matcher(codeOnly(leaking)).find()).isTrue();
        assertThat(USER_PHONE_READ.matcher(codeOnly(commentOnly)).find()).isFalse();
    }

    @Test
    @DisplayName("should_populateGuestPhoneOnlyInGetBooking_when_scanningBookingService")
    void should_populateGuestPhoneOnlyInGetBooking_when_scanningBookingService() throws IOException {
        String code = codeOnly(Files.readString(Path.of("src/main/java/com/beautica/booking/service/BookingService.java")));

        assertThat(code.split("withProviderVisibleGuestPhone\\(", -1).length - 1)
                .as("the wither is the only populator and is called exactly once - by the detail read").isEqualTo(1);
        int callAt = code.indexOf("withProviderVisibleGuestPhone(");
        int detailAt = code.indexOf("public BookingDetailResponse getBooking(");
        int nextMethodAt = code.indexOf("private static ClientAuthoredReviewResponse toClientAuthoredReview");
        assertThat(callAt).isBetween(detailAt, nextMethodAt);
    }
}
