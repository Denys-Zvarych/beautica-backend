package com.beautica.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Staging startup guard (Phase 404, D8). The only bot is prod's {@code @beautica_app_bot}, so a staging deploy with
 * {@code app.telegram.enabled=true && mode=live} would message real people — the same intent as
 * {@code scripts/_prod_denylist.sh}. Refuses to boot in that cell; {@code mode=capture} (Mailpit) and the flag off
 * both boot.
 */
@Component
@Profile("staging")
public class TelegramEnvGuard {

    public TelegramEnvGuard(TelegramProperties properties) {
        if (properties.enabled() && properties.mode() == TelegramMode.LIVE) {
            throw new IllegalStateException("Refusing to start: app.telegram.enabled=true with mode=live on the "
                    + "staging profile — the only bot is prod's. Use APP_TELEGRAM_ENABLED=false or "
                    + "app.telegram.mode=capture on staging.");
        }
    }
}
