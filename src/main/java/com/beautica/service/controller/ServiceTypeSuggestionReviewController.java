package com.beautica.service.controller;

import com.beautica.service.service.ServiceTypeSuggestionService;
import com.beautica.service.service.ServiceTypeSuggestionService.DecisionOutcome;
import com.beautica.service.service.ServiceTypeSuggestionService.ReviewView;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Token-authenticated admin review pages for service-type suggestions
 * (server-rendered HTML). Mirrors {@link CategoryRequestReviewController}.
 *
 * <p>All three routes are {@code permitAll} in {@link com.beautica.config.SecurityConfig}
 * — the unguessable single-use token IS the authentication. CSRF is globally
 * disabled (stateless JWT app), and the single-use token is itself the anti-forgery
 * mechanism for the form POSTs.
 *
 * <p>Critical: the {@code GET /review} page performs NO state change. Approval and
 * rejection happen only on the {@code POST} routes, so an email scanner / link
 * pre-fetcher that follows the GET link cannot auto-approve a suggestion.
 *
 * <p>Every outcome renders the same neutral result page shape so a caller cannot
 * probe whether a given token corresponds to a real, pending, expired, or already-
 * decided suggestion.
 */
@Controller
@RequestMapping("/api/v1/service-types/suggestions")
@RequiredArgsConstructor
@Validated
public class ServiceTypeSuggestionReviewController {

    // Token is 32 random bytes base64url-encoded (~43 chars). Cap generously to
    // block oversized-input abuse on this permitAll route while leaving headroom.
    private static final int MAX_TOKEN_LENGTH = 128;
    // base64url charset (A-Za-z0-9-_). Rejects any unexpected character before it
    // reaches routing/service, mirroring CategoryRequestReviewController.
    private static final String TOKEN_PATTERN = "[A-Za-z0-9\\-_]{1,128}";

    // The single-use token rides in the URL (query param on every route here). These
    // headers are the effective control against residual URL exposure: no-store stops
    // any intermediary proxy or browser cache from retaining the token-bearing response,
    // and no-referrer stops a later cross-origin navigation from leaking the token via
    // the Referer header.
    private static final String CACHE_CONTROL_NO_STORE = "no-store, no-cache, must-revalidate";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    private static final String REFERRER_POLICY_NO_REFERRER = "no-referrer";

    private final ServiceTypeSuggestionService suggestionService;

    /**
     * Runs before every handler in this controller. Every route here renders a
     * token-context page, so the anti-cache / anti-leak headers are applied uniformly
     * at one place rather than repeated per handler. View/model returns are unaffected.
     */
    @ModelAttribute
    void applyTokenPageSecurityHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        response.setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY_NO_REFERRER);
    }

    @GetMapping("/review")
    public String review(
            @RequestParam("token") @NotBlank @Size(max = MAX_TOKEN_LENGTH)
            @Pattern(regexp = TOKEN_PATTERN) String token,
            Model model) {
        ReviewView view = suggestionService.loadForReview(token);
        if (!view.valid()) {
            return neutralStatus(model);
        }
        model.addAttribute("token", token);
        model.addAttribute("categoryName", view.categoryName());
        model.addAttribute("suggestedName", view.suggestedName());
        model.addAttribute("description", view.description());
        return "service-type-suggestion-review";
    }

    @PostMapping("/approve")
    public String approve(
            @RequestParam("token") @NotBlank @Size(max = MAX_TOKEN_LENGTH)
            @Pattern(regexp = TOKEN_PATTERN) String token,
            Model model) {
        return renderDecision(suggestionService.approve(token), model);
    }

    @PostMapping("/reject")
    public String reject(
            @RequestParam("token") @NotBlank @Size(max = MAX_TOKEN_LENGTH)
            @Pattern(regexp = TOKEN_PATTERN) String token,
            Model model) {
        return renderDecision(suggestionService.reject(token), model);
    }

    private String renderDecision(DecisionOutcome outcome, Model model) {
        switch (outcome) {
            case APPROVED -> result(model, "Пропозицію прийнято",
                    "Пропозицію прийнято. Тип послуги буде додано після перевірки.");
            case REJECTED -> result(model, "Пропозицію відхилено",
                    "Пропозицію відхилено. Жодних змін у каталозі не зроблено.");
            case ALREADY_DECIDED -> result(model, "Рішення вже прийнято",
                    "Цю пропозицію вже опрацьовано. Повторні дії не потрібні.");
            case INVALID_OR_EXPIRED -> neutralStatus(model);
        }
        return "service-type-suggestion-result";
    }

    private String neutralStatus(Model model) {
        return result(model, "Посилання недоступне",
                "Це посилання недійсне або термін його дії минув.");
    }

    private String result(Model model, String heading, String message) {
        model.addAttribute("heading", heading);
        model.addAttribute("message", message);
        return "service-type-suggestion-result";
    }
}
