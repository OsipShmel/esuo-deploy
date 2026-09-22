package honey.road.node.demo;

import jakarta.annotation.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /admin/demo/reset} — see {@link DemoResetService}. */
@RestController
class DemoResetController {
    private final DemoResetService reset;

    DemoResetController(DemoResetService reset) { this.reset = reset; }

    record ResetRequest(@Nullable String scenario) {
    }

    @PostMapping("/api/v1/admin/demo/reset")
    DemoResetService.Result reset(@RequestBody(required = false) ResetRequest body) {
        return reset.reset(body == null ? null : body.scenario());
    }
}
