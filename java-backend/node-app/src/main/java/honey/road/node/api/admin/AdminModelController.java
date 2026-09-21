package honey.road.node.api.admin;

import honey.road.node.model.ChainedDurationModel;
import honey.road.node.model.ModelParams;
import honey.road.node.queue.application.BranchConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Duration model card for the admin screen (ml-model.md, sect 8).
 *
 * <p>Read-only on purpose. Training happens offline and a new version arrives as configuration, so there is no
 * {@code POST /admin/model/train} here: {@code trainable: false} tells the screen to hide the retrain button.
 * A button that retrains from an operator's desk would be theatre — the decision to change the model belongs
 * to the center, which publishes it to the whole network.
 */
@RestController
@RequestMapping("/api/v1/admin/model")
public class AdminModelController {
    private final ChainedDurationModel durations;
    private final BranchConfigService config;

    public AdminModelController(ChainedDurationModel durations, BranchConfigService config) {
        this.durations = durations;
        this.config = config;
    }

    @GetMapping
    public Map<String, Object> get() {
        var params = durations.params();
        var section = config.view().model();
        var out = new LinkedHashMap<String, Object>();
        out.put("version", params.usable() ? params.version() : null);
        out.put("status", status(params));
        out.put("enabled", params.enabled());
        out.put("source", "center");
        out.put("trainable", false);
        for (var field : new String[]{"trained_at", "train_from", "train_to"}) {
            if (section.hasNonNull(field)) { out.put(field, section.get(field).asString()); }
        }
        if (section.hasNonNull("metrics")) {
            var metrics = section.get("metrics");
            out.put("metrics", metrics);
            // Экран показывает «наблюдений: N» отдельным полем, а обучатель кладёт их внутрь метрик.
            if (metrics.hasNonNull("observations")) { out.put("observations", metrics.get("observations").asInt()); }
        }
        return out;
    }

    /**
     * {@code active} — модель применяется; {@code disabled} — коэффициенты есть, но выключены
     * переопределением отделения; {@code none} — модель не публиковалась, работает каскад средних.
     */
    private static String status(ModelParams params) {
        if (params.usable()) {
            return "active";
        }
        return params.version() == null ? "none" : "disabled";
    }
}
