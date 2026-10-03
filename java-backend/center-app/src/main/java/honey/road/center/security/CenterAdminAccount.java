package honey.road.center.security;

import honey.road.center.config.CenterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The network admin account, taken from the environment ({@code CENTER_ADMIN_LOGIN}, {@code CENTER_ADMIN_PASSWORD}).
 * Only the bcrypt hash is kept. No password configured means no account: the private API is closed for everyone.
 */
@Component
class CenterAdminAccount {
    private static final Logger log = LoggerFactory.getLogger(CenterAdminAccount.class);

    record Account(String login, String passwordHash, CenterRole role) {
        @Override
        public String toString() { return "Account[login=%s, role=%s]".formatted(login, role); }
    }

    private final Optional<Account> account;

    CenterAdminAccount(CenterProperties props, PasswordEncoder encoder) {
        var security = props.security();
        if (security.adminPassword().isBlank()) {
            log.warn("no center admin configured, private API is closed (set CENTER_ADMIN_PASSWORD)");
            this.account = Optional.empty();
        } else {
            this.account = Optional.of(new Account(security.adminLogin(), encoder.encode(security.adminPassword()),
                    CenterRole.NETWORK_ADMIN));
        }
    }

    Optional<Account> find(String login) {
        return account.filter(a -> a.login().equals(login));
    }
}
