"""Job source adapters (P1 Adapter) and the JobSourceRegistry (simple factory).

Each adapter converts one source format into a list of RawJobRecord, the
single shape JobImportService understands.
"""
import csv
import io
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

from django.conf import settings

from agent.llm.client import get_llm_client
from agent.llm.types import LLMError
from careerpilot.errors import ValidationFailed


@dataclass
class RawJobRecord:
    source_type: str
    title: str = ""
    company: str = ""
    location: str = ""
    description: str = ""
    external_id: str | None = None
    source_url: str = ""
    application_url: str = ""
    deadline_text: str = ""
    work_term: str = ""
    job_type: str = ""
    work_model_text: str = ""
    salary_text: str = ""
    contact: str = ""
    required_documents: list = field(default_factory=list)
    is_full_detail: bool = False
    warnings: list = field(default_factory=list)


class JobSourceAdapter(ABC):
    @abstractmethod
    def source_type(self) -> str: ...

    @abstractmethod
    def parse(self, payload, user=None) -> list: ...

    def validate(self, raw):
        """Returns a list of problems; an empty list means the record is importable."""
        problems = []
        if not raw.title.strip():
            problems.append("missing title")
        if not raw.company.strip():
            problems.append("missing company")
        return problems


class YorkBrowserImportAdapter(JobSourceAdapter):
    """Adapts the Experience York page extract sent by the browser extension.

    The extension sends the label/value pairs it read from the page, e.g.
    {"Job Title": ..., "Organization": ..., "Application Deadline": ...}.
    """

    LABELS = {
        "title": ("job title", "position title", "title"),
        "company": ("organization", "organization name", "employer", "company"),
        "location": ("job location", "location", "city"),
        "deadline_text": ("application deadline", "deadline", "closing date"),
        "work_term": ("work term", "term"),
        "job_type": ("job type", "position type"),
        "salary_text": ("salary", "compensation", "wage"),
        "external_id": ("job id", "posting id", "id"),
        "work_model_text": ("work arrangement", "work model", "remote/hybrid"),
        "application_url": ("application url", "apply url"),
        "contact": ("contact", "contact name"),
        "documents": ("application documents required", "required documents", "documents required"),
    }

    def source_type(self):
        return "YORK"

    def parse(self, payload, user=None):
        records = []
        for item in payload.get("records", []):
            fields = {str(k).strip().lower().rstrip(":"): str(v).strip() for k, v in (item.get("fields") or {}).items()}
            raw = RawJobRecord(source_type="YORK", source_url=item.get("url", ""))
            for attr, labels in self.LABELS.items():
                value = next((fields[label] for label in labels if fields.get(label)), "")
                if attr == "documents":
                    raw.required_documents = [d.strip() for d in value.replace(";", ",").split(",") if d.strip()]
                else:
                    setattr(raw, attr, value)
            raw.external_id = raw.external_id or None
            raw.description = (item.get("detail_text") or fields.get("job description") or "").strip()
            raw.is_full_detail = bool(item.get("detail_text"))
            records.append(raw)
        return records


class LinkedInBrowserImportAdapter(JobSourceAdapter):
    """Disabled by design: LinkedIn's terms forbid automated extraction. Use paste import."""

    def source_type(self):
        return "LINKEDIN"

    def parse(self, payload, user=None):
        if not settings.FEATURE_LINKEDIN_EXTRACTION:
            raise ValidationFailed("LinkedIn page extraction is disabled; paste the posting text instead")
        return YorkBrowserImportAdapter().parse(payload, user)


class ManualJobImportAdapter(JobSourceAdapter):
    """Pasted free text -> structured fields using the LLM (job.structure_paste)."""

    MAX_CHARS = 20000

    def __init__(self, llm=None):
        self.llm = llm

    def source_type(self):
        return "MANUAL"

    def parse(self, payload, user=None):
        text = (payload.get("text") or "").strip()
        if not text:
            raise ValidationFailed("paste the job posting text", fields={"text": "required"})
        if len(text) > self.MAX_CHARS:
            raise ValidationFailed("posting text is limited to 20,000 characters", fields={"text": "too long"})
        raw = RawJobRecord(source_type=payload.get("source_type", "MANUAL"), description=text,
                           source_url=payload.get("url", ""), is_full_detail=True,
                           title=payload.get("title", ""), company=payload.get("company", ""))
        try:
            data = (self.llm or get_llm_client()).generate_structured(user, "job.structure_paste", {"text": text}).data
        except LLMError as exc:
            raw.warnings.append(f"AI structuring unavailable ({exc}); fill the fields manually")
            return [raw]
        for attr in ("title", "company", "location", "deadline_text", "work_term", "job_type", "salary_text",
                     "application_url", "contact"):
            if not getattr(raw, attr) and data.get(attr):
                setattr(raw, attr, str(data[attr]).strip())
        raw.work_model_text = data.get("work_model", "")
        raw.required_documents = data.get("required_documents") or []
        return [raw]


class MockJobSourceAdapter(JobSourceAdapter):
    """JSON list or CSV file of postings (CLI bulk import, fixtures, sample data)."""

    def source_type(self):
        return "MOCK"

    def parse(self, payload, user=None):
        rows = payload.get("records")
        if rows is None and payload.get("csv"):
            rows = list(csv.DictReader(io.StringIO(payload["csv"])))
        if not isinstance(rows, list):
            raise ValidationFailed("file must contain a list of postings")
        records = []
        for row in rows:
            row = {str(k).strip().lower(): v for k, v in row.items()}
            docs = row.get("required_documents") or []
            if isinstance(docs, str):
                docs = [d.strip() for d in docs.split(",") if d.strip()]
            records.append(RawJobRecord(
                source_type=payload.get("source_type", "MANUAL"),
                title=str(row.get("title") or ""), company=str(row.get("company") or ""),
                location=str(row.get("location") or ""), description=str(row.get("description") or ""),
                external_id=str(row["external_id"]) if row.get("external_id") else None,
                source_url=str(row.get("url") or row.get("source_url") or ""),
                application_url=str(row.get("application_url") or ""),
                deadline_text=str(row.get("deadline") or ""), work_term=str(row.get("work_term") or ""),
                job_type=str(row.get("job_type") or ""), work_model_text=str(row.get("work_model") or ""),
                salary_text=str(row.get("salary") or ""), required_documents=docs,
                is_full_detail=len(str(row.get("description") or "")) >= 300,
            ))
        return records


class JobSourceRegistry:
    def __init__(self):
        self._adapters = {"york": YorkBrowserImportAdapter, "linkedin": LinkedInBrowserImportAdapter,
                          "manual": ManualJobImportAdapter, "file": MockJobSourceAdapter,
                          "mock": MockJobSourceAdapter}

    def get_adapter(self, source):
        cls = self._adapters.get((source or "").lower())
        if cls is None:
            raise ValidationFailed(f"unsupported job source '{source}'")
        return cls()
