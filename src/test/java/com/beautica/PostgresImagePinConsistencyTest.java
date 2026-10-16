package com.beautica;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural gate: every Testcontainers definition, docker-compose and the expected-major property
 * must agree on one PostgreSQL major, so CI never tests a different major than prod runs.
 */
@DisplayName("PostgreSQL image pin consistency")
class PostgresImagePinConsistencyTest {

    private static final Path BACKEND_ROOT = Path.of("").toAbsolutePath();
    private static final Pattern IMAGE_LITERAL = Pattern.compile("\"postgres:\\d");
    private static final Pattern IMAGE_TAG_MAJOR = Pattern.compile("^postgres:(\\d+)");

    @Test
    void should_haveNoPostgresImageLiteral_when_scanningTestSourcesExceptTheConstant() throws IOException {
        Path testSources = BACKEND_ROOT.resolve("src/test/java");
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(testSources)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("TestPostgresImage.java"))
                    .filter(p -> !p.getFileName().toString().equals("PostgresImagePinConsistencyTest.java"))
                    .filter(PostgresImagePinConsistencyTest::containsPostgresLiteral)
                    .toList();
        }

        assertThat(offenders).as("files with a postgres image literal instead of TestPostgresImage.IMAGE").isEmpty();
    }

    @Test
    void should_matchTestImage_when_readingDockerComposeImage() throws IOException {
        String compose = Files.readString(BACKEND_ROOT.resolve("docker/local/docker-compose.yml"));
        Matcher m = Pattern.compile("(?m)^\\s*image:\\s*(postgres:\\S+)\\s*$").matcher(compose);

        assertThat(m.find()).as("postgres image line in docker-compose.yml").isTrue();
        assertThat(m.group(1)).isEqualTo(TestPostgresImage.IMAGE);
    }

    @Test
    void should_matchTagMajor_when_readingExpectedMajorFromApplicationYml() throws IOException {
        String yml = Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));
        Matcher expected = Pattern.compile("(?m)^\\s*expected-major:\\s*(\\d+)\\s*$").matcher(yml);
        Matcher tag = IMAGE_TAG_MAJOR.matcher(TestPostgresImage.IMAGE);

        assertThat(expected.find()).as("beautica.db.expected-major in application.yml").isTrue();
        assertThat(tag.find()).isTrue();
        assertThat(expected.group(1)).isEqualTo(tag.group(1));
    }

    private static boolean containsPostgresLiteral(Path file) {
        try {
            return IMAGE_LITERAL.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
