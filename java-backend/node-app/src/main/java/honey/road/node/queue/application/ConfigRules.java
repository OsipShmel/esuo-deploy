package honey.road.node.queue.application;

import honey.road.node.queue.domain.DomainException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure config rules (sect 4.8): merging and bounds of local overrides, over snake_case JSON trees as stored in
 * {@code branch_config}.
 */
public final class ConfigRules {
    private ConfigRules() {
    }

    /** Deep merge of {@code patch} into {@code base}: objects merge, everything else is replaced. */
    public static ObjectNode merge(JsonNode base, JsonNode patch) {
        var result = (ObjectNode) base.deepCopy();
        mergeInto(result, patch);
        return result;
    }

    private static void mergeInto(ObjectNode target, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return;
        }
        for (var entry : patch.properties()) {
            var existing = target.get(entry.getKey());
            if (entry.getValue().isObject() && existing != null && existing.isObject()) {
                mergeInto((ObjectNode) existing, entry.getValue());
            } else {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    /**
     * An override is allowed only if the key has bounds and every numeric value (including nested ones, as in
     * {@code target_wait_min}) is within {@code [min, max]}.
     */
    public static void checkWithinBounds(JsonNode overrides, JsonNode bounds) {
        if (overrides == null || overrides.isNull() || overrides.isEmpty()) {
            return;
        }
        if (!overrides.isObject()) {
            throw DomainException.validation("Переопределения должны быть объектом");
        }
        for (var entry : overrides.properties()) {
            var bound = bounds == null ? null : bounds.get(entry.getKey());
            if (bound == null || !bound.isObject() || !bound.has("min") || !bound.has("max")) {
                throw new DomainException("CONFIG_OUT_OF_BOUNDS",
                        "Параметр '" + entry.getKey() + "' нельзя переопределить локально");
            }
            checkLeaves(entry.getKey(), entry.getValue(), bound.get("min").asDouble(), bound.get("max").asDouble());
        }
    }

    private static void checkLeaves(String path, JsonNode value, double min, double max) {
        if (value.isObject()) {
            for (var e : value.properties()) {
                checkLeaves(path + "." + e.getKey(), e.getValue(), min, max);
            }
        } else if (!value.isNumber() || value.asDouble() < min || value.asDouble() > max) {
            throw new DomainException("CONFIG_OUT_OF_BOUNDS",
                    "Значение '" + path + "' вне границ [" + trim(min) + ", " + trim(max) + "]");
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Keeps only the overrides still allowed by the new bounds. */
    public static ObjectNode pruneOutOfBounds(JsonNode overrides, JsonNode bounds) {
        ObjectNode kept = overrides != null && overrides.isObject() ? (ObjectNode) overrides.deepCopy() : emptyObject();
        for (var name : List.copyOf(kept.propertyNames())) {
            var single = kept.objectNode();
            single.set(name, kept.get(name));
            try {
                checkWithinBounds(single, bounds);
            } catch (DomainException e) {
                kept.remove(name);
            }
        }
        return kept;
    }

    private static ObjectNode emptyObject() {
        return JsonMapper.builder().build().createObjectNode();
    }
}
