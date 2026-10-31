package com.careerpilot.integrations;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.List;

public interface DetectedEventRepository extends TenantRepository<DetectedEmailEvent> {
    List<DetectedEmailEvent> findByUserAndReviewStatusOrderByCreatedAtDesc(User user, String reviewStatus);

    List<DetectedEmailEvent> findByUserOrderByCreatedAtDesc(User user);

    void deleteByEmail_Account(EmailAccount account);
}
