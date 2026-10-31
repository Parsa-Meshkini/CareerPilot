package com.careerpilot.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Shared HTTP plumbing for the vendor adapters. Error text never includes request headers (they carry the key). */
final class HttpSupport {
    private HttpSupport() {}

    /** One readable line from a vendor error body: the JSON error.message when there is one, else the collapsed text. */
    static String errorText(String body) {
        String text = body == null ? "" : body;
        try {
            String message = com.careerpilot.common.Json.MAPPER.readTree(text).path("error").path("message").asText("");
            if (!message.isBlank()) text = message;
        } catch (Exception notJson) {
            // keep the raw text
        }
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    static HttpClient client(int timeoutSeconds) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    static String postJson(HttpClient http, int timeoutSeconds, String url, Map<String, String> headers, String body, String vendor) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmProviderException(vendor + " network error: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(vendor + " call interrupted", false, e);
        }
        int status = response.statusCode();
        if (status >= 400) {
            boolean retryable = status == 408 || status == 429 || status >= 500;
            throw new LlmProviderException(vendor + " HTTP " + status + ": " + errorText(response.body()), retryable);
        }
        return response.body();
    }
}
