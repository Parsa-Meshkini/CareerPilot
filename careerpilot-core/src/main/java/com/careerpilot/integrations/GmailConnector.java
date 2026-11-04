package com.careerpilot.integrations;

import com.careerpilot.common.ValidationException;
import com.careerpilot.users.User;
import com.sun.net.httpserver.HttpServer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Google's "installed app" OAuth flow. A one-shot web server on 127.0.0.1 receives the redirect from the consent screen,
 * the code is exchanged for a refresh token, and the token goes into the {@link CredentialVault}. The student never types
 * a password into CareerPilot, and the scope is read-only.
 */
@Component
public class GmailConnector {
    private final GmailAdapter gmail;
    private final EmailSyncService sync;

    public GmailConnector(GmailAdapter gmail, EmailSyncService sync) {
        this.gmail = gmail;
        this.sync = sync;
    }

    /** @param showUrl receives the consent URL (the CLI prints it, the GUI opens a browser) */
    public EmailAccount connect(User user, Consumer<String> showUrl, int timeoutSeconds) {
        if (!gmail.configured())
            throw new ValidationException("Gmail is not configured: set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in .env (see the README)");
        String state = UUID.randomUUID().toString();
        CompletableFuture<String> code = new CompletableFuture<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot listen for the Google redirect: " + e.getMessage(), e);
        }
        server.createContext("/", exchange -> {
            Map<String, String> q = query(exchange.getRequestURI());
            String page;
            if (!state.equals(q.get("state"))) {
                page = "Sign-in was not started by CareerPilot.";
            } else if (q.containsKey("code")) {
                code.complete(q.get("code"));
                page = "CareerPilot is connected to Gmail. You can close this tab.";
            } else {
                code.completeExceptionally(new ValidationException("Google sign-in was cancelled: " + q.getOrDefault("error", "no code returned")));
                page = "Sign-in was cancelled. You can close this tab.";
            }
            byte[] body = ("<html><body style=\"font-family:sans-serif\"><p>" + page + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            showUrl.accept(gmail.authUrl(redirect, state));
            String authCode;
            try {
                authCode = code.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new ValidationException("Google sign-in timed out; run the command again");
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for Google", e);
            }
            String refresh = gmail.exchangeAuthCode(authCode, redirect);
            String address = gmail.profileAddress(refresh);
            if (address.isEmpty()) throw new ValidationException("Google did not tell us which mailbox was connected");
            return sync.registerGmail(user, address, refresh);
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
