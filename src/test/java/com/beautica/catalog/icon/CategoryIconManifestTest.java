package com.beautica.catalog.icon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B540 — the bundled icon set: manifest ↔ files ↔ committed hashes. {@code checksums.sha256} was taken from
 * {@code beautica-mobile/assets/icons/} at copy time; the eight rows below are the "SHA-256 (committed file)" column of
 * {@code beautica-mobile/tool/asset_docs/icons.README.md}, so a drift from the app's set is caught here.
 */
@DisplayName("Bundled category icons — manifest, files, hashes (B540)")
class CategoryIconManifestTest {

    private static final Path FOLDER = Path.of("src/main/resources/catalog/category-icons");

    private static final Map<String, String> README_COMMITTED_HASHES = Map.of(
            "category_hairdressing.svg", "043b6c184e493fa08bc46a87b7d3b12f81358f42cf1f0a33a558bddbf85481e8",
            "category_nail_service.svg", "50c25c5adace277a4b9389adfa91cab32663091f2c0980566e04edf7f2e879ae",
            "category_lash_extensions.svg", "d603110f434f64811c0714fdaabbfef782ccca58537a5d941ebd06257b52ebdf",
            "category_makeup.svg", "5f4ede77b0a4e3b0a51868232a943b44c7822c650066e74ac1d2f4003f17077c",
            "category_podology.svg", "704b3431678716e5a27f9ceb2e8e0a7f1446ceaa03f346132f61ba0eac004034",
            "category_barbering.svg", "6a85f2b3946c783fa7ce627a9612358fde2bd1bf462f80276c928fe22e32dd58",
            "category_beard_care.svg", "7e817e7d680a5301dfe1c0ba7549f39051ef269e29e0df6e11e578b14c17c068",
            "category_body_massage.svg", "7a222e0baa1c38d7d0833a5ed9ab50a932d501caf4c6665885ebcb498c371b18");

    private static Map<String, String> manifest() throws IOException {
        return new ObjectMapper().readValue(FOLDER.resolve("manifest.json").toFile(),
                new TypeReference<Map<String, String>>() { });
    }

    private static Set<String> svgFiles() throws IOException {
        try (Stream<Path> files = Files.list(FOLDER)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".svg"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test
    @DisplayName("22 wire keys, each mapped to a distinct file that is present; no unmapped file")
    void should_mapEveryFileOnce_when_manifestRead() throws IOException {
        Map<String, String> manifest = manifest();

        Set<String> files = svgFiles();

        assertThat(manifest).hasSize(22);
        assertThat(new TreeSet<>(manifest.values())).isEqualTo(files);
        assertThat(manifest.keySet()).allMatch(k -> k.matches("^[A-Z][A-Z0-9_]{0,99}$"));
    }

    @Test
    @DisplayName("every file matches checksums.sha256, and the README's committed-file hashes")
    void should_matchCommittedHashes_when_filesHashed() throws Exception {
        Map<String, String> checksums = new HashMap<>();
        for (String line : Files.readAllLines(FOLDER.resolve("checksums.sha256"), StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split("\\s+");
            checksums.put(parts[1], parts[0]);
        }

        Map<String, String> actual = new HashMap<>();
        for (String file : svgFiles()) {
            actual.put(file, sha256(FOLDER.resolve(file)));
        }

        assertThat(actual).isEqualTo(checksums);
        README_COMMITTED_HASHES.forEach((file, hash) -> assertThat(actual).containsEntry(file, hash));
    }

    @Test
    @DisplayName("every bundled icon passes the sanitiser (the backfill cannot be refused)")
    void should_passSanitizer_when_bundledIconsChecked() throws IOException {
        for (String file : svgFiles()) {
            byte[] raw = Files.readAllBytes(FOLDER.resolve(file));

            assertThat(CategoryIconSanitizer.sanitize(raw).sha256()).as(file).hasSize(64);
        }
    }
}
