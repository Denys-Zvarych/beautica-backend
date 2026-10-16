package com.beautica.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every real route template survives {@link PiiRedactor#redactPath} unchanged (Phase 371, audit-fix cycle 2,
 * finding 3). A masked static segment would make Sentry drop that endpoint's transactions and rename its events
 * to {@code [token]}. Scans the live controller set, so a future route with a long static word is caught here.
 */
@DisplayName("Route templates — never altered by path redaction")
class RouteTemplateRedactionTest {

    private static List<String> routeTemplates() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> templates = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.beautica")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = classMapping == null || classMapping.path().length == 0
                    ? new String[] {""} : classMapping.path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                String[] paths = methodMapping.path().length == 0 ? new String[] {""} : methodMapping.path();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        templates.add(prefix + path);
                    }
                }
            }
        }
        return templates;
    }

    @Test
    @DisplayName("all @RequestMapping templates under com.beautica — redactPath is the identity")
    void should_keepEveryRouteTemplate_when_redactingPath() throws Exception {
        List<String> templates = routeTemplates();
        Map<String, String> altered = new TreeMap<>();

        for (String template : templates) {
            String redacted = PiiRedactor.redactPath(template);
            if (!redacted.equals(template)) {
                altered.put(template, redacted);
            }
        }

        assertThat(templates).as("route scan must not be vacuous").hasSizeGreaterThan(100);
        assertThat(templates).anyMatch(t -> t.contains("verify-password-reset-otp"));
        assertThat(altered).as("route templates altered by redactPath").isEmpty();
    }
}
