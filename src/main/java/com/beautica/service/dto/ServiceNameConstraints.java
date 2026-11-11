package com.beautica.service.dto;

/**
 * Single source of the service-definition {@code name} ALLOW-LIST (phase 464), used by
 * {@link CreateServiceDefinitionRequest}, {@link UpdateServiceDefinitionRequest},
 * {@link BulkServiceItemRequest} and {@code ServiceCatalogService.canonicalName}.
 *
 * <p>A canonical name (NFKC, internal space runs collapsed, stripped) may contain only letters
 * {@code \p{L}} of any script (incl. U+02BC modifier apostrophe), decimal digits {@code \p{Nd}},
 * the plain space U+0020 (never two in a row) and the punctuation
 * {@code - – — . , ' ’ ( ) + / & « » " № : % ! ?}, and must contain at least one letter or digit.
 * Everything else — emoji, symbols, marks, controls, format characters, every other space or
 * separator — is rejected by construction instead of being listed. The four Hangul fillers
 * (U+115F, U+1160, U+3164, U+FFA0) are categorised as letters ({@code Lo}) yet render blank, so
 * they are subtracted from the letter class explicitly.
 */
public final class ServiceNameConstraints {

    /** Invisible Hangul fillers that Unicode classifies as letters ({@code Lo}). */
    private static final String INVISIBLE_LETTERS = "\\x{115F}\\x{1160}\\x{3164}\\x{FFA0}";

    /** Allowed punctuation: {@code - – — . , ' ’ ( ) + / & « » " № : % ! ?}. */
    private static final String PUNCTUATION =
            "\\-\\x{2013}\\x{2014}.,'\\x{2019}()+/&\\x{00AB}\\x{00BB}\"\\x{2116}:%!?";

    private static final String LETTER_OR_DIGIT = "[\\p{L}\\p{Nd}&&[^" + INVISIBLE_LETTERS + "]]";

    private static final String WORD_CHAR =
            "[\\p{L}\\p{Nd}" + PUNCTUATION + "&&[^" + INVISIBLE_LETTERS + "]]";

    /**
     * The allow-list rule on the canonical (NFKC + space-collapsed + stripped) name: runs of
     * allowed non-space characters separated by single spaces, at least one letter or digit.
     */
    public static final String ALLOWED_NAME_REGEX =
            "^(?=.*" + LETTER_OR_DIGIT + ")" + WORD_CHAR + "+(?: " + WORD_CHAR + "+)*$";

    /**
     * Fast-400 DTO pattern on the RAW input: the same allowed characters, plus combining marks
     * ({@code \p{M}}, so a decomposed й = и + U+0306 reaches NFKC) and space separators
     * ({@code \p{Zs}}: NBSP and friends fold to U+0020 under NFKC; edge/duplicate spaces are
     * normalised by {@code canonicalName}). Anything still disallowed after normalisation — a
     * leftover mark, a non-folding space — is rejected there by {@link #ALLOWED_NAME_REGEX}.
     */
    public static final String NAME_REGEX =
            "^[\\p{L}\\p{Nd}\\p{M}\\p{Zs}" + PUNCTUATION + "&&[^" + INVISIBLE_LETTERS + "]]*$";

    public static final String NAME_REGEX_MESSAGE = "Name contains invalid characters";

    private ServiceNameConstraints() {
    }
}
