import pytest
from rest_framework.test import APIClient


@pytest.fixture
def user(db, django_user_model):
    return django_user_model.objects.create_user(username="ana@example.com", email="ana@example.com", password="pass12345")


@pytest.fixture
def other_user(db, django_user_model):
    return django_user_model.objects.create_user(username="bo@example.com", email="bo@example.com", password="pass12345")


@pytest.fixture(autouse=True)
def tmp_storage(settings, tmp_path):
    """Keeps generated files out of backend/media during tests."""
    from careerpilot import storage as st

    settings.STORAGE_ROOT = tmp_path
    old, st.storage.root = st.storage.root, tmp_path
    yield tmp_path
    st.storage.root = old


@pytest.fixture
def api(user):
    client = APIClient()
    client.force_authenticate(user=user)
    return client


from tests.llm_helpers import mock_llm  # noqa: E402,F401
