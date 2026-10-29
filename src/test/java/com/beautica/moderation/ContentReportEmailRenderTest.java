package com.beautica.moderation;

import com.beautica.moderation.dto.ContentReportEmailModel;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.dto.ModerationActionLink;
import com.beautica.moderation.dto.ReportedContent;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.notification.EmailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Phase 414 D1–D3 — renders the real {@code templates/email/content-report.html} through a real Thymeleaf engine into a
 * real {@link MimeMessage}, one case per target type: escaped + bidi-visible UGC, stars, the photo, the profile name and
 * avatar, the reporter's comment, the open count and exactly the buttons the model carries.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Content report support e-mail — rendering (phase 414)")
class ContentReportEmailRenderTest {

    private static final String BASE = "https://api.beautica.test/api/v1/moderation/actions/";
    private static final String RLO = "‮";

    @Mock
    private JavaMailSender mailSender;

    private EmailService emailService;
    private MimeMessage message;

    @BeforeEach
    void setUp() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        emailService = new EmailService(mailSender, engine, "noreply@beautica.app");
        message = new MimeMessage(Session.getInstance(new Properties()));
        lenient().when(mailSender.createMimeMessage()).thenReturn(message);
    }

    @Test
    @DisplayName("REVIEW: <script> and U+202E in the text render HTML-escaped with the literal \\u202E escape")
    void should_escapeMarkupAndBidi_when_reviewTextHostile() throws Exception {
        ReportedContent content = ReportedContent.review("<script>alert(1)</script> evil" + RLO + "txet", (short) 1,
                false);

        String html = render(ReportTargetType.REVIEW, content, null, List.of());

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "\\u202E")
                .doesNotContain("<script>").doesNotContain(RLO);
    }

    @Test
    @DisplayName("REVIEW: 3 stars render as ★★★☆☆")
    void should_renderStars_when_reviewHasRating() throws Exception {
        String html = render(ReportTargetType.REVIEW, ReportedContent.review("ok", (short) 3, false), null, List.of());

        assertThat(html).contains("★★★☆☆");
    }

    @Test
    @DisplayName("CLIENT_REVIEW: text, stars and the auto-hidden notice for HIDDEN_PENDING")
    void should_renderClientReviewWithHiddenNotice_when_hiddenPending() throws Exception {
        String html = render(ReportTargetType.CLIENT_REVIEW, ReportedContent.review("rude client", (short) 5, true),
                null, List.of());

        assertThat(html).contains("rude client", "★★★★★", "автоматично приховано");
    }

    @Test
    @DisplayName("PORTFOLIO_PHOTO: <img src> is the public URL handed in by the loader (buildPublicUrl)")
    void should_renderPhoto_when_portfolioPhoto() throws Exception {
        String url = "https://pub.r2.test/masters/abc/portfolio/photo.webp";

        String html = render(ReportTargetType.PORTFOLIO_PHOTO, ReportedContent.photo(url), null, List.of());

        assertThat(html).contains("src=\"" + url + "\"");
    }

    @Test
    @DisplayName("MASTER_PROFILE: name (bidi-escaped, HTML-escaped) and avatar")
    void should_renderNameAndAvatar_when_masterProfile() throws Exception {
        String avatar = "https://pub.r2.test/users/u1/avatar.webp";

        String html = render(ReportTargetType.MASTER_PROFILE,
                ReportedContent.profile("Олена <b>" + RLO + "Ш", avatar), null, List.of());

        assertThat(html).contains("Олена &lt;b&gt;\\u202EШ", "src=\"" + avatar + "\"").doesNotContain(RLO);
    }

    @Test
    @DisplayName("SALON_PROFILE: name without avatar renders no broken image")
    void should_renderNameOnly_when_salonWithoutAvatar() throws Exception {
        String html = render(ReportTargetType.SALON_PROFILE, ReportedContent.profile("Salon Lux", ""), null,
                List.of());

        assertThat(html).contains("Salon Lux").doesNotContain("alt=\"Аватар\"");
    }

    @Test
    @DisplayName("CLIENT_PROFILE gone: «Вміст уже видалено», no content blocks")
    void should_renderGone_when_targetMissing() throws Exception {
        String html = render(ReportTargetType.CLIENT_PROFILE, ReportedContent.gone(), null, List.of());

        assertThat(html).contains("Вміст уже видалено").doesNotContain("data-block=\"profile\"");
    }

    @Test
    @DisplayName("reporter comment, reason, open count render; the comment is escaped and bidi-visible")
    void should_renderCommentReasonAndCount_when_present() throws Exception {
        String html = render(ReportTargetType.REVIEW, ReportedContent.review("x", (short) 2, false),
                "<i>fake</i>" + RLO, List.of());

        assertThat(html).contains("&lt;i&gt;fake&lt;/i&gt;\\u202E", "HARASSMENT", ">7<").doesNotContain(RLO);
    }

    @Test
    @DisplayName("buttons: exactly the links in the model, each href = the link URL, Ukrainian labels")
    void should_renderButtons_when_linksPresent() throws Exception {
        List<ModerationActionLink> links = List.of(
                new ModerationActionLink(ModerationAction.REMOVE, BASE + "tokenRemove"),
                new ModerationActionLink(ModerationAction.DISMISS, BASE + "tokenDismiss"),
                new ModerationActionLink(ModerationAction.RESTORE, BASE + "tokenRestore"));

        String html = render(ReportTargetType.REVIEW, ReportedContent.review("x", (short) 2, true), null, links);

        assertThat(html).contains("href=\"" + BASE + "tokenRemove\"", "href=\"" + BASE + "tokenDismiss\"",
                "href=\"" + BASE + "tokenRestore\"", "Видалити", "Залишити", "Відновити");
    }

    @Test
    @DisplayName("no RESTORE link in the model → no «Відновити» button")
    void should_omitRestoreButton_when_notMinted() throws Exception {
        List<ModerationActionLink> links = List.of(
                new ModerationActionLink(ModerationAction.REMOVE, BASE + "a"),
                new ModerationActionLink(ModerationAction.DISMISS, BASE + "b"));

        String html = render(ReportTargetType.REVIEW, ReportedContent.review("x", (short) 2, false), null, links);

        assertThat(html).doesNotContain("data-action=\"RESTORE\"").doesNotContain("Відновити");
    }

    @Test
    @DisplayName("audit-fix: the reporter is never named — not even by id")
    void should_notRenderReporterId_when_rendered() throws Exception {
        UUID reporterId = UUID.randomUUID();
        ContentReportEmailModel model = new ContentReportEmailModel(UUID.randomUUID(), ReportTargetType.REVIEW,
                UUID.randomUUID(), ReportReason.SPAM, reporterId, false, ReportedContent.review("x", (short) 1, false),
                null, 1, List.of());

        emailService.sendContentReportEmail("support@beautica.test", model).join();

        assertThat(message.getContent().toString()).doesNotContain(reporterId.toString())
                .doesNotContain("поскаржився");
    }

    @Test
    @DisplayName("audit-fix: URLs in review text and comment render defanged under the «не переходьте» label")
    void should_renderDefangedUrlsWithWarning_when_textHasLinks() throws Exception {
        String html = render(ReportTargetType.REVIEW, ReportedContent.review("go https://evil.com/x", (short) 1, false),
                "see www.phish.example", List.of());

        assertThat(html).contains("go hxxps://evil[.]com/x", "see www[.]phish[.]example",
                        "Текст користувача — не переходьте за посиланнями")
                .doesNotContain("https://evil.com").doesNotContain("www.phish.example");
    }

    @Test
    @DisplayName("phase 396: the burst send → [BURST] subject + burst banner")
    void should_flagBurst_when_burstSend() throws Exception {
        ContentReportEmailModel model = new ContentReportEmailModel(UUID.randomUUID(), ReportTargetType.REVIEW,
                UUID.randomUUID(), ReportReason.SPAM, UUID.randomUUID(), true,
                ReportedContent.review("x", (short) 1, false), null, 5, List.of());

        emailService.sendContentReportBurstEmail("support@beautica.test", model).join();

        assertThat(message.getSubject()).isEqualTo("[BURST] [Beautica Report] REVIEW — SPAM");
        assertThat(message.getContent().toString()).contains("Сплеск скарг");
    }

    @Test
    @DisplayName("the template renders user values with th:text only — never th:utext")
    void should_neverUseUtext_when_templateParsed() throws Exception {
        String template;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("templates/email/content-report.html")) {
            assertThat(in).as("template on the classpath").isNotNull();
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(template).doesNotContain("th:utext");
    }

    private String render(ReportTargetType type, ReportedContent content, String comment,
                          List<ModerationActionLink> links) throws Exception {
        ContentReportEmailModel model = new ContentReportEmailModel(UUID.randomUUID(), type, UUID.randomUUID(),
                ReportReason.HARASSMENT, UUID.randomUUID(), false, content, comment, 7, links);

        emailService.sendContentReportEmail("support@beautica.test", model).join();

        verify(mailSender).send(message);
        assertThat(message.getSubject()).isEqualTo("[Beautica Report] " + type.name() + " — HARASSMENT");
        return message.getContent().toString();
    }
}
