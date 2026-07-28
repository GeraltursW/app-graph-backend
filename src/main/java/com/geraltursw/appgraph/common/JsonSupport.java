package com.geraltursw.appgraph.common;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JsonSupport {
    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Cannot serialize JSON", exception);
        }
    }

    public Map<String, Object> object(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return mapper.readValue(String.valueOf(value), new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            return new LinkedHashMap<>();
        }
    }

    public List<Object> array(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        try {
            return mapper.readValue(String.valueOf(value), new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            return List.of();
        }
    }
}
