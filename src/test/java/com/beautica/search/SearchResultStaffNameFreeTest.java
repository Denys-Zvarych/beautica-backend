package com.beautica.search;

import com.beautica.search.dto.SalonSearchResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins why UserService does not clear the salon search caches when a SALON_OWNER renames
 * themself (phase 361): a cached salon search page carries no staff first/last name or title.
 * If this fails because a field was added, evict SearchCacheNames.SALONS_ALL for SALON_OWNER in
 * UserService#evictUserCachesAfterCommit and add an IT before updating this list.
 */
class SearchResultStaffNameFreeTest {

    @Test
    void should_carryNoStaffNameOrTitleField_when_salonSearchResultDeclared() {
        List<String> components = Arrays.stream(SalonSearchResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactlyInAnyOrder(
                "salonId", "name", "cityLabel", "districtLabel", "avatarUrl", "priceMin", "priceMax",
                "serviceNames", "street", "buildingNo", "locationNote", "matchedServiceNames");
    }
}
