package com.beautica.service.service;

import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 377 audit (perf F2): booking writes evict the salon catalogue ONLY ({@link
 * SalonCatalogCacheEvictor#evictCatalogOnly}) — never the public salon booking page — while bookability
 * writers keep {@link SalonCatalogCacheEvictor#evict}, which also evicts the page.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonCatalogCacheEvictor — catalogue-only vs catalogue + salon booking page")
class SalonCatalogCacheEvictorTest {

    private static final Path BOOKING_SERVICE_SOURCES = Path.of("src/main/java/com/beautica/booking/service");
    private static final String FULL_EVICT_CALL = "salonCatalogCacheEvictor.evict(";
    private static final String CATALOG_ONLY_CALL = "salonCatalogCacheEvictor.evictCatalogOnly(";

    @Mock private SalonBookingInfoCacheEvictor salonBookingInfoCacheEvictor;
    @InjectMocks private SalonCatalogCacheEvictor evictor;

    @Test
    @DisplayName("evict → also evicts the salon booking page")
    void should_evictSalonBookingPage_when_fullEvict() {
        UUID salonId = UUID.randomUUID();

        evictor.evict(salonId);

        verify(salonBookingInfoCacheEvictor).evictSalon(salonId);
    }

    @Test
    @DisplayName("evictCatalogOnly → never touches the salon booking page")
    void should_notEvictSalonBookingPage_when_catalogOnly() {
        evictor.evictCatalogOnly(UUID.randomUUID());

        verifyNoInteractions(salonBookingInfoCacheEvictor);
    }

    @Test
    @DisplayName("guard: no booking-package writer calls the full evict (booking writes must not evict salon-booking-slug-info)")
    void should_useCatalogOnly_when_bookingWriterEvicts() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(BOOKING_SERVICE_SOURCES)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }

        long catalogOnlyCalls = 0;
        for (Path source : sources) {
            String code = Files.readString(source);
            assertThat(code).as("%s must use evictCatalogOnly", source.getFileName()).doesNotContain(FULL_EVICT_CALL);
            catalogOnlyCalls += code.split(java.util.regex.Pattern.quote(CATALOG_ONLY_CALL), -1).length - 1;
        }

        assertThat(catalogOnlyCalls).as("non-vacuity: the booking call sites were found").isGreaterThanOrEqualTo(9);
    }
}
