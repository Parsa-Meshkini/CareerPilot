package com.careerpilot.integrations;

import com.careerpilot.common.ValidationException;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** RFC 822 parsing helpers shared by the .eml adapter and the normalizer. */
final class MimeSupport {
    private static final Session SESSION = Session.getInstance(new Properties());

    private MimeSupport() {}

    static MimeMessage parse(byte[] data) {
        try {
            return new MimeMessage(SESSION, new ByteArrayInputStream(data));
        } catch (MessagingException e) {
            throw new ValidationException("file is not a readable email");
        }
    }

    /** Parses one message into a RawEmail. Raises ValidationException when it is not an email. */
    static RawEmail rawFromBytes(byte[] data, String fallbackId) {
        try {
            MimeMessage message = parse(data);
            String from = header(message, "From"), subject = header(message, "Subject");
            if (from.isEmpty() && subject.isEmpty()) throw new ValidationException("file is not a readable email (no From or Subject header)");
            String id = header(message, "Message-ID").replaceAll("^<|>$", "").strip();
            if (id.isEmpty()) id = fallbackId != null ? fallbackId : sha256(data).substring(0, 32);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String h : new String[]{"From", "To", "Subject", "Date"}) headers.put(h, header(message, h));
            String refs = header(message, "References");
            String thread = refs.isEmpty() ? id : refs.split("\\s+")[0];
            return new RawEmail(id, thread, headers, data);
        } catch (MessagingException e) {
            throw new ValidationException("file is not a readable email");
        }
    }

    static String header(MimeMessage message, String name) throws MessagingException {
        String value = message.getHeader(name, null);
        return value == null ? "" : value.strip();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Preferred body: text/plain, otherwise text/html (returned with a flag), searching nested multiparts. */
    record Body(String text, boolean html) {}

    static Body body(Part part) throws MessagingException, IOException {
        Body plain = find(part, "text/plain");
        if (plain != null) return plain;
        Body html = find(part, "text/html");
        return html != null ? html : new Body("", false);
    }

    private static Body find(Part part, String mime) throws MessagingException, IOException {
        if (part.isMimeType(mime) && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()))
            return new Body(textOf(part), mime.equals("text/html"));
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                Body b = find(bp, mime);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static String textOf(Part part) throws MessagingException, IOException {
        Object content = part.getContent();
        if (content instanceof String s) return s;
        return new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    static String displayName(Address[] from) {
        if (from == null || from.length == 0) return "";
        if (from[0] instanceof InternetAddress ia) {
            String name = ia.getPersonal() == null ? "" : ia.getPersonal();
            return name.isEmpty() ? ia.getAddress() : name + " <" + ia.getAddress() + ">";
        }
        return from[0].toString();
    }
}
