package honey.road.center.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Center login; the response has the node's shape, so the shared frontend login works for both. */
@RestController
@RequestMapping("/api/v1/auth")
class CenterAuthController {

    record LoginRequest(@NotBlank @Size(max = 64) String login, @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() { return "LoginRequest[login=%s]".formatted(login); }
    }

    record LoginResponse(String accessToken, String tokenType, Instant expiresAt, CenterRole role,
                         @Nullable UUID employeeId) {
    }

    private final CenterLoginService loginService;

    CenterAuthController(CenterLoginService loginService) { this.loginService = loginService; }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return loginService.login(request);
    }
}
