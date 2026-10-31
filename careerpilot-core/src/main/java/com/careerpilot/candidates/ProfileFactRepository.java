package com.careerpilot.candidates;

import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProfileFactRepository extends TenantRepository<ProfileFact> {
    List<ProfileFact> findByUserAndVerified(User user, boolean verified);

    List<ProfileFact> findByUserAndIdIn(User user, Collection<UUID> ids);

    List<ProfileFact> findByUserAndFactTypeAndVerified(User user, FactType type, boolean verified);
}
