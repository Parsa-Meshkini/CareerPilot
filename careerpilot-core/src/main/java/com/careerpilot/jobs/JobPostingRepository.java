package com.careerpilot.jobs;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JobPostingRepository extends TenantRepository<JobPosting> {
    Optional<JobPosting> findFirstByUserAndSourceTypeAndExternalId(User user, SourceType sourceType, String externalId);

    Optional<JobPosting> findFirstByUserAndFingerprint(User user, String fingerprint);

    List<JobPosting> findByUserAndDeadlineBetween(User user, LocalDate from, LocalDate to);
}
