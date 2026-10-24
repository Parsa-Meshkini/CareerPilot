from django.urls import path

from . import views

urlpatterns = [
    path("", views.profile),
    path("facts/", views.facts),
    path("facts/verify/", views.verify_facts),
    path("facts/reject/", views.reject_facts),
    path("facts/<uuid:fact_id>/", views.fact_detail),
    path("goal/", views.goal),
    path("resume/", views.upload_resume),
    path("resume/<uuid:resume_id>/", views.resume_detail),
    path("resume/<uuid:resume_id>/retry/", views.resume_retry),
]
