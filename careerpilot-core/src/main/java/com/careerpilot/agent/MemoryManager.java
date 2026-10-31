package com.careerpilot.agent;

import com.careerpilot.users.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Long-term agent memory (episodic, preference, strategy, feedback) with keyword x recency x importance recall. */
@Component
@Transactional
public class MemoryManager {
    static final double HALF_LIFE_DAYS = 30;
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "to", "for", "of", "in", "on", "my", "me", "at", "with", "is", "it");

    private final AgentMemoryRepository memories;

    public MemoryManager(AgentMemoryRepository memories) { this.memories = memories; }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text == null ? "" : text.toLowerCase());
        while (m.find()) if (!STOP.contains(m.group()) && m.group().length() > 1) out.add(m.group());
        return out;
    }

    public AgentMemoryEntry remember(User user, AgentMemoryEntry.Kind kind, String content, UUID relatedObjectId, double importance) {
        AgentMemoryEntry e = new AgentMemoryEntry();
        e.setUser(user);
        e.setKind(kind);
        e.setContent(content.length() > 2000 ? content.substring(0, 2000) : content);
        e.setRelatedObjectId(relatedObjectId);
        e.setImportance(importance);
        return memories.save(e);
    }

    public AgentMemoryEntry remember(User user, AgentMemoryEntry.Kind kind, String content) { return remember(user, kind, content, null, 0.5); }

    /** Ranks entries by keyword overlap x recency x importance (no vector search in Stage 2). */
    @Transactional(readOnly = true)
    public List<AgentMemoryEntry> recall(User user, String query, List<AgentMemoryEntry.Kind> kinds, int limit) {
        Set<String> q = tokens(query);
        Instant now = Instant.now();
        record Scored(double score, AgentMemoryEntry entry) {}
        List<Scored> scored = new ArrayList<>();
        for (AgentMemoryEntry e : memories.findByUserOrderByCreatedAtDesc(user).stream().limit(500).toList()) {
            if (kinds != null && !kinds.isEmpty() && !kinds.contains(e.getKind())) continue;
            Set<String> overlap = new HashSet<>(q);
            overlap.retainAll(tokens(e.getContent()));
            double ov = q.isEmpty() ? 0.0 : (double) overlap.size() / q.size();
            double ageDays = Duration.between(e.getCreatedAt(), now).toSeconds() / 86400.0;
            double recency = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
            // preferences and strategies always matter a little even without keyword overlap
            double base = e.getKind() == AgentMemoryEntry.Kind.PREFERENCE || e.getKind() == AgentMemoryEntry.Kind.STRATEGY ? 0.3 : 0.05;
            scored.add(new Scored((base + ov) * (0.5 + recency) * (0.5 + e.getImportance()), e));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(limit).filter(s -> s.score() > 0.05 || q.isEmpty()).map(Scored::entry).toList();
    }

    public List<AgentMemoryEntry> recall(User user, String query, int limit) { return recall(user, query, null, limit); }

    @Transactional(readOnly = true)
    public List<AgentMemoryEntry> all(User user) { return memories.findByUserOrderByCreatedAtDesc(user); }

    public void forget(User user, UUID id) { memories.delete(memories.owned(user, id)); }
}
