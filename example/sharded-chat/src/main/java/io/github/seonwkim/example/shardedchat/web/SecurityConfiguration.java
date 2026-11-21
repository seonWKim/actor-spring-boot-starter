package io.github.seonwkim.example.shardedchat.web;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Activating a color is a write: {@code POST /actuator/generation} needs the {@code deployer} user
 * (HTTP Basic), whose password the control app hands each node it starts
 * ({@code sharded-chat.deployer-password}, through the environment). Everything else is open: this is a
 * local demo. Without a password the node makes up one, so nobody can activate through it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http.authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/actuator/generation")
                        .hasRole("DEPLOYER")
                        .anyExchange()
                        .permitAll())
                .httpBasic(Customizer.withDefaults())
                // Called by the control app, not from a browser session.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }

    @Bean
    MapReactiveUserDetailsService deployer(@Value("${sharded-chat.deployer-password:}") String password) {
        String secret = password.isBlank() ? UUID.randomUUID().toString() : password;
        return new MapReactiveUserDetailsService(User.withUsername("deployer")
                .password("{noop}" + secret)
                .roles("DEPLOYER")
                .build());
    }
}
