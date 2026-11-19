package com.beautica.config;

import com.beautica.auth.filter.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code app.rate-limit.trust-forwarded-headers} binds into {@link ClientIpProperties} and the autowired resolver. */
@DisplayName("ClientIpProperties — app.rate-limit.trust-forwarded-headers binding")
class ClientIpPropertiesBindingTest {

    private static final String REMOTE_ADDR = "10.1.2.3";
    private static final String FORWARDED_CLIENT = "203.0.113.7";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClientIpProperties.class)
    static class BindingConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class, ClientIpResolver.class);

    /** Loads the real {@code application.yml} + {@code application-<profile>.yml} (same pattern as FirebaseLocalProfileContextTest). */
    private final ApplicationContextRunner realYmlRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindingConfig.class);

    private static MockHttpServletRequest forwardedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE_ADDR);
        request.addHeader("X-Forwarded-For", "6.6.6.6, " + FORWARDED_CLIENT);
        return request;
    }

    @Test
    @DisplayName("should_bindFalse_when_propertySetToFalse")
    void should_bindFalse_when_propertySetToFalse() {
        runner.withPropertyValues("app.rate-limit.trust-forwarded-headers=false").run(context -> {
            boolean bound = context.getBean(ClientIpProperties.class).trustForwardedHeaders();
            String resolved = context.getBean(ClientIpResolver.class).resolve(forwardedRequest());

            assertThat(bound).isFalse();
            assertThat(resolved).as("autowired resolver keys on the socket address").isEqualTo(REMOTE_ADDR);
        });
    }

    @Test
    @DisplayName("should_defaultToTrue_when_propertyAbsent")
    void should_defaultToTrue_when_propertyAbsent() {
        runner.run(context -> {
            boolean bound = context.getBean(ClientIpProperties.class).trustForwardedHeaders();
            String resolved = context.getBean(ClientIpResolver.class).resolve(forwardedRequest());

            assertThat(bound).isTrue();
            assertThat(resolved).as("autowired resolver keys on the rightmost X-Forwarded-For entry")
                    .isEqualTo(FORWARDED_CLIENT);
        });
    }

    @ParameterizedTest(name = "profiles [{0}] → trust-forwarded-headers = {1}")
    @CsvSource(delimiter = '|', value = {
            "local        | false",
            "prod         | true",
            "prod,staging | true",
            "test         | true"
    })
    @DisplayName("should_bindProfilePin_when_realYmlLoadedForProfile")
    void should_bindProfilePin_when_realYmlLoadedForProfile(String profiles, boolean expected) {
        realYmlRunner.withSystemProperties("spring.profiles.active=" + profiles).run(context -> {
            boolean bound = context.getBean(ClientIpProperties.class).trustForwardedHeaders();

            assertThat(bound).as("app.rate-limit.trust-forwarded-headers under profiles " + profiles)
                    .isEqualTo(expected);
        });
    }
}
