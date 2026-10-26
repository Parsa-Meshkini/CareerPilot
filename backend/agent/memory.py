"""MemoryManager: long-term agent memory (episodic, preference, strategy, feedback)."""
import math
import re

from django.utils import timezone

from .models import AgentMemoryEntry

_WORD = re.compile(r"[a-z0-9]+")
_STOP = {"the", "a", "an", "and", "or", "to", "for", "of", "in", "on", "my", "me", "at", "with", "is", "it"}


def _tokens(text):
    return {w for w in _WORD.findall((text or "").lower()) if w not in _STOP and len(w) > 1}


class MemoryManager:
    HALF_LIFE_DAYS = 30

    def remember(self, user, kind, content, related_object_id=None, importance=0.5):
        return AgentMemoryEntry.objects.create(user=user, kind=kind, content=content[:2000],
                                               related_object_id=related_object_id, importance=importance)

    def recall(self, user, query="", kinds=None, limit=10):
        """Ranks entries by keyword overlap x recency x importance (no vector search in Stage 2)."""
        qs = AgentMemoryEntry.objects.filter(user=user)
        if kinds:
            qs = qs.filter(kind__in=kinds)
        q = _tokens(query)
        now = timezone.now()
        scored = []
        for entry in qs[:500]:
            overlap = len(q & _tokens(entry.content)) / len(q) if q else 0.0
            age_days = (now - entry.created_at).total_seconds() / 86400
            recency = math.pow(0.5, age_days / self.HALF_LIFE_DAYS)
            # preferences and strategies always matter a little even without keyword overlap
            base = 0.3 if entry.kind in (AgentMemoryEntry.PREFERENCE, AgentMemoryEntry.STRATEGY) else 0.05
            scored.append(((base + overlap) * (0.5 + recency) * (0.5 + entry.importance), entry))
        scored.sort(key=lambda s: s[0], reverse=True)
        return [e for score, e in scored[:limit] if score > 0.05 or not q]
