package com.careerpilot.candidates;

import com.careerpilot.common.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks and normalises the JSON payload of a profile fact against {@link FactType}'s declared fields. */
public final class FactValidator {
    private static final Set<String> LIST_FIELDS = Set.of("bullets", "technologies");
    private static final Pattern DATE = Pattern.compile("^\\d{4}(-\\d{2}(-\\d{2})?)?$");

    private FactValidator() {}

    public static Map<String, Object> validate(FactType type, Map<String, Object> data) {
        if (type == null) throw new ValidationException("unknown fact type").with("fields", Map.of("fact_type", "unknown type"));
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> clean = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(type.required);
        names.addAll(type.optional);
        for (String name : names) {
            Object raw = data.get(name);
            if (LIST_FIELDS.contains(name)) {
                clean.put(name, toList(raw, name.equals("bullets") ? "\n" : ","));
                continue;
            }
            String value = raw == null ? "" : raw.toString().strip();
            if (type.required.contains(name) && value.isEmpty()) errors.put(name, "required");
            if (!value.isEmpty() && (name.endsWith("_date") || name.endsWith("_on")) && !DATE.matcher(value).matches())
                errors.put(name, "use YYYY, YYYY-MM or YYYY-MM-DD");
            clean.put(name, value);
        }
        String start = (String) clean.getOrDefault("start_date", ""), end = (String) clean.getOrDefault("end_date", "");
        if (!start.isEmpty() && !end.isEmpty() && DATE.matcher(start).matches() && DATE.matcher(end).matches() && end.compareTo(start) < 0)
            errors.put("end_date", "end date is before start date");
        if (!errors.isEmpty()) throw new ValidationException("invalid profile fact").with("fields", errors);
        return clean;
    }

    private static List<String> toList(Object raw, String separator) {
        List<String> out = new ArrayList<>();
        if (raw instanceof String s) {
            for (String part : s.split(java.util.regex.Pattern.quote(separator))) if (!part.isBlank()) out.add(part.strip());
        } else if (raw instanceof Iterable<?> it) {
            for (Object o : it) if (o != null && !o.toString().isBlank()) out.add(o.toString().strip());
        }
        return out;
    }
}
