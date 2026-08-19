package com.beautica.config;

import com.beautica.notification.sms.NoOpSmsService;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.sms.TurbosmsService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.7 — the {@code app.booking.sms.enabled} bean-selection matrix.
 *
 * <h2>Why assert by CLASS, and why assert the count</h2>
 * The expensive failure mode of a conditional-bean gate is not "the wrong messages went out", it is
 * a context that will not start: two {@link SmsService} beans (if a stray {@code @Service} is
 * restored on {@link TurbosmsService}) or none (if both conditions evaluate false). Neither shows up
 * in a test that only checks the flag "took effect" without exploding — so every row below names the
 * expected implementation class AND pins the bean count at exactly one.
 *
 * <p><b>{@link ApplicationContextRunner}, not {@code @SpringBootTest}</b> (Anti-Bug §M1). The
 * subject is one {@code @Configuration} class and the {@code Environment} it reads; booting Tomcat,
 * Flyway and a Postgres container to observe a conditional would cost minutes per run and prove
 * nothing extra. The runner evaluates real {@code @ConditionalOnProperty} conditions against a real
 * environment, which is the entire mechanism under test.
 */
@DisplayName("SMS feature gate — app.booking.sms.enabled selects the SmsService implementation")
class SmsFeatureGateTest {

    private static final String FLAG = "app.booking.sms.enabled";

    /** Matched by simple name so the rule survives a package move of the interface. */
    private static final String OTP_SENDER_SIMPLE_NAME = "OtpSmsSender";

