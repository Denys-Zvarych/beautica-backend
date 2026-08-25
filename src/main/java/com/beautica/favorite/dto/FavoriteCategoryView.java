package com.beautica.favorite.dto;

/**
 * One chip in the favourites screen's category FILTER axis: a {@code platform_categories.name}
 * code and its Ukrainian {@code display_name}.
 *
 * <p>A {@code record} rather than two parallel lists on the enclosing DTO — {@code List<String>
 * categoryCodes}, {@code List<String> categoryLabels}) — because the pair travels together by
 * construction (see {@link FavoriteMasterResponse}'s "both or neither" rule) and a generated
 * OpenAPI/Dio client turns this shape into one typed model the mobile chip renderer iterates
 * directly; two same-length parallel lists would leave the client re-zipping them by index and
 * one dropped element away from a silent code/label mismatch.
 *
 * @param code  {@code platform_categories.name} (e.g. {@code MANICURE}) — the value denormalised
 *              into {@code service_definitions.category}, not the {@code BIGSERIAL} id, and the
 *              same vocabulary {@code ApprovedCategoryResponse.name} publishes
 * @param label that category's Ukrainian {@code platform_categories.display_name} (e.g. «Манікюр»)
 */
public record FavoriteCategoryView(String code, String label) {
}
