package honey.road.center.security;

import honey.road.center.config.CenterProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

/**
 * Center API access (design 4.1): closed by default. The public paths are listed here explicitly; everything else
 * under {@code /api/**} and {@code /actuator/**} needs {@link CenterRole#NETWORK_ADMIN}, so a new endpoint is born closed.
 * Pages and static assets stay open: the network console page loads, its data does not without a login.
 */
@Configuration(proxyBeanMethods = false)
public class CenterSecurityConfig {

    public static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/branches",                                  // branch picker of the client
            "/api/v1/branches/*/info",
            "/api/v1/branches/*/slots",
            "/api/v1/branches/*/booking-limits",
            "/api/v1/branches/*/appointments",
            "/api/v1/branches/*/qr-tickets",
            "/api/v1/branches/*/tickets/*",                      // the ticket token itself is the secret
            "/api/v1/branches/*/tickets/*/cancel",
            "/api/v1/branches/*/tickets/*/postpone",
            "/api/v1/branches/*/tickets/*/check-in",
            "/api/v1/branches/*/tickets/*/stream",
            "/api/v1/webhooks/mp/appointments",                  // protected by its own HMAC signature
            "/actuator/health", "/actuator/health/**",
            "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    SecurityFilterChain centerSecurity(HttpSecurity http, JwtAuthenticationConverter centerJwtAuthenticationConverter,
                                       @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
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
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/**", "/actuator/**").hasRole(CenterRole.NETWORK_ADMIN.name())
                        .anyRequest().permitAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(centerJwtAuthenticationConverter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CenterProperties props) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id", "Last-Event-ID"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
