"""Django settings for CareerPilot.

All secrets and environment-specific values come from the repository-root
``.env`` file (copied from ``.env.example``). Nothing secret is hard-coded here.
"""
import os
from pathlib import Path

import dj_database_url
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = BASE_DIR.parent
load_dotenv(REPO_ROOT / ".env")


def env(name, default=None):
    return os.environ.get(name, default)


def env_bool(name, default=False):
    return str(env(name, default)).lower() in ("1", "true", "yes", "on")


def env_int(name, default):
    try:
        return int(env(name, default))
    except (TypeError, ValueError):
        return default


SECRET_KEY = env("DJANGO_SECRET_KEY", "dev-only-insecure-key")
DEBUG = env_bool("DJANGO_DEBUG", True)
ALLOWED_HOSTS = [h.strip() for h in env("DJANGO_ALLOWED_HOSTS", "localhost,127.0.0.1").split(",") if h.strip()]

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "rest_framework.authtoken",
    "corsheaders",
    "careerpilot",
    "users",
    "agent",
    "candidates",
    "notifications",
    "jobs",
    "applications",
    "documents",
    "integrations",
]

MIDDLEWARE = [
    "corsheaders.middleware.CorsMiddleware",
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
]

ROOT_URLCONF = "careerpilot.urls"
WSGI_APPLICATION = "careerpilot.wsgi.application"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ]
        },
    }
]

# SQLite by default so the project runs with no external services;
# set DATABASE_URL to a postgres:// URL to use PostgreSQL instead.
DATABASES = {
    "default": dj_database_url.parse(env("DATABASE_URL") or f"sqlite:///{BASE_DIR / 'db.sqlite3'}")
}

AUTH_USER_MODEL = "users.User"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
LANGUAGE_CODE = "en-us"
TIME_ZONE = "America/Toronto"
USE_TZ = True
STATIC_URL = "static/"

# Private object storage (local filesystem implementation of StorageService).
STORAGE_ROOT = Path(env("STORAGE_ROOT") or (BASE_DIR / "media"))

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "rest_framework.authentication.TokenAuthentication",
        "rest_framework.authentication.SessionAuthentication",
    ],
    "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.IsAuthenticated"],
    "EXCEPTION_HANDLER": "careerpilot.errors.api_exception_handler",
}

CORS_ALLOWED_ORIGINS = [o.strip() for o in env("CORS_ALLOWED_ORIGINS", "http://localhost:5173").split(",") if o.strip()]
CORS_ALLOWED_ORIGIN_REGEXES = [r"^chrome-extension://.*$"]

# ---- LLM subsystem (see agent/llm) ----
LLM_PROVIDER = env("LLM_PROVIDER", "gemini")
LLM_API_KEY = env("LLM_API_KEY", "")
LLM_MODEL = env("LLM_MODEL", "")
LLM_BASE_URL = env("LLM_BASE_URL", "")
LLM_USER_DAILY_TOKEN_BUDGET = env_int("LLM_USER_DAILY_TOKEN_BUDGET", 200000)
LLM_RUN_TOKEN_BUDGET = env_int("LLM_RUN_TOKEN_BUDGET", 40000)
LLM_TIMEOUT_SECONDS = env_int("LLM_TIMEOUT_SECONDS", 60)

# ---- Email / events ----
EMAIL_AUTO_APPLY_THRESHOLD = float(env("EMAIL_AUTO_APPLY_THRESHOLD", "0.85"))

FEATURE_AUTO_TRIAGE = env_bool("FEATURE_AUTO_TRIAGE", True)
FEATURE_LINKEDIN_EXTRACTION = env_bool("FEATURE_LINKEDIN_EXTRACTION", False)
