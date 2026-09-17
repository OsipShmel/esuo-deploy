package honey.road.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import honey.road.config.NodeProperties;
import honey.road.security.auth.UserCredentials;

import java.time.Clock;
import java.time.Instant;

@Service
public class TokenService {

    public record IssuedToken(String value, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final NodeProperties props;
    private final Clock clock;

    TokenService(JwtEncoder encoder, NodeProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public IssuedToken issue(UserCredentials user) {
        var now = clock.instant();
        var expiresAt = now.plus(props.security().jwtTtl());
        var claims = JwtClaimsSet.builder()
                .issuer(props.jwtIssuer())
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(honey.road.security.JwtClaims.LOGIN, user.login())
                .claim(honey.road.security.JwtClaims.ROLE, user.role().name())
                .claim(honey.road.security.JwtClaims.BRANCH_ID, props.branchId());
        if (user.employeeId() != null) {
            claims.claim(honey.road.security.JwtClaims.EMPLOYEE_ID, user.employeeId().toString());
        }
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var jwt = encoder.encode(JwtEncoderParameters.from(header, claims.build()));
        return new IssuedToken(jwt.getTokenValue(), expiresAt);
    }
}
