package com.careerpilot.jobs;

import com.careerpilot.common.ValidationException;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Simple factory that maps a source name to its adapter. */
@Component
public class JobSourceRegistry {
    private final Map<String, JobSourceAdapter> adapters;

    public JobSourceRegistry(YorkBrowserImportAdapter york, LinkedInBrowserImportAdapter linkedin,
                             ManualJobImportAdapter manual, MockJobSourceAdapter file) {
        adapters = Map.of("york", york, "linkedin", linkedin, "manual", manual, "file", file, "mock", file);
    }

    public JobSourceAdapter get(String source) {
        JobSourceAdapter adapter = adapters.get(source == null ? "" : source.toLowerCase());
        if (adapter == null) throw new ValidationException("unsupported job source '" + source + "'");
        return adapter;
    }
}
