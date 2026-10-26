from django.urls import path

from . import views

urlpatterns = [
    path("", views.list_jobs),
    path("batches/", views.create_batch),
    path("batches/list/", views.list_batches),
    path("batches/<uuid:batch_id>/", views.batch_detail),
    path("batches/<uuid:batch_id>/triage/", views.triage_batch),
    path("import/manual/", views.import_manual),
    path("import/file/", views.import_file),
    path("score/", views.score_all),
    path("compare/", views.compare_jobs),
    path("<uuid:job_id>/", views.job_detail),
    path("<uuid:job_id>/analyze/", views.analyze_job),
    path("<uuid:job_id>/match/", views.match_job),
]
