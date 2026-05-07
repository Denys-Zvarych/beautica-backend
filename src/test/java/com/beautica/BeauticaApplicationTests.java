package com.beautica;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("BeauticaApplication — smoke")
class BeauticaApplicationTests extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BeauticaApplicationTests.class);

    @Test
    @DisplayName("Spring context loads without errors")
    void contextLoads() {
        log.debug("Act: Spring Boot context initialization has completed");
        log.trace("Assert: reaching this line means context load succeeded");
    }
}
