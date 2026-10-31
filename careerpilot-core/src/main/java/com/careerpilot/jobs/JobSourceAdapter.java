package com.careerpilot.jobs;

import com.careerpilot.users.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Target interface of the Adapter pattern: one implementation per job source format. */
public interface JobSourceAdapter {
    SourceType sourceType();

    List<RawJobRecord> parse(Map<String, Object> payload, User user);

    /** Returns the problems that make a record unimportable; empty means importable. */
    default List<String> validate(RawJobRecord raw) {
        List<String> problems = new ArrayList<>();
        if (raw.title.isBlank()) problems.add("missing title");
        if (raw.company.isBlank()) problems.add("missing company");
        return problems;
    }
}
