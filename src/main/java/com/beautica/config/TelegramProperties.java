package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Typed config for the Telegram channel (Phase 404, D7). Prefix {@code app.telegram}.
 *
 * <p><b>Fail-fast only where it matters.</b> With the flag off (the default) or in {@code capture} mode the
 * credentials may be blank — nothing reaches Telegram. With {@code enabled=true && mode=live} a blank token,
 * username or webhook secret fails the context at bind time: unlike Turbosms (blank token = loud at send time),
 * a live Telegram channel without credentials would silently route every message to SMS fallback.
 *
 * <p>In that same cell (and only there, so a dark deploy can never fail boot on a credential it does not use)
 * each value is format-checked: the webhook secret must use the Bot API {@code secret_token} alphabet with
 * ≥ 32 chars of entropy; the token
 * must look like {@code <digits>:<base64url>} (it is spliced into the URL path, so {@code /}, {@code ?} or
 * {@code #} would redirect the call); the username is stored without {@code @}; the API base URL must be
 * {@code https} (the token travels in the path) — plain {@code http} is allowed for loopback hosts only (tests).
 *
 * <p>No secret value is ever echoed in a failure message.
 *
 * @param enabled       master switch, env {@code APP_TELEGRAM_ENABLED}, default {@code false}
 * @param mode          {@code live} (default) or {@code capture}
 * @param botToken      env {@code TELEGRAM_BOT_TOKEN}; secret, never logged
 * @param botUsername   env {@code TELEGRAM_BOT_USERNAME}, without {@code @}
 * @param webhookSecret env {@code TELEGRAM_WEBHOOK_SECRET} ({@code openssl rand -hex 32})
 * @param apiBaseUrl    Bot API origin, default {@code https://api.telegram.org}
 * @param linkTokenTtl  lifetime of a chat-link token (used from phase 405), default 30 minutes (= phase 405 D6),
 *                      positive and at most {@link #MAX_LINK_TOKEN_TTL}
 * @param stagingAllowedChatIds env {@code TELEGRAM_STAGING_ALLOWED_CHAT_IDS} (Phase 432, D3): comma-separated tester
 *                      chat ids, each a positive long in plain decimal digits (private chat id = user id; group
 *                      ids are negative and rejected; {@code 0x1F} / {@code #1F} are rejected). Bound as strings and
 *                      parsed here, NOT by Spring conversion, whose failure report would print the whole raw value
 *                      (every tester's id) to the boot log. A blank entry, a negative, zero, a non-number or an
 *                      id beyond {@code Long.MAX_VALUE} fails boot on every profile, naming the property only.
 *                      Stored canonical (decimal, no sign/leading zeros); use {@link #stagingAllowedChatIdValues()}.
 *                      Read only on staging ({@code StagingTelegramChatAllowList}); every other environment ignores
 *                      it. Required non-empty on staging when enabled + live ({@link TelegramEnvGuard}).
 */
@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("live") TelegramMode mode,
        @DefaultValue("") String botToken,
        @DefaultValue("") String botUsername,
        @DefaultValue("") String webhookSecret,
        @DefaultValue("https://api.telegram.org") String apiBaseUrl,
        @DefaultValue("30m") Duration linkTokenTtl,
        @DefaultValue List<String> stagingAllowedChatIds) {

    /**
     * Upper bound on {@link #linkTokenTtl} (404 security audit INFO): a link token is a bearer capability for a
     * guest's phone hash, and phase 409 purges rows only one day past expiry, so a longer TTL is a misconfiguration.
     */
    public static final Duration MAX_LINK_TOKEN_TTL = Duration.ofHours(24);
    private static final Pattern WEBHOOK_SECRET = Pattern.compile("^[A-Za-z0-9_-]{32,256}$");
    /** Bot API token shape; shared with {@code TelegramBotApiClient}, which splices the token into the URL path. */
    public static final Pattern BOT_TOKEN = Pattern.compile("^[0-9]+:[A-Za-z0-9_-]+$");
    /** ASCII decimal digits only — no sign, no {@code 0x}/{@code #} radix prefix (Long.decode would accept those). */
    private static final Pattern CHAT_ID = Pattern.compile("^[0-9]{1,19}$");
    private static final Pattern BOT_USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{4,31}$");
    private static final Pattern API_BASE_URL =
            Pattern.compile("^(https://[^/?#\\s]+|http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?)$");

    public TelegramProperties {
        mode = mode == null ? TelegramMode.LIVE : mode;
        botToken = botToken == null ? "" : botToken.strip();
        botUsername = botUsername == null ? "" : botUsername.strip();
        webhookSecret = webhookSecret == null ? "" : webhookSecret.strip();
        if (enabled && mode == TelegramMode.LIVE) {
            requirePresent("app.telegram.bot-token (TELEGRAM_BOT_TOKEN)", botToken);
            requirePresent("app.telegram.bot-username (TELEGRAM_BOT_USERNAME)", botUsername);
            requirePresent("app.telegram.webhook-secret (TELEGRAM_WEBHOOK_SECRET)", webhookSecret);
            requireFormat("app.telegram.bot-token", botToken, BOT_TOKEN, "<digits>:<base64url>");
            requireFormat("app.telegram.bot-username", botUsername, BOT_USERNAME, "5-32 chars [A-Za-z0-9_], no '@'");
            requireFormat("app.telegram.webhook-secret", webhookSecret, WEBHOOK_SECRET,
                    "32-256 chars [A-Za-z0-9_-]");
            if (apiBaseUrl == null || !API_BASE_URL.matcher(apiBaseUrl).matches()) {
                throw new IllegalStateException(
                        "app.telegram.api-base-url must be an https origin (http only for localhost)");
            }
        }
        stagingAllowedChatIds = requirePositiveChatIds(stagingAllowedChatIds);
        OutboundTimeouts.requirePositive("app.telegram.link-token-ttl", linkTokenTtl);
        if (linkTokenTtl.compareTo(MAX_LINK_TOKEN_TTL) > 0) {
            throw new IllegalStateException("app.telegram.link-token-ttl must not exceed " + MAX_LINK_TOKEN_TTL);
        }
    }

    /** Redacted — the generated record {@code toString} would print the token and the webhook secret. */
    @Override
    public String toString() {
        return "TelegramProperties[enabled=" + enabled + ", mode=" + mode + ", botUsername=" + botUsername
                + ", apiBaseUrl=" + apiBaseUrl + ", linkTokenTtl=" + linkTokenTtl
                + ", stagingAllowedChatIds=<" + stagingAllowedChatIds.size() + " entries>"
                + ", botToken=" + (botToken.isEmpty() ? "<blank>" : "<redacted>")
                + ", webhookSecret=" + (webhookSecret.isEmpty() ? "<blank>" : "<redacted>") + "]";
    }

    /** The tester allow-list as numbers; every entry was validated (positive, decimal) at bind time. */
    public List<Long> stagingAllowedChatIdValues() {
        return stagingAllowedChatIds.stream().map(Long::valueOf).toList();
    }

    /**
     * Decimal digits only, positive, within {@code long}. Chat ids are never echoed: the message names the property
     * only (a private chat id is the user's id). Returns the canonical decimal form of each entry.
     */
    private static List<String> requirePositiveChatIds(List<String> chatIds) {
        if (chatIds == null) {
            return List.of();
        }
        List<String> canonical = new java.util.ArrayList<>(chatIds.size());
        for (String raw : chatIds) {
            String entry = raw == null ? "" : raw.strip();
            long chatId;
            try {
                chatId = CHAT_ID.matcher(entry).matches() ? Long.parseLong(entry) : 0L;
            } catch (NumberFormatException overflow) {
                chatId = 0L;
            }
            if (chatId <= 0) {
                throw new IllegalStateException("app.telegram.staging-allowed-chat-ids (TELEGRAM_STAGING_ALLOWED_CHAT_IDS) "
                        + "must be comma-separated positive decimal chat ids with no blank entries");
            }
            canonical.add(Long.toString(chatId));
        }
        return List.copyOf(canonical);
    }

    private static void requirePresent(String property, String value) {
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    property + " must be set when app.telegram.enabled=true and app.telegram.mode=live");
        }
    }

    private static void requireFormat(String property, String value, Pattern pattern, String expected) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalStateException(property + " has an invalid format (expected " + expected + ")");
        }
    }
}
