package com.beautica.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the global Jackson {@code StreamReadConstraints} applied
 * to every request-parsing {@code ObjectMapper} (see {@link JsonConfig}).
 *
 * <p>Binding prefix: {@code app.json}.
 *
 * <h2>Why this exists</h2>
 * Jackson 2.18's default {@code maxStringLength} is ~20 MB. Without an explicit cap a
 * single multi-megabyte JSON string value (e.g. a {@code comment} / {@code note} field)
 * is fully materialised onto the heap during the parse phase, <em>before</em> any
 * {@code @Size(max = …)} Bean-Validation constraint can reject it. That is a
 * cross-cutting DoS-amplification gap for every DTO. Capping the string-token length at
 * the parser level rejects the oversized token at parse time, surfacing as a clean 400
 * via {@code GlobalExceptionHandler#handleMessageNotReadable}.
 *
 * <h2>Choosing the cap</h2>
 * The largest legitimate free-text request-body field in the codebase is bounded at
 * 4000 characters; worst-case UTF-8 (4 bytes/char) is ~16 KB. {@code maxStringLength}
 * counts decoded characters, so a 64 KB (65 536 char) cap leaves &gt;16× headroom over
 * the largest declared {@code @Size} while still blocking multi-MB payloads. No JSON
 * request body carries base64 image data (media upload is multipart, not JSON), so no
 * endpoint legitimately needs a larger string token.
 */
@Validated
@ConfigurationProperties(prefix = "app.json")
public class JsonConstraintsProperties {

    /** 64 KB in characters — generous headroom over the 4000-char largest free-text field. */
    private static final int DEFAULT_MAX_STRING_LENGTH = 65_536;

    /** Jackson default is 1000; request bodies are shallow, so a tighter bound is safe. */
    private static final int DEFAULT_MAX_NESTING_DEPTH = 64;

    /** Jackson default is 1000 digits; no numeric field needs anywhere near that. */
    private static final int DEFAULT_MAX_NUMBER_LENGTH = 100;

    /**
     * Maximum number of characters allowed in a single JSON string token. A token
     * exceeding this is rejected with a {@code StreamConstraintsException} during the
     * parse phase. Lower bound guards against a misconfiguration that would reject the
     * largest legitimate {@code @Size} field.
     */
    @Min(8_192)
    @Max(16_777_216)
    private int maxStringLength = DEFAULT_MAX_STRING_LENGTH;

    /** Maximum JSON nesting depth (objects/arrays) accepted by the parser. */
    @Min(8)
    @Max(1_000)
    private int maxNestingDepth = DEFAULT_MAX_NESTING_DEPTH;

    /** Maximum number of characters in a single numeric token accepted by the parser. */
    @Min(16)
    @Max(1_000)
    private int maxNumberLength = DEFAULT_MAX_NUMBER_LENGTH;

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public void setMaxStringLength(int maxStringLength) {
        this.maxStringLength = maxStringLength;
    }

    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    public void setMaxNestingDepth(int maxNestingDepth) {
        this.maxNestingDepth = maxNestingDepth;
    }

    public int getMaxNumberLength() {
        return maxNumberLength;
    }

    public void setMaxNumberLength(int maxNumberLength) {
        this.maxNumberLength = maxNumberLength;
    }
}
