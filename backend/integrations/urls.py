from django.urls import path

from . import views

urlpatterns = [
    path("accounts/", views.list_accounts),
    path("accounts/mock/", views.connect_mock),
    path("accounts/<uuid:account_id>/", views.disconnect),
    path("accounts/<uuid:account_id>/sync/", views.sync),
    path("upload/", views.upload_eml),
    path("events/", views.list_events),
    path("events/<uuid:event_id>/confirm/", views.confirm_event),
    path("events/<uuid:event_id>/dismiss/", views.dismiss_event),
]
