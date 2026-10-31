package com.careerpilot.applications;

import com.careerpilot.common.TenantRepository;

import java.util.List;
import java.util.Optional;

public interface StatusChangeRepository extends TenantRepository<StatusChange> {
    List<StatusChange> findByApplicationOrderByCreatedAtAsc(Application application);

    Optional<StatusChange> findFirstByApplicationOrderByCreatedAtDesc(Application application);
}
