package com.beautica.config;

import com.beautica.common.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.data.web.config.SortHandlerMethodArgumentResolverCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.SortHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Global backstop that rejects <b>dotted association paths</b> in the {@code sort} query parameter
 * on every endpoint, before the value can reach a repository.
 *
 * <p><b>The attack.</b> Spring Data splices a caller-supplied sort property directly into the
 * query's {@code ORDER BY} — property names cannot be bind parameters. A dotted path resolves as
 * valid JPQL against the query's root entity, so on a {@code Booking} root
 * {@code ?sort=client.passwordHash,asc} and {@code ?sort=client.email,asc} are both well-formed
 * and change the row order. Paging with alternating directions then turns the endpoint into a
 * <b>binary-search oracle over the ordering of secrets the caller can never read directly</b>.
 *
 * <p><b>Why a global guard and not only per-endpoint whitelists.</b>
 * {@link com.beautica.common.web.SortWhitelist} states each endpoint's precise contract, but it
 * only protects endpoints that remember to call it — the original finding existed because six
 * endpoints simply omitted it. This resolver needs no cooperation from the controller, so an
 * endpoint added tomorrow that forgets the whitelist is still not an oracle. The two layers are
 * deliberately redundant; neither is sufficient alone.
 *
 * <p><b>How it is wired.</b> {@code Pageable}/{@code Sort} are not built-in Spring MVC types —
 * they resolve only through the custom argument resolvers Spring Data registers via its own
 * {@code WebMvcConfigurer}. Argument resolvers are consulted in registration order and the first
 * whose {@code supportsParameter} returns {@code true} wins, so this configurer is annotated
 * {@link Ordered#HIGHEST_PRECEDENCE} to be added ahead of Spring Data's. The delegates are plain
 * Spring Data resolvers, so all normal behaviour ({@code @PageableDefault}, the global
 * {@code spring.data.web.pageable.max-page-size} cap, qualifiers) is preserved verbatim — this
 * class only inspects the already-parsed result and rejects.
 *
 * <p><b>A rejection is a 400, never a 500.</b> Argument resolution runs inside the dispatcher's
 * exception-handling scope, so the thrown {@link BusinessException} is translated by
 * {@code GlobalExceptionHandler#handleBusiness}, which genericises {@code BAD_REQUEST} to
 * {@code "Invalid request"} — the response never echoes the probed property and therefore never
 * confirms whether it resolved.
 *
 * <p>Only the dot is rejected here, not unknown flat properties: a flat property that does not
 * exist is an ordinary {@code PropertyReferenceException} confined to the query's own root
 * entity, whereas a dotted path is what lets the caller <em>traverse</em> into another table.
 * Narrowing flat properties is the per-endpoint whitelist's job.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SortPathGuardConfig implements WebMvcConfigurer {

    /**
     * Separator Spring Data uses for nested sort properties ({@code client.email}). Kept as a
     * named constant so the guard cannot silently drift from
     * {@code SortHandlerMethodArgumentResolverSupport}'s default property delimiter.
     */
    private static final String NESTED_PATH_SEPARATOR = ".";

    private final ObjectProvider<SortHandlerMethodArgumentResolverCustomizer> sortCustomizers;
    private final ObjectProvider<PageableHandlerMethodArgumentResolverCustomizer> pageableCustomizers;

    public SortPathGuardConfig(
            ObjectProvider<SortHandlerMethodArgumentResolverCustomizer> sortCustomizers,
            ObjectProvider<PageableHandlerMethodArgumentResolverCustomizer> pageableCustomizers) {
        this.sortCustomizers = sortCustomizers;
        this.pageableCustomizers = pageableCustomizers;
    }

    /**
     * Registers the guarded resolvers ahead of Spring Data's own.
     *
     * <p><b>Boot's customizers are re-applied by hand, and must stay that way.</b> Spring Boot
     * configures {@code spring.data.web.*} — critically {@code pageable.max-page-size: 100}, the
     * global pagination cap (Anti-Bug §J) — by applying
     * {@link PageableHandlerMethodArgumentResolverCustomizer} to the resolver instance <em>it</em>
     * creates. These instances are ours, so without replaying the customizers they would silently
     * fall back to Spring Data's built-in default of 2000 and quietly undo the cap. Resolving the
     * customizers from the context (rather than re-reading the properties here) keeps this in
     * lockstep with whatever Boot is configured to do.
     */
    @Override
    public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
        GuardedSortResolver sortResolver = new GuardedSortResolver();
        sortCustomizers.orderedStream().forEach(customizer -> customizer.customize(sortResolver));

        PageableHandlerMethodArgumentResolver pageableResolver =
                new PageableHandlerMethodArgumentResolver(sortResolver);
        pageableCustomizers.orderedStream().forEach(customizer -> customizer.customize(pageableResolver));

        resolvers.add(sortResolver);
        resolvers.add(pageableResolver);
    }

    /**
     * Rejects a {@link Sort} containing any dotted property. Applied to both the bare {@code Sort}
     * parameter type and — because {@link PageableHandlerMethodArgumentResolver} delegates its
     * sort parsing to this instance — to the {@code Sort} nested inside every {@code Pageable}.
     */
    private static void rejectNestedPaths(Sort sort) {
        for (Sort.Order order : sort) {
            if (order.getProperty().contains(NESTED_PATH_SEPARATOR)) {
                // Message is internal only (debug log); GlobalExceptionHandler genericises the
                // BAD_REQUEST body so the rejected path is never reflected back to the caller.
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "Nested sort property is not permitted: " + order.getProperty());
            }
        }
    }

    /** Spring Data's resolver, with the nested-path rejection applied to its parsed result. */
    private static final class GuardedSortResolver extends SortHandlerMethodArgumentResolver {

        @Override
        @NonNull
        public Sort resolveArgument(@NonNull MethodParameter parameter,
                                    ModelAndViewContainer mavContainer,
                                    @NonNull NativeWebRequest webRequest,
                                    WebDataBinderFactory binderFactory) {
            Sort sort = super.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
            rejectNestedPaths(sort);
            return sort;
        }
    }
}
