from careerpilot.tasks import task


@task("jobs.process_batch")
def process_batch(batch_id):
    from .import_service import JobImportService

    return JobImportService().process_batch(batch_id)
