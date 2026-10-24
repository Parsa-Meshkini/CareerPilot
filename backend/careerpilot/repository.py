"""Tenant-scoped repository base (Repository architectural pattern).

Every user-owned model is read and written through a repository that always
filters by ``user``, so one student can never see another student's data:
asking for someone else's object id behaves exactly like a missing id (404).
"""
from careerpilot.errors import NotFoundError


class TenantScopedRepository:
    model = None

    def queryset(self, user):
        return self.model.objects.filter(user=user)

    def find_by_id(self, user, obj_id):
        obj = self.queryset(user).filter(pk=obj_id).first()
        if obj is None:
            raise NotFoundError(f"{self.model.__name__} {obj_id} not found")
        return obj

    def list_by_user(self, user, **filters):
        return self.queryset(user).filter(**filters)

    def save(self, obj):
        obj.save()
        return obj

    def delete(self, user, obj_id):
        self.find_by_id(user, obj_id).delete()
