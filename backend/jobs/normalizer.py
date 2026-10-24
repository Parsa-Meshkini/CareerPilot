"""JobNormalizer and DuplicateDetector (F06)."""
import re
from datetime import date, datetime

from .models import JobPosting

DATE_FORMATS = ("%Y-%m-%d", "%Y/%m/%d", "%B %d, %Y", "%b %d, %Y", "%d %B %Y", "%d %b %Y", "%m/%d/%Y",
                "%d/%m/%Y", "%B %d %Y", "%b %d %Y", "%Y-%m-%dT%H:%M:%S")


def parse_deadline(text):
    if not text:
        return None
    cleaned = re.sub(r"(\d)(st|nd|rd|th)\b", r"\1", text.strip())
    cleaned = re.sub(r"\s+(at\s+)?\d{1,2}:\d{2}.*$", "", cleaned, flags=re.I)  # drop time of day
    cleaned = re.sub(r"^[A-Za-z]+day,?\s+", "", cleaned)                      # drop weekday
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(cleaned, fmt).date()
        except ValueError:
            continue
    return None


def detect_work_model(*texts):
    blob = " ".join(t for t in texts if t).lower()
    if "hybrid" in blob:
        return "HYBRID"
    if re.search(r"\bremote\b|work from home|wfh", blob):
        return "REMOTE"
    if re.search(r"on-?site|in[- ]person|in office", blob):
        return "ONSITE"
    return "UNKNOWN"


def clean_text(text):
    text = (text or "").replace("\r\n", "\n").replace(" ", " ")
    text = re.sub(r"[ \t]+", " ", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


class JobNormalizer:
    FULL_MIN_CHARS = 300

    def normalize(self, raw, user):
        job = JobPosting(
            user=user, source_type=raw.source_type, external_id=(raw.external_id or None),
            source_url=raw.source_url[:500], title=clean_text(raw.title)[:200],
            company=clean_text(raw.company)[:200], location=clean_text(raw.location)[:200],
            job_type=clean_text(raw.job_type)[:60], salary_text=clean_text(raw.salary_text)[:120],
            work_term=clean_text(raw.work_term)[:60], description=clean_text(raw.description),
            application_url=raw.application_url[:500], contact=clean_text(raw.contact)[:200],
            required_documents=raw.required_documents, deadline_text=clean_text(raw.deadline_text)[:80],
        )
        job.deadline = parse_deadline(job.deadline_text)
        warnings = list(raw.warnings)
        if job.deadline_text and job.deadline is None:
            warnings.append(f"could not parse deadline '{job.deadline_text}'")
        job.work_model = detect_work_model(raw.work_model_text, job.location, job.description[:2000])
        full = raw.is_full_detail and len(job.description) >= self.FULL_MIN_CHARS
        job.completeness = JobPosting.FULL if full else JobPosting.SUMMARY
        job.compute_fingerprint()
        return job, warnings


class DuplicateDetector:
    def find_duplicate(self, user, job):
        if job.external_id:
            match = JobPosting.objects.filter(user=user, source_type=job.source_type,
                                              external_id=job.external_id).first()
            if match:
                return match
        return JobPosting.objects.filter(user=user, fingerprint=job.fingerprint).first()


def days_until(deadline):
    return None if deadline is None else (deadline - date.today()).days
