package honey.road.center.security;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
class CenterLoginService {
    private static final Logger log = LoggerFactory.getLogger(CenterLoginService.class);

    private final CenterAdminAccount accounts;
    private final PasswordEncoder passwordEncoder;
    private final CenterTokenService tokens;
    /** Password is checked even for an unknown login so response time does not reveal whether the user exists. */
    private final String dummyHash;

    CenterLoginService(CenterAdminAccount accounts, PasswordEncoder passwordEncoder, CenterTokenService tokens) {
        this.accounts = accounts; this.passwordEncoder = passwordEncoder; this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    CenterAuthController.LoginResponse login(CenterAuthController.LoginRequest request) {
        var account = accounts.find(request.login());
        var matches = passwordEncoder.matches(request.password(),
                account.map(CenterAdminAccount.Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !matches) {
            log.info("login failed login={}", request.login());
            throw new EsuoException(ErrorCode.UNAUTHENTICATED, "incorrect login or password");
        }
        var a = account.get();
        var token = tokens.issue(a.login(), a.role());
        log.info("login ok login={} role={}", a.login(), a.role());
        return new CenterAuthController.LoginResponse(token.value(), "Bearer", token.expiresAt(), a.role(), null);
    }
}
