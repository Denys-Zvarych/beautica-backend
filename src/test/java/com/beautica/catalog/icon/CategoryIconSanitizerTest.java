package com.beautica.catalog.icon;

import com.beautica.common.exception.CategoryIconInvalidException;
import com.beautica.common.exception.CategoryIconInvalidException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * B539 (SVC-59) — table-driven over the shared fixture folder {@code src/test/resources/catalog/icon-fixtures}:
 * every {@code accept/} file passes (the 22 bundled icons included) and re-sanitises to identical bytes; every
 * {@code reject/<REASON>__<case>.svg} is rejected with exactly that reason.
 */
@DisplayName("CategoryIconSanitizer — SVC-59 allow-list, reject never strip, canonical bytes")
class CategoryIconSanitizerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/catalog/icon-fixtures");
    private static final String MINIMAL =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M0 0L24 24\"/></svg>";

    static Stream<Path> acceptFixtures() throws IOException {
        return list("accept");
    }

    static Stream<Path> rejectFixtures() throws IOException {
        return list("reject");
    }

    private static Stream<Path> list(String folder) throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES.resolve(folder))) {
            return files.filter(p -> p.toString().endsWith(".svg")).sorted().toList().stream();
        }
    }

    @Test
    @DisplayName("fixture folders hold the 22 bundled icons and one reject case per rule")
    void should_holdBundledIconsAndRejectCases_when_fixturesListed() throws IOException {
        long bundled = acceptFixtures().filter(p -> p.getFileName().toString().startsWith("category_")).count();

        long rejects = rejectFixtures().count();

        assertThat(bundled).isEqualTo(22);
        assertThat(rejects).isGreaterThanOrEqualTo(Reason.values().length - 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptFixtures")
    @DisplayName("accept fixture passes, output is canonical and idempotent")
    void should_acceptAndCanonicalise_when_fixtureIsValid(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);

        SanitizedIcon first = CategoryIconSanitizer.sanitize(raw);
        SanitizedIcon second = CategoryIconSanitizer.sanitize(first.bytes());

        String canonical = new String(first.bytes(), StandardCharsets.UTF_8);
        assertThat(canonical).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"")
                .doesNotContain("<?xml", "<!--", " id=", "data-name", "version=");
        assertThat(first.sha256()).matches("^[0-9a-f]{64}$");
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(second.sha256()).isEqualTo(first.sha256());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectFixtures")
    @DisplayName("reject fixture is refused with the reason in its file name")
    void should_rejectWithNamedReason_when_fixtureBreaksARule(Path file) throws IOException {
        Reason expected = Reason.valueOf(file.getFileName().toString().split("__")[0]);
        byte[] raw = Files.readAllBytes(file);

        CategoryIconInvalidException ex =
                catchThrowableOfType(() -> CategoryIconSanitizer.sanitize(raw), CategoryIconInvalidException.class);

        assertThat(ex).as("expected a rejection").isNotNull();
        assertThat(ex.getReason()).isEqualTo(expected);
    }

    @Test
    @DisplayName("expected.json (the table the B540 Python check reads) is exactly this sanitiser's verdict per fixture")
    void should_matchSharedVerdictTable_when_everyFixtureSanitised() throws IOException {
        java.util.Map<String, String> expected = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                FIXTURES.resolve("expected.json").toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() { });

        java.util.Map<String, String> actual = new java.util.TreeMap<>();
        for (String folder : java.util.List.of("accept", "reject")) {
            for (Path file : list(folder).toList()) {
                String verdict;
                try {
                    CategoryIconSanitizer.sanitize(Files.readAllBytes(file));
                    verdict = "OK";
                } catch (CategoryIconInvalidException ex) {
                    verdict = ex.getReason().name();
                }
                actual.put(folder + "/" + file.getFileName(), verdict);
            }
        }

        assertThat(actual).isEqualTo(new java.util.TreeMap<>(expected));
    }

    @Test
    @DisplayName("16 KiB + 1 byte is TOO_LARGE; exactly 16 KiB is not refused for size")
    void should_rejectTooLarge_when_overSixteenKibibytes() {
        byte[] atCap = padded(CategoryIconSanitizer.MAX_BYTES);
        byte[] overCap = padded(CategoryIconSanitizer.MAX_BYTES + 1);

        CategoryIconInvalidException over =
                catchThrowableOfType(() -> CategoryIconSanitizer.sanitize(overCap), CategoryIconInvalidException.class);
        SanitizedIcon accepted = CategoryIconSanitizer.sanitize(atCap);

        assertThat(over.getReason()).isEqualTo(Reason.TOO_LARGE);
        assertThat(accepted.bytes()).isEqualTo(MINIMAL.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("empty input is NOT_SVG")
    void should_rejectNotSvg_when_empty() {
        CategoryIconInvalidException ex =
                catchThrowableOfType(() -> CategoryIconSanitizer.sanitize(new byte[0]), CategoryIconInvalidException.class);

        assertThat(ex.getReason()).isEqualTo(Reason.NOT_SVG);
    }

    @Test
    @DisplayName("canonical form: sorted attributes, collapsed whitespace, currentColor spelling, self-closed")
    void should_writeCanonicalForm_when_inputIsMessy() {
        String messy = "<?xml version=\"1.0\"?>\n<!-- c -->\n<svg viewBox=\" 0  0 24\n24 \" "
                + "xmlns=\"http://www.w3.org/2000/svg\" id=\"Layer_1\"><path   fill=\"CURRENTCOLOR\" d=\"M0  0\n L24 24\">"
                + "<!-- inner --></path></svg>";

        SanitizedIcon icon = CategoryIconSanitizer.sanitize(messy.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(icon.bytes(), StandardCharsets.UTF_8)).isEqualTo(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">"
                        + "<path d=\"M0 0 L24 24\" fill=\"currentColor\"/></svg>");
    }

    /** {@link #MINIMAL} plus a trailing comment padding the raw size to exactly {@code size} bytes. */
    private static byte[] padded(int size) {
        String open = MINIMAL + "<!--";
        String close = "-->";
        int fill = size - open.length() - close.length();
        return (open + "x".repeat(fill) + close).getBytes(StandardCharsets.UTF_8);
    }
}
