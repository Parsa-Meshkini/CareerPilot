package com.careerpilot.common;

import com.careerpilot.users.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository base (Repository architectural pattern).
 * Every user-owned entity is read through a method that filters by user, so asking for another
 * student's id behaves exactly like a missing id.
 */
@NoRepositoryBean
public interface TenantRepository<T> extends JpaRepository<T, UUID> {
    Optional<T> findByIdAndUser(UUID id, User user);

    List<T> findByUser(User user);

    default T owned(User user, UUID id) {
        return findByIdAndUser(id, user)
                .orElseThrow(() -> new NotFoundException("object " + id + " not found"));
    }
}
