package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed config for the Turbosms SMS provider (Phase 13.1).
 *
 * <p>Binding prefix {@code app.sms.turbosms}. The {@code token} is env-driven
 * ({@code TURBOSMS_TOKEN}) and deliberately NOT {@code @Validated}/{@code @NotBlank}:
 * the app boots and serves all non-SMS traffic even when SMS is unconfigured,
 * matching {@code app.support} and the R2/Firebase keys (graceful degradation
 * rather than fail-fast). {@link TurbosmsService} surfaces a clean failure when a
 * send is attempted with a blank token.
 *
 * <p>The token is a secret — it is never logged (Anti-Bug §I-3 / java-skill
 * security rule).
 */
@ConfigurationProperties(prefix = "app.sms.turbosms")
public class TurbosmsProperties {

    /** Provider API token (Bearer). Blank when SMS is unconfigured. */
    private String token = "";

    /** Sender ("from") name registered with Turbosms; defaults to "Beautica". */
    private String senderName = "Beautica";

    /**
     * Provider message-send endpoint. Defaults to the production Turbosms URL;
     * overridable so integration tests can point the {@code RestClient} at a
     * WireMock stub.
     */
    private String baseUrl = "https://api.turbosms.ua/message/send.json";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
