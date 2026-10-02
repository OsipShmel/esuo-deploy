package honey.road.center.security;

import honey.road.center.config.CenterProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
class CenterTokenService {

    record IssuedToken(String value, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final CenterProperties props;
    private final Clock clock;

    CenterTokenService(JwtEncoder centerJwtEncoder, CenterProperties props, Clock clock) {
        this.encoder = centerJwtEncoder; this.props = props; this.clock = clock;
    }

    IssuedToken issue(String login, CenterRole role) {
        var now = clock.instant();
        var expiresAt = now.plus(props.security().jwtTtl());
        var claims = JwtClaimsSet.builder()
                .issuer(props.security().issuer())
                .subject(login)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(CenterJwtConfig.CLAIM_LOGIN, login)
                .claim(CenterJwtConfig.CLAIM_ROLE, role.name())
                .build();
        var jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new IssuedToken(jwt.getTokenValue(), expiresAt);
    }
}
