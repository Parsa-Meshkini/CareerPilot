from careerpilot.repository import TenantScopedRepository

from .models import DetectedEmailEvent, EmailAccount, EmailMessage


class EmailAccountRepository(TenantScopedRepository):
    model = EmailAccount

    def get_or_create(self, user, provider, address=""):
        return self.model.objects.get_or_create(user=user, provider=provider, address=address)[0]


class EmailMessageRepository(TenantScopedRepository):
    model = EmailMessage

    def exists(self, account, provider_message_id):
        return self.model.objects.filter(account=account, provider_message_id=provider_message_id).exists()


class DetectedEventRepository(TenantScopedRepository):
    model = DetectedEmailEvent

    def list_events(self, user, status=None):
        qs = self.queryset(user).select_related("email", "application__job")
        return qs.filter(review_status=status) if status else qs
