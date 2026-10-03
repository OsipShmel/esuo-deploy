package honey.road.swarm;

import honey.road.contracts.event.EventEnvelope;

import java.util.Map;

/** Where the swarm sends what a branch node would: events (JetStream) and heartbeats (core NATS). */
interface EventSink {
    void event(VirtualBranch branch, EventEnvelope envelope);

    void heartbeat(VirtualBranch branch, Map<String, Object> heartbeat);
}
