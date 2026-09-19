package honey.road.node.queue.domain;

import java.time.LocalTime;
import java.util.Map;

/**
 * Действующие правила очереди (раздел 6.9). Хранится JSON-снимком в {@code branch_config}.
 * Поля после {@code scoringStrategy} добавлены в версии 2 и имеют безопасные значения по умолчанию,
 * чтобы снимок версии 1 читался без ошибок.
 */
public record QueuePolicy(
        int configVersion, Map<String, Map<String, Integer>> targetWaitMin,
        int earlyCheckinMin, int graceLateMin, int lateCheckinRetentionMin,
        int slotDurationMin, int slotCapacity, int bookingHorizonDays,
        LocalTime openingTime, LocalTime closingTime,
        int slackMin, int callTimeoutSec, int maxRecalls,
        boolean autoCallOnFree, String scoringStrategy,
        String latePolicy, String requeueDefaultMode,
        Integer postponeMaxCount, Integer postponeMaxMin,
        Integer defaultServiceDurationSec,
        Integer approachingPositionLte, Integer approachingEtaLteMin) {

    public static final String LATE_CONVERT = "convert_to_qr";
    public static final String LATE_REJECT = "reject";
    public static final String REQUEUE_KEEP = "keep_position";
    public static final String REQUEUE_TAIL = "to_tail";

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
        scoringStrategy = scoringStrategy == null ? "uniform" : scoringStrategy;
        latePolicy = latePolicy == null ? LATE_CONVERT : latePolicy;
        if (!LATE_CONVERT.equals(latePolicy) && !LATE_REJECT.equals(latePolicy)) {
            throw new IllegalArgumentException("late-policy: convert_to_qr | reject");
        }
        requeueDefaultMode = requeueDefaultMode == null ? REQUEUE_KEEP : requeueDefaultMode;
        if (!REQUEUE_KEEP.equals(requeueDefaultMode) && !REQUEUE_TAIL.equals(requeueDefaultMode)) {
            throw new IllegalArgumentException("requeue-default-mode: keep_position | to_tail");
        }
        postponeMaxCount = postponeMaxCount == null ? 1 : postponeMaxCount;
        postponeMaxMin = postponeMaxMin == null ? 30 : postponeMaxMin;
        defaultServiceDurationSec = defaultServiceDurationSec == null ? 300 : defaultServiceDurationSec;
        approachingPositionLte = approachingPositionLte == null ? 3 : approachingPositionLte;
        approachingEtaLteMin = approachingEtaLteMin == null ? 5 : approachingEtaLteMin;
    }

    public int targetWait(TicketSource source, String category) {
        var values = targetWaitMin.get(source == TicketSource.appointment ? "qr" : source.name());
        if (values == null || !values.containsKey(category)) {
            throw DomainException.validation("Неизвестная категория клиента");
        }
        return values.get(category);
    }
}
