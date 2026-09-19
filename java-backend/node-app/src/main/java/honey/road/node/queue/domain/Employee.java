package honey.road.node.queue.domain;

import java.util.UUID;

public record Employee(UUID id, String branchId, String fullName, boolean active) {}
