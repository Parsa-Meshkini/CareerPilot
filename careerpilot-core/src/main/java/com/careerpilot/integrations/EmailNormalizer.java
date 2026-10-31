package com.careerpilot.integrations;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;

/** RawEmail to EmailMessage with a plain-text excerpt of at most 4 KB. */
@Component
public class EmailNormalizer {
    private static final Pattern TAGS = Pattern.compile("<(script|style).*?</\\1>|<[^>]+>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("^(>.*|On .+ wrote:)$", Pattern.MULTILINE);

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    public EmailMessage normalize(RawEmail raw, EmailAccount account) {
        try {
            MimeMessage message = MimeSupport.parse(raw.mimeBody());
            String sender = MimeSupport.displayName(message.getFrom());
            if (sender.isEmpty()) sender = raw.headers().getOrDefault("From", "");
            String subject = message.getSubject() == null ? raw.headers().getOrDefault("Subject", "") : message.getSubject();
            EmailMessage m = new EmailMessage();
            m.setUser(account.getUser());
            m.setAccount(account);
            m.setProviderMessageId(cut(raw.providerMessageId(), 300));
            m.setThreadId(cut(raw.threadId(), 300));
            m.setSender(cut(sender, 320));
            m.setSubject(cut(String.join(" ", subject.strip().split("\\s+")), 500));
            m.setReceivedAt(message.getSentDate() == null ? Instant.now() : message.getSentDate().toInstant());
            m.setExcerpt(cut(excerpt(message), EmailMessage.EXCERPT_LIMIT));
            return m;
        } catch (MessagingException | IOException e) {
            throw new com.careerpilot.common.ValidationException("cannot read email " + raw.providerMessageId());
        }
    }

    private static String excerpt(MimeMessage message) throws MessagingException, IOException {
        MimeSupport.Body body = MimeSupport.body(message);
        String text = body.text();
        if (body.html()) text = unescape(TAGS.matcher(text).replaceAll(" "));
        text = QUOTED.matcher(text).replaceAll("");
        return text.replaceAll("\\n\\s*\\n+", "\n\n").replaceAll("[ \\t]+", " ").strip();
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }
}
