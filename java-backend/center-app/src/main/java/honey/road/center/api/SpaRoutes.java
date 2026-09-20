package honey.road.center.api;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Frontend pages served by the center (frontend-concept.md 2): client at {@code /} and {@code /b/**} (the MP status
 * link {@code /b/{b}/t/{token}}), network monitoring at {@code /network}.
 */
@Hidden
@Controller
class SpaRoutes {
    @GetMapping({"/", "/b/**"})
    String client() { return "forward:/client.html"; }

    @GetMapping({"/network", "/network/**"})
    String network() { return "forward:/network.html"; }
}
