package com.careerpilot.analytics;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.List;

public interface StrategyAdjustmentRepository extends TenantRepository<StrategyAdjustment> {
    List<StrategyAdjustment> findByUserOrderByCreatedAtDesc(User user);

    List<StrategyAdjustment> findByUserAndStatus(User user, StrategyAdjustment.Status status);
}
