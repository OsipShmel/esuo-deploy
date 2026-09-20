package honey.road.node.api.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Frontend pages served by the node (frontend-concept.md 2): client at {@code /} and {@code /b/**}, the rest by name.
 * Client-side routes forward to the page; {@code /assets/**} are plain static resources.
 */
@Hidden
@Controller
class SpaRoutes {
    @GetMapping({"/", "/b/**"})
    String client() { return "forward:/client.html"; }

    @GetMapping({"/board", "/board/**"})
    String board() { return "forward:/board.html"; }

    @GetMapping({"/operator", "/operator/**"})
    String operator() { return "forward:/operator.html"; }

    @GetMapping({"/admin", "/admin/**"})
    String admin() { return "forward:/admin.html"; }

    @GetMapping({"/demo", "/demo/**"})
    String demo() { return "forward:/demo.html"; }
}
