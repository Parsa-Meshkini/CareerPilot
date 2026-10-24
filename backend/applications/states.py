"""Application lifecycle (P3 State).

Application (the context) delegates every lifecycle action to its current
ApplicationState object. The base class rejects every action; each concrete
state overrides only the transitions that are legal from it (Stage 1 SM-1).
allowed_actions() is derived from those overrides, so the GUI and CLI can only
ever offer legal actions.
"""
from careerpilot.errors import ConflictError

ACTIONS = ("mark_prepared", "submit", "schedule_interview", "receive_offer", "reject", "accept", "decline", "withdraw")


class InvalidTransitionError(ConflictError):
    code = "invalid_transition"

    def __init__(self, from_state, action, allowed):
        super().__init__(f"cannot {action.replace('_', ' ')} an application that is {from_state}",
                         from_state=from_state, action=action, allowed_actions=allowed)


class ApplicationState:
    name = ""
    terminal = False

    def _reject(self, action):
        raise InvalidTransitionError(self.name, action, self.allowed_actions())

    def mark_prepared(self, app):
        self._reject("mark_prepared")

    def submit(self, app):
        self._reject("submit")

    def schedule_interview(self, app, when=None):
        self._reject("schedule_interview")

    def receive_offer(self, app):
        self._reject("receive_offer")

    def reject(self, app):
        self._reject("reject")

    def accept(self, app):
        self._reject("accept")

    def decline(self, app):
        self._reject("decline")

    def withdraw(self, app):
        self._reject("withdraw")

    def allowed_actions(self):
        return [a for a in ACTIONS if getattr(type(self), a) is not getattr(ApplicationState, a)]


class SavedState(ApplicationState):
    name = "SAVED"

    def mark_prepared(self, app):
        app.set_state(PreparedState())

    def submit(self, app):
        app.set_state(AppliedState())

    def withdraw(self, app):
        app.set_state(WithdrawnState())


class PreparedState(ApplicationState):
    name = "PREPARED"

    def mark_prepared(self, app):
        pass  # rebuilding a package keeps it prepared

    def submit(self, app):
        app.set_state(AppliedState())

    def withdraw(self, app):
        app.set_state(WithdrawnState())


class AppliedState(ApplicationState):
    name = "APPLIED"

    def schedule_interview(self, app, when=None):
        app.set_state(InterviewState())

    def receive_offer(self, app):
        app.set_state(OfferState())

    def reject(self, app):
        app.set_state(RejectedState())

    def withdraw(self, app):
        app.set_state(WithdrawnState())


class InterviewState(ApplicationState):
    name = "INTERVIEW"

    def schedule_interview(self, app, when=None):
        pass  # another round; state unchanged, the Interview record is added by the service

    def receive_offer(self, app):
        app.set_state(OfferState())

    def reject(self, app):
        app.set_state(RejectedState())

    def withdraw(self, app):
        app.set_state(WithdrawnState())


class OfferState(ApplicationState):
    name = "OFFER"

    def accept(self, app):
        app.set_state(AcceptedState())

    def decline(self, app):
        app.set_state(DeclinedState())


class AcceptedState(ApplicationState):
    name, terminal = "ACCEPTED", True


class DeclinedState(ApplicationState):
    name, terminal = "DECLINED", True


class RejectedState(ApplicationState):
    name, terminal = "REJECTED", True


class WithdrawnState(ApplicationState):
    name, terminal = "WITHDRAWN", True


STATES = {cls.name: cls for cls in (SavedState, PreparedState, AppliedState, InterviewState, OfferState,
                                    AcceptedState, DeclinedState, RejectedState, WithdrawnState)}
