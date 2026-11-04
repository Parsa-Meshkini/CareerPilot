package com.careerpilot.interviews;

import com.careerpilot.applications.Application;
import com.careerpilot.common.TenantRepository;

import java.util.Optional;

public interface InterviewPrepPlanRepository extends TenantRepository<InterviewPrepPlan> {
    Optional<InterviewPrepPlan> findFirstByApplicationOrderByVersionDesc(Application application);
}
