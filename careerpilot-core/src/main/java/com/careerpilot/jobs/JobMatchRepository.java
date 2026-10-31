package com.careerpilot.jobs;

import com.careerpilot.users.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobMatchRepository extends JpaRepository<JobMatch, UUID> {
    Optional<JobMatch> findByJob(JobPosting job);

    List<JobMatch> findByUser(User user);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update JobMatch m set m.outdated = true where m.user.id = ?1")
    void markOutdated(UUID userId);
}
