package com.fraudplatform.casemanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.security")
    public UserProperties userProperties() {
        return new UserProperties();
    }

    @Bean
    public UserDetailsService userDetailsService(UserProperties props) {
        var manager = new InMemoryUserDetailsManager();
        for (UserProperties.UserEntry u : props.getUsers()) {
            manager.createUser(
                User.withUsername(u.getUsername())
                    .password(u.getPassword())
                    .roles(u.getRoles())
                    .build()
            );
        }
        return manager;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(basic -> {});
        return http.build();
    }

    public static class UserProperties {
        private List<UserEntry> users = List.of();
        public List<UserEntry> getUsers() { return users; }
        public void setUsers(List<UserEntry> users) { this.users = users; }

        public static class UserEntry {
            private String username;
            private String password;
            private String roles;
            public String getUsername() { return username; }
            public void setUsername(String username) { this.username = username; }
            public String getPassword() { return password; }
            public void setPassword(String password) { this.password = password; }
            public String getRoles() { return roles; }
            public void setRoles(String roles) { this.roles = roles; }
        }
    }
}
