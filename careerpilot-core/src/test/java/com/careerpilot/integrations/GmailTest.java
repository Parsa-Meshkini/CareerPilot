package com.careerpilot.integrations;

import com.careerpilot.IntegrationTest;
import com.careerpilot.common.AppProperties;
import com.careerpilot.common.ConflictException;
import com.careerpilot.common.ValidationException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Gmail adapter, vault and consent flow against a local stand-in for Google (token, profile and messages endpoints). */
class GmailTest extends IntegrationTest {
    @Autowired AppProperties props;
    @Autowired GmailConnector connector;
    @Autowired EmailSyncService sync;
    @Autowired CredentialVault vault;
    @Autowired EmailAccountRepository accounts;

    HttpServer google;
    final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    final AtomicBoolean revoked = new AtomicBoolean();

    @BeforeEach
    void startGoogle() throws IOException {
        google = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        google.createContext("/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (revoked.get()) { reply(ex, 400, "{\"error\":\"invalid_grant\"}"); return; }
            reply(ex, 200, form.contains("authorization_code")
                    ? "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-secret\"}" : "{\"access_token\":\"at-2\"}");
        });
        google.createContext("/gmail/profile", ex -> reply(ex, 200, "{\"emailAddress\":\"student@gmail.test\"}"));
        google.createContext("/gmail/messages", ex -> {
            lastAuthHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            String path = ex.getRequestURI().getPath();
            if (path.equals("/gmail/messages")) { reply(ex, 200, "{\"messages\":[{\"id\":\"m1\"}]}"); return; }
            String mime = "From: recruiter@acme.test\r\nTo: student@gmail.test\r\nSubject: Interview invitation\r\nMessage-ID: <m1@acme>\r\n\r\nWe would like to invite you to an interview.";
            reply(ex, 200, "{\"threadId\":\"t1\",\"raw\":\"" + Base64.getUrlEncoder().encodeToString(mime.getBytes(StandardCharsets.UTF_8)) + "\"}");
        });
        google.start();
        String base = "http://127.0.0.1:" + google.getAddress().getPort();
        props.getEmail().setGoogleClientId("client-id");
        props.getEmail().setGoogleClientSecret("client-secret");
        props.getEmail().setGoogleTokenUrl(base + "/token");
        props.getEmail().setGmailApiBase(base + "/gmail");
        props.getEmail().setGoogleAuthUrl(base + "/auth");
    }

    @AfterEach
    void stopGoogle() {
        google.stop(0);
        props.getEmail().setGoogleClientId("");
        props.getEmail().setGoogleClientSecret("");
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** Plays the browser: opens the redirect URI from the consent URL with a code and the same state. */
    EmailAccount connectLikeABrowser(boolean approve) {
        return connector.connect(user, url -> {
            Map<String, String> q = new java.util.HashMap<>();
            for (String pair : URI.create(url).getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            assertEquals(GmailAdapter.SCOPE, q.get("scope"));
            assertEquals("offline", q.get("access_type"));
            String back = q.get("redirect_uri") + (approve ? "?code=abc&state=" : "?error=access_denied&state=") + q.get("state");
            new Thread(() -> {
                try {
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(back)).build(), HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).start();
        }, 20);
    }

    @Test
    void consentFlowStoresAnEncryptedRefreshTokenAndSyncFetchesMail() {
        EmailAccount account = connectLikeABrowser(true);
        assertEquals("student@gmail.test", account.getAddress());
        assertEquals("rt-secret", vault.get(GmailAdapter.vaultKey(account)).orElseThrow());

        List<RawEmail> mail = registryFetch(account);
        assertEquals(1, mail.size());
        assertEquals("Bearer at-2", lastAuthHeader.get());
        assertEquals("t1", mail.get(0).threadId());
        assertEquals("Interview invitation", mail.get(0).headers().get("Subject"));

        Map<String, Object> summary = sync.syncAccount(user, account.getId());
        assertEquals(1, ((Number) summary.get("fetched")).intValue());
    }

    List<RawEmail> registryFetch(EmailAccount account) {
        return new GmailAdapter(props, vault).fetchNewMessages(account, null);
    }

    @Test
    void tokenFileIsNotPlainText() throws IOException {
        EmailAccount account = connectLikeABrowser(true);
        java.nio.file.Path file = props.getStorageRoot().resolve("vault").resolve("gmail-" + account.getId() + ".enc");
        assertFalse(new String(java.nio.file.Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("rt-secret"));
    }

    @Test
    void cancellingConsentIsAReadableError() {
        ValidationException e = assertThrows(ValidationException.class, () -> connectLikeABrowser(false));
        assertTrue(e.getMessage().contains("cancelled"));
    }

    @Test
    void revokedAccessAsksToReconnect() {
        EmailAccount account = connectLikeABrowser(true);
        revoked.set(true);
        assertThrows(ConflictException.class, () -> registryFetch(account));
        assertEquals(EmailAccount.REAUTH_REQUIRED, account.getStatus());
    }

    @Test
    void unconfiguredGmailExplainsWhatToSet() {
        props.getEmail().setGoogleClientId("");
        ValidationException e = assertThrows(ValidationException.class, () -> connector.connect(user, u -> {}, 1));
        assertTrue(e.getMessage().contains("GOOGLE_CLIENT_ID"));
    }

    @Test
    void disconnectRemovesTheStoredToken() {
        EmailAccount account = connectLikeABrowser(true);
        sync.disconnect(user, account.getId());
        assertTrue(vault.get(GmailAdapter.vaultKey(account)).isEmpty());
    }
}
