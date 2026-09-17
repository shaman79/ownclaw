package com.ownclaw.config;

import com.ownclaw.auth.JwtAuthFilter;
import com.ownclaw.auth.OpsAuthFilter;
import com.ownclaw.auth.SecurityHeadersFilter;
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

    /** Response headers for every request, including the static client. */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        var reg = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(-1);
        return reg;
    }

    /**
     * Authenticates /api/ops/* with the ops token. Registered at order 0 so it runs before
     * the JWT filter, which treats /api/ops/ as exempt. Refuses everything when the token
     * is unset, so the ops API is off by default.
     */
    @Bean
    public FilterRegistrationBean<OpsAuthFilter> opsAuthFilter(OwnClawConfig config) {
        var reg = new FilterRegistrationBean<>(new OpsAuthFilter(config));
        reg.addUrlPatterns("/api/ops", "/api/ops/*");
        reg.setOrder(0);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(AuthService authService) {
        var reg = new FilterRegistrationBean<>(new JwtAuthFilter(authService));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(1);
        return reg;
    }
}
