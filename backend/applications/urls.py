from django.urls import path

from . import views

urlpatterns = [
    path("", views.applications),
    path("<uuid:app_id>/", views.application_detail),
    path("<uuid:app_id>/transition/", views.transition),
    path("changes/<uuid:change_id>/undo/", views.undo),
]
