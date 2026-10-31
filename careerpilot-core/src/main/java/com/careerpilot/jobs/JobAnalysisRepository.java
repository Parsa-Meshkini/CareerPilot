package com.careerpilot.jobs;

import com.careerpilot.users.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobAnalysisRepository extends JpaRepository<JobAnalysis, UUID> {
    Optional<JobAnalysis> findByJob(JobPosting job);

    List<JobAnalysis> findByUser(User user);
}
