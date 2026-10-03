package honey.road.center.monitoring;

import honey.road.center.config.CenterProperties;
import honey.road.center.network.BranchRegistry;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Metrics history of a branch (design 6.3); network admin only, like the whole private center API. */
@RestController
class MetricsController {
    private static final Duration DEFAULT_RANGE = Duration.ofHours(1);

    private final BranchMetrics metrics;
    private final BranchRegistry registry;
    private final CenterProperties.Metrics props;
    private final Clock clock;

    MetricsController(BranchMetrics metrics, BranchRegistry registry, CenterProperties props, Clock clock) {
        this.metrics = metrics; this.registry = registry; this.props = props.metrics(); this.clock = clock;
    }

    /** {@code [{ts, sys, queue, clock_skew_ms}]}, oldest first; by default the last hour. */
    @GetMapping("/api/v1/branches/{branchId}/metrics")
    List<Map<String, Object>> metrics(@PathVariable String branchId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to) {
        var end = to == null ? clock.instant() : to;
        var start = from == null ? end.minus(DEFAULT_RANGE) : from;
        if (!start.isBefore(end)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "from должно быть раньше to");
        }
        if (Duration.between(start, end).compareTo(props.maxRange()) > 0) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR,
                    "Интервал не больше " + props.maxRange().toHours() + " ч: столько центр хранит историю метрик");
        }
        if (!registry.exists(branchId)) {
            throw new EsuoException(ErrorCode.BRANCH_NOT_FOUND, "Отделение " + branchId + " не найдено");
        }
        return metrics.range(branchId, start, end);
    }
}
