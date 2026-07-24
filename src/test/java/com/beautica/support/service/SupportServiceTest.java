package com.beautica.support.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.config.SupportProperties;
import com.beautica.notification.EmailService;
import com.beautica.support.dto.ContactSupportRequest;
import com.beautica.support.dto.ContactSupportResponse;
import com.beautica.support.dto.SupportAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SupportService} — the Help / Contact-us business logic.
 *
 * <p>Pure Mockito slice: {@link SupportProperties} and {@link EmailService} are
 * mocked, no Spring context. Every defensive validation branch is exercised:
 * the unconfigured-inbox 503 guard, attachment count/size/total-size caps,
 * magic-byte content sniffing (declared type is ignored), and filename
 * sanitization. The happy path asserts {@link EmailService} is invoked with the
 * resolved support recipient and the correctly materialised attachment list.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupportService — Help / Contact-us business logic")
class SupportServiceTest {

    @Mock
    private SupportProperties supportProperties;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private SupportService supportService;

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static final String SUPPORT_INBOX = "support@beautica.test";
    private static final String SENDER_EMAIL = "client@beautica.test";

    private UUID senderUserId;
    private ContactSupportRequest request;

    @BeforeEach
    void setUp() {
        senderUserId = UUID.randomUUID();
        request = new ContactSupportRequest(
                "I cannot confirm my booking, please help.", "Booking issue");
    }

    // ── magic-byte payloads ─────────────────────────────────────────────────────
    // Padded to >= 12 bytes (MIN_SNIFF_BYTES) so the signature check is reached.

