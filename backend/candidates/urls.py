from django.urls import path

from . import views

urlpatterns = [
    path("", views.profile),
    path("facts/", views.facts),
    path("facts/verify/", views.verify_facts),
    path("facts/reject/", views.reject_facts),
    path("facts/<uuid:fact_id>/", views.fact_detail),
    path("goal/", views.goal),
]
