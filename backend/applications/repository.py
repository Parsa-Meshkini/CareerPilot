from careerpilot.errors import NotFoundError
from careerpilot.repository import TenantScopedRepository

from .models import Application, StatusChange

OPEN_STATES = ("SAVED", "PREPARED", "APPLIED", "INTERVIEW", "OFFER")


class ApplicationRepository(TenantScopedRepository):
    model = Application

    def queryset(self, user):
        return super().queryset(user).select_related("job")

    def find_by_job(self, user, job_id):
        return self.queryset(user).filter(job_id=job_id).first()

    def find_open_by_user(self, user):
        return self.queryset(user).filter(state_name__in=OPEN_STATES)

    def save_change(self, change):
        change.save()
        return change

    def find_change(self, user, change_id):
        change = StatusChange.objects.filter(user=user, pk=change_id).select_related("application").first()
        if change is None:
            raise NotFoundError("status change not found")
        return change

    def latest_change(self, application):
        return application.changes.filter(reverted=False).order_by("-created_at").first()
