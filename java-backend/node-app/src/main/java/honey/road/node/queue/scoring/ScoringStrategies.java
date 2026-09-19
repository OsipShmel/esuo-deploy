package honey.road.node.queue.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Выбор стратегии по {@code queue.scoring-strategy}; неизвестное имя → uniform (с предупреждением). */
@Component
public class ScoringStrategies {
    private static final Logger log = LoggerFactory.getLogger(ScoringStrategies.class);
    private final Map<String, ScoringStrategy> byName;
    private final ScoringStrategy fallback;

    public ScoringStrategies(List<ScoringStrategy> strategies, UniformScoringStrategy uniform) {
        this.byName = strategies.stream().collect(Collectors.toMap(ScoringStrategy::name, Function.identity()));
        this.fallback = uniform;
    }

    public ScoringStrategy get(String name) {
        var s = byName.get(name);
        if (s == null) {
            log.warn("unknown scoring strategy '{}', using uniform", name);
            return fallback;
        }
        return s;
    }
}
