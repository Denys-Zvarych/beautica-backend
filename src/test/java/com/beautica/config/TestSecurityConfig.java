package com.beautica.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public PasswordEncoder passwordEncoder() {
        // BCrypt cost lowered to 4 for test speed; production uses 10.
        // Acceptable: Testcontainers DB round-trip dominates test time, not BCrypt.
        // The authentication path (encode + verify) is still exercised — only the work factor differs.
        return new BCryptPasswordEncoder(4);
    }
}
