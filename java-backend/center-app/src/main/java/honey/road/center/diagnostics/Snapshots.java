package honey.road.center.diagnostics;

import honey.road.center.gateway.BranchRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Diagnostics snapshot of a branch on request (design 7): RPC {@code diag.snapshot}, then stored. Nothing is stored
 * when the branch did not answer: an empty snapshot would only say what the 503 already says.
 */
@Service
class Snapshots {
    private static final Logger log = LoggerFactory.getLogger(Snapshots.class);
    /** The node gives its sections 8 s; the rest is transport. */
    static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);

    private final BranchRpcClient rpc;
    private final SnapshotStore store;
    private final Clock clock;

    Snapshots(BranchRpcClient rpc, SnapshotStore store, Clock clock) {
        this.rpc = rpc; this.store = store; this.clock = clock;
    }

    Map<String, Object> take(String branchId, String requestedBy) {
        var result = rpc.call(branchId, "diag.snapshot", Map.of(), null, RPC_TIMEOUT);
        if (!result.ok()) {
            throw new LogPull.BranchProblem(result);
        }
        var id = UUID.randomUUID();
        var takenAt = clock.instant();
        var payload = result.data().toString();
        int size = payload.getBytes(StandardCharsets.UTF_8).length;
        store.save(id, branchId, takenAt, requestedBy, payload, size);
        log.info("diagnostics snapshot taken branch={} id={} by={} bytes={}", branchId, id, requestedBy, size);
        return SnapshotStore.view(id, branchId, takenAt, requestedBy, size, result.data());
    }
}
