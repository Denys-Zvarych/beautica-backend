package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ContentReportRateLimitedException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.moderation.dto.CreateReportRequest;
import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.event.ContentReportCreatedEvent;
import com.beautica.moderation.repository.ContentReportRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentReportService — unit (phase 395)")
class ContentReportServiceTest {

    private static final UUID REPORTER_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-10-28T10:15:30.123456Z");
    /** Nanosecond clock: the service must truncate to the microseconds Postgres stores. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-28T10:15:30.123456789Z"), ZoneOffset.UTC);

    @Mock
    private ContentReportRepository repository;

    @Mock
    private ReportTargetVisibilityResolver resolver;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private LoadingCache<String, Bucket> buckets;

    private ContentReportService service;

    @BeforeEach
    void setUp() {
        buckets = bucketsWithCapacity(20);
        service = new ContentReportService(repository, resolver, eventPublisher, buckets, CLOCK);
    }

    // ── each target type: visible → created, invisible → 404 ─────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReportTargetType.class)
    @DisplayName("visible, not-owned target → report inserted, 201 result, creation event published")
    void should_createReport_when_targetVisible(ReportTargetType type) {
        CreateReportRequest request = new CreateReportRequest(type, TARGET_ID, ReportReason.OFFENSIVE, null);
        stubNoExisting(type);
        when(resolver.resolve(type, TARGET_ID, REPORTER_ID, Role.CLIENT))
                .thenReturn(Optional.of(new ReportTargetVisibilityResolver.ReportTarget(false)));
        when(repository.insertIgnoringDuplicate(any(), eq(REPORTER_ID), eq(type.name()), eq(TARGET_ID),
                eq("OFFENSIVE"), isNull(), eq(CREATED_AT))).thenReturn(1);

        ContentReportService.CreateReportResult result = service.createReport(REPORTER_ID, Role.CLIENT, request);

        assertThat(result.created()).isTrue();
        assertThat(result.report().status()).isEqualTo(ReportStatus.OPEN);
        assertThat(result.report().createdAt()).isEqualTo(CREATED_AT);
        verify(repository, never()).findById(any());
        ArgumentCaptor<ContentReportCreatedEvent> event = ArgumentCaptor.forClass(ContentReportCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reportId()).isEqualTo(result.report().id());
        assertThat(event.getValue().targetType()).isEqualTo(type);
        assertThat(event.getValue().targetId()).isEqualTo(TARGET_ID);
        assertThat(event.getValue().reporterUserId()).isEqualTo(REPORTER_ID);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReportTargetType.class)
    @DisplayName("invisible or missing target → 404, nothing inserted, no event")
    void should_throwNotFound_when_targetNotVisible(ReportTargetType type) {
        CreateReportRequest request = new CreateReportRequest(type, TARGET_ID, ReportReason.SPAM, null);
        stubNoExisting(type);
        when(resolver.resolve(type, TARGET_ID, REPORTER_ID, Role.SALON_MASTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReport(REPORTER_ID, Role.SALON_MASTER, request))
                .isInstanceOf(NotFoundException.class);

        verify(repository, never()).insertIgnoringDuplicate(any(), any(), anyString(), any(), anyString(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    // ── self-report ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("own content → 400, nothing inserted")
    void should_throwBadRequest_when_reporterOwnsTarget() {
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.MASTER_PROFILE, TARGET_ID, ReportReason.SPAM, null);
        stubNoExisting(ReportTargetType.MASTER_PROFILE);
        when(resolver.resolve(ReportTargetType.MASTER_PROFILE, TARGET_ID, REPORTER_ID, Role.INDEPENDENT_MASTER))
                .thenReturn(Optional.of(new ReportTargetVisibilityResolver.ReportTarget(true)));

        assertThatThrownBy(() -> service.createReport(REPORTER_ID, Role.INDEPENDENT_MASTER, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, never()).insertIgnoringDuplicate(any(), any(), anyString(), any(), anyString(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    // ── idempotent repeat (D4) ─────────────────────────────────────────────────

    @Test
    @DisplayName("existing report on the same target → returned as-is (200), no insert, no event, no token spent")
    void should_returnExisting_when_reportAlreadyExists() {
        UUID existingId = UUID.randomUUID();
        ContentReport existing = report(existingId);
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.OFFENSIVE, null);
        when(repository.findByReporterUserIdAndTargetTypeAndTargetId(REPORTER_ID, ReportTargetType.REVIEW, TARGET_ID))
                .thenReturn(Optional.of(existing));

        ContentReportService.CreateReportResult result = service.createReport(REPORTER_ID, Role.CLIENT, request);

        assertThat(result.created()).isFalse();
        assertThat(result.report().id()).isEqualTo(existingId);
        verify(repository, never()).insertIgnoringDuplicate(any(), any(), anyString(), any(), anyString(), any(), any());
        verifyNoInteractions(resolver, eventPublisher);
        assertThat(buckets.get(REPORTER_ID.toString()).getAvailableTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("a concurrent twin inserted first (ON CONFLICT → 0 rows) → winner returned as a repeat, no event")
    void should_returnWinner_when_concurrentInsertLostTheRace() {
        UUID winnerId = UUID.randomUUID();
        ContentReport winner = report(winnerId);
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.OFFENSIVE, null);
        when(repository.findByReporterUserIdAndTargetTypeAndTargetId(REPORTER_ID, ReportTargetType.REVIEW, TARGET_ID))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(resolver.resolve(ReportTargetType.REVIEW, TARGET_ID, REPORTER_ID, Role.CLIENT))
                .thenReturn(Optional.of(new ReportTargetVisibilityResolver.ReportTarget(false)));
        when(repository.insertIgnoringDuplicate(any(), any(), anyString(), any(), anyString(), any(), any())).thenReturn(0);

        ContentReportService.CreateReportResult result = service.createReport(REPORTER_ID, Role.CLIENT, request);

        assertThat(result.created()).isFalse();
        assertThat(result.report().id()).isEqualTo(winnerId);
        verifyNoInteractions(eventPublisher);
    }

    // ── OTHER needs a comment (D3) ─────────────────────────────────────────────

    @ParameterizedTest(name = "comment=\"{0}\"")
    @ValueSource(strings = {"", "   ", "abcd", "  ab  "})
    @DisplayName("OTHER with a missing or < 5-character (trimmed) comment → 400 before any lookup")
    void should_throwBadRequest_when_otherWithoutSufficientComment(String comment) {
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.OTHER, comment);

        assertThatThrownBy(() -> service.createReport(REPORTER_ID, Role.CLIENT, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(repository, resolver, eventPublisher);
    }

    @Test
    @DisplayName("OTHER with a null comment → 400")
    void should_throwBadRequest_when_otherWithNullComment() {
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.OTHER, null);

        assertThatThrownBy(() -> service.createReport(REPORTER_ID, Role.CLIENT, request))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(repository, resolver, eventPublisher);
    }

    @Test
    @DisplayName("OTHER with a 5-character comment → accepted; the comment is stored trimmed")
    void should_storeTrimmedComment_when_otherHasSufficientComment() {
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.OTHER, "  abcde \n");
        stubNoExisting(ReportTargetType.REVIEW);
        when(resolver.resolve(ReportTargetType.REVIEW, TARGET_ID, REPORTER_ID, Role.CLIENT))
                .thenReturn(Optional.of(new ReportTargetVisibilityResolver.ReportTarget(false)));
        when(repository.insertIgnoringDuplicate(any(), any(), anyString(), any(), anyString(), any(), any())).thenReturn(1);

        ContentReportService.CreateReportResult result = service.createReport(REPORTER_ID, Role.CLIENT, request);

        assertThat(result.created()).isTrue();
        verify(repository).insertIgnoringDuplicate(any(), eq(REPORTER_ID), eq("REVIEW"), eq(TARGET_ID),
                eq("OTHER"), eq("abcde"), eq(CREATED_AT));
    }

    @Test
    @DisplayName("a blank comment on a non-OTHER reason is stored as null")
    void should_storeNullComment_when_commentBlank() {
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.SPAM, "   ");
        stubNoExisting(ReportTargetType.REVIEW);
        when(resolver.resolve(ReportTargetType.REVIEW, TARGET_ID, REPORTER_ID, Role.CLIENT))
                .thenReturn(Optional.of(new ReportTargetVisibilityResolver.ReportTarget(false)));
        when(repository.insertIgnoringDuplicate(any(), any(), anyString(), any(), anyString(), any(), any())).thenReturn(1);

        service.createReport(REPORTER_ID, Role.CLIENT, request);

        verify(repository).insertIgnoringDuplicate(any(), eq(REPORTER_ID), eq("REVIEW"), eq(TARGET_ID),
                eq("SPAM"), isNull(), eq(CREATED_AT));
    }

    // ── rate limit (D6) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget exhausted → 429 with a 24 h Retry-After, before any visibility lookup")
    void should_throwRateLimited_when_budgetExhausted() {
        service = new ContentReportService(repository, resolver, eventPublisher, bucketsWithCapacity(0), CLOCK);
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.SPAM, null);
        stubNoExisting(ReportTargetType.REVIEW);

        assertThatThrownBy(() -> service.createReport(REPORTER_ID, Role.CLIENT, request))
                .isInstanceOf(ContentReportRateLimitedException.class)
                .extracting(ex -> ((ContentReportRateLimitedException) ex).getRetryAfterSeconds())
                .isEqualTo(Duration.ofHours(24).toSeconds());

        verifyNoInteractions(resolver, eventPublisher);
        verify(repository, never()).insertIgnoringDuplicate(any(), any(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("each new report attempt spends exactly one token from the reporter's own bucket")
    void should_spendOneToken_when_newReportAttempted() {
        CreateReportRequest request =
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.SPAM, null);
        stubNoExisting(ReportTargetType.REVIEW);
        when(resolver.resolve(ReportTargetType.REVIEW, TARGET_ID, REPORTER_ID, Role.CLIENT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReport(REPORTER_ID, Role.CLIENT, request))
                .isInstanceOf(NotFoundException.class);

        assertThat(buckets.get(REPORTER_ID.toString()).getAvailableTokens()).isEqualTo(19);
        assertThat(buckets.get(UUID.randomUUID().toString()).getAvailableTokens()).isEqualTo(20);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void stubNoExisting(ReportTargetType type) {
        when(repository.findByReporterUserIdAndTargetTypeAndTargetId(REPORTER_ID, type, TARGET_ID))
                .thenReturn(Optional.empty());
    }

    private static ContentReport report(UUID id) {
        ContentReport report = BeanUtils.instantiateClass(ContentReport.class);
        ReflectionTestUtils.setField(report, "id", id);
        ReflectionTestUtils.setField(report, "status", ReportStatus.OPEN);
        ReflectionTestUtils.setField(report, "createdAt", CREATED_AT);
        return report;
    }

    private static LoadingCache<String, Bucket> bucketsWithCapacity(long capacity) {
        return Caffeine.newBuilder().build(key -> {
            if (capacity == 0) {
                Bucket bucket = Bucket.builder()
                        .addLimit(BandwidthBuilder.builder().capacity(1).refillIntervally(1, Duration.ofDays(1)).build())
                        .build();
                bucket.tryConsume(1);
                return bucket;
            }
            return Bucket.builder()
                    .addLimit(BandwidthBuilder.builder().capacity(capacity).refillIntervally(capacity, Duration.ofDays(1))
                            .build())
                    .build();
        });
    }
}
