package com.beautica.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hardens Jackson's parser against DoS-amplification by capping the size of the
 * primitive tokens it will materialise, <em>before</em> Bean Validation runs.
 *
 * <h2>Mechanism</h2>
 * Spring Boot builds the application {@code ObjectMapper} — including the one wired into
 * the MVC {@code MappingJackson2HttpMessageConverter} — through a single
 * {@code Jackson2ObjectMapperBuilder}. {@link Jackson2ObjectMapperBuilderCustomizer}
 * beans are applied to that builder, so configuring constraints here affects the actual
 * request-parsing mapper, not a side mapper.
 *
 * <p>{@code Jackson2ObjectMapperBuilder} does not expose {@code StreamReadConstraints}
 * directly, so we install them on the underlying {@code JsonFactory} via
 * {@link org.springframework.http.converter.json.Jackson2ObjectMapperBuilder#postConfigurer}.
 * Every parse the mapper performs goes through that factory, so the cap is global.
 *
 * @see JsonConstraintsProperties for the cap values and their justification
 */
@Configuration
@EnableConfigurationProperties(JsonConstraintsProperties.class)
public class JsonConfig {

    /**
     * Applies {@link StreamReadConstraints} to the underlying {@code JsonFactory} of the
     * Boot-managed {@code ObjectMapper}.
     *
     * <p>A JSON string token longer than {@code maxStringLength} causes the parser to
     * throw {@code StreamConstraintsException} during read. Spring MVC wraps that in
     * {@code HttpMessageNotReadableException}, which {@code GlobalExceptionHandler} maps
     * to a generic HTTP 400 — never a 500, and never an OOM.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer streamReadConstraintsCustomizer(
            JsonConstraintsProperties properties) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxStringLength(properties.getMaxStringLength())
                .maxNestingDepth(properties.getMaxNestingDepth())
                .maxNumberLength(properties.getMaxNumberLength())
                .build();

        return builder -> builder.postConfigurer(
                mapper -> mapper.getFactory().setStreamReadConstraints(constraints));
    }
}
