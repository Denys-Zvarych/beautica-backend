package com.beautica.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit coverage for {@link NoDigitsValidator}, the implementation backing
 * {@link NoDigits} on the person-name fields (firstName / lastName) of the
 * registration, invite-accept and master-profile-update DTOs.
 *
 * <p>Contract pinned here:
 * <ul>
 *   <li>{@code null} / blank → valid ({@code @NotBlank} owns emptiness, §A).</li>
 *   <li>letters of any script (Cyrillic, Latin), hyphen, apostrophe (ASCII {@code '}
 *       and typographic {@code ’}), and spaces → valid.</li>
 *   <li>any Unicode decimal digit {@code \p{Nd}} anywhere in the value → invalid;
 *       this includes ASCII {@code 0-9} <em>and</em> other-script digits (Arabic-Indic,
 *       Devanagari, …), which is the whole reason the pattern is {@code \p{Nd}} and not
 *       {@code [0-9]}.</li>
 * </ul>
 *
 * <p>Style mirrors {@link StrongPasswordValidatorTest} — no Spring context, the validator
 * is instantiated directly and {@code isValid(value, null)} is called with a {@code null}
 * context (the validator never touches it).
 */
@DisplayName("NoDigitsValidator — unit")
class NoDigitsValidatorTest {

    private final NoDigitsValidator validator = new NoDigitsValidator();

    // ════════════════════════════════════════════════════════════════════════════
    // VALID — emptiness is owned by @NotBlank
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("accepts null so @NotBlank owns the required message (Bean Validation skips null)")
    void should_accept_when_valueIsNull() {
        boolean valid = validator.isValid(null, null);

        assertThat(valid).as("value=%s", (Object) null).isTrue();
    }

    @Test
    @DisplayName("accepts blank so @NotBlank owns the required message")
    void should_accept_when_valueIsBlank() {
        String input = "   ";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("value=%s", input).isTrue();
    }

    @Test
    @DisplayName("accepts an empty string (blank → @NotBlank owns it)")
    void should_accept_when_valueIsEmpty() {
        boolean valid = validator.isValid("", null);

        assertThat(valid).as("value=<empty>").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════════
    // VALID — letters / hyphen / apostrophe / space (any script)
    // ════════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "accepts \"{0}\"")
    @ValueSource(strings = {
            "Іван",            // pure Cyrillic letters
            "Anna",            // pure Latin letters
            "Грицюк-Філатов",  // hyphenated Cyrillic
            "Анна-Марія",      // hyphenated Cyrillic
            "О’Коннор",        // typographic apostrophe (U+2019)
            "Дар'я",           // ASCII apostrophe
            "Mary Jane"        // internal space
    })
    @DisplayName("accepts a digit-free name (letters of any script, hyphen, apostrophe, space)")
    void should_accept_when_valueHasNoDigit(String input) {
        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("value=%s", input).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════════
    // INVALID — contains a Unicode decimal digit (\p{Nd})
    // ════════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "rejects \"{0}\" (contains ASCII digit)")
    @ValueSource(strings = {"Іван2", "John123"})
    @DisplayName("rejects a name containing an ASCII digit 0-9")
    void should_reject_when_valueContainsAsciiDigit(String input) {
        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("value=%s", input).isFalse();
    }

    @Test
    @DisplayName("rejects a name containing an other-script digit (Arabic-Indic ٢) — locks in \\p{Nd}, not [0-9]")
    void should_reject_when_valueContainsOtherScriptDigit() {
        // U+0662 ARABIC-INDIC DIGIT TWO — matches \p{Nd} but not the ASCII range [0-9].
        // If the validator regressed to [0-9] this would slip through, so this case is the
        // explicit guard for the Unicode-digit contract.
        String input = "Ali٢";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("value=%s (contains U+0662 ARABIC-INDIC DIGIT TWO)", input).isFalse();
    }
}
