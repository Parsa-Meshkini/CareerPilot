package com.careerpilot.llm;

import com.careerpilot.users.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface LlmUsageRepository extends JpaRepository<LlmUsageRecord, UUID> {
    @Query("select coalesce(sum(r.inputTokens + r.outputTokens), 0) from LlmUsageRecord r where r.user = ?1 and r.createdAt >= ?2")
    long tokensSince(User user, Instant since);
}
