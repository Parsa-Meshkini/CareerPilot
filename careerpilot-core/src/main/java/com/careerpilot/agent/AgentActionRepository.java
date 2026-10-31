package com.careerpilot.agent;

import com.careerpilot.common.TenantRepository;

import java.util.List;

public interface AgentActionRepository extends TenantRepository<AgentActionRecord> {
    List<AgentActionRecord> findByRunOrderByExecutedAtAsc(AgentRun run);
}
