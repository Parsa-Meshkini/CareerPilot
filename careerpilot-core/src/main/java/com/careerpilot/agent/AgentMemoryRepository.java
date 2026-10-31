package com.careerpilot.agent;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.List;

public interface AgentMemoryRepository extends TenantRepository<AgentMemoryEntry> {
    List<AgentMemoryEntry> findTop10ByUserAndKindOrderByCreatedAtDesc(User user, AgentMemoryEntry.Kind kind);

    List<AgentMemoryEntry> findByUserAndKindOrderByCreatedAtDesc(User user, AgentMemoryEntry.Kind kind);

    List<AgentMemoryEntry> findByUserOrderByCreatedAtDesc(User user);
}