    /** The one adapter that implements it, and therefore the one unavoidable exemption. */
    private static final String OTP_SENDER_IMPL_SIMPLE_NAME = "TurbosmsOtpSmsSender";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SmsDependencies.class, SmsConfig.class);

    @Test
    @DisplayName("absent flag — a brand-new environment resolves the no-op sender")
    void should_registerNoOpSmsService_when_flagIsAbsent() {
        // The state of a brand-new environment: no property set anywhere. Spending money must be an
        // explicit act, so "never heard of the flag" has to mean OFF, not "provider default".
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(SmsService.class).isInstanceOf(NoOpSmsService.class);
        });
    }

    @Test
    @DisplayName("flag=false — the no-op sender is resolved")
    void should_registerNoOpSmsService_when_flagIsFalse() {
        runner.withPropertyValues(FLAG + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(SmsService.class).isInstanceOf(NoOpSmsService.class);
        });
    }

    @Test
    @DisplayName("flag=true — the paid Turbosms adapter is resolved, blank token and all")
    void should_registerTurbosmsService_when_flagIsTrue() {
        // SmsDependencies leaves TurbosmsProperties at its defaults, so the token here is BLANK — and
        // that is deliberate: a blank TURBOSMS_TOKEN is NOT a boot failure (contrast S3Config, which
        // fails fast on blank R2 credentials). The token is optional by design so the app serves all
        // non-SMS traffic without it, and TurbosmsService#send raises a clean SmsDeliveryException on
        // first use. "Gate on, credential missing" must be loud at send time, never silent like
        // "gate off".
        runner.withPropertyValues(FLAG + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(SmsService.class).isInstanceOf(TurbosmsService.class);
        });
    }

    @Test
    @DisplayName("every valid flag value leaves EXACTLY one SmsService bean — never two, never none")
    void should_registerExactlyOneSmsServiceBean_when_flagTakesAnyValidValue() {
        // Every injection point (seven services) takes SmsService by constructor, so a duplicate is
        // a NoUniqueBeanDefinitionException at boot and an absence is a NoSuchBeanDefinition — both
        // total outages, in every profile, discovered only on deploy.
        runner.run(context -> assertThat(context.getBeanNamesForType(SmsService.class)).hasSize(1));
        runner.withPropertyValues(FLAG + "=false")
                .run(context -> assertThat(context.getBeanNamesForType(SmsService.class)).hasSize(1));
        runner.withPropertyValues(FLAG + "=true")
                .run(context -> assertThat(context.getBeanNamesForType(SmsService.class)).hasSize(1));
    }

    @Test
    @DisplayName("flag=false — the paid adapter is absent from the context entirely")
    void should_notRegisterTurbosmsService_when_flagIsFalse() {
        // The negative half of the matrix. Without it, a condition that accidentally matched BOTH
        // branches would still satisfy "the bean is a NoOpSmsService" if ordering happened to
        // favour it, and the paid adapter would sit in the context one autowire away.
        runner.withPropertyValues(FLAG + "=false").run(context -> {
            assertThat(context).doesNotHaveBean(TurbosmsService.class);
            assertThat(context).hasSingleBean(NoOpSmsService.class);
        });
    }

    @Test
    @DisplayName("flag=true — the no-op sender is absent from the context entirely")
    void should_notRegisterNoOpSmsService_when_flagIsTrue() {
        runner.withPropertyValues(FLAG + "=true").run(context -> {
            assertThat(context).doesNotHaveBean(NoOpSmsService.class);
            assertThat(context).hasSingleBean(TurbosmsService.class);
        });
    }

    /**
     * <b>The documentation used to be wrong about this, and the error was in the dangerous
     * direction</b> (LOW, 2026-08-18). {@code SmsConfig}'s Javadoc and the {@code
     * APP_BOOKING_SMS_ENABLED} row in {@code ARCHITECTURE-backend.md} both said only the literal
     * lowercase strings were understood — so an operator who set {@code APP_BOOKING_SMS_ENABLED=TRUE}
     * would reasonably have believed they had NOT enabled spend. {@link ConditionalOnProperty}
     * compares with {@code equalsIgnoreCase}, so they would have been billing.
     *
     * <p>Both docs are corrected; this row is what keeps them honest. It also pins the OTHER
     * direction ({@code FALSE} is off), so a future Spring change that tightened the comparison would
     * surface here rather than as a silent production behaviour change.
     */
    @Test
    @DisplayName("flag=TRUE/FALSE — havingValue is matched case-INSENSITIVELY, both ways")
    void should_matchTheFlagCaseInsensitively_when_theSpellingIsUppercase() {
        runner.withPropertyValues(FLAG + "=TRUE").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .as("an operator writing TRUE IS spending money — the docs used to deny this")
                    .getBean(SmsService.class).isInstanceOf(TurbosmsService.class);
        });
        runner.withPropertyValues(FLAG + "=FALSE").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(SmsService.class).isInstanceOf(NoOpSmsService.class);
        });
    }

    /**
     * The guest phone-OTP carve-out (HIGH, 2026-08-18): {@code PhoneOtpService} injects
     * {@link OtpSmsSender}, which {@link SmsConfig} registers UNCONDITIONALLY, so an auth code is
     * delivered whatever the booking money gate says. Asserted in every one of the three flag states
     * this class enumerates, because "unconditional" is exactly the claim.
     *
     * <p>The second assertion is the one that protects the rest of this class: the OTP bean must not
     * be an {@link SmsService}. If it were, every "exactly one SmsService bean" row above would have
     * to be relaxed to two, a restored {@code @Service} on {@link TurbosmsService} would stop being a
     * loud boot failure, and every bare {@code SmsService} injection point would become ambiguous.
     */
    @Test
    @DisplayName("the OTP sender exists in EVERY flag state, and is never an SmsService bean")
    void should_registerTheOtpSenderUnconditionally_when_theBookingGateTakesAnyValue() {
        for (String[] scenario : new String[][]{{}, {FLAG + "=false"}, {FLAG + "=true"}}) {
            runner.withPropertyValues(scenario).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(OtpSmsSender.class);
                assertThat(context.getBeanNamesForType(SmsService.class))
                        .as("the OTP bean must stay OUTSIDE the SmsService type, or the gate's "
                                + "exactly-one invariant collapses — scenario=%s",
                                (Object) scenario)
                        .hasSize(1);
            });
        }
    }

    /**
     * The <b>structural</b> half of the OTP carve-out (security LOW, 2026-08-19). Everything above
     * this row proves the carve-out WORKS; this one proves it cannot be turned into a side door.
     *
     * <h4>The gap</h4>
     * {@link OtpSmsSender} is a public interface in {@code com.beautica.notification.sms} and
     * {@link SmsConfig#otpSmsSender} registers it unconditionally, so it is one {@code @Autowired}
     * away from any class in the codebase. The whole design of the money gate is that
     * {@link SmsService} is the ONLY way to send booking copy and it is always gated — a future
     * booking sender that injected {@link OtpSmsSender} instead would send real, billed SMS with
     * {@code app.booking.sms.enabled=false} and every test in this class would stay green, because
     * the bean matrix is exactly as asserted. Until now that was fenced by a Javadoc paragraph
     * ({@code OtpSmsSender}'s "a booking sender cannot reach this interface by accident") and by
     * reviewers remembering it.
     *
     * <h4>The rule</h4>
     * Only {@code com.beautica.auth..} may depend on {@link OtpSmsSender} at all — the guest
     * phone-OTP is an auth credential and {@code PhoneOtpService} lives there. Exactly two classes
     * outside it are exempt, and both are named EXPLICITLY rather than excluded by a package
     * wildcard, so a third exception has to be argued for in this file:
     * <ul>
     *   <li>{@link SmsConfig}, which constructs the bean;</li>
     *   <li>{@code TurbosmsOtpSmsSender}, the implementation — a rule that forbade implementing the
     *       interface outside {@code auth..} would forbid the sender itself. Excluding it by simple
     *       name keeps the exemption to that ONE adapter, where a
     *       {@code resideOutsideOfPackage("com.beautica.notification.sms..")} form would have
     *       exempted every future class in the sender's package as a side effect.</li>
     * </ul>
     *
     * <p>Matched by SIMPLE NAME, per the finding's wording, which also makes the rule survive a
     * package move of the interface. It is stated as a dependency rule rather than a call rule
     * because merely HOLDING the reference (a field, a constructor parameter) is the reviewable
     * event — by the time there is a call the mistake is already shipped.
     *
     * <p><b>Falsified:</b> adding an {@code OtpSmsSender} field to
     * {@code com.beautica.booking.service.BookingSmsDispatcher} turned this row RED with that class
     * named in the violation; reverted before this change was finished.
     *
     * <p>ArchUnit rather than a hand-rolled reflection scan, for the reason
     * {@code ServiceCatalogServiceArchitectureTest} gives. It lives HERE rather than in a new
     * architecture test class because the invariant it guards is this class's subject — the gate and
     * its one carve-out — and splitting them would let either move without the other's test
     * noticing.
     */
    @Test
    @DisplayName("only com.beautica.auth.. may depend on OtpSmsSender — the money gate has no side door")
    void should_confineTheOtpSenderToTheAuthPackage_when_scanningEveryProductionClass() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.beautica");

        // Anti-vacuity: a typo'd package or a renamed interface would leave the rule asserting over
        // nothing and passing for the wrong reason.
        assertThat(production).isNotEmpty();
        assertThat(production.stream().map(JavaClass::getSimpleName))
                .as("the rule below matches by simple name — both must still exist, or an "
                        + "exemption would silently be excluding nothing")
                .contains(OTP_SENDER_SIMPLE_NAME, OTP_SENDER_IMPL_SIMPLE_NAME);

        noClasses()
                .that().resideOutsideOfPackage("com.beautica.auth..")
                .and().doNotHaveFullyQualifiedName(SmsConfig.class.getName())
                .and().doNotHaveSimpleName(OTP_SENDER_IMPL_SIMPLE_NAME)
                .should().dependOnClassesThat().haveSimpleName(OTP_SENDER_SIMPLE_NAME)
                .as("only the auth package (plus SmsConfig, which constructs the bean, and "
                        + "TurbosmsOtpSmsSender, which implements it) may reach OtpSmsSender — "
                        + "booking copy sends through SmsService, which is what the "
                        + "app.booking.sms.enabled gate selects")
                .check(production);
    }

    @Test
    @DisplayName("flag=on — a relaxed boolean spelling resolves NEITHER bean and fails every injection point")
    void should_failContextStartup_when_theFlagUsesAnUnrecognisedSpelling() {
        // @ConditionalOnProperty compares the RAW STRING; it does not go through Spring's relaxed
        // boolean binder. So `on`, `yes` and `1` — every one of which BookingSmsProperties.Sms#enabled
        // would happily bind as TRUE — match neither havingValue="true" nor havingValue="false", and
        // matchIfMissing does not apply because the property IS present. The context is left with no
        // SmsService at all.
        //
        // SmsConsumer is what makes that observable AS the production failure it is: this slice
        // registers no injection point of its own, so without a consumer an empty context would look
        // like a harmless no-op instead of the NoSuchBeanDefinitionException that takes down all seven
        // real constructor injections at boot. Asserted, not assumed — the mistake is invisible in a
        // config review and total in production.
        ApplicationContextRunner withConsumer = new ApplicationContextRunner()
                .withUserConfiguration(SmsDependencies.class, SmsConfig.class, SmsConsumer.class);

        for (String spelling : new String[]{"on", "yes", "1"}) {
            withConsumer.withPropertyValues(FLAG + "=" + spelling).run(context -> {
                assertThat(context)
                        .as("a relaxed boolean must never be quietly treated as 'off' — value=%s", spelling)
                        .hasFailed();
                assertThat(context).getFailure()
                        .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
            });
            // And the same spelling against the bare slice: zero beans, so nothing is silently paid for.
            runner.withPropertyValues(FLAG + "=" + spelling)
                    .run(context -> assertThat(context.getBeanNamesForType(SmsService.class)).isEmpty());
        }
    }

    /** A stand-in for the seven real constructor injections of {@link SmsService}. */
    @Configuration(proxyBeanMethods = false)
    static class SmsConsumer {

        @Bean
        SmsConsumerBean smsConsumerBean(SmsService smsService) {
            return new SmsConsumerBean(smsService);
        }
    }

    record SmsConsumerBean(SmsService smsService) {
    }

    /**
     * The collaborators {@link SmsConfig#turbosmsService} needs, supplied directly rather than by
     * auto-configuration so the runner stays a pure slice. {@link TurbosmsProperties} is left at its
     * defaults — blank token included, which is the point of the last case above.
     */
    @Configuration(proxyBeanMethods = false)
    static class SmsDependencies {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        TurbosmsProperties turbosmsProperties() {
            return new TurbosmsProperties();
        }
    }
}
