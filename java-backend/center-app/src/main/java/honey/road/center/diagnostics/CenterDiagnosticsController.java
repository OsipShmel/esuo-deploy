package honey.road.center.diagnostics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

/**
 * {@code POST /api/v1/center/diagnostics[?heap=true]}: a snapshot of the center itself (scale design 6.2); network
 * admin only. {@code heap=true} adds the classes that fill the heap (a full GC). {@code GET /api/v1/network/center}:
 * the «Центр» line of the network screen (scale design 8.1).
 */
@RestController
class CenterDiagnosticsController {
    private final CenterSnapshot snapshot;
    private final CenterLine line;

    CenterDiagnosticsController(CenterSnapshot snapshot, CenterLine line) {
        this.snapshot = snapshot; this.line = line;
    }

    @GetMapping("/api/v1/network/center")
    Map<String, Object> line() {
        return line.now();
    }

    @PostMapping("/api/v1/center/diagnostics")
    Map<String, Object> take(Principal principal, @RequestParam(defaultValue = "false") boolean heap)
            throws InterruptedException {
        return snapshot.take(principal == null ? "unknown" : principal.getName(), heap);
    }
}
