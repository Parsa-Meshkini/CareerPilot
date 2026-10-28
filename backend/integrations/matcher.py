"""ApplicationMatcher: finds which open application an email is about."""
import re
from dataclasses import dataclass, field

from applications.models import Application

from .classifiers import ATS_DOMAINS

TERMINAL = ("ACCEPTED", "DECLINED", "REJECTED", "WITHDRAWN")
STOP = {"inc", "corp", "corporation", "ltd", "llc", "limited", "the", "and", "company", "co", "group", "canada"}
W_DOMAIN, W_SUBJECT, W_BODY, W_TITLE = 0.5, 0.4, 0.3, 0.2
AMBIGUITY_GAP = 0.1


@dataclass
class MatchCandidate:
    application_id: object = None
    confidence: float = 0.0
    ambiguous: bool = False
    candidates: list = field(default_factory=list)   # [{application_id, label, score}], best first


def tokens(text, minimum=3):
    return {t for t in re.findall(r"[a-z0-9]+", (text or "").lower()) if len(t) >= minimum and t not in STOP}


def fraction_in(wanted, haystack):
    return len(wanted & haystack) / len(wanted) if wanted else 0.0


class ApplicationMatcher:
    def match(self, event, email, extracted=None):
        extracted = extracted or {}
        apps = (Application.objects.filter(user=email.user).exclude(state_name__in=TERMINAL)
                .select_related("job"))
        domain = email.sender_domain
        domain_tokens = set() if domain.endswith(ATS_DOMAINS) else tokens(domain.replace("-", " ").replace(".", " "))
        subject, body = tokens(email.subject), tokens(email.excerpt)
        hinted = tokens(extracted.get("company"))
        scored = []
        for app in apps:
            company = tokens(app.job.company)
            score = W_DOMAIN * bool(company & domain_tokens)
            # a company name the LLM read from the email counts like a mention in the subject
            score += W_SUBJECT * max(fraction_in(company, subject), fraction_in(company, hinted))
            score += W_BODY * fraction_in(company, body)
            title = tokens(app.job.title)
            score += W_TITLE * fraction_in(title, subject | body)
            if score > 0:
                scored.append((round(min(score, 1.0), 2), app))
        scored.sort(key=lambda s: s[0], reverse=True)
        candidates = [{"application_id": str(a.id), "label": f"{a.job.title} at {a.job.company}", "score": s}
                      for s, a in scored[:5]]
        if not scored:
            return MatchCandidate()
        best = scored[0]
        ambiguous = len(scored) > 1 and best[0] - scored[1][0] < AMBIGUITY_GAP
        confidence = min(best[0], 0.6) if ambiguous else best[0]
        return MatchCandidate(best[1].id, confidence, ambiguous, candidates)
