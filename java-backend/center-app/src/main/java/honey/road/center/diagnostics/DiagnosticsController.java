package honey.road.center.diagnostics;

import honey.road.center.gateway.RpcResponses;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Branch diagnostics on request (design 5.7); network admin only, like the whole private center API. */
@RestController
class DiagnosticsController {
    private static final int LIST_LIMIT = 50;

    private final LogPull logPull;
    private final Snapshots snapshots;
    private final SnapshotStore store;
    private final RpcResponses responses;

    DiagnosticsController(LogPull logPull, Snapshots snapshots, SnapshotStore store, RpcResponses responses) {
        this.logPull = logPull; this.snapshots = snapshots; this.store = store; this.responses = responses;
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

    /** Snapshot of the branch right now (design 7): taken, stored for 30 days, returned. */
    @PostMapping("/api/v1/branches/{branchId}/diagnostics/snapshots")
    ResponseEntity<Object> takeSnapshot(@PathVariable String branchId, Principal principal) {
        try {
            return ResponseEntity.ok(snapshots.take(branchId, principal.getName()));
        } catch (LogPull.BranchProblem e) {
            return responses.of(e.result());
        }
    }

    /** Stored snapshots of the branch, newest first, without the payload. */
    @GetMapping("/api/v1/branches/{branchId}/diagnostics/snapshots")
    List<Map<String, Object>> snapshots(@PathVariable String branchId) {
        return store.list(branchId, LIST_LIMIT);
    }

    @GetMapping("/api/v1/diagnostics/snapshots/{id}")
    Map<String, Object> snapshot(@PathVariable UUID id) {
        return store.get(id).orElseThrow(() -> new EsuoException(ErrorCode.NOT_FOUND, "Снимок " + id + " не найден"));
    }
}
