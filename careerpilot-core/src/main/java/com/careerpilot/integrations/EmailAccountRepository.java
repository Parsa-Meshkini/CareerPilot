package com.careerpilot.integrations;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.Optional;

public interface EmailAccountRepository extends TenantRepository<EmailAccount> {
    Optional<EmailAccount> findByUserAndProviderAndAddress(User user, String provider, String address);
}
