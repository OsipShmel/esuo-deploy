package honey.road.center.diagnostics;

import honey.road.center.gateway.RpcResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Branch diagnostics on request (design 5.7); network admin only, like the whole private center API. */
@RestController
class DiagnosticsController {
    private final LogPull logPull;
    private final RpcResponses responses;

    DiagnosticsController(LogPull logPull, RpcResponses responses) {
        this.logPull = logPull; this.responses = responses;
    }

    /** Full logs of a branch for an interval → CENTER_LOG → Loki; the answer links to them in Grafana. */
    @PostMapping("/api/v1/branches/{branchId}/diagnostics/logs")
    ResponseEntity<Object> logs(@PathVariable String branchId, @RequestBody(required = false) LogPull.Request body) {
        try {
            return ResponseEntity.ok(logPull.pull(branchId, body == null ? LogPull.Request.EMPTY : body));
        } catch (LogPull.BranchProblem e) {
            return responses.of(e.result());
        }
    }
}
