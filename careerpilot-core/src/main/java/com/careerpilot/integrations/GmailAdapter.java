package com.careerpilot.integrations;

import com.careerpilot.common.AppProperties;
import com.careerpilot.common.ConflictException;
import com.careerpilot.common.Json;
import com.careerpilot.common.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapter for the Gmail REST API (read-only scope). Turns the mailbox into {@link RawEmail}s, exactly like the .eml and
 * mock adapters, so everything after the fetch (normalise, classify, match, publish) is shared. The refresh token lives
 * in the {@link CredentialVault}; an access token is requested for each sync and never stored.
 */
@Component
public class GmailAdapter implements EmailProviderAdapter {
    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    static final int MAX_MESSAGES = 50;

    private final AppProperties.Email config;
    private final CredentialVault vault;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public GmailAdapter(AppProperties props, CredentialVault vault) {
        this.config = props.getEmail();
        this.vault = vault;
    }

    @Override public String providerName() { return "GMAIL"; }

    public boolean configured() { return !config.getGoogleClientId().isBlank() && !config.getGoogleClientSecret().isBlank(); }

    static String vaultKey(EmailAccount account) { return "gmail-" + account.getId(); }

    @Override
    public List<RawEmail> fetchNewMessages(EmailAccount account, Instant since) {
        String refresh = vault.get(vaultKey(account)).orElseThrow(() -> reauth(account));
        String token = accessToken(account, refresh);
        String query = "newer_than:30d" + (since == null ? "" : " after:" + (since.getEpochSecond() - 60));
        JsonNode listed = get(token, config.getGmailApiBase() + "/messages?maxResults=" + MAX_MESSAGES + "&q=" + enc(query));
        List<RawEmail> out = new ArrayList<>();
        for (JsonNode ref : listed.path("messages")) {
            JsonNode full = get(token, config.getGmailApiBase() + "/messages/" + ref.path("id").asText() + "?format=raw");
            byte[] mime = Base64.getUrlDecoder().decode(full.path("raw").asText());
            try {
                RawEmail email = MimeSupport.rawFromBytes(mime, "gmail-" + ref.path("id").asText());
                out.add(new RawEmail(email.providerMessageId(), full.path("threadId").asText(email.threadId()), email.headers(), mime));
            } catch (ValidationException unreadable) {
                // a message with no usable headers is skipped; the rest of the sync continues
            }
        }
        return out;
    }

    /** Exchanges the one-time code from the consent screen for tokens; returns the refresh token. */
    String exchangeAuthCode(String code, String redirectUri) {
        JsonNode tokens = form(config.getGoogleTokenUrl(), Map.of("code", code, "client_id", config.getGoogleClientId(),
                "client_secret", config.getGoogleClientSecret(), "redirect_uri", redirectUri, "grant_type", "authorization_code"));
        String refresh = tokens.path("refresh_token").asText("");
        if (refresh.isEmpty()) throw new ValidationException("Google did not return a refresh token; remove CareerPilot from your Google account's third-party access and connect again");
        return refresh;
    }

    /** The signed-in address, read with a fresh access token. */
    String profileAddress(String refreshToken) {
        String token = refresh(refreshToken).path("access_token").asText();
        return get(token, config.getGmailApiBase() + "/profile").path("emailAddress").asText("");
    }

    String authUrl(String redirectUri, String state) {
        return config.getGoogleAuthUrl() + "?response_type=code&access_type=offline&prompt=consent&client_id=" + enc(config.getGoogleClientId())
                + "&redirect_uri=" + enc(redirectUri) + "&scope=" + enc(SCOPE) + "&state=" + enc(state);
    }

    private String accessToken(EmailAccount account, String refreshToken) {
        try {
            return refresh(refreshToken).path("access_token").asText();
        } catch (ConflictException revoked) {
            account.setStatus(EmailAccount.REAUTH_REQUIRED);
            throw revoked;
        }
    }

    private JsonNode refresh(String refreshToken) {
        return form(config.getGoogleTokenUrl(), Map.of("refresh_token", refreshToken, "client_id", config.getGoogleClientId(),
                "client_secret", config.getGoogleClientSecret(), "grant_type", "refresh_token"));
    }

    private static ConflictException reauth(EmailAccount account) {
        account.setStatus(EmailAccount.REAUTH_REQUIRED);
        return new ConflictException("the Gmail connection has expired; connect the account again");
    }

    private JsonNode form(String url, Map<String, String> fields) {
        String body = fields.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private JsonNode get(String token, String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).GET().build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 400 || status == 401 || status == 403) throw new ConflictException("Google rejected the stored sign-in (HTTP " + status + "); connect the account again");
            if (status >= 400) throw new IllegalStateException("Gmail HTTP " + status);
            return Json.MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Gmail network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gmail call interrupted", e);
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
