package com.careerpilot.interviews;

import com.careerpilot.applications.Application;
import com.careerpilot.common.TenantRepository;

import java.util.List;

public interface MockInterviewSessionRepository extends TenantRepository<MockInterviewSession> {
    List<MockInterviewSession> findByApplicationOrderByCreatedAtDesc(Application application);
}
