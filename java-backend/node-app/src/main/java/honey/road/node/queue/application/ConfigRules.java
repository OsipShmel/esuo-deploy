package honey.road.node.queue.application;

import honey.road.node.queue.domain.DomainException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Чистые правила конфигурации (раздел 4.8): наложение и границы локальных переопределений.
 * Работает над JSON-деревьями с ключами snake_case, как хранится в {@code branch_config}.
 */
public final class ConfigRules {
    private ConfigRules() {
    }

    /** Глубокое наложение {@code patch} на {@code base}; вложенные объекты сливаются, остальное заменяется. */
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
     * Переопределение допустимо, только если для ключа заданы границы и каждое числовое значение
     * (в том числе вложенное, как в {@code target_wait_min}) лежит в {@code [min, max]}.
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

    /** Оставляет только переопределения, которые всё ещё допустимы при новых границах. */
    public static ObjectNode pruneOutOfBounds(JsonNode overrides, JsonNode bounds) {
        ObjectNode kept = overrides != null && overrides.isObject() ? (ObjectNode) overrides.deepCopy() : emptyObject();
        for (var name : java.util.List.copyOf(kept.propertyNames())) {
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
        return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
    }
}
