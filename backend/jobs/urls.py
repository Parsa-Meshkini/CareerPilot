from django.urls import path

from . import views

urlpatterns = [
    path("", views.list_jobs),
    path("batches/", views.create_batch),
    path("batches/list/", views.list_batches),
    path("batches/<uuid:batch_id>/", views.batch_detail),
    path("import/manual/", views.import_manual),
    path("import/file/", views.import_file),
    path("<uuid:job_id>/", views.job_detail),
]
