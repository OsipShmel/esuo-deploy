package honey.road.node.security.auth;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final honey.road.node.security.auth.LoginService loginService;

    AuthController(honey.road.node.security.auth.LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    honey.road.node.security.auth.LoginResponse login(@Valid @RequestBody honey.road.node.security.auth.LoginRequest request) {
        return loginService.login(request);
    }
}
