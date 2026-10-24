"""Domain errors and their mapping to HTTP responses."""
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import exception_handler


class DomainError(Exception):
    """Base class for business-rule violations raised by services."""

    http_status = status.HTTP_400_BAD_REQUEST
    code = "domain_error"

    def __init__(self, message, **details):
        super().__init__(message)
        self.message = message
        self.details = details

    def to_dict(self):
        return {"error": self.code, "message": self.message, **self.details}


class NotFoundError(DomainError):
    http_status = status.HTTP_404_NOT_FOUND
    code = "not_found"


class ValidationFailed(DomainError):
    code = "validation_failed"


class ConflictError(DomainError):
    http_status = status.HTTP_409_CONFLICT
    code = "conflict"


def api_exception_handler(exc, context):
    if isinstance(exc, DomainError):
        return Response(exc.to_dict(), status=exc.http_status)
    return exception_handler(exc, context)
