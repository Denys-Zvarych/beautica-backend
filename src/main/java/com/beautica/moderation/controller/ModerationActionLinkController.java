package com.beautica.moderation.controller;

import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.dto.ModerationActionOutcome;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ModerationActionLinkService;
import com.beautica.moderation.service.ModerationActionLinkService.ConfirmForm;
import com.beautica.moderation.service.ModerationActionLinkService.ConfirmView;
import com.beautica.moderation.service.ModerationActionLinkService.OutcomeView;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Phase 414 — the pages behind the support e-mail's one-click moderation buttons (server-rendered, Ukrainian only).
 * Both routes are {@code permitAll} (SecurityConfig): the single-use token is the capability.
 * <ul>
 *   <li>{@code GET /{token}} (D7) acts on NOTHING — it renders a confirm page with a fresh single-use nonce, so a mail
 *       scanner that prefetches the link cannot moderate anything.</li>
 *   <li>{@code POST /confirm} (D9) needs the token AND the page's current nonce, then runs the phase-396 resolve.</li>
 * </ul>
 * Every response is an HTML page with a D10 status, never JSON: malformed or missing parameters are an
 * {@code INVALID} page (no bean-validation 400), and an unexpected error is a detail-free 500 page. Hidden from the
 * OpenAPI spec: not an app contract.
 */
@Hidden
@Controller
@RequestMapping("/api/v1/moderation/actions")
@RequiredArgsConstructor
public class ModerationActionLinkController {

    private static final Logger log = LoggerFactory.getLogger(ModerationActionLinkController.class);

    static final String CONFIRM_VIEW = "moderation-action-confirm";
    static final String RESULT_VIEW = "moderation-action-result";

    private static final String CACHE_CONTROL_NO_STORE = "no-store, no-cache, must-revalidate";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    // same-origin, not the phase doc's no-referrer: a no-referrer page makes browsers send `Origin: null` on its own
    // form POST, which the scoped CORS entry for /confirm (SecurityConfig) must never admit. The page loads no
    // external asset and links nowhere, so same-origin leaks the token-bearing URL to no third party either.
    private static final String REFERRER_POLICY = "same-origin";
    private static final String ROBOTS_HEADER = "X-Robots-Tag";
    private static final String ROBOTS_NONE = "noindex, nofollow";
    private static final String CSP_HEADER = "Content-Security-Policy";
    // Audit-fix: the pages are inline-styled HTML with one same-origin form — nothing else may load, frame or post.
    static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; form-action 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'";

    private final ModerationActionLinkService actionLinkService;

    /** D12 + CSP: the anti-cache / anti-leak / anti-index / CSP headers on every page here, error pages included. */
    @ModelAttribute
    void applyTokenPageHeaders(HttpServletResponse response) {
        applyHeaders(response);
    }

    @GetMapping("/{token}")
    public String confirmPage(@PathVariable("token") String token, Model model, HttpServletResponse response) {
        ConfirmView view = actionLinkService.renderConfirm(token);
        return switch (view) {
            case ConfirmForm form -> confirmForm(form, model);
            case OutcomeView outcome -> result(outcome.outcome(), model, response);
        };
    }

    @PostMapping("/confirm")
    public String confirm(@RequestParam(value = "token", required = false) String token,
                          @RequestParam(value = "nonce", required = false) String nonce,
                          Model model, HttpServletResponse response) {
        return result(actionLinkService.confirm(token, nonce), model, response);
    }

    /** Last resort: an unexpected failure still renders a friendly page, never a stack trace or JSON body. */
    @ExceptionHandler(RuntimeException.class)
    public String onUnexpected(RuntimeException ex, Model model, HttpServletResponse response) {
        log.error("Moderation link page failed: {}", ex.getClass().getSimpleName());
        applyHeaders(response);
        return result(ModerationActionOutcome.FAILED, model, response);
    }

    private String confirmForm(ConfirmForm form, Model model) {
        model.addAttribute("token", form.token());
        model.addAttribute("nonce", form.nonce().nonce());
        model.addAttribute("targetLabel", targetLabel(form.nonce().targetType()));
        model.addAttribute("actionLabel", actionLabel(form.nonce().targetType(), form.nonce().action()));
        model.addAttribute("openReports", form.nonce().openReports());
        return CONFIRM_VIEW;
    }

    private static String result(ModerationActionOutcome outcome, Model model, HttpServletResponse response) {
        response.setStatus(outcome.httpStatus().value());
        model.addAttribute("heading", heading(outcome));
        model.addAttribute("message", message(outcome));
        return RESULT_VIEW;
    }

    private static void applyHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        response.setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY);
        response.setHeader(ROBOTS_HEADER, ROBOTS_NONE);
        response.setHeader(CSP_HEADER, CSP);
    }

    private static String heading(ModerationActionOutcome outcome) {
        return switch (outcome) {
            case DONE_REMOVED, DONE_DISMISSED, DONE_RESTORED -> "Готово";
            case DONE_ACTIONED -> "Скаргу підтверджено";
            case INVALID -> "Посилання недійсне";
            case ALREADY_RESOLVED -> "Скаргу вже розглянуто";
            case CONTENT_GONE -> "Вміст уже видалено";
            case FAILED -> "Не вдалося виконати дію";
        };
    }

    private static String message(ModerationActionOutcome outcome) {
        return switch (outcome) {
            case DONE_REMOVED -> "Рішення «Видалити» застосовано. Усі скарги на цей об'єкт закрито.";
            case DONE_DISMISSED -> "Скарги відхилено, вміст залишено. Усі скарги на цей об'єкт закрито.";
            case DONE_RESTORED -> "Відгук знову видимий. Усі скарги на нього закрито.";
            case DONE_ACTIONED -> "Скаргу підтверджено. Усі скарги на цей профіль закрито. Напишіть власнику профілю "
                    + "за адресою з листа.";
            case INVALID -> "Посилання недійсне або термін його дії минув. Якщо ви відкривали сторінку "
                    + "підтвердження кілька разів, оновіть сторінку з листа або скористайтеся чергою модерації.";
            case ALREADY_RESOLVED -> "Скаргу вже розглянуто. Повторні дії не потрібні.";
            case CONTENT_GONE -> "Вміст уже видалено. Скарги на нього закрито.";
            case FAILED -> "Спробуйте пізніше або скористайтеся чергою модерації.";
        };
    }

    private static String targetLabel(ReportTargetType type) {
        return switch (type) {
            case REVIEW -> "Відгук клієнта";
            case CLIENT_REVIEW -> "Відгук про клієнта";
            case PORTFOLIO_PHOTO -> "Фото з портфоліо";
            case MASTER_PROFILE -> "Профіль майстра";
            case SALON_PROFILE -> "Профіль салону";
            case CLIENT_PROFILE -> "Профіль клієнта";
        };
    }

    /** A profile REMOVE only confirms the report (ACTIONED) — it is never labelled as a deletion. */
    private static String actionLabel(ReportTargetType type, ModerationAction action) {
        return switch (action) {
            case REMOVE -> type.isProfile() ? "Підтвердити скаргу" : "Видалити";
            case DISMISS -> "Залишити";
            case RESTORE -> "Відновити";
        };
    }
}
