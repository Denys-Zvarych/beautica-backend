package com.beautica.notification.telegram.webhook;

import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.link.ClaimResult;
import com.beautica.notification.telegram.link.TelegramLinkService;
import com.beautica.notification.telegram.webhook.TelegramBotReplies.Reply;
import com.beautica.notification.telegram.webhook.TelegramWebhookSecretVerifier.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.BufferedReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 406 audit — the controller's own failure paths, with the real {@link TelegramUpdateHandler} over a mocked
 * {@link TelegramLinkService}: an unexpected exception is a class-name-only log + empty 500 (no Postgres detail with a
 * chat id in any log line), and a full concurrency cap answers «try again» without touching the service.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("TelegramWebhookController — error and busy paths (phase 406 audit)")
class TelegramWebhookControllerTest {

    private static final long CHAT = 7_123_456_789L;
    private static final String TOKEN = "B".repeat(43);

    @Mock private TelegramWebhookSecretVerifier verifier;
    @Mock private TelegramLinkService linkService;
    @Mock private TelegramChatAllowList allowList;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramUpdateHandler(linkService, allowList);
        when(verifier.verify(any())).thenReturn(Outcome.ACCEPTED);
        lenient().when(allowList.allows(anyLong())).thenReturn(true);
    }

    private static MockHttpServletRequest start() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/telegram/webhook");
        request.setContent(TelegramWebhookTestSupport.text(CHAT, "/start " + TOKEN).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    @DisplayName("log no chat id when handler throws")
    void should_logNoChatId_when_handlerThrows(CapturedOutput output) throws Exception {
        when(linkService.claim(anyString(), anyLong())).thenThrow(new DataIntegrityViolationException(
                "ERROR: new row violates check constraint \"chk_tg_token_claim\" Detail: Failing row contains "
                        + "(claimed_chat_id=" + CHAT + ")"));
        TelegramWebhookController controller =
                new TelegramWebhookController(verifier, handler, objectMapper, new Semaphore(4));

        ResponseEntity<Reply> response = controller.receive(start());

        assertThat(response.getStatusCode()).as("poison update: 200 so Telegram drops it").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
        assertThat(output.getAll())
                .contains("event=telegram_update type=dropped result=DataIntegrityViolationException")
                .doesNotContain(Long.toString(CHAT)).doesNotContain("chk_tg_token_claim").doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @DisplayName("return 200 without reply when handler throws poison")
    @ValueSource(classes = {IllegalArgumentException.class, IllegalStateException.class})
    void should_return200WithoutReply_when_handlerThrowsPoison(Class<? extends RuntimeException> type,
                                                               CapturedOutput output) throws Exception {
        when(linkService.claim(anyString(), anyLong()))
                .thenThrow(type.getConstructor(String.class).newInstance("chat " + CHAT));
        TelegramWebhookController controller =
                new TelegramWebhookController(verifier, handler, objectMapper, new Semaphore(4));

        ResponseEntity<Reply> response = controller.receive(start());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
        assertThat(output.getAll()).contains("type=dropped result=" + type.getSimpleName())
                .doesNotContain(Long.toString(CHAT));
    }

    @Test
    @DisplayName("return 500 and log class only when handler throws transient")
    void should_return500AndLogClassOnly_when_handlerThrowsTransient(CapturedOutput output) throws Exception {
        when(linkService.claim(anyString(), anyLong()))
                .thenThrow(new CannotGetJdbcConnectionException("pool exhausted for chat " + CHAT));
        TelegramWebhookController controller =
                new TelegramWebhookController(verifier, handler, objectMapper, new Semaphore(4));

        ResponseEntity<Reply> response = controller.receive(start());

        assertThat(response.getStatusCode()).as("transient: Telegram redelivers")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNull();
        assertThat(output.getAll()).contains("event=telegram_update type=error result=CannotGetJdbcConnectionException")
                .doesNotContain(Long.toString(CHAT));
    }

    @Test
    @DisplayName("release slot when handler throws")
    void should_releaseSlot_when_handlerThrows() throws Exception {
        when(linkService.claim(anyString(), anyLong()))
                .thenThrow(new DataIntegrityViolationException("boom"))
                .thenReturn(ClaimResult.CLAIMED);
        Semaphore slots = new Semaphore(1);
        TelegramWebhookController controller = new TelegramWebhookController(verifier, handler, objectMapper, slots);

        controller.receive(start());
        ResponseEntity<Reply> second = controller.receive(start());

        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().text()).isEqualTo(TelegramBotReplies.ASK_PHONE);
        assertThat(slots.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("answer try again without service call when all slots busy")
    void should_answerTryAgainWithoutServiceCall_when_allSlotsBusy() throws Exception {
        TelegramWebhookController controller =
                new TelegramWebhookController(verifier, handler, objectMapper, new Semaphore(0));

        ResponseEntity<Reply> response = controller.receive(start());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().chatId()).isEqualTo(CHAT);
        assertThat(response.getBody().text()).isEqualTo(TelegramBotReplies.TRY_AGAIN);
        verifyNoInteractions(linkService);
    }

    /** A request that records whether its body stream was ever opened. */
    private static final class BodyTrackingRequest extends MockHttpServletRequest {
        private final AtomicBoolean bodyRead = new AtomicBoolean();
        private final long declaredLength;

        BodyTrackingRequest(byte[] content, long declaredLength) {
            super("POST", "/api/v1/telegram/webhook");
            setContent(content);
            this.declaredLength = declaredLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            bodyRead.set(true);
            return super.getInputStream();
        }

        @Override
        public BufferedReader getReader() throws UnsupportedEncodingException {
            bodyRead.set(true);
            return super.getReader();
        }

        @Override
        public long getContentLengthLong() {
            return declaredLength;
        }

        @Override
        public int getContentLength() {
            return (int) declaredLength;
        }
    }

    @ParameterizedTest(name = "verifier outcome {0}")
    @EnumSource(value = Outcome.class, names = {"REJECTED", "THROTTLED"})
    @DisplayName("a rejected or throttled call never opens the body stream and never reaches the handler")
    void should_neverReadBody_when_secretRejectedOrThrottled(Outcome outcome) throws Exception {
        when(verifier.verify(any())).thenReturn(outcome);
        BodyTrackingRequest request = new BodyTrackingRequest(
                TelegramWebhookTestSupport.text(CHAT, "/start " + TOKEN).getBytes(StandardCharsets.UTF_8), -1);
        Semaphore slots = new Semaphore(4);
        TelegramWebhookController controller = new TelegramWebhookController(verifier, handler, objectMapper, slots);

        ResponseEntity<Reply> response = controller.receive(request);

        assertThat(response.getStatusCode()).isEqualTo(outcome == Outcome.REJECTED
                ? HttpStatus.UNAUTHORIZED : HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNull();
        assertThat(request.bodyRead).as("body stream opened on a %s call", outcome).isFalse();
        assertThat(slots.availablePermits()).isEqualTo(4);
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("an oversized body with no Content-Length (chunked) is refused 413 after reading at most cap+1 bytes")
    void should_return413_when_chunkedBodyExceedsCapWithoutContentLength() throws Exception {
        byte[] oversized = new byte[TelegramWebhookController.MAX_BODY_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        BodyTrackingRequest request = new BodyTrackingRequest(oversized, -1);
        TelegramWebhookController controller =
                new TelegramWebhookController(verifier, handler, objectMapper, new Semaphore(4));

        ResponseEntity<Reply> response = controller.receive(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("a declared Content-Length over the cap is refused 413 without opening the body")
    void should_return413WithoutReadingBody_when_declaredLengthExceedsCap() throws Exception {
        BodyTrackingRequest request = new BodyTrackingRequest(new byte[0],
                TelegramWebhookController.MAX_BODY_BYTES + 1L);
        TelegramWebhookController controller =
                new TelegramWebhookController(verifier, handler, objectMapper, new Semaphore(4));

        ResponseEntity<Reply> response = controller.receive(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(request.bodyRead).isFalse();
    }

    @Test
    @DisplayName("the concurrency slot is returned after a normal reply")
    void should_releaseSlot_when_handlerSucceeds() throws Exception {
        when(linkService.claim(anyString(), anyLong())).thenReturn(ClaimResult.CLAIMED);
        Semaphore slots = new Semaphore(1);
        TelegramWebhookController controller = new TelegramWebhookController(verifier, handler, objectMapper, slots);

        ResponseEntity<Reply> first = controller.receive(start());
        ResponseEntity<Reply> second = controller.receive(start());

        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().text()).isEqualTo(TelegramBotReplies.ASK_PHONE);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().text()).as("not the busy reply").isEqualTo(TelegramBotReplies.ASK_PHONE);
        assertThat(slots.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("the concurrency slot is returned after a transient failure answered 500")
    void should_releaseSlot_when_handlerThrowsTransient() throws Exception {
        when(linkService.claim(anyString(), anyLong()))
                .thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        Semaphore slots = new Semaphore(1);
        TelegramWebhookController controller = new TelegramWebhookController(verifier, handler, objectMapper, slots);

        ResponseEntity<Reply> response = controller.receive(start());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(slots.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("a busy answer does not hand back a slot it never took (no over-release)")
    void should_notReleaseSlot_when_busyReplyGiven() throws Exception {
        Semaphore slots = new Semaphore(0);
        TelegramWebhookController controller = new TelegramWebhookController(verifier, handler, objectMapper, slots);

        controller.receive(start());
        controller.receive(start());

        assertThat(slots.availablePermits()).isZero();
        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("an unparseable body does not take a concurrency slot")
    void should_notTakeSlot_when_bodyMalformed() throws Exception {
        Semaphore slots = new Semaphore(1);
        TelegramWebhookController controller = new TelegramWebhookController(verifier, handler, objectMapper, slots);
        MockHttpServletRequest malformed = new MockHttpServletRequest("POST", "/api/v1/telegram/webhook");
        malformed.setContent("{not json".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Reply> response = controller.receive(malformed);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
        assertThat(slots.availablePermits()).isEqualTo(1);
    }
}
