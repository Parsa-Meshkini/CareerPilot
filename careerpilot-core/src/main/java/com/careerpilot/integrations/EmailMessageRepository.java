package com.careerpilot.integrations;

import com.careerpilot.common.TenantRepository;

public interface EmailMessageRepository extends TenantRepository<EmailMessage> {
    boolean existsByAccountAndProviderMessageId(EmailAccount account, String providerMessageId);

    void deleteByAccount(EmailAccount account);
}
