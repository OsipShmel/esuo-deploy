package honey.road.queue.domain;

import java.util.UUID;

public record ServiceWindow(UUID id, String branchId, String name, String status, UUID currentEmployeeId, int version) {}
