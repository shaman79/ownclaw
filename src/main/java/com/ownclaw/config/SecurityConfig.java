package com.ownclaw.config;

import org.springframework.context.annotation.Configuration;

/**
 * Phase 1: No authentication. Single-user mode.
 * Spring Security is not on the classpath — all endpoints are open.
 * Authentication (JWT, Telegram user binding) will be added in Phase 2.
 */
@Configuration
public class SecurityConfig {
    // Placeholder for Phase 2 security configuration
}
