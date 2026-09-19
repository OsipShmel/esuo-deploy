package honey.road.node.config;

import honey.road.node.queue.domain.QueuePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.LocalTime;
import java.util.Map;

@ConfigurationProperties("queue")
public record QueueProperties(
        int configVersion, Map<String, Map<String, Integer>> targetWaitMin,
        int earlyCheckinMin, int graceLateMin, int lateCheckinRetentionMin,
        int slotDurationMin, int slotCapacity, int bookingHorizonDays,
        LocalTime openingTime, LocalTime closingTime,
        int slackMin, int callTimeoutSec, int maxRecalls,
        boolean autoCallOnFree, String scoringStrategy) {
    public QueuePolicy toPolicy() {
        return new QueuePolicy(configVersion, targetWaitMin, earlyCheckinMin, graceLateMin,
                lateCheckinRetentionMin, slotDurationMin, slotCapacity, bookingHorizonDays,
                openingTime, closingTime, slackMin, callTimeoutSec, maxRecalls, autoCallOnFree, scoringStrategy);
    }
}
