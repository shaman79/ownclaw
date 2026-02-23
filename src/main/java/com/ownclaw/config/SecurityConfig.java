package com.ownclaw.config;

import com.ownclaw.auth.JwtAuthFilter;
import com.ownclaw.users.AuthService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the JWT authentication filter for REST API endpoints.
 * Spring Security is not on the classpath — auth is enforced via a plain servlet filter.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(AuthService authService) {
        var reg = new FilterRegistrationBean<>(new JwtAuthFilter(authService));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(1);
        return reg;
    }
}
