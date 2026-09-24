package com.fraudplatform.rules.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * CSRF is disabled intentionally: this is a stateless REST API authenticated
     * via HTTP Basic. CSRF attacks require a browser session cookie — with
     * STATELESS session management there is no session cookie to hijack.
     * Reference: https://docs.spring.io/spring-security/reference/features/exploits/csrf.html#csrf-when-to-use
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/health").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(basic -> {});
        return http.build();
    }
}
