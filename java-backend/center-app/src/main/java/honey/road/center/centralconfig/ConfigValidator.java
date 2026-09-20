package honey.road.center.centralconfig;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.error.FieldViolation;
import honey.road.contracts.inbox.ConfigMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Body of {@code PUT /config/global} and {@code PUT /config/branches/{b}}: {@code {queue?, bounds?, planning?}}.
 * Checks key names and value types so the node never gets a message it would reject.
 */
final class ConfigValidator {
    static final Set<String> SECTIONS = Set.of("queue", "bounds", "planning");

    private static final Set<String> STRINGS = Set.of("scoring_strategy", "late_policy", "requeue_default_mode");
    private static final Set<String> TIMES = Set.of("opening_time", "closing_time");
    private static final Set<String> DECIMALS = Set.of("slow_window_factor");
    private static final Map<String, Set<String>> ENUMS = Map.of(
            "scoring_strategy", Set.of("uniform", "heuristic"),
            "late_policy", Set.of("convert_to_qr", "reject"),
            "requeue_default_mode", Set.of("keep_position", "to_tail"));

    private ConfigValidator() {
    }

    static void validate(Map<String, Object> body) {
        if (body == null) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Тело запроса обязательно");
        }
        var errors = new ArrayList<FieldViolation>();
        body.forEach((section, value) -> {
            if (!SECTIONS.contains(section)) {
                errors.add(new FieldViolation(section, "Неизвестная секция; допустимы queue, bounds, planning"));
            } else if (value != null && !(value instanceof Map)) {
                errors.add(new FieldViolation(section, "Ожидается объект или null"));
            }
        });
        if (body.get("queue") instanceof Map<?, ?> queue) { queue(queue, errors); }
        if (body.get("bounds") instanceof Map<?, ?> bounds) { bounds(bounds, errors); }
        if (!errors.isEmpty()) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Некорректная конфигурация", errors);
        }
    }

    private static void queue(Map<?, ?> queue, List<FieldViolation> errors) {
        queue.forEach((k, v) -> {
            var key = String.valueOf(k);
            var field = "queue." + key;
            if (!ConfigMessage.QUEUE_KEYS.contains(key)) {
                errors.add(new FieldViolation(field, "Неизвестный параметр очереди"));
            } else if (v == null) {
                return;
            } else if (key.equals("target_wait_min")) {
                targetWait(v, field, errors);
            } else if (key.equals("auto_call_on_free")) {
                if (!(v instanceof Boolean)) { errors.add(new FieldViolation(field, "Ожидается true или false")); }
            } else if (ENUMS.containsKey(key)) {
                if (!ENUMS.get(key).contains(String.valueOf(v))) {
                    errors.add(new FieldViolation(field, "Допустимо: " + String.join(", ", ENUMS.get(key))));
                }
            } else if (TIMES.contains(key)) {
                if (!(v instanceof String s) || !s.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                    errors.add(new FieldViolation(field, "Ожидается время ЧЧ:ММ"));
                }
            } else if (DECIMALS.contains(key)) {
                if (!(v instanceof Number n) || n.doubleValue() <= 0) {
                    errors.add(new FieldViolation(field, "Ожидается положительное число"));
                }
            } else if (!STRINGS.contains(key) && !nonNegativeInt(v)) {
                errors.add(new FieldViolation(field, "Ожидается целое число ≥ 0"));
            }
        });
    }

    private static void targetWait(Object v, String field, List<FieldViolation> errors) {
        if (!(v instanceof Map<?, ?> sources)) {
            errors.add(new FieldViolation(field, "Ожидается {qr|walkin: {категория: минуты}}"));
            return;
        }
        sources.forEach((source, categories) -> {
            if (!Set.of("qr", "walkin").contains(String.valueOf(source)) || !(categories instanceof Map<?, ?> m)) {
                errors.add(new FieldViolation(field + "." + source, "Ожидается qr или walkin с объектом категорий"));
                return;
            }
            m.forEach((category, minutes) -> {
                if (!nonNegativeInt(minutes) || ((Number) minutes).intValue() == 0) {
                    errors.add(new FieldViolation(field + "." + source + "." + category, "Ожидается целое число > 0"));
                }
            });
        });
    }

    private static void bounds(Map<?, ?> bounds, List<FieldViolation> errors) {
        bounds.forEach((k, v) -> {
            var key = String.valueOf(k);
            var field = "bounds." + key;
            if (!ConfigMessage.QUEUE_KEYS.contains(key)) {
                errors.add(new FieldViolation(field, "Неизвестный параметр очереди"));
            } else if (!(v instanceof Map<?, ?> range) || !(range.get("min") instanceof Number min)
                    || !(range.get("max") instanceof Number max)) {
                errors.add(new FieldViolation(field, "Ожидается {min, max}"));
            } else if (min.doubleValue() > max.doubleValue()) {
                errors.add(new FieldViolation(field, "min больше max"));
            }
        });
    }

    private static boolean nonNegativeInt(Object v) {
        return v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() == Math.rint(n.doubleValue());
    }
}
