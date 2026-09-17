package honey.road.security.auth;

import honey.road.security.Role;
import jakarta.annotation.Nullable;

import java.util.UUID;

public record UserCredentials(UUID id, String login, String passwordHash, Role role, @Nullable UUID employeeId) {

    @Override
    public String toString() {
        return "UserCredentials[id=%s, login=%s, role=%s]".formatted(id, login, role);
    }
}
