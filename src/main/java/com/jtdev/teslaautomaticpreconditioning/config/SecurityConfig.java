package com.jtdev.teslaautomaticpreconditioning.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Slf4j
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${app.api.secret-key:default-secret-key}")
    private String secretKey;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        log.debug("Using secret key: {}", secretKey);
        return http
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyExchange().authenticated())
                .authenticationManager(bearerTokenAuthenticationManager())
                .addFilterAt(bearerTokenAuthenticationFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }

    @Bean
    public AuthenticationWebFilter bearerTokenAuthenticationFilter() {
        AuthenticationWebFilter filter = new AuthenticationWebFilter(bearerTokenAuthenticationManager());
        filter.setServerAuthenticationConverter(bearerTokenAuthenticationConverter());
        filter.setRequiresAuthenticationMatcher(
                ServerWebExchangeMatchers.pathMatchers("/**")
        );
        return filter;
    }

    @Bean
    public ServerAuthenticationConverter bearerTokenAuthenticationConverter() {
        return exchange -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
            
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                return Mono.just(new UsernamePasswordAuthenticationToken(token, token));
            } else {
                // Only log when we're going to deny
                String path = exchange.getRequest().getPath().value();
                String method = exchange.getRequest().getMethod().name();
                
                if (authHeader != null) {
                    log.warn("DENIED: Authorization header not Bearer format for {} {}: {}", method, path, authHeader);
                } else {
                    log.warn("DENIED: No Authorization header for {} {}", method, path);
                }
                log.debug("All headers: {}", exchange.getRequest().getHeaders());
            }
            
            return Mono.empty();
        };
    }

    @Bean
    public ReactiveAuthenticationManager bearerTokenAuthenticationManager() {
        return authentication -> {
            String token = authentication.getCredentials().toString();
            
            if (secretKey.equals(token)) {
                return Mono.just(new UsernamePasswordAuthenticationToken(
                        "api-user", 
                        null, 
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_API"))
                ));
            }
            
            // Only log when we're going to deny
            log.warn("DENIED: Token mismatch - expected length: {}, actual length: {}", secretKey.length(), token.length());
            log.debug("Expected token: {}", secretKey);
            log.debug("Actual token: {}", token);
            return Mono.empty();
        };
    }
}
