package com.careerpilot.agent;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.List;

public interface AgentRunRepository extends TenantRepository<AgentRun> {
    List<AgentRun> findByUserOrderByCreatedAtDesc(User user);
}
