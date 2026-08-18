package com.beautica.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Placeholders — unit")
class PlaceholdersTest {

    @Test
    @DisplayName("should_substituteEveryPlaceholder_when_allAreMapped")
    void should_substituteEveryPlaceholder_when_allAreMapped() {
        String rendered = Placeholders.format(
                "Ви записані на {serviceName} до {masterName} {date} о {time}. Скасувати: {cancelUrl}",
                Map.of("serviceName", "Манікюр", "masterName", "Оксана", "date", "20.05.2026",
                        "time", "10:00", "cancelUrl", "https://beautica.app/c/abc"));

        assertThat(rendered).isEqualTo(
                "Ви записані на Манікюр до Оксана 20.05.2026 о 10:00. Скасувати: https://beautica.app/c/abc");
    }

    @Test
    @DisplayName("should_notExpandAPlaceholderThatArrivedAsAVALUE_when_aServiceNameContainsOne")
    void should_notExpandAPlaceholderThatArrivedAsAValue_when_aServiceNameContainsOne() {
        // The defect chained String.replace had: {serviceName} was substituted BEFORE {cancelUrl},
        // so a provider naming a service "Манікюр {cancelUrl}" got the guest's one-time cancel link
        // expanded a SECOND time, in a position of their choosing, inside platform copy.
        String rendered = Placeholders.format(
                "Запис на {serviceName}. Скасувати: {cancelUrl}",
                Map.of("serviceName", "Манікюр {cancelUrl}", "cancelUrl", "https://beautica.app/c/abc"));

        assertThat(rendered)
                .as("the literal braces from the DATA must survive as text, never be expanded")
                .isEqualTo("Запис на Манікюр {cancelUrl}. Скасувати: https://beautica.app/c/abc");
        assertThat(countOccurrences(rendered, "https://beautica.app/c/abc"))
                .as("the cancel link appears exactly once, where the TEMPLATE puts it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("should_emitTheValueLiterally_when_itContainsRegexReplacementSyntax")
    void should_emitTheValueLiterally_when_itContainsRegexReplacementSyntax() {
        // Without Matcher.quoteReplacement a "$1" in a value is read as a group reference and an
        // unmatched one throws — failing the whole send rather than rendering a service name.
        String rendered = Placeholders.format(
                "Запис на {serviceName}", Map.of("serviceName", "Ціна $1 \\ знижка"));

        assertThat(rendered).isEqualTo("Запис на Ціна $1 \\ знижка");
    }

    @Test
    @DisplayName("should_leaveAnUnmappedPlaceholderInPlace_when_theTemplateNamesAnUnknownVariable")
    void should_leaveAnUnmappedPlaceholderInPlace_when_theTemplateNamesAnUnknownVariable() {
        // Matches the chained-replace behaviour this replaced: a template referencing a variable the
        // caller does not supply degrades to a visible {foo}, never to a silently emptied slot.
        String rendered = Placeholders.format("Запис на {serviceName} {foo}",
                Map.of("serviceName", "Манікюр"));

        assertThat(rendered).isEqualTo("Запис на Манікюр {foo}");
    }

    @Test
    @DisplayName("should_returnNull_when_theTemplateIsNull")
    void should_returnNull_when_theTemplateIsNull() {
        assertThat(Placeholders.format(null, Map.of("a", "b"))).isNull();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
