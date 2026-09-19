package honey.road.node.queue.domain;

import java.time.LocalTime;
import java.util.Map;

public record QueuePolicy(
        int configVersion, Map<String, Map<String, Integer>> targetWaitMin,
        int earlyCheckinMin, int graceLateMin, int lateCheckinRetentionMin,
        int slotDurationMin, int slotCapacity, int bookingHorizonDays,
        LocalTime openingTime, LocalTime closingTime,
        int slackMin, int callTimeoutSec, int maxRecalls,
        boolean autoCallOnFree, String scoringStrategy) {

    public QueuePolicy {
        if (configVersion < 1 || slotDurationMin < 1 || slotCapacity < 1 || bookingHorizonDays < 1
                || earlyCheckinMin < 0 || graceLateMin < 0 || lateCheckinRetentionMin < 0
                || slackMin < 0 || callTimeoutSec < 1 || maxRecalls < 0
                || openingTime == null || closingTime == null || !closingTime.isAfter(openingTime)) {
            throw new IllegalArgumentException("Некорректная конфигурация очереди");
        }
        if (targetWaitMin == null || !targetWaitMin.containsKey("qr") || !targetWaitMin.containsKey("walkin")) {
            throw new IllegalArgumentException("Не заданы целевые времена ожидания");
        }
        targetWaitMin = targetWaitMin.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
        if (!targetWaitMin.get("qr").keySet().equals(targetWaitMin.get("walkin").keySet())
                || !targetWaitMin.get("qr").containsKey("default")
                || targetWaitMin.values().stream().flatMap(m -> m.values().stream()).anyMatch(v -> v <= 0)) {
            throw new IllegalArgumentException("Некорректные категории или времена ожидания");
        }
    }

    public int targetWait(honey.road.node.queue.domain.TicketSource source, String category) {
        var values = targetWaitMin.get(source == honey.road.node.queue.domain.TicketSource.appointment ? "qr" : source.name());
        if (values == null || !values.containsKey(category)) {
            throw honey.road.node.queue.domain.DomainException.validation("Неизвестная категория клиента");
        }
        return values.get(category);
    }
}
