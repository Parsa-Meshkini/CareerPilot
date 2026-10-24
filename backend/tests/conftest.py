import pytest
from rest_framework.test import APIClient


@pytest.fixture
def user(db, django_user_model):
    return django_user_model.objects.create_user(username="ana@example.com", email="ana@example.com", password="pass12345")


@pytest.fixture
def other_user(db, django_user_model):
    return django_user_model.objects.create_user(username="bo@example.com", email="bo@example.com", password="pass12345")


@pytest.fixture
def api(user):
    client = APIClient()
    client.force_authenticate(user=user)
    return client
