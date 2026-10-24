from careerpilot.repository import TenantScopedRepository

from .models import CandidateProfile, CareerGoal, ProfileFact


class CandidateRepository(TenantScopedRepository):
    model = ProfileFact

    def find_profile_by_user(self, user):
        profile, _ = CandidateProfile.objects.get_or_create(user=user, defaults={"email": user.email})
        return profile

    def save_profile(self, profile):
        profile.save()
        return profile

    def save_facts(self, facts):
        for f in facts:
            f.save()
        return facts

    def find_facts(self, user, ids):
        return list(self.queryset(user).filter(pk__in=ids))

    def find_goal(self, user):
        return CareerGoal.objects.filter(user=user).first()

    def save_goal(self, goal):
        goal.save()
        return goal
