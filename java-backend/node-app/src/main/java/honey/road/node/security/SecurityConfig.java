package honey.road.node.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;
import honey.road.node.config.NodeProperties;

import java.util.List;

/**
 * Public API (client, board) strictly under /api/v1/public/**, the rest is closed.
 * (было решено в обход доков, на сколько помню)
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    private static final String[] PUBLIC_API = {"/api/v1/public/**", "/api/v1/auth/login"};
    private static final String[] PUBLIC_TECH = {"/actuator/health/**", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html"};

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            JwtAuthenticationConverter jwtConverter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {

        AuthenticationEntryPoint unauthenticated = (req, res, ex) -> resolver.resolveException(req, res, null, ex);
        AccessDeniedHandler forbidden = (req, res, ex) -> resolver.resolveException(req, res, null, ex);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_API).permitAll()
                        .requestMatchers(PUBLIC_TECH).permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.BRANCH_ADMIN.name())
                        .requestMatchers("/api/v1/admin/**").hasRole(Role.BRANCH_ADMIN.name())
                        .requestMatchers("/api/v1/**").hasRole(Role.OPERATOR.name())
                        .anyRequest().permitAll())
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    /**
     * {@code Authorization} header; SSE paths ({@code .../stream}) also accept {@code ?access_token=}
     * since EventSource cannot send headers.
     */
    static BearerTokenResolver bearerTokenResolver() {
        var header = new DefaultBearerTokenResolver();
        var query = new DefaultBearerTokenResolver();
        query.setAllowUriQueryParameter(true);
        return request -> request.getRequestURI().endsWith("/stream") && request.getParameter("access_token") != null
                ? query.resolve(request) : header.resolve(request);
    }

    /** See Role or sect 13.1. */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.BRANCH_ADMIN.name()).implies(Role.OPERATOR.name())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(NodeProperties props) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.security().corsOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id", "Last-Event-ID"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
