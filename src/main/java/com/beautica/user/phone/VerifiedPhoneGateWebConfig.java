package com.beautica.user.phone;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** B516 — registers {@link VerifiedPhoneGateInterceptor} for every route (it is a no-op while the flag is off). */
@Configuration
public class VerifiedPhoneGateWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<VerifiedPhoneGate> gate;

    public VerifiedPhoneGateWebConfig(ObjectProvider<VerifiedPhoneGate> gate) {
        this.gate = gate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new VerifiedPhoneGateInterceptor(gate));
    }
}
