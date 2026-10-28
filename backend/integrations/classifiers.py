"""Email classification strategies (P2 Strategy): rules, LLM, and the hybrid that tries rules first."""
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime

from agent.llm.client import get_llm_client
from agent.llm.types import LLMBudgetExceeded, LLMError

from .models import (APPLICATION_RECEIVED, EVENT_TYPES, FOLLOW_UP, INFO_REQUESTED, INTERVIEW_INVITATION,
                     INTERVIEW_SCHEDULED, NOT_RELEVANT, OFFER, REJECTION)

ATS_DOMAINS = ("greenhouse.io", "lever.co", "myworkday.com", "workday.com", "icims.com", "smartrecruiters.com",
               "ashbyhq.com", "taleo.net", "jobvite.com", "bamboohr.com")


@dataclass
class Classification:
    event_type: str
    confidence: float
    extracted_data: dict = field(default_factory=dict)
    source: str = "rules"


class EmailClassificationStrategy(ABC):
    @abstractmethod
    def classify(self, email, user=None) -> Classification: ...


@dataclass(frozen=True)
class Rule:
    event_type: str
    pattern: re.Pattern
    confidence: float
    subject_only: bool = False


def _rule(event_type, confidence, regex, subject_only=False):
    return Rule(event_type, re.compile(regex, re.I), confidence, subject_only)


# Ordered from most to least decisive: the first rule that matches wins.
DEFAULT_RULES = [
    _rule(OFFER, 0.9, r"pleased to offer|delighted to offer|offer of employment|offer letter|extend (you )?an offer"),
    _rule(REJECTION, 0.92, r"unfortunately|not (be )?moving forward|not move forward|regret to inform|"
                           r"not been selected|other candidates|will not be proceeding|decided not to"),
    _rule(INTERVIEW_SCHEDULED, 0.88, r"interview (is )?(confirmed|scheduled)|confirm(ing)? your interview"),
    _rule(INTERVIEW_INVITATION, 0.88, r"invite you to (an )?(interview|a call|a chat)|interview invitation|"
                                      r"schedule (an|your) interview|availability for (an )?interview|"
                                      r"would like to (set up|arrange|interview)"),
    _rule(INFO_REQUESTED, 0.8, r"online assessment|coding (challenge|assessment)|additional information|"
                               r"please (provide|submit|upload|complete)|transcript"),
    _rule(APPLICATION_RECEIVED, 0.9, r"thank you for (applying|your application)|application (was )?received|"
                                     r"we('ve| have) received your application|received your application"),
    _rule(FOLLOW_UP, 0.6, r"following up|checking in|just checking"),
]

_MONTHS = ("jan feb mar apr may jun jul aug sep oct nov dec").split()
_DATE_ISO = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})\b")
_DATE_TEXT = re.compile(r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.? (\d{1,2})(?:st|nd|rd|th)?"
                        r"(?:,? (\d{4}))?(?:,? at| @|,)? (\d{1,2})(?::(\d{2}))? ?(am|pm)\b", re.I)
_FORMAT = re.compile(r"\b(zoom|teams|google meet|video|phone|on-?site|in[- ]person)\b", re.I)


def extract_schedule(text, default_year):
    """Finds the first interview date/time in the text. Returns an ISO string without timezone, or ''."""
    m = _DATE_ISO.search(text)
    if m:
        y, mo, d, h, mi = (int(x) for x in m.groups())
    else:
        m = _DATE_TEXT.search(text)
        if not m:
            return ""
        mo = _MONTHS.index(m.group(1).lower()) + 1
        d, y = int(m.group(2)), int(m.group(3) or default_year)
        h, mi = int(m.group(4)) % 12 + (12 if m.group(6).lower() == "pm" else 0), int(m.group(5) or 0)
    try:
        return datetime(y, mo, d, h, mi).strftime("%Y-%m-%dT%H:%M")
    except ValueError:
        return ""


class RuleBasedClassifier(EmailClassificationStrategy):
    """Sender-domain and subject/body patterns. Fast, free and explainable."""

    def __init__(self, rules=None):
        self.rules = rules or DEFAULT_RULES

    def classify(self, email, user=None):
        subject, body = email.subject, email.excerpt
        for rule in self.rules:
            haystack = subject if rule.subject_only else f"{subject}\n{body}"
            if rule.pattern.search(haystack):
                confidence = rule.confidence
                if email.sender_domain.endswith(ATS_DOMAINS):
                    confidence = min(0.97, confidence + 0.05)   # an applicant-tracking system sent it
                return Classification(rule.event_type, round(confidence, 2), self._details(rule, email), "rules")
        return Classification(NOT_RELEVANT, 0.5, {}, "rules")

    @staticmethod
    def _details(rule, email):
        data = {}
        if rule.event_type in (INTERVIEW_INVITATION, INTERVIEW_SCHEDULED):
            year = email.received_at.year if email.received_at else datetime.now().year
            when = extract_schedule(f"{email.subject}\n{email.excerpt}", year)
            if when:
                data["scheduled_at"] = when
            fmt = _FORMAT.search(email.excerpt)
            if fmt:
                data["format"] = fmt.group(1).lower()
        return data


class LLMClassifier(EmailClassificationStrategy):
    """Asks the model (prompt email.classify). Budget and provider errors propagate as LLMError."""

    def __init__(self, llm=None):
        self._llm = llm

    @property
    def llm(self):
        return self._llm or get_llm_client()

    def classify(self, email, user=None):
        result = self.llm.generate_structured(user or email.user, "email.classify", {
            "sender": email.sender, "subject": email.subject, "body": email.excerpt})
        data = result.data
        event_type = data["event_type"] if data["event_type"] in EVENT_TYPES else NOT_RELEVANT
        details = {k: str(data[k]).strip() for k in ("company", "job_title", "scheduled_at", "format") if data.get(k)}
        return Classification(event_type, round(float(data["confidence"]), 2), details, "llm")


class HybridClassifier(EmailClassificationStrategy):
    """Rules first; the LLM is only called when the rules are below the threshold (Stage 1: 0.85)."""

    def __init__(self, rules=None, llm_classifier=None, threshold=0.85):
        self.rules = rules or RuleBasedClassifier()
        self.llm = llm_classifier or LLMClassifier()
        self.threshold = threshold

    def classify(self, email, user=None):
        first = self.rules.classify(email, user)
        if first.confidence >= self.threshold:
            return first
        try:
            second = self.llm.classify(email, user)
        except LLMBudgetExceeded:
            return first
        except LLMError:
            return first   # offline or provider down: the weaker rule result still goes to review
        if second.event_type == first.event_type:
            merged = {**second.extracted_data, **first.extracted_data}
            return Classification(first.event_type, max(first.confidence, second.confidence), merged, "rules+llm")
        return second
