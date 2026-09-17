package honey.road.queue.domain;

import java.util.UUID;

public record Employee(UUID id, String branchId, String fullName, boolean active) {}
