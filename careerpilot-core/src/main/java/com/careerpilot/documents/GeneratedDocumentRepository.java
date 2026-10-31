package com.careerpilot.documents;

import com.careerpilot.applications.Application;
import com.careerpilot.common.TenantRepository;
import com.careerpilot.users.User;

import java.util.List;
import java.util.Optional;

public interface GeneratedDocumentRepository extends TenantRepository<GeneratedDocument> {
    List<GeneratedDocument> findByApplicationOrderByCreatedAtDesc(Application application);

    Optional<GeneratedDocument> findFirstByApplicationAndDocTypeOrderByCreatedAtDesc(Application application, String docType);

    List<GeneratedDocument> findByUserAndStale(User user, boolean stale);
}
