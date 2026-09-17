package honey.road.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import honey.road.config.NodeProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** HS256-key; Issuance and validation of JWTs without an external IdP (решение D11, возможен пересмотр). */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    private static final int MIN_KEY_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(NodeProperties props) {
        var bytes = Base64.getDecoder().decode(props.security().jwtSecret());
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("esuo.security.jwt-secret (env JWT_SECRET) must be >= 256 bits (base64)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, NodeProperties props) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.jwtIssuer()),     // exp/nbf + iss
                new JwtClaimValidator<String>(honey.road.security.JwtClaims.BRANCH_ID, props.branchId()::equals)));
        return decoder;
    }

    /** role → ROLE_x; name principal'а = login (отправляем в логи секуры, вместо UUID). */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var role = honey.road.security.Role.valueOf(Objects.requireNonNull(jwt.getClaimAsString(honey.road.security.JwtClaims.ROLE)));
            return List.of(new SimpleGrantedAuthority(role.authority()));
        });
        converter.setPrincipalClaimName(honey.road.security.JwtClaims.LOGIN);
        return converter;
    }
}
