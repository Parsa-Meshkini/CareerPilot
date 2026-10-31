package com.careerpilot.integrations;

import java.time.Instant;
import java.util.List;

/** Target interface of the Adapter pattern: turns a mailbox into a list of {@link RawEmail}, whatever the source. */
public interface EmailProviderAdapter {
    String providerName();

    List<RawEmail> fetchNewMessages(EmailAccount account, Instant since);
}
