package honey.road.node.config;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;


@Component
public class JsonCodec {
    private final JsonMapper mapper;

    public JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String writeValueAsString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("data serialization error", ex);
        }
    }

    public tools.jackson.databind.JsonNode readTree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Error reading saved data", ex);
        }
    }

    public tools.jackson.databind.JsonNode toTree(Object value) {
        return mapper.valueToTree(value);
    }

    public <T> T treeToValue(tools.jackson.databind.JsonNode tree, Class<T> type) {
        try {
            return mapper.treeToValue(tree, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Invalid data: " + ex.getOriginalMessage(), ex);
        }
    }

    public <T> T readValue(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Error reading saved data", ex);
        }
    }
}
