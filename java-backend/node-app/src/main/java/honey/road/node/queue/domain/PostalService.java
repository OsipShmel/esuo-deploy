package honey.road.node.queue.domain;

import java.util.UUID;

public record PostalService(UUID id, String branchId, String code, String name, String numberPrefix, int slotDurationMin, int slotCapacity, boolean active) {}
