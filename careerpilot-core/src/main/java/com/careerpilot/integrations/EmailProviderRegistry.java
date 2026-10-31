package com.careerpilot.integrations;

import com.careerpilot.common.ValidationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Simple factory: provider name to adapter. Gmail OAuth would register here; see integrations notes in the report. */
@Component
public class EmailProviderRegistry {
    private final Map<String, EmailProviderAdapter> adapters = new TreeMap<>();

    public EmailProviderRegistry(List<EmailProviderAdapter> all) { all.forEach(a -> adapters.put(a.providerName(), a)); }

    public EmailProviderAdapter get(String provider) {
        EmailProviderAdapter a = adapters.get(provider);
        if (a == null) throw new ValidationException("email provider '" + provider + "' is not available").with("available", adapters.keySet());
        return a;
    }
}
