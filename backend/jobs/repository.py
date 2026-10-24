from django.db.models import Q

from careerpilot.repository import TenantScopedRepository

from .models import ImportBatch, JobPosting


class JobRepository(TenantScopedRepository):
    model = JobPosting

    def find_by_external_id(self, user, source_type, external_id):
        return self.queryset(user).filter(source_type=source_type, external_id=external_id).first()

    def find_by_fingerprint(self, user, fingerprint):
        return self.queryset(user).filter(fingerprint=fingerprint).first()

    def list_jobs(self, user, q=None, deadline_before=None, shortlisted=None, include_dismissed=False):
        qs = self.queryset(user)
        if not include_dismissed:
            qs = qs.filter(dismissed=False)
        if q:
            qs = qs.filter(Q(title__icontains=q) | Q(company__icontains=q) | Q(location__icontains=q))
        if deadline_before:
            qs = qs.filter(deadline__lte=deadline_before)
        if shortlisted is not None:
            qs = qs.filter(shortlisted=shortlisted)
        return qs


class ImportBatchRepository(TenantScopedRepository):
    model = ImportBatch
