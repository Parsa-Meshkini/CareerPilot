"""EmailEventDetector: the Strategy context. Swap the classifier at runtime with set_strategy()."""
from .classifiers import HybridClassifier
from .models import NOT_RELEVANT, DetectedEmailEvent


class EmailEventDetector:
    def __init__(self, strategy=None):
        self.strategy = strategy or HybridClassifier()

    def set_strategy(self, strategy):
        self.strategy = strategy

    def detect(self, email, user=None):
        """Returns an unsaved DetectedEmailEvent, or None when the email is not about an application."""
        result = self.strategy.classify(email, user)
        if result.event_type == NOT_RELEVANT:
            return None
        return DetectedEmailEvent(user=email.user, email=email, event_type=result.event_type,
                                  confidence=result.confidence, extracted_data=result.extracted_data,
                                  classifier=result.source)
