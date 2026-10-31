package com.careerpilot.integrations;

import com.careerpilot.common.ValidationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reads .eml files the student uploads. Nothing is fetched from a server: files are handed in (Tier 1, no OAuth). */
@Component
public class EmlFileAdapter implements EmailProviderAdapter {
    public record Upload(String filename, byte[] data) {}
    public record Rejected(String filename, String reason) {}
    public record Loaded(List<RawEmail> good, List<Rejected> bad) {}

    private final Map<UUID, List<RawEmail>> pending = new ConcurrentHashMap<>();

    @Override public String providerName() { return "EML"; }

    public Loaded load(EmailAccount account, List<Upload> files) {
        List<RawEmail> good = new ArrayList<>();
        List<Rejected> bad = new ArrayList<>();
        for (Upload f : files) {
            try {
                good.add(MimeSupport.rawFromBytes(f.data(), null));
            } catch (ValidationException e) {
                bad.add(new Rejected(f.filename(), e.getMessage()));
            }
        }
        pending.put(account.getId(), good);
        return new Loaded(good, bad);
    }

    @Override
    public List<RawEmail> fetchNewMessages(EmailAccount account, Instant since) {
        List<RawEmail> out = pending.remove(account.getId());
        return out == null ? List.of() : out;
    }
}
