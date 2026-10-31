package com.careerpilot.applications;

import com.careerpilot.common.TenantRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewRepository extends TenantRepository<Interview> {
    List<Interview> findByApplicationOrderByRoundAsc(Application application);

    Optional<Interview> findBySourceEventId(UUID sourceEventId);
}
