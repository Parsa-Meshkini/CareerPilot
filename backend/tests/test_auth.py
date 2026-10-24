from rest_framework.test import APIClient


def test_register_login_and_me(db):
    client = APIClient()
    r = client.post("/api/auth/register/", {"email": "New@Example.com", "password": "longpassword"}, format="json")
    assert r.status_code == 201
    token = r.data["token"]

    r = client.post("/api/auth/login/", {"email": "new@example.com", "password": "longpassword"}, format="json")
    assert r.status_code == 200 and r.data["token"] == token

    client.credentials(HTTP_AUTHORIZATION=f"Token {token}")
    assert client.get("/api/auth/me/").data["email"] == "new@example.com"


def test_wrong_password_and_anonymous_access(db):
    client = APIClient()
    client.post("/api/auth/register/", {"email": "a@b.com", "password": "longpassword"}, format="json")
    assert client.post("/api/auth/login/", {"email": "a@b.com", "password": "nope"}, format="json").status_code == 401
    assert client.get("/api/auth/me/").status_code == 401


def test_task_queue_runs_registered_task_and_isolates_failures():
    from careerpilot.tasks import TaskQueue, task

    seen = []

    @task("test.ok")
    def ok(value):
        seen.append(value)

    @task("test.boom")
    def boom():
        raise RuntimeError("bad")

    q = TaskQueue(mode="sync")
    q.enqueue("test.ok", {"value": 3})
    handle = q.enqueue("test.boom", {})
    assert seen == [3]
    assert handle.error == "bad"
