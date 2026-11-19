package com.beautica.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone-first hardening B17 (R6) — the identity error-code contract the app maps ({@code data.code} → HTTP status).
 * A LITERAL table here, checked three ways: every code the enums can emit is in the table (and nothing else), every
 * status matches what the exception produces, and the committed golden
 * {@code src/test/resources/contract/identity-error-codes.json} equals the table byte for byte. The mobile repo keeps a
 * byte copy ({@code tool/contract/identity-error-codes.json}, {@code scripts/sync_error_codes.sh}).
 *
 * <p>After a deliberate change, regenerate the golden with
 * {@code ./gradlew test --tests '*IdentityErrorCodeContractTest' -Dcontract.update=true} and sync the mobile copy.
 */
class IdentityErrorCodeContractTest {

    private static final Path GOLDEN = Path.of("src/test/resources/contract/identity-error-codes.json");
    private static final String UPDATE_PROPERTY = "contract.update";

    private record Entry(String code, int status, String family) {
    }

    /** The contract. Edit deliberately; the golden and the mobile copy follow. */
    private static final List<Entry> TABLE = List.of(
            // IdentityException.Code (B514/B516/B520)
            new Entry("PHONE_NOT_UA_MOBILE", 400, "identity"),
            new Entry("PHONE_ALREADY_REGISTERED", 409, "identity"),
            new Entry("REGISTRATION_TICKET_INVALID", 400, "identity"),
            new Entry("APP_CHECK_FAILED", 403, "identity"),
            new Entry("APP_CHECK_UNAVAILABLE", 503, "identity"),
            new Entry("PHONE_CHANGE_REQUIRES_OTP", 400, "identity"),
            new Entry("PHONE_NOT_VERIFIED", 409, "identity"),
            new Entry("INVITE_PHONE_REQUIRED", 400, "identity"),
            new Entry("INVITE_PHONE_MISMATCH", 400, "identity"),
            new Entry("INVITE_SMS_LIMIT", 429, "identity"),
            // VerificationException.Code — always 400
            new Entry("INVALID_CODE", 400, "verification"),
            new Entry("CODE_EXPIRED", 400, "verification"),
            new Entry("ALREADY_VERIFIED", 400, "verification"),
            new Entry("CURRENT_PASSWORD_INVALID", 400, "verification"),
            // EmailNotVerifiedException — login of a legacy account without a verified identity
            new Entry("EMAIL_NOT_VERIFIED", 403, "login"),
            // InviteTokenException.Code — default statuses (accept's INVITE_NOT_FOUND overrides to 404)
            new Entry("INVITE_NOT_FOUND", 400, "invite"),
            new Entry("INVITE_EXPIRED", 400, "invite"),
            new Entry("INVITE_USED", 400, "invite"),
            new Entry("INVITE_REVOKED", 400, "invite"),
            new Entry("INVITE_SALON_INACTIVE", 409, "invite"));

    @Test
    @DisplayName("should_listExactlyTheEmittableCodes_when_tableComparedWithEnums")
    void should_listExactlyTheEmittableCodes_when_tableComparedWithEnums() {
        Set<String> emittable = Stream.of(
                        Arrays.stream(IdentityException.Code.values()).map(Enum::name),
                        Arrays.stream(VerificationException.Code.values()).map(Enum::name),
                        Stream.of("EMAIL_NOT_VERIFIED"),
                        Arrays.stream(InviteTokenException.Code.values()).map(Enum::name))
                .flatMap(s -> s)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> tabled = TABLE.stream().map(Entry::code).collect(Collectors.toCollection(TreeSet::new));

        assertThat(tabled).hasSize(TABLE.size()).isEqualTo(emittable);
    }

    @Test
    @DisplayName("should_matchTheStatusEachExceptionProduces_when_tableComparedWithEnums")
    void should_matchTheStatusEachExceptionProduces_when_tableComparedWithEnums() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (IdentityException.Code code : IdentityException.Code.values()) {
            actual.put(code.name(), new IdentityException(code).getStatus().value());
        }
        for (VerificationException.Code code : VerificationException.Code.values()) {
            actual.put(code.name(), new VerificationException(code).getStatus().value());
        }
        actual.put("EMAIL_NOT_VERIFIED", HttpStatus.FORBIDDEN.value());
        for (InviteTokenException.Code code : InviteTokenException.Code.values()) {
            actual.put(code.name(), new InviteTokenException(code, "contract").getStatus().value());
        }

        Map<String, Integer> tabled = TABLE.stream().collect(Collectors.toMap(Entry::code, Entry::status));

        assertThat(tabled).isEqualTo(actual);
    }

    @Test
    @DisplayName("should_equalTheCommittedGolden_when_tableRendered")
    void should_equalTheCommittedGolden_when_tableRendered() throws IOException {
        String rendered = render();
        if (Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY))) {
            Files.writeString(GOLDEN, rendered, StandardCharsets.UTF_8);
        }

        String golden = Files.readString(GOLDEN, StandardCharsets.UTF_8);

        assertThat(golden)
                .as("golden out of date — rerun with -D" + UPDATE_PROPERTY + "=true and sync the mobile copy")
                .isEqualTo(rendered);
    }

    /** {@code [{code,status,family}]} sorted by code, two-space indent, one entry per line, trailing newline. */
    private static String render() {
        String body = TABLE.stream()
                .sorted(Comparator.comparing(Entry::code))
                .map(e -> "  {\"code\": \"" + e.code() + "\", \"status\": " + e.status()
                        + ", \"family\": \"" + e.family() + "\"}")
                .collect(Collectors.joining(",\n"));
        return "[\n" + body + "\n]\n";
    }
}
