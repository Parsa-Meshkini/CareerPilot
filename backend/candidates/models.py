import uuid
from dataclasses import asdict, dataclass

from django.conf import settings
from django.db import models


class CandidateProfile(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="profile")
    full_name = models.CharField(max_length=120, blank=True)
    headline = models.CharField(max_length=200, blank=True)
    summary = models.TextField(blank=True)
    location = models.CharField(max_length=120, blank=True)
    email = models.EmailField(blank=True)
    phone = models.CharField(max_length=40, blank=True)
    links = models.JSONField(default=list, blank=True)
    updated_at = models.DateTimeField(auto_now=True)

    def get_verified_facts(self):
        return ProfileFact.objects.filter(user=self.user, verified=True)

    def get_skills(self):
        return self.get_verified_facts().filter(fact_type=ProfileFact.SKILL)


class ProfileFact(models.Model):
    """One verifiable item about the student.

    Stage 1 modelled Education/Experience/Project/Skill/Certification as
    subclasses; they are stored in one table with a type discriminator and a
    typed JSON payload validated by FACT_FIELDS (see services.validate_fact).
    """

    EDUCATION, EXPERIENCE, PROJECT, SKILL, CERTIFICATION = "EDUCATION", "EXPERIENCE", "PROJECT", "SKILL", "CERTIFICATION"
    TYPES = [(t, t.title()) for t in (EDUCATION, EXPERIENCE, PROJECT, SKILL, CERTIFICATION)]
    MANUAL, RESUME_EXTRACTION = "MANUAL", "RESUME_EXTRACTION"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="facts")
    fact_type = models.CharField(max_length=20, choices=TYPES)
    data = models.JSONField(default=dict)
    source = models.CharField(max_length=20, default=MANUAL,
                              choices=[(MANUAL, "Manual"), (RESUME_EXTRACTION, "Resume extraction")])
    verified = models.BooleanField(default=False)
    needs_review = models.BooleanField(default=False)
    review_note = models.CharField(max_length=200, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["fact_type", "-created_at"]
        indexes = [models.Index(fields=["user", "verified"])]

    def verify(self):
        self.verified = True
        self.needs_review = False
        self.review_note = ""

    def to_context_text(self):
        d = self.data
        if self.fact_type == self.EXPERIENCE:
            bullets = "; ".join(d.get("bullets", []))
            return f"{d.get('title')} at {d.get('organization')} ({_span(d)}). {bullets}".strip()
        if self.fact_type == self.EDUCATION:
            return f"{d.get('credential') or ''} {d.get('program')} at {d.get('institution')} ({_span(d)})".strip()
        if self.fact_type == self.PROJECT:
            tech = ", ".join(d.get("technologies", []))
            return f"Project {d.get('name')}: {d.get('description', '')} [{tech}]".strip()
        if self.fact_type == self.SKILL:
            extra = f" ({d['proficiency']})" if d.get("proficiency") else ""
            return f"Skill: {d.get('name')}{extra}"
        return f"Certification: {d.get('name')} from {d.get('issuer', '')}".strip()

    def label(self):
        d = self.data
        return d.get("title") or d.get("name") or d.get("program") or self.fact_type.title()


def _span(d):
    return f"{d.get('start_date') or '?'} to {d.get('end_date') or 'present'}"


class UploadedResume(models.Model):
    PENDING, PARSED, FAILED, FAILED_RETRYABLE = "PENDING", "PARSED", "FAILED", "FAILED_RETRYABLE"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="resumes")
    storage_key = models.CharField(max_length=300)
    original_name = models.CharField(max_length=200)
    mime_type = models.CharField(max_length=100)
    parse_status = models.CharField(max_length=20, default=PENDING)
    status_message = models.CharField(max_length=300, blank=True)
    extracted_count = models.IntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)

    def mark_parsed(self, count):
        self.parse_status, self.extracted_count, self.status_message = self.PARSED, count, ""

    def mark_failed(self, message, retryable=False):
        self.parse_status = self.FAILED_RETRYABLE if retryable else self.FAILED
        self.status_message = message[:300]


@dataclass
class StrategyWeights:
    """Factor weights inside a MatchScoringStrategy; tuned by F19 within bounds."""

    skill: float = 0.5
    experience: float = 0.2
    preference: float = 0.15
    constraint: float = 0.15

    MAX_STEP = 0.10

    def get(self, factor):
        return getattr(self, factor)

    def apply_adjustment(self, factor, new_value):
        old = self.get(factor)
        if abs(new_value - old) > self.MAX_STEP + 1e-9:
            raise ValueError(f"adjustment of {factor} exceeds ±{self.MAX_STEP}")
        setattr(self, factor, round(max(0.0, min(1.0, new_value)), 3))

    def normalized(self):
        total = self.skill + self.experience + self.preference + self.constraint or 1.0
        return {k: v / total for k, v in self.to_dict().items()}

    def to_dict(self):
        return {k: v for k, v in asdict(self).items()}


DEFAULT_WEIGHTS = {
    "coop_internship": StrategyWeights(skill=0.45, experience=0.10, preference=0.15, constraint=0.30),
    "entry_level": StrategyWeights(skill=0.50, experience=0.25, preference=0.15, constraint=0.10),
    "career_switch": StrategyWeights(skill=0.55, experience=0.15, preference=0.20, constraint=0.10),
}


class CareerGoal(models.Model):
    COOP, INTERNSHIP, NEW_GRAD, CAREER_SWITCH = "COOP", "INTERNSHIP", "NEW_GRAD", "CAREER_SWITCH"
    GOAL_TYPES = [(g, g.replace("_", " ").title()) for g in (COOP, INTERNSHIP, NEW_GRAD, CAREER_SWITCH)]
    WORK_MODELS = [(m, m.title()) for m in ("ONSITE", "HYBRID", "REMOTE", "ANY")]
    STRATEGY_KEYS = {COOP: "coop_internship", INTERNSHIP: "coop_internship",
                     NEW_GRAD: "entry_level", CAREER_SWITCH: "career_switch"}

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="career_goal")
    goal_type = models.CharField(max_length=20, choices=GOAL_TYPES, default=COOP)
    target_roles = models.JSONField(default=list, blank=True)
    preferred_locations = models.JSONField(default=list, blank=True)
    work_term = models.CharField(max_length=40, blank=True)
    work_model = models.CharField(max_length=10, choices=WORK_MODELS, default="ANY")
    min_match_score = models.IntegerField(default=60)
    weights_data = models.JSONField(default=dict, blank=True)
    updated_at = models.DateTimeField(auto_now=True)

    def to_strategy_key(self):
        return self.STRATEGY_KEYS[self.goal_type]

    @property
    def weights(self):
        default = DEFAULT_WEIGHTS[self.to_strategy_key()]
        return StrategyWeights(**{**default.to_dict(), **(self.weights_data or {})})

    @weights.setter
    def weights(self, value):
        self.weights_data = value.to_dict()
