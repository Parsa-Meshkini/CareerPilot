from django.urls import path

from . import views

urlpatterns = [
    path("packages/applications/<uuid:app_id>/", views.packages),
    path("packages/<uuid:package_id>/download/", views.download_package),
    path("applications/<uuid:app_id>/", views.list_documents),
    path("applications/<uuid:app_id>/<str:kind>/", views.generate),
    path("<uuid:doc_id>/", views.document_detail),
    path("<uuid:doc_id>/download/<str:fmt>/", views.download),
]
