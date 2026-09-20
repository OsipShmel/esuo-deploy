package honey.road.node.config;

import honey.road.node.queue.domain.QueuePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.Map;

/** {@code queue-defaults.yml} → {@link QueuePolicy}. */
@ConfigurationProperties("queue")
public record QueueProperties(
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

    public QueuePolicy toPolicy() {
        return new QueuePolicy(configVersion, targetWaitMin, earlyCheckinMin, graceLateMin,
                lateCheckinRetentionMin, slotDurationMin, slotCapacity, bookingHorizonDays,
                openingTime, closingTime, slackMin, callTimeoutSec, maxRecalls, autoCallOnFree, scoringStrategy,
                latePolicy, requeueDefaultMode, postponeMaxCount, postponeMaxMin, defaultServiceDurationSec,
                approachingPositionLte, approachingEtaLteMin, idleAlertMin, longServiceMin,
                heuristicMinSamples, recommendOverdueMin, recommendOverdueCount, slowWindowFactor);
    }
}
