from careerpilot.tasks import task


@task("candidates.parse_resume")
def parse_resume(resume_id):
    from .services import CandidateService

    return CandidateService().parse_resume(resume_id)
