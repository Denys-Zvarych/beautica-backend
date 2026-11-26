package com.beautica.catalog.icon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * B540 (SVC-E12) — the app's 22 bundled category icons, copied byte-identical into
 * {@code classpath:catalog/category-icons/} with {@code manifest.json} ({@code {"<WIRE_KEY>": "category_<x>.svg"}},
 * from the mobile {@code categoryIconFor} switch). Loaded once at startup; a missing file or a malformed manifest
 * fails the boot (a packaging error, never a runtime one). {@code CategoryIconManifestTest} pins files ↔ manifest ↔
 * the committed hashes.
 */
@Component
public class BundledCategoryIcons {

    static final String FOLDER = "catalog/category-icons/";
    static final String MANIFEST = FOLDER + "manifest.json";
    private static final Pattern FILE_NAME = Pattern.compile("^category_[a-z_]+\\.svg$");

    private final Map<String, String> svgByWireName;

    public BundledCategoryIcons(ObjectMapper objectMapper) {
        this.svgByWireName = load(objectMapper);
    }

    /** The bundled SVG text for this wire name, if the app ships one. */
    public Optional<String> svgFor(String wireName) {
        return Optional.ofNullable(svgByWireName.get(wireName));
    }

    /** Wire names that have a bundled icon. */
    public Set<String> wireNames() {
        return svgByWireName.keySet();
    }

    private static Map<String, String> load(ObjectMapper objectMapper) {
        try (InputStream manifest = new ClassPathResource(MANIFEST).getInputStream()) {
            Map<String, String> files = objectMapper.readValue(manifest, new TypeReference<Map<String, String>>() { });
            Map<String, String> svgs = new TreeMap<>();
            for (Map.Entry<String, String> entry : files.entrySet()) {
                svgs.put(entry.getKey(), readSvg(entry.getValue()));
            }
            return Collections.unmodifiableMap(svgs);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot load the bundled category icon manifest", ex);
        }
    }

    private static String readSvg(String fileName) throws IOException {
        if (!FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalStateException("Bad bundled category icon file name in manifest: " + fileName);
        }
        try (InputStream in = new ClassPathResource(FOLDER + fileName).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
