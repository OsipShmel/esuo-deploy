package honey.road.node.queue.domain;

import java.time.LocalTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Effective queue rules (sect 6.9), stored as a JSON snapshot in {@code branch_config}.
 * Fields after {@code scoringStrategy} were added in v2 and have safe defaults so v1 snapshots still load.
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
        Integer approachingPositionLte, Integer approachingEtaLteMin,
        Integer idleAlertMin, Integer longServiceMin,
        Integer heuristicMinSamples, Integer recommendOverdueMin, Integer recommendOverdueCount,
        Double slowWindowFactor) {

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
        targetWaitMin = targetWaitMin.entrySet().stream().collect(Collectors.toUnmodifiableMap(
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
        idleAlertMin = idleAlertMin == null ? 15 : idleAlertMin;
        longServiceMin = longServiceMin == null ? 20 : longServiceMin;
        heuristicMinSamples = heuristicMinSamples == null ? 5 : heuristicMinSamples;
        recommendOverdueMin = recommendOverdueMin == null ? 10 : recommendOverdueMin;
        recommendOverdueCount = recommendOverdueCount == null ? 5 : recommendOverdueCount;
        slowWindowFactor = slowWindowFactor == null ? 1.5 : slowWindowFactor;
    }

    public int targetWait(TicketSource source, String category) {
        var values = targetWaitMin.get(source == TicketSource.appointment ? "qr" : source.name());
        if (values == null || !values.containsKey(category)) {
            throw DomainException.validation("Неизвестная категория клиента");
        }
        return values.get(category);
    }
}
