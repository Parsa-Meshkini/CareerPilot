package com.careerpilot.common;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JPA converters that store JSON values in TEXT columns (the Django JSONField equivalent). */
public final class JsonConverters {
    private JsonConverters() {}

    @Converter
    public static class MapConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override public String convertToDatabaseColumn(Map<String, Object> attribute) {
            return Json.write(attribute == null ? Map.of() : attribute);
        }
        @Override public Map<String, Object> convertToEntityAttribute(String db) {
            if (db == null || db.isBlank()) return new LinkedHashMap<>();
            return new LinkedHashMap<>(Json.map(db));
        }
    }

    @Converter
    public static class ListConverter implements AttributeConverter<List<Object>, String> {
        @Override public String convertToDatabaseColumn(List<Object> attribute) {
            return Json.write(attribute == null ? List.of() : attribute);
        }
        @Override public List<Object> convertToEntityAttribute(String db) {
            if (db == null || db.isBlank()) return new ArrayList<>();
            return new ArrayList<>(Json.list(db));
        }
    }

    @Converter
    public static class StringListConverter implements AttributeConverter<List<String>, String> {
        @Override public String convertToDatabaseColumn(List<String> attribute) {
            return Json.write(attribute == null ? List.of() : attribute);
        }
        @Override public List<String> convertToEntityAttribute(String db) {
            if (db == null || db.isBlank()) return new ArrayList<>();
            try {
                return new ArrayList<>(Json.MAPPER.readValue(db, new TypeReference<List<String>>() {}));
            } catch (Exception e) {
                throw new IllegalStateException("bad string list in column: " + db, e);
            }
        }
    }

    @Converter
    public static class MapListConverter implements AttributeConverter<List<Map<String, Object>>, String> {
        @Override public String convertToDatabaseColumn(List<Map<String, Object>> attribute) {
            return Json.write(attribute == null ? List.of() : attribute);
        }
        @Override public List<Map<String, Object>> convertToEntityAttribute(String db) {
            if (db == null || db.isBlank()) return new ArrayList<>();
            try {
                return new ArrayList<>(Json.MAPPER.readValue(db, new TypeReference<List<Map<String, Object>>>() {}));
            } catch (Exception e) {
                throw new IllegalStateException("bad object list in column: " + db, e);
            }
        }
    }
}
