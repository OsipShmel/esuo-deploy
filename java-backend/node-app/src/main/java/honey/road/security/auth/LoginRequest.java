package honey.road.security.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record LoginRequest(
        @NotBlank @Size(max = 64) String login,
        @NotBlank @Size(max = 128) String password
) {
    @Override
    public String toString() {
        return "LoginRequest[login=%s]".formatted(login);
    }
}
