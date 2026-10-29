package com.beautica.moderation.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.moderation.config.ModerationActionLinkProperties;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.entity.ModerationActionToken;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.repository.ModerationActionTokenRepository;
import com.beautica.moderation.service.ModerationActionTokenService.ClaimedToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Persistable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationActionTokenService — minting and claim release (phase 414 audit-fix)")
class ModerationActionTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Mock
    private ModerationActionTokenRepository repository;

    @Mock
    private ModerationService moderationService;

    @Mock
    private TokenGenerator tokenGenerator;

    private ModerationActionTokenService service;

    @BeforeEach
    void setUp() {
        service = new ModerationActionTokenService(repository, moderationService, tokenGenerator,
                new ModerationActionLinkProperties(Duration.ofDays(7)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("mint: ONE saveAll of new (Persistable#isNew) rows — no per-row save, so no SELECT-before-insert")
    @SuppressWarnings("unchecked")
    void should_saveAllNewRows_when_minting() {
        when(tokenGenerator.generateToken()).thenReturn("raw-1", "raw-2", "raw-3");
        when(tokenGenerator.hash(any())).thenAnswer(inv -> "h".repeat(60) + inv.<String>getArgument(0).substring(4));

        Map<ModerationAction, String> raw = service.mint(ReportTargetType.REVIEW, TARGET_ID,
                List.of(ModerationAction.REMOVE, ModerationAction.DISMISS, ModerationAction.RESTORE));

        ArgumentCaptor<List<ModerationActionToken>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        verify(repository, never()).save(any());
        assertThat(rows.getValue()).hasSize(3).allSatisfy(row -> {
            assertThat(row).isInstanceOf(Persistable.class);
            assertThat(row.isNew()).as("a minted row is new: persist, never merge").isTrue();
            assertThat(row.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        });
        assertThat(raw).containsEntry(ModerationAction.REMOVE, "raw-1").containsEntry(ModerationAction.RESTORE, "raw-3");
    }

    @Test
    @DisplayName("releaseClaim: un-uses exactly THIS claim (token hash + claim instant), in the repository")
    void should_releaseByHashAndClaimInstant_when_releasing() {
        when(tokenGenerator.hash("raw-token")).thenReturn("a".repeat(64));

        service.releaseClaim("raw-token",
                new ClaimedToken(ReportTargetType.REVIEW, TARGET_ID, ModerationAction.REMOVE, NOW));

        verify(repository).releaseClaim("a".repeat(64), NOW);
    }
}
