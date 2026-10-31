package com.careerpilot.documents;

import com.careerpilot.applications.Application;
import com.careerpilot.common.TenantRepository;

import java.util.List;

public interface ApplicationPackageRepository extends TenantRepository<ApplicationPackage> {
    List<ApplicationPackage> findByApplicationOrderByCreatedAtDesc(Application application);
}
