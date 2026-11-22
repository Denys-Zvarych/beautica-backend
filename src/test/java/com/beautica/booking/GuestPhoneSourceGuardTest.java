package com.beautica.booking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GST-80 / GST-D14 / CLI-D08 / BOOK-78 (user 2026-11-21) as a structural gate: booking detail carries NO phone for any
 * role - the client's phone lives only on the provider client card. {@code BookingDetailResponse} keeps the
 * {@code guestPhone} / {@code clientPhone} components (always null, for old app builds) but its code must not read a
 * phone from anything - neither the booking's guest phone nor any user/account type - and no main source may
 * populate them through a phone wither. Source scan (no ArchUnit on the classpath), comments stripped, so the javadoc
 * may still name the rule.
 */
@DisplayName("GuestPhoneSourceGuard - BookingDetailResponse never carries a phone (GST-80 / CLI-D08)")
class GuestPhoneSourceGuardTest {

    private static final Path DTO = Path.of("src/main/java/com/beautica/booking/dto/BookingDetailResponse.java");
    private static final Pattern USER_PHONE_READ = Pattern.compile("\\bget(Phone|PhoneNumber)\\s*\\(");
    private static final Pattern GUEST_PHONE_READ = Pattern.compile("\\bgetGuestPhone\\s*\\(");
    private static final Pattern CLIENT_PHONE_CHAIN = Pattern.compile("getClient\\s*\\(\\s*\\)\\s*\\.\\s*get\\w*Phone\\w*");
    /**
     * Any phone-named method ({@code getPhoneVerifiedAt(}, {@code phoneNumber(}, ...) invoked on a user/client
     * receiver - a {@code client}/{@code user}/{@code account} variable, any {@code *User}/{@code *Client} name, or a
     * {@code getClient()}/{@code getUser()} call.
     */
    static final Pattern USER_RECEIVER_PHONE_CALL = Pattern.compile(
            "\\b(?:account|\\w*[Uu]ser|\\w*[Cc]lient)\\s*(?:\\(\\s*\\))?\\s*\\.\\s*\\w*[Pp]hone\\w*\\s*\\(");
    /** A call or method reference of any provider-visible phone wither (the retired phase 386 / B518 shape). */
    static final Pattern PHONE_WITHER_CALL = Pattern.compile("(?:\\.|::)\\s*withProviderVisible\\w*Phone\\b");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /** Drops block comments (javadoc) and line comments so only executable code is scanned. */
    static String codeOnly(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String dtoCode() throws IOException {
        return codeOnly(Files.readString(DTO));
    }

    @Test
    @DisplayName("should_notReadAnyPhone_when_scanningBookingDetailResponse")
    void should_notReadAnyPhone_when_scanningBookingDetailResponse() throws IOException {
        String code = dtoCode();

        assertThat(USER_PHONE_READ.matcher(code).find())
                .as("BookingDetailResponse must not call getPhone()/getPhoneNumber() on any type").isFalse();
        assertThat(GUEST_PHONE_READ.matcher(code).find())
                .as("BookingDetailResponse must not read booking.getGuestPhone()").isFalse();
        assertThat(CLIENT_PHONE_CHAIN.matcher(code).find())
                .as("BookingDetailResponse must not chain getClient() into a phone getter").isFalse();
        assertThat(USER_RECEIVER_PHONE_CALL.matcher(code).find())
                .as("BookingDetailResponse must not call any phone-named method on a user/client receiver").isFalse();
        assertThat(code).doesNotContain("withProviderVisibleGuestPhone").doesNotContain(
                "withProviderVisibleRegisteredPhone");
    }

    @Test
    @DisplayName("should_flagAnyPhoneMethodOnUserReceivers_when_scannerRunOnSyntheticSources")
    void should_flagAnyPhoneMethodOnUserReceivers_when_scannerRunOnSyntheticSources() {
        List<String> leaking = List.of(
                "client.getPhoneVerifiedAt()", "booking.getClient().phoneNumber()", "user.getPhone()",
                "masterUser . maskedPhone ( )", "account.getPhoneE164()", "b.getUser().hasPhone()");
        List<String> clean = List.of(
                "booking.getGuestName()", "clientPhone.isBlank()", "client.getFirstName()", "this.phoneLabel()");

        leaking.forEach(src -> assertThat(USER_RECEIVER_PHONE_CALL.matcher(src).find()).as(src).isTrue());
        clean.forEach(src -> assertThat(USER_RECEIVER_PHONE_CALL.matcher(src).find()).as(src).isFalse());
    }

    @Test
    @DisplayName("the scanner itself flags a phone read and ignores comments (guard against a vacuous pass)")
    void should_flagPhoneReadsAndIgnoreComments_when_scannerRunOnSyntheticSources() {
        String leaking = "String p = booking.getClient().getPhoneNumber(); String g = booking.getGuestPhone();";
        String commentOnly = "/** never calls getPhone() */ // getGuestPhone() is forbidden\nint x = 1;";

        assertThat(USER_PHONE_READ.matcher(codeOnly(leaking)).find()).isTrue();
        assertThat(GUEST_PHONE_READ.matcher(codeOnly(leaking)).find()).isTrue();
        assertThat(CLIENT_PHONE_CHAIN.matcher(codeOnly(leaking)).find()).isTrue();
        assertThat(USER_PHONE_READ.matcher(codeOnly(commentOnly)).find()).isFalse();
        assertThat(GUEST_PHONE_READ.matcher(codeOnly(commentOnly)).find()).isFalse();
    }

    /** Per-file count of phone-wither calls (comments stripped); files with zero calls are omitted. */
    static Map<String, Integer> witherCallsByFile(Map<String, String> sources) {
        Map<String, Integer> counts = new TreeMap<>();
        sources.forEach((file, source) -> {
            Matcher m = PHONE_WITHER_CALL.matcher(codeOnly(source));
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                counts.put(file, n);
            }
        });
        return counts;
    }

