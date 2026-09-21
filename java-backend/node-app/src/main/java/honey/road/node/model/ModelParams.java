package honey.road.node.model;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Duration model coefficients from the {@code model} config section (ml-model.md 4.4), and the formula that
 * applies them (3.2). A pure value: no database, no Spring, no ambient time — so the decision is reproducible.
 *
 * <pre>
 *   m    = mu + a[service] + b[service|category] + e[employee] + g[employee|service] + h[hour] (+ w[window])
 *   p50  = exp(m)
 *   mean = exp(m + sigma^2 / 2)
 *   p90  = exp(m + 1.2816 * sigma)
 * </pre>
 *
 * <p>This is the Java twin of {@code python-ai-service/src/esuo_ml/predict.py}; {@code DurationModelTest} checks
 * the two against one shared file so they cannot drift apart silently.
 *
 * <p>Why {@code counts} matters: zero coefficients are not written to the section (it travels inside every config
 * message), so a missing key alone cannot tell "known group with no effect" from "group never seen". {@code counts}
 * lists the groups the trainer actually saw, and that distinction decides {@code basis} and {@code samples}.
 */
public final class ModelParams {
    /** 90th percentile of the standard normal: {@code p90 = exp(m + Z90 * sigma)}. */
    private static final double Z90 = 1.2816;
    private static final double SIGMA_FLOOR = 0.05;
    private static final double DEFAULT_SIGMA = 0.35;

    /** Absent section: the chain goes straight to the cascade. */
    public static final ModelParams NONE = new ModelParams();

    private final String version;
    private final boolean enabled;
    private final boolean useWindow;
    private final double mu;
    private final double sigmaDefault;
    private final Map<String, Double> a = new HashMap<>();
    private final Map<String, Double> b = new HashMap<>();
    private final Map<String, Double> e = new HashMap<>();
    private final Map<String, Double> g = new HashMap<>();
    private final Map<String, Double> h = new HashMap<>();
    private final Map<String, Double> w = new HashMap<>();
    private final Map<String, Double> sigma = new HashMap<>();
    private final Map<String, Map<String, Integer>> counts = new HashMap<>();

    private ModelParams() {
        this.version = null;
        this.enabled = false;
        this.useWindow = false;
        this.mu = 0;
        this.sigmaDefault = DEFAULT_SIGMA;
    }

    private ModelParams(JsonNode section) {
        this.version = text(section, "version");
        this.enabled = !section.has("enabled") || section.get("enabled").asBoolean();
        this.useWindow = section.has("use_window") && section.get("use_window").asBoolean();
        this.mu = section.has("mu") ? section.get("mu").asDouble() : 0;
        this.sigmaDefault = section.has("sigma_default") ? section.get("sigma_default").asDouble() : DEFAULT_SIGMA;
        readDoubles(section, "a", a);
        readDoubles(section, "b", b);
        readDoubles(section, "e", e);
        readDoubles(section, "g", g);
        readDoubles(section, "h", h);
        readDoubles(section, "w", w);
        readDoubles(section, "sigma", sigma);
        var counted = section.get("counts");
        if (counted != null && counted.isObject()) {
            for (var group : counted.properties()) {
                var byKey = new HashMap<String, Integer>();
                if (group.getValue().isObject()) {
                    for (var entry : group.getValue().properties()) {
                        byKey.put(entry.getKey(), entry.getValue().asInt());
                    }
                }
                counts.put(group.getKey(), byKey);
            }
        }
    }

    /** Parses the stored section; an absent, empty or unusable one yields {@link #NONE}. */
    public static ModelParams of(JsonNode section) {
        if (section == null || !section.isObject() || section.isEmpty()) {
            return NONE;
        }
        var params = new ModelParams(section);
        // Без версии и без базы услуг модель ничего не может: считаем, что её нет, и уходим на каскад.
        return params.version == null || params.counts.getOrDefault("a", Map.of()).isEmpty() ? NONE : params;
    }

    public boolean usable() {
        return this != NONE && enabled && version != null;
    }

    public String version() {
        return version;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * @return {@code null} when the model cannot answer — the caller falls back to the cascade (5.3)
     */
    public DurationModel.Prediction predict(String serviceCode, String category, String employeeId,
                                            int hourOfDay, String windowId) {
        if (!usable() || serviceCode == null || !known("a", serviceCode)) {
            return null;
        }
        var pairB = serviceCode + "|" + category;
        var pairG = employeeId == null ? null : employeeId + "|" + serviceCode;
        var hourKey = String.valueOf(hourOfDay);

        double m = mu + a.getOrDefault(serviceCode, 0.0);
        boolean complete = true;
        int narrowest = samples("a", serviceCode);

        if (known("b", pairB)) {
            m += b.getOrDefault(pairB, 0.0);
        } else if (!"default".equals(category)) {
            complete = false;                          // незнакомая категория: поправка 0
        }

        if (employeeId != null) {
            if (known("e", employeeId)) {
                m += e.getOrDefault(employeeId, 0.0);
                narrowest = samples("e", employeeId);
            } else {
                complete = false;                      // новый сотрудник: обслуживает «как все»
            }
            if (pairG != null && known("g", pairG)) {
                m += g.getOrDefault(pairG, 0.0);
                narrowest = samples("g", pairG);
            }
        }

        if (known("h", hourKey)) {
            m += h.getOrDefault(hourKey, 0.0);
        }

        if (useWindow && windowId != null) {
            if (known("w", windowId)) {
                m += w.getOrDefault(windowId, 0.0);
            } else {
                complete = false;
            }
        }

        double s = Math.max(SIGMA_FLOOR, sigma.getOrDefault(serviceCode, sigmaDefault));
        return new DurationModel.Prediction(
                seconds(Math.exp(m + s * s / 2)),
                seconds(Math.exp(m)),
                seconds(Math.exp(m + Z90 * s)),
                complete ? DurationModel.Prediction.BASIS_MODEL : DurationModel.Prediction.BASIS_MODEL_PARTIAL,
                version, narrowest);
    }

    private boolean known(String section, String key) {
        return counts.getOrDefault(section, Map.of()).containsKey(key);
    }

    private int samples(String section, String key) {
        return counts.getOrDefault(section, Map.of()).getOrDefault(key, 0);
    }

    private static Duration seconds(double value) {
        return Duration.ofMillis(Math.max(1, Math.round(value * 1000)));
    }

    private static void readDoubles(JsonNode section, String field, Map<String, Double> into) {
        var node = section.get(field);
        if (node == null || !node.isObject()) {
            return;
        }
        for (var entry : node.properties()) {
            if (entry.getValue().isNumber()) {
                into.put(entry.getKey(), entry.getValue().asDouble());
            }
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
