package honey.road.center.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import honey.road.center.config.CenterProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * HS256 tokens issued and checked by the center itself, like on the node. The issuer is validated, so a node token
 * signed with the same secret (all-in-one stand) is not accepted here.
 */
@Configuration(proxyBeanMethods = false)
class CenterJwtConfig {

    static final String CLAIM_ROLE = "role";
    static final String CLAIM_LOGIN = "login";
    private static final int MIN_KEY_BYTES = 32;

    @Bean
    SecretKey centerJwtKey(CenterProperties props) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(props.security().jwtSecret());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("esuo.center.security.jwt-secret (env JWT_SECRET) must be base64", e);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("esuo.center.security.jwt-secret (env JWT_SECRET) must be >= 256 bits (base64)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder centerJwtEncoder(SecretKey centerJwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(centerJwtKey));
    }

    @Bean
    JwtDecoder centerJwtDecoder(SecretKey centerJwtKey, CenterProperties props) {
        var decoder = NimbusJwtDecoder.withSecretKey(centerJwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.security().issuer()));
        return decoder;
    }

    /** role claim → ROLE_x; an unknown role gives no authority (403), not an error. Principal name = login. */
    @Bean
    JwtAuthenticationConverter centerJwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> authorities(jwt.getClaimAsString(CLAIM_ROLE)));
        converter.setPrincipalClaimName(CLAIM_LOGIN);
        return converter;
    }

    static List<GrantedAuthority> authorities(String role) {
        return Arrays.stream(CenterRole.values())
                .filter(r -> r.name().equals(role))
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority(r.authority()))
                .toList();
    }
}
