from django.urls import path

from . import views

urlpatterns = [
    path("runs/", views.runs),
    path("runs/<uuid:run_id>/", views.run_detail),
    path("runs/<uuid:run_id>/approve/", views.approve),
    path("runs/<uuid:run_id>/answer/", views.answer),
    path("actions/<uuid:action_id>/undo/", views.undo_action),
    path("tools/", views.tools),
    path("memory/", views.memory),
]
