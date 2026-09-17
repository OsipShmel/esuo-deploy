package com.osipshmel.security.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.esuo.contracts.error.ErrorCode;
import ru.esuo.contracts.error.EsuoException;
import com.osipshmel.security.TokenService;

import java.util.UUID;

@Service
class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserCredentialsRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    /** Сверяем пароль даже для несуществующего логина — время ответа не выдаёт, есть ли пользователь. */
    private final String dummyHash;

    LoginService(UserCredentialsRepository users, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    LoginResponse login(LoginRequest request) {
        var user = users.findActiveByLogin(request.login());
        var matches = passwordEncoder.matches(request.password(),
                user.map(UserCredentials::passwordHash).orElse(dummyHash));

        if (user.isEmpty() || !matches) {
            log.info("login failed login={}", request.login());
            throw new EsuoException(ErrorCode.UNAUTHENTICATED, "incorrect login or password");
        }

        var u = user.get();
        var token = tokens.issue(u);
        log.info("login ok login={} role={}", u.login(), u.role());
        return new LoginResponse(token.value(), "Bearer", token.expiresAt(), u.role(), u.employeeId());
    }
}