    private static byte[] jpegBytes() {
        byte[] b = new byte[20];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    private static byte[] pngBytes() {
        byte[] b = new byte[20];
        b[0] = (byte) 0x89;
        b[1] = 0x50;
        b[2] = 0x4E;
        b[3] = 0x47;
        return b;
    }

    private static byte[] webpBytes() {
        byte[] b = new byte[20];
        b[0] = 0x52; // R
        b[1] = 0x49; // I
        b[2] = 0x46; // F
        b[3] = 0x46; // F
        b[8] = 0x57; // W
        b[9] = 0x45; // E
        b[10] = 0x42; // B
        b[11] = 0x50; // P
        // VP8 chunk fourCC 'VP8 ' (lossy) at 12..15 — required since the WebP sniffer
        // now asserts a real VP8 chunk (a bare RIFF+WEBP container is rejected).
        b[12] = 0x56; // V
        b[13] = 0x50; // P
        b[14] = 0x38; // 8
        b[15] = 0x20; // ' '
        return b;
    }

    private static byte[] pdfBytes() {
        byte[] b = new byte[20];
        b[0] = 0x25; // %
        b[1] = 0x50; // P
        b[2] = 0x44; // D
        b[3] = 0x46; // F
        return b;
    }

    private static byte[] garbageBytes() {
        byte[] b = new byte[20];
        // No recognised signature; all zero except a benign filler.
        b[0] = 0x00;
        b[1] = 0x01;
        b[2] = 0x02;
        b[3] = 0x03;
        return b;
    }

    private static MockMultipartFile filePart(String filename, String declaredType, byte[] bytes) {
        return new MockMultipartFile("attachments", filename, declaredType, bytes);
    }

    private void stubConfiguredInbox() {
        when(supportProperties.getEmail()).thenReturn(SUPPORT_INBOX);
    }

    @SuppressWarnings("unchecked")
    private List<SupportAttachment> captureSentAttachments() {
        ArgumentCaptor<List<SupportAttachment>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendSupportContactEmail(
                eq(SUPPORT_INBOX),
                eq(senderUserId.toString()),
                eq(SENDER_EMAIL),
                eq(request.subject()),
                eq(request.message()),
                captor.capture());
        return captor.getValue();
    }

    // ── 503 guard ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unconfigured support inbox")
    class UnconfiguredInbox {

        @Test
        @DisplayName("contact — throws 503 BusinessException when SUPPORT_EMAIL is blank")
        void should_throw503_when_supportEmailBlank() {
            when(supportProperties.getEmail()).thenReturn("");

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("contact — throws 503 when SUPPORT_EMAIL is whitespace only")
        void should_throw503_when_supportEmailWhitespace() {
            when(supportProperties.getEmail()).thenReturn("   ");

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("contact — throws 503 when SUPPORT_EMAIL is null")
        void should_throw503_when_supportEmailNull() {
            when(supportProperties.getEmail()).thenReturn(null);

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            verifyNoInteractions(emailService);
        }
    }

    // ── happy path ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("contact — sends email to resolved inbox with no attachments when list is null")
        void should_sendEmail_when_noAttachments() {
            stubConfiguredInbox();

            ContactSupportResponse response =
                    supportService.contact(senderUserId, SENDER_EMAIL, request, null);

            assertThat(response).isEqualTo(ContactSupportResponse.accepted());
            List<SupportAttachment> sent = captureSentAttachments();
            assertThat(sent).isEmpty();
        }

        @Test
        @DisplayName("contact — sends email with empty attachment list when list is empty")
        void should_sendEmail_when_emptyAttachmentList() {
            stubConfiguredInbox();

            supportService.contact(senderUserId, SENDER_EMAIL, request, List.of());

            assertThat(captureSentAttachments()).isEmpty();
        }

        @Test
        @DisplayName("contact — materialises each allowed type with detected content-type and bytes")
        void should_materialiseAllAllowedTypes_when_validAttachments() {
            stubConfiguredInbox();

            List<MultipartFile> files = List.of(
                    filePart("photo.jpg", "image/jpeg", jpegBytes()),
                    filePart("shot.png", "image/png", pngBytes()),
                    filePart("pic.webp", "image/webp", webpBytes()),
                    filePart("doc.pdf", "application/pdf", pdfBytes()));

            supportService.contact(senderUserId, SENDER_EMAIL, request, files);

            List<SupportAttachment> sent = captureSentAttachments();
            assertThat(sent)
                    .extracting(SupportAttachment::filename, SupportAttachment::contentType)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("photo.jpg", "image/jpeg"),
                            org.assertj.core.groups.Tuple.tuple("shot.png", "image/png"),
                            org.assertj.core.groups.Tuple.tuple("pic.webp", "image/webp"),
                            org.assertj.core.groups.Tuple.tuple("doc.pdf", "application/pdf"));
            assertThat(sent.get(0).bytes()).isEqualTo(jpegBytes());
        }

        @Test
        @DisplayName("contact — accepts exactly 5 attachments (count boundary)")
        void should_accept_when_exactlyFiveAttachments() {
            stubConfiguredInbox();

            List<MultipartFile> files = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                files.add(filePart("f" + i + ".png", "image/png", pngBytes()));
            }

            supportService.contact(senderUserId, SENDER_EMAIL, request, files);

            assertThat(captureSentAttachments()).hasSize(5);
        }
    }

    // ── content-type sniffing (magic bytes, not declared header) ─────────────────

    @Nested
    @DisplayName("magic-byte content sniffing")
    class ContentSniffing {

