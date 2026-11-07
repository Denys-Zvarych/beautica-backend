package com.beautica.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Phase 437 — logs the effective {@code app.client-version} Android window once at startup (INFO), so an operator
 * can confirm in the deploy log what {@code GET /api/v1/app/config} will serve before any client hits it. The values
 * are build numbers and a public Play URL — no secrets or PII.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientVersionStartupLogger implements InitializingBean {

    private final ClientVersionProperties clientVersionProperties;

    @Override
    public void afterPropertiesSet() {
        ClientVersionProperties.Android android = clientVersionProperties.android();
        log.info("App client version (android): minVersionCode={}, latestVersionCode={}, storeUrl={}",
                android.minVersionCode(), android.latestVersionCode(), android.storeUrl());
    }
}
