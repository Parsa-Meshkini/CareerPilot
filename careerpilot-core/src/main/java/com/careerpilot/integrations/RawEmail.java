package com.careerpilot.integrations;

import java.util.Map;

/** A message as fetched from a provider: identifiers, headers as sent, and the full RFC 822 bytes. */
public record RawEmail(String providerMessageId, String threadId, Map<String, String> headers, byte[] mimeBody) {}