        @Test
        @DisplayName("contact — detected type wins over a spoofed declared content-type")
        void should_useDetectedType_when_declaredHeaderLies() {
            stubConfiguredInbox();

            // Declared image/png but the bytes are actually a PDF — detected type must win.
            List<MultipartFile> files =
                    List.of(filePart("evil.png", "image/png", pdfBytes()));

            supportService.contact(senderUserId, SENDER_EMAIL, request, files);

            List<SupportAttachment> sent = captureSentAttachments();
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).contentType()).isEqualTo("application/pdf");
            // Extension is forced from the DETECTED type, not the declared .png.
            assertThat(sent.get(0).filename()).isEqualTo("evil.pdf");
        }

        @Test
        @DisplayName("contact — 400 when bytes match no allow-listed signature despite image/png header")
        void should_throw400_when_garbageBytesDeclaredAsImage() {
            stubConfiguredInbox();

            List<MultipartFile> files =
                    List.of(filePart("fake.png", "image/png", garbageBytes()));

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(emailService, never()).sendSupportContactEmail(
                    anyString(), anyString(), anyString(), any(), anyString(), anyList());
        }

        @Test
        @DisplayName("contact — 400 when file is shorter than the magic-byte sniff window")
        void should_throw400_when_fileTooSmallToSniff() {
            stubConfiguredInbox();

            // 4 bytes < MIN_SNIFF_BYTES (12), but non-empty so it passes the empty check.
            List<MultipartFile> files =
                    List.of(filePart("tiny.png", "image/png", new byte[]{1, 2, 3, 4}));

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("contact — detects WebP only when WEBP marker present at offset 8 (not bare RIFF)")
        void should_throw400_when_riffButNotWebp() {
            stubConfiguredInbox();

            // RIFF header but WAVE (not WEBP) at offset 8 → not allow-listed → 400.
            byte[] riffWave = new byte[20];
            riffWave[0] = 0x52; // R
            riffWave[1] = 0x49; // I
            riffWave[2] = 0x46; // F
            riffWave[3] = 0x46; // F
            riffWave[8] = 0x57; // W
            riffWave[9] = 0x41; // A
            riffWave[10] = 0x56; // V
            riffWave[11] = 0x45; // E
            List<MultipartFile> files = List.of(filePart("audio.webp", "image/webp", riffWave));

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("contact — 400 when RIFF+WEBP container present but VP8 chunk fourCC is missing/invalid")
        void should_reject_when_riffButNotWebp_vp8ChunkMissing() {
            stubConfiguredInbox();

            // Valid RIFF + 'WEBP' container header but the chunk fourCC at 12..15 is NOT a
            // real VP8 chunk ('JUNK') — a non-image RIFF renamed to .webp. Must be rejected
            // even though the 12-byte container magic matches.
            byte[] riffWebpNoVp8 = new byte[20];
            riffWebpNoVp8[0] = 0x52;  // R
            riffWebpNoVp8[1] = 0x49;  // I
            riffWebpNoVp8[2] = 0x46;  // F
            riffWebpNoVp8[3] = 0x46;  // F
            riffWebpNoVp8[8] = 0x57;  // W
            riffWebpNoVp8[9] = 0x45;  // E
            riffWebpNoVp8[10] = 0x42; // B
            riffWebpNoVp8[11] = 0x50; // P
            riffWebpNoVp8[12] = 0x4A; // J  (not 'V')
            riffWebpNoVp8[13] = 0x55; // U
            riffWebpNoVp8[14] = 0x4E; // N
            riffWebpNoVp8[15] = 0x4B; // K
            List<MultipartFile> files = List.of(filePart("fake.webp", "image/webp", riffWebpNoVp8));

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(emailService, never()).sendSupportContactEmail(
                    anyString(), anyString(), anyString(), any(), anyString(), anyList());
        }
    }

    // ── attachment limits ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("attachment limits")
    class AttachmentLimits {

        @Test
        @DisplayName("contact — 400 when more than 5 attachments")
        void should_throw400_when_tooManyAttachments() {
            stubConfiguredInbox();

            List<MultipartFile> files = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                files.add(filePart("f" + i + ".png", "image/png", pngBytes()));
            }

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(emailService, never()).sendSupportContactEmail(
                    anyString(), anyString(), anyString(), any(), anyString(), anyList());
        }

        @Test
        @DisplayName("contact — 400 when a single file exceeds 5 MB (declared size)")
        void should_throw400_when_singleFileExceedsMax() {
            stubConfiguredInbox();

            // MockMultipartFile.getSize() == bytes.length; build a >5MB png.
            byte[] huge = pngBytes();
            byte[] big = new byte[(int) (5L * 1024 * 1024) + 1];
            System.arraycopy(huge, 0, big, 0, huge.length);
            List<MultipartFile> files = List.of(filePart("big.png", "image/png", big));

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("contact — 400 when total size across files exceeds 5 MB")
        void should_throw400_when_totalSizeExceedsMax() {
            stubConfiguredInbox();

            // Two files each ~3 MB → individually under 5 MB, together over 5 MB total.
            int threeMb = 3 * 1024 * 1024;
            byte[] a = new byte[threeMb];
            byte[] b = new byte[threeMb];
            byte[] png = pngBytes();
            System.arraycopy(png, 0, a, 0, png.length);
            System.arraycopy(png, 0, b, 0, png.length);
            List<MultipartFile> files = List.of(
                    filePart("a.png", "image/png", a),
                    filePart("b.png", "image/png", b));

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(emailService, never()).sendSupportContactEmail(
                    anyString(), anyString(), anyString(), any(), anyString(), anyList());
        }

        @Test
        @DisplayName("contact — 400 when an attachment is empty")
        void should_throw400_when_attachmentEmpty() {
            stubConfiguredInbox();

            List<MultipartFile> files =
                    List.of(filePart("empty.png", "image/png", new byte[0]));

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("contact — 400 when reading the attachment bytes throws IOException")
        void should_throw400_when_fileReadFails() throws IOException {
            stubConfiguredInbox();

            MultipartFile broken = org.mockito.Mockito.mock(MultipartFile.class);
            when(broken.isEmpty()).thenReturn(false);
            when(broken.getSize()).thenReturn(100L);
            // Service now streams via getInputStream() (single-pass bounded read), not getBytes().
            when(broken.getInputStream()).thenThrow(new IOException("stream broke"));

            List<MultipartFile> files = List.of(broken);

            assertThatThrownBy(() ->
                    supportService.contact(senderUserId, SENDER_EMAIL, request, files))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── filename sanitization ────────────────────────────────────────────────────

    @Nested
    @DisplayName("filename sanitization")
    class FilenameSanitization {

        @Test
        @DisplayName("contact — strips path traversal segments from the filename")
        void should_stripPathTraversal_when_filenameHasPath() {
            stubConfiguredInbox();

            List<MultipartFile> files = List.of(
                    filePart("../../../etc/passwd.png", "image/png", pngBytes()));

            supportService.contact(senderUserId, SENDER_EMAIL, request, files);

            List<SupportAttachment> sent = captureSentAttachments();
            assertThat(sent.get(0).filename()).isEqualTo("passwd.png");
        }

        @Test
        @DisplayName("contact — reduces disallowed charset to underscores and forces detected extension")
        void should_reduceCharset_when_filenameHasSpecialChars() {
            stubConfiguredInbox();

            List<MultipartFile> files = List.of(
                    filePart("my photo (1)!@#.jpeg", "image/jpeg", jpegBytes()));

            supportService.contact(senderUserId, SENDER_EMAIL, request, files);

            List<SupportAttachment> sent = captureSentAttachments();
            // Spaces and specials → underscores; extension forced to detected .jpg.
            assertThat(sent.get(0).filename()).isEqualTo("my_photo__1____.jpg");
        }

        @Test
        @DisplayName("contact — forces detected extension overriding a misleading declared one")
        void should_forceDetectedExtension_when_extensionMisleading() {
            stubConfiguredInbox();

            // Real PNG bytes, but named .exe → output must end in .png.
            List<MultipartFile> files = List.of(
                    filePart("malware.exe", "application/octet-stream", pngBytes()));

            supportService.contact(senderUserId, SENDER_EMAIL, request, files);

            assertThat(captureSentAttachments().get(0).filename()).isEqualTo("malware.png");
        }

        @Test
        @DisplayName("contact — falls back to 'attachment' when filename is null")
        void should_fallbackToAttachment_when_filenameNull() {
            stubConfiguredInbox();

            List<MultipartFile> files = List.of(filePart(null, "image/png", pngBytes()));

            supportService.contact(senderUserId, SENDER_EMAIL, request, files);

            assertThat(captureSentAttachments().get(0).filename()).isEqualTo("attachment.png");
        }
    }
}
