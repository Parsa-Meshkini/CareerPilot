"""F09: automatic batch triage after an import (TriageTrigger is an Observer of JobsImportedEvent)."""
import logging

from django.conf import settings

from careerpilot.tasks import task, task_queue
from notifications.events import EventListener, JobsImportedEvent

log = logging.getLogger(__name__)


class TriageTrigger(EventListener):
    MIN_JOBS = 2

    def handle(self, event):
        if not isinstance(event, JobsImportedEvent) or len(event.job_ids) < self.MIN_JOBS:
            return
        if not settings.FEATURE_AUTO_TRIAGE:
            return
        from candidates.models import CandidateProfile, CareerGoal

        profile = CandidateProfile.objects.filter(user_id=event.user_id).first()
        if (profile is not None and not profile.auto_triage) or not CareerGoal.objects.filter(user_id=event.user_id).exists():
            return  # disabled by the student, or nothing to score against yet
        task_queue.enqueue("agent.triage_batch", {"user_id": event.user_id, "job_ids": [str(j) for j in event.job_ids],
                                                  "batch_id": str(event.batch_id) if event.batch_id else None})


def start_triage(user, job_ids, batch_id=None):
    from .controller import AgentController

    label = f"batch {batch_id}" if batch_id else f"{len(job_ids)} jobs"
    return AgentController().run_goal(user, f"Triage {len(job_ids)} newly imported postings ({label})",
                                      goal_type="TRIAGE_BATCH",
                                      context={"batch_job_ids": [str(j) for j in job_ids], "batch_id": batch_id})


@task("agent.triage_batch")
def triage_batch(user_id, job_ids, batch_id=None):
    from django.contrib.auth import get_user_model

    return start_triage(get_user_model().objects.get(pk=user_id), job_ids, batch_id)
