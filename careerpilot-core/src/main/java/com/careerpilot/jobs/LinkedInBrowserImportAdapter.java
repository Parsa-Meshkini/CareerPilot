package com.careerpilot.jobs;

import com.careerpilot.common.ValidationException;
import com.careerpilot.users.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Disabled by design: LinkedIn's terms forbid automated extraction, so students use paste import instead. */
@Component
public class LinkedInBrowserImportAdapter implements JobSourceAdapter {
    private final boolean enabled;
    private final YorkBrowserImportAdapter delegate = new YorkBrowserImportAdapter();

    public LinkedInBrowserImportAdapter(@Value("${FEATURE_LINKEDIN_EXTRACTION:false}") boolean enabled) { this.enabled = enabled; }

    @Override public SourceType sourceType() { return SourceType.LINKEDIN; }

    @Override
    public List<RawJobRecord> parse(Map<String, Object> payload, User user) {
        if (!enabled) throw new ValidationException("LinkedIn page extraction is disabled; paste the posting text instead");
        return delegate.parse(payload, user).stream().peek(r -> r.sourceType = SourceType.LINKEDIN).toList();
    }
}
