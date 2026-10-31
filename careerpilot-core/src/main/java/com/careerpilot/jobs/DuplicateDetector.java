package com.careerpilot.jobs;

import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Finds an already imported posting by external id first, then by company/title/location fingerprint. */
@Component
public class DuplicateDetector {
    private final JobPostingRepository jobs;

    public DuplicateDetector(JobPostingRepository jobs) { this.jobs = jobs; }

    public Optional<JobPosting> findDuplicate(User user, JobPosting job) {
        if (job.getExternalId() != null) {
            Optional<JobPosting> match = jobs.findFirstByUserAndSourceTypeAndExternalId(user, job.getSourceType(), job.getExternalId());
            if (match.isPresent()) return match;
        }
        return jobs.findFirstByUserAndFingerprint(user, job.getFingerprint());
    }
}
