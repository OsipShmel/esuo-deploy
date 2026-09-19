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

    public <T> T readValue(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Error reading saved data", ex);
        }
    }
}
