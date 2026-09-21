package honey.road.center.centralconfig;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.error.FieldViolation;
import honey.road.contracts.inbox.ConfigMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Body of {@code PUT /config/global} and {@code PUT /config/branches/{b}}:
 * {@code {queue?, bounds?, planning?, model?}}.
 * Checks key names and value types so the node never gets a message it would reject.
 */
final class ConfigValidator {
    static final Set<String> SECTIONS = Set.of("queue", "bounds", "planning", "model");

    /** Keys of the {@code planning} section (load-planning.md 3.1). */
    private static final Set<String> PLANNING_KEYS = Set.of(
            "base_arrivals_per_day", "utilization_target", "weekdays", "calendar", "profiles",
            "thresholds", "levers");

    private static final Set<String> PLANNING_OBJECTS = Set.of("weekdays", "profiles", "thresholds", "levers");

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
                errors.add(new FieldViolation(section, "Неизвестная секция; допустимы queue, bounds, planning, model"));
            } else if (value != null && !(value instanceof Map)) {
                errors.add(new FieldViolation(section, "Ожидается объект или null"));
            }
        });
        if (body.get("queue") instanceof Map<?, ?> queue) { queue(queue, errors); }
        if (body.get("bounds") instanceof Map<?, ?> bounds) { bounds(bounds, errors); }
        if (body.get("planning") instanceof Map<?, ?> planning) { planning(planning, errors); }
        if (body.get("model") instanceof Map<?, ?> model) { model(model, errors); }
        if (!errors.isEmpty()) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Некорректная конфигурация", errors);
        }
    }

    /**
     * Load planning (load-planning.md 3.1). Until now the section was only checked for being an object,
     * so a typo in a profile reached the node and broke the forecast silently. Here the shape is checked;
     * the numbers themselves are the manager's business.
     */
    private static void planning(Map<?, ?> planning, List<FieldViolation> errors) {
        planning.forEach((k, v) -> {
            var key = String.valueOf(k);
            var field = "planning." + key;
            if (!PLANNING_KEYS.contains(key)) {
                errors.add(new FieldViolation(field, "Неизвестный параметр планирования; допустимы: "
                        + String.join(", ", new TreeSet<>(PLANNING_KEYS))));
            } else if (v == null) {
                return;
            } else if (key.equals("calendar")) {
                calendar(v, field, errors);
            } else if (PLANNING_OBJECTS.contains(key)) {
                if (!(v instanceof Map)) { errors.add(new FieldViolation(field, "Ожидается объект")); }
                else if (key.equals("profiles")) { profiles((Map<?, ?>) v, field, errors); }
            } else if (!(v instanceof Number n) || n.doubleValue() < 0) {
                errors.add(new FieldViolation(field, "Ожидается неотрицательное число"));
            }
        });
    }

    private static void profiles(Map<?, ?> profiles, String field, List<FieldViolation> errors) {
        profiles.forEach((code, body) -> {
            var path = field + "." + code;
            if (!(body instanceof Map<?, ?> profile)) {
                errors.add(new FieldViolation(path, "Ожидается объект профиля"));
                return;
            }
            if (Boolean.TRUE.equals(profile.get("closed"))) {
                return;                                   // закрытый день: остальные поля не нужны
            }
            for (var required : List.of("hourly_curve", "service_mix", "category_mix", "planned_windows")) {
                if (profile.get(required) != null && !(profile.get(required) instanceof Map)) {
                    errors.add(new FieldViolation(path + "." + required, "Ожидается объект"));
                }
            }
            for (var time : List.of("opening_time", "closing_time")) {
                if (profile.get(time) != null
                        && (!(profile.get(time) instanceof String s) || !s.matches("([01]\\d|2[0-3]):[0-5]\\d"))) {
                    errors.add(new FieldViolation(path + "." + time, "Ожидается время ЧЧ:ММ"));
                }
            }
        });
    }

    private static void calendar(Object value, String field, List<FieldViolation> errors) {
        if (!(value instanceof List<?> rows)) {
            errors.add(new FieldViolation(field, "Ожидается список {date, profile}"));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            var path = field + "[" + i + "]";
            if (!(rows.get(i) instanceof Map<?, ?> row)) {
                errors.add(new FieldViolation(path, "Ожидается объект {date, profile}"));
            } else if (!(row.get("date") instanceof String date) || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                errors.add(new FieldViolation(path + ".date", "Ожидается дата ГГГГ-ММ-ДД"));
            } else if (!(row.get("profile") instanceof String profile) || profile.isBlank()) {
                errors.add(new FieldViolation(path + ".profile", "Обязателен код профиля"));
            }
        }
    }

    /**
     * Duration model coefficients (ml-model.md 4.4). The section is produced by the offline trainer, so this
     * guards against a hand-edited or truncated file rather than against a careless manager: an unknown key
     * or a non-numeric coefficient would be silently ignored on the node and quietly skew every forecast.
     */
    private static void model(Map<?, ?> model, List<FieldViolation> errors) {
        model.forEach((k, v) -> {
            var key = String.valueOf(k);
            var field = "model." + key;
            if (!ConfigMessage.MODEL_KEYS.contains(key)) {
                errors.add(new FieldViolation(field, "Неизвестный параметр модели"));
            } else if (v == null) {
                return;
            } else if (ConfigMessage.MODEL_COEFFICIENT_KEYS.contains(key)) {
                if (!(v instanceof Map<?, ?> groups)) {
                    errors.add(new FieldViolation(field, "Ожидается объект «группа: число»"));
                } else {
                    groups.forEach((group, coefficient) -> {
                        if (!(coefficient instanceof Number)) {
                            errors.add(new FieldViolation(field + "." + group, "Ожидается число"));
                        }
                    });
                }
            } else if (key.equals("mu") || key.equals("sigma_default")) {
                if (!(v instanceof Number)) { errors.add(new FieldViolation(field, "Ожидается число")); }
            } else if (key.equals("enabled") || key.equals("use_window")) {
                if (!(v instanceof Boolean)) { errors.add(new FieldViolation(field, "Ожидается true или false")); }
            } else if (key.equals("counts") || key.equals("metrics")) {
                if (!(v instanceof Map)) { errors.add(new FieldViolation(field, "Ожидается объект")); }
            } else if (!(v instanceof String s) || s.isBlank()) {
                errors.add(new FieldViolation(field, "Ожидается непустая строка"));
            }
        });
        // Версия обязательна только у настоящей публикации модели — той, что несёт коэффициенты.
        // Переопределение отделения вида {"enabled": false} версии не несёт и нести не должно:
        // оно лишь выключает на этом отделении ту модель, которая уже приехала из глобальной секции.
        boolean carriesCoefficients = model.keySet().stream().map(String::valueOf)
                .anyMatch(k -> ConfigMessage.MODEL_COEFFICIENT_KEYS.contains(k) || k.equals("mu"));
        if (carriesCoefficients && model.get("version") == null) {
            errors.add(new FieldViolation("model.version", "Версия модели обязательна: по ней нода "
                    + "понимает, что приехала новая модель, и публикует model.trained"));
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
