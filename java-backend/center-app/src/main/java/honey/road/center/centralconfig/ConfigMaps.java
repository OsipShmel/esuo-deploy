package honey.road.center.centralconfig;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deep merge of JSON-like maps (sect 4.8): nested objects merge, everything else is replaced, null removes a key. */
final class ConfigMaps {
    private ConfigMaps() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> patch) {
        var result = new LinkedHashMap<String, Object>(base == null ? Map.of() : base);
        if (patch == null) { return result; }
        patch.forEach((key, value) -> {
            if (value == null) {
                result.remove(key);
            } else if (value instanceof Map<?, ?> p && result.get(key) instanceof Map<?, ?> b) {
                result.put(key, merge((Map<String, Object>) b, (Map<String, Object>) p));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }
}
