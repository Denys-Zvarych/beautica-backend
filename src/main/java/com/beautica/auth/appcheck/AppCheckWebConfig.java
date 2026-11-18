package com.beautica.auth.appcheck;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** B514 — registers {@link AppCheckInterceptor} for every route; it acts only on {@link RequireAppCheck} handlers. */
@Configuration
public class AppCheckWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<AppCheckGuard> guard;

    public AppCheckWebConfig(ObjectProvider<AppCheckGuard> guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AppCheckInterceptor(guard));
    }
}