    private static Map<String, String> allMainSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.put(MAIN_SOURCES.relativize(f).toString().replace('\\', '/'), Files.readString(f));
            }
        }
        return sources;
    }

    @Test
    @DisplayName("should_callNoPhoneWither_when_scanningEveryMainJavaFile")
    void should_callNoPhoneWither_when_scanningEveryMainJavaFile() throws IOException {
        Map<String, String> sources = allMainSources();

        Map<String, Integer> calls = witherCallsByFile(sources);

        assertThat(sources).hasSizeGreaterThan(100);
        assertThat(calls).as("no main source populates a booking-detail phone").isEmpty();
    }

    @Test
    @DisplayName("the cross-file call count flags any wither call or method reference and ignores comments")
    void should_flagWitherCall_when_scannerRunOnSyntheticSources() {
        String comment = "/** see {@code #withProviderVisibleGuestPhone} */ class D { }";
        String call = "class S { void a() { withPhone = detail.withProviderVisibleRegisteredPhone(booking); } }";
        String methodRef = "class M { Object y() { return list.stream().map(D::withProviderVisibleGuestPhone); } }";

        Map<String, Integer> found = witherCallsByFile(Map.of("D.java", comment, "S.java", call, "M.java", methodRef));

        assertThat(found).containsOnly(Map.entry("S.java", 1), Map.entry("M.java", 1));
    }

    @Test
    @DisplayName("should_notConsultPhoneAudience_when_scanningGetBooking")
    void should_notConsultPhoneAudience_when_scanningGetBooking() throws IOException {
        String code = codeOnly(Files.readString(
                Path.of("src/main/java/com/beautica/booking/service/BookingService.java")));

        int detailAt = code.indexOf("public BookingDetailResponse getBooking(");
        int nextMethodAt = code.indexOf("private BookingDetailResponse withClientBlockState(");

        assertThat(detailAt).isNotNegative();
        assertThat(nextMethodAt).isGreaterThan(detailAt);
        assertThat(code.substring(detailAt, nextMethodAt)).doesNotContain("isGuestPhoneAudience");
    }
}
