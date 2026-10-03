package honey.road.center.diagnostics;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

/** {@code POST /api/v1/center/diagnostics}: a snapshot of the center itself (scale design 6.2); network admin only. */
@RestController
class CenterDiagnosticsController {
    private final CenterSnapshot snapshot;

    CenterDiagnosticsController(CenterSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @PostMapping("/api/v1/center/diagnostics")
    Map<String, Object> take(Principal principal) throws InterruptedException {
        return snapshot.take(principal == null ? "unknown" : principal.getName());
    }
}
