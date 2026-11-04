package com.careerpilot.notifications;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.List;

public interface NotificationRepository extends TenantRepository<Notification> {
    List<Notification> findByUserOrderByCreatedAtDesc(User user);

    boolean existsByUserAndDedupeKey(User user, String dedupeKey);

    long countByUserAndReadFalse(User user);
}
