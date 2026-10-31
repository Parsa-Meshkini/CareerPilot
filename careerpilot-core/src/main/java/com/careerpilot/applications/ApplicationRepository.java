package com.careerpilot.applications;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.users.User;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends TenantRepository<Application> {
    Optional<Application> findByUserAndJob(User user, JobPosting job);

    List<Application> findByUserAndStateName(User user, String stateName);
}
