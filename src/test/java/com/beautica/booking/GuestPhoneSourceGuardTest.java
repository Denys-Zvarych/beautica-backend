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
 * Phase 386 HARD RULE as a structural gate: a registered user's phone must never reach a provider through
 * {@code BookingDetailResponse}. The guest phone has exactly one legal source - {@code booking.getGuestPhone()} - so
 * the DTO file must not read a phone from any user/account type. This is a source scan (no ArchUnit on the
 * classpath): any {@code getPhone(} / {@code getPhoneNumber(} call in the file, or a {@code .getClient()} chained into
 * one, fails the build. It scans the CODE only (comments stripped), so the javadoc may still name the rule.
 *
 * <p>B518 (GST-D14 / CLI-D08) carve-out: exactly ONE method may read a registered client's phone -
 * {@code withProviderVisibleRegisteredPhone}, and only behind an SMS-verified gate. That method body is cut out of the
 * scan (so every other read still fails), and separate tests pin its verified-only data gate and its single,
 * provider-audience-gated call site in {@code BookingService#getBooking}.
 */
@DisplayName("GuestPhoneSourceGuard - BookingDetailResponse never reads a user's phone (phase 386 hard rule)")
class GuestPhoneSourceGuardTest {

    private static final Path DTO = Path.of("src/main/java/com/beautica/booking/dto/BookingDetailResponse.java");
    private static final Pattern USER_PHONE_READ = Pattern.compile("\\bget(Phone|PhoneNumber)\\s*\\(");
    private static final Pattern CLIENT_PHONE_CHAIN = Pattern.compile("getClient\\s*\\(\\s*\\)\\s*\\.\\s*get\\w*Phone\\w*");
    /**
     * Any phone-named method ({@code getPhoneVerifiedAt(}, {@code phoneNumber(}, ...) invoked on a user/client
     * receiver - a {@code client}/{@code user}/{@code account} variable, any {@code *User}/{@code *Client} name, or a
     * {@code getClient()}/{@code getUser()} call. Booking receivers ({@code booking.getGuestPhone()}) do not match.
     */
    static final Pattern USER_RECEIVER_PHONE_CALL = Pattern.compile(
            "\\b(?:account|\\w*[Uu]ser|\\w*[Cc]lient)\\s*(?:\\(\\s*\\))?\\s*\\.\\s*\\w*[Pp]hone\\w*\\s*\\(");
    /** A call (receiver {@code .} or method reference {@code ::}) of the allow-listed wither - not its declaration. */
    static final Pattern REGISTERED_PHONE_WITHER_CALL =
            Pattern.compile("(?:\\.|::)\\s*withProviderVisibleRegisteredPhone\\b");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /** Drops block comments (javadoc) and line comments so only executable code is scanned. */
    static String codeOnly(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static final Path SERVICE = Path.of("src/main/java/com/beautica/booking/service/BookingService.java");
    /** The single allow-listed registered-phone path (B518); its body is excluded from the phone-read scan. */
    static final String REGISTERED_PHONE_SIGNATURE =
            "public BookingDetailResponse withProviderVisibleRegisteredPhone(Booking booking) {";

    private static String dtoCode() throws IOException {
        return codeOnly(Files.readString(DTO));
    }

    /** Returns the body (between the outer braces) of the method whose declaration ends with {@code signature}. */
    static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        assertThat(start).as("allow-listed method %s must exist", signature).isNotNegative();
        int open = start + signature.length() - 1;
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open + 1, i);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    /** The source with the one allow-listed method removed - what the "no other phone read" scan sees. */
    static String withoutAllowListedMethod(String code, String signature) {
        String body = methodBody(code, signature);
        int start = code.indexOf(signature);
        return code.substring(0, start) + code.substring(start + signature.length() + body.length() + 1);
    }

    @Test
    @DisplayName("should_notReadAnyPhoneFromAUser_when_scanningBookingDetailResponse")
    void should_notReadAnyPhoneFromAUser_when_scanningBookingDetailResponse() throws IOException {
        String code = withoutAllowListedMethod(dtoCode(), REGISTERED_PHONE_SIGNATURE);

        assertThat(USER_PHONE_READ.matcher(code).find())
                .as("BookingDetailResponse must not call getPhone()/getPhoneNumber() on any user type")
                .isFalse();
        assertThat(CLIENT_PHONE_CHAIN.matcher(code).find())
                .as("BookingDetailResponse must not chain getClient() into a phone getter")
                .isFalse();
        assertThat(USER_RECEIVER_PHONE_CALL.matcher(code).find())
                .as("BookingDetailResponse must not call any phone-named method on a user/client receiver")
                .isFalse();
    }

    @Test
    @DisplayName("should_flagAnyPhoneMethodOnUserReceivers_when_scannerRunOnSyntheticSources")
    void should_flagAnyPhoneMethodOnUserReceivers_when_scannerRunOnSyntheticSources() {
        List<String> leaking = List.of(
                "client.getPhoneVerifiedAt()", "booking.getClient().phoneNumber()", "user.getPhone()",
                "masterUser . maskedPhone ( )", "account.getPhoneE164()", "b.getUser().hasPhone()");
        List<String> clean = List.of(
                "booking.getGuestPhone()", "withPhones(guestPhone, clientPhone)", "clientPhone.isBlank()",
                "client.getFirstName()", "this.phoneLabel()");

        leaking.forEach(src -> assertThat(USER_RECEIVER_PHONE_CALL.matcher(src).find()).as(src).isTrue());
        clean.forEach(src -> assertThat(USER_RECEIVER_PHONE_CALL.matcher(src).find()).as(src).isFalse());
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
    @DisplayName("the carve-out removes ONLY the allow-listed method - a phone read in any other method still fails")
    void should_stillFlagPhoneReadOutsideAllowList_when_allowListedMethodIsCutOut() {
        String source = REGISTERED_PHONE_SIGNATURE + " if (x) { return this; } return y(); }\n"
                + "public BookingDetailResponse other(Booking b) { return z(b.getClient().getPhoneNumber()); }";

        String scanned = withoutAllowListedMethod(source, REGISTERED_PHONE_SIGNATURE);

        assertThat(scanned).doesNotContain("withProviderVisibleRegisteredPhone");
        assertThat(USER_PHONE_READ.matcher(scanned).find()).isTrue();
        assertThat(CLIENT_PHONE_CHAIN.matcher(scanned).find()).isTrue();
    }

    @Test
    @DisplayName("should_readRegisteredPhoneOnlyBehindVerifiedGate_when_scanningTheAllowListedWither")
    void should_readRegisteredPhoneOnlyBehindVerifiedGate_when_scanningTheAllowListedWither() throws IOException {
        String code = dtoCode();

        String body = methodBody(code, REGISTERED_PHONE_SIGNATURE).replaceAll("\\s+", " ");

        assertThat(code.split("withProviderVisibleRegisteredPhone\\(", -1).length - 1)
                .as("exactly one declaration of the allow-listed wither").isEqualTo(1);
        int gateAt = body.indexOf("client.getPhoneVerifiedAt() == null");
        assertThat(gateAt).as("the wither short-circuits on an unverified phone").isNotNegative();
        assertThat(body.indexOf("return this;")).as("the verified gate returns before any phone is attached")
                .isGreaterThan(gateAt);
        assertThat(body).contains("User client = booking.getClient();");
        assertThat(body.split("getPhoneNumber\\(", -1).length - 1)
                .as("phone read only in the null check and the single assignment").isEqualTo(2);
        assertThat(body.lastIndexOf("getPhoneNumber(")).isGreaterThan(body.indexOf("return this;"));
    }

    /** Per-file count of wither calls (comments stripped); files with zero calls are omitted. */
    static Map<String, Integer> witherCallsByFile(Map<String, String> sources) {
        Map<String, Integer> counts = new TreeMap<>();
        sources.forEach((file, source) -> {
            Matcher m = REGISTERED_PHONE_WITHER_CALL.matcher(codeOnly(source));
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
    @DisplayName("should_callRegisteredPhoneWitherExactlyOnceInAllMainSources_when_scanningEveryJavaFile")
    void should_callRegisteredPhoneWitherExactlyOnceInAllMainSources_when_scanningEveryJavaFile() throws IOException {
        Map<String, String> sources = allMainSources();

        Map<String, Integer> calls = witherCallsByFile(sources);

        assertThat(sources).hasSizeGreaterThan(100);
        assertThat(calls)
                .as("the registered-phone wither is called exactly once across src/main/java - in BookingService")
                .containsExactly(Map.entry("com/beautica/booking/service/BookingService.java", 1));
    }

    @Test
    @DisplayName("the cross-file call count flags a second call site and ignores declarations and comments")
    void should_flagSecondCallSite_when_witherCalledFromAnotherFile() {
        String service = "class S { void a() { withPhone = detail.withProviderVisibleRegisteredPhone(booking); } }";
        String dto = "/** see {@code #withProviderVisibleRegisteredPhone} */ class D { "
                + REGISTERED_PHONE_SIGNATURE + " return this; } }";
        String leak = "class L { Object x(D d, Booking b) { return d.withProviderVisibleRegisteredPhone(b); } }";
        String methodRef = "class M { Object y() { return list.stream().map(D::withProviderVisibleRegisteredPhone); } }";

        Map<String, Integer> clean = witherCallsByFile(Map.of("S.java", service, "D.java", dto));
        Map<String, Integer> leaking = witherCallsByFile(
                Map.of("S.java", service, "D.java", dto, "L.java", leak, "M.java", methodRef));

        assertThat(clean).containsExactly(Map.entry("S.java", 1));
        assertThat(leaking).containsOnly(Map.entry("S.java", 1), Map.entry("L.java", 1), Map.entry("M.java", 1));
        assertThat(leaking.values().stream().mapToInt(Integer::intValue).sum()).isNotEqualTo(1);
    }

    @Test
    @DisplayName("should_callRegisteredPhoneWitherOnlyFromProviderAudienceGate_when_scanningBookingService")
    void should_callRegisteredPhoneWitherOnlyFromProviderAudienceGate_when_scanningBookingService() throws IOException {
        String code = codeOnly(Files.readString(SERVICE)).replaceAll("\\s+", " ");

        int callAt = code.indexOf("withProviderVisibleRegisteredPhone(");
        assertThat(callAt).isBetween(code.indexOf("public BookingDetailResponse getBooking("),
                code.indexOf("private static ClientAuthoredReviewResponse toClientAuthoredReview"));
        assertThat(code.substring(0, callAt)).endsWith("} else if (booking.getClient().getPhoneVerifiedAt() != null"
                + " && authz.isGuestPhoneAudience() && !authz.isOwningClientViewer(actorUserId, booking)) {"
                + " withPhone = detail.");
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
