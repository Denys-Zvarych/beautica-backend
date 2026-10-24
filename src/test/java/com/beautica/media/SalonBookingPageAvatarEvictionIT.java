package com.beautica.media;

import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Phase 377 — a salon master's avatar is rendered on its salon's public booking page
 * ({@code GET /api/v1/book/s/{slug}/info}, {@code masters[].avatarUrl}). An avatar upload must evict that
 * page after commit ({@code MediaService → MasterProfileCacheEvictor → SalonBookingInfoCacheEvictor
 * #evictByMasterSlug}), and ONLY the pages listing that master — a sibling salon's cached page stays
 * (per-key eviction, never {@code clear()}).
 *
 * <p>Same context as {@link MasterAvatarCacheEvictionIT} ({@link TestSecurityConfig} + mocked
 * {@link R2StorageService}) so it reuses that cached Spring context.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 377 — salon master avatar upload evicts only its salon's booking page, after commit")
class SalonBookingPageAvatarEvictionIT extends AbstractMediaIntegrationTest {

    private static final String CDN = "https://cdn.example/";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;

    @MockBean
    private R2StorageService r2StorageService;

    @Override
    protected TestRestTemplate restTemplate() {
        return restTemplate;
    }

    @Override
    protected ObjectMapper objectMapper() {
        return objectMapper;
    }

    @Override
    protected PasswordEncoder passwordEncoder() {
        return passwordEncoder;
    }

    @BeforeEach
    void stubStorage() {
        when(r2StorageService.isEnabled()).thenReturn(true);
        when(r2StorageService.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        doNothing().when(r2StorageService).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    private record SalonWithMaster(String salonSlug, String masterEmail) {
    }

    /** A salon with a booking slug and one bookable, slugged, loginable SALON_MASTER. */
    private SalonWithMaster salonWithBookableMaster(String tag) {
        String n = tag + "-" + Long.toString(System.nanoTime(), 36);
        UUID ownerId = insertSalonOwner("p377-owner-" + n + "@beautica.test");
        UUID salonId = insertSalon(ownerId, "P377 Avatar Salon " + n);
        String salonSlug = "p377-avatar-" + n;
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", salonSlug, salonId);
        String email = "p377-master-" + n + "@beautica.test";
        UUID userId = insertUser(email, "SALON_MASTER", salonId);
        jdbcTemplate.update("UPDATE users SET first_name = 'Avatarna', last_name = 'Maisterka' WHERE id = ?", userId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0, 0, true, ?, NOW(), NOW())",
                masterId, userId, salonId, "avatarna-m-" + n);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, masterId);
        return new SalonWithMaster(salonSlug, email);
    }

    private JsonNode salonPage(String slug) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/book/s/" + slug + "/info", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private static String firstMasterAvatar(JsonNode page) {
        JsonNode url = page.path("masters").get(0).path("avatarUrl");
        return url.isNull() || url.isMissingNode() ? null : url.asText();
    }

    private Cache pageCache() {
        return cacheManager.getCache(SalonBookingInfoCacheEvictor.CACHE);
    }

    @Test
    @DisplayName("upload: the master's salon page is evicted and serves the new avatar; a sibling salon's page stays cached")
    void should_serveNewAvatarOnSalonPage_when_salonMasterUploadsAvatar() throws Exception {
        SalonWithMaster target = salonWithBookableMaster("t");
        SalonWithMaster sibling = salonWithBookableMaster("s");
        assertThat(firstMasterAvatar(salonPage(target.salonSlug()))).as("warm-up: no avatar yet").isNull();
        salonPage(sibling.salonSlug());
        Object siblingCached = pageCache().get(sibling.salonSlug()).get();
        String token = loginAndGetToken(target.masterEmail());

        ResponseEntity<String> upload = restTemplate.exchange("/api/v1/media/avatar", HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);

        assertThat(upload.getStatusCode()).as("upload body=%s", upload.getBody()).isEqualTo(HttpStatus.OK);
        String uploadedUrl = objectMapper.readTree(upload.getBody()).path("data").path("avatarUrl").asText();
        assertThat(pageCache().get(target.salonSlug())).as("evicted after commit, not by the 60 s TTL").isNull();
        assertThat(firstMasterAvatar(salonPage(target.salonSlug()))).isEqualTo(uploadedUrl).startsWith(CDN);
        assertThat(pageCache().get(sibling.salonSlug()))
                .as("per-key eviction: a salon page not listing this master is untouched").isNotNull();
        assertThat(pageCache().get(sibling.salonSlug()).get()).isSameAs(siblingCached);
    }
}
