package com.careerpilot.notifications;

import com.careerpilot.applications.ApplicationService;
import com.careerpilot.applications.InvalidTransitionException;
import com.careerpilot.applications.StatusChange;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.EventListener;
import com.careerpilot.events.Events;
import com.careerpilot.integrations.EventTypes;
import com.careerpilot.users.User;
import com.careerpilot.users.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * F15 observer: a confident (or student-confirmed) email event moves the application through its State machine, recorded as
 * an EMAIL_EVENT change that can be undone. An event that is illegal for the current state changes nothing and asks for review.
 */
@Component
public class ApplicationStatusUpdater implements EventListener<Events.ApplicationEventDetected> {
    static final Map<String, String> ACTIONS = Map.of(
            EventTypes.APPLICATION_RECEIVED, "submit",
            EventTypes.INTERVIEW_INVITATION, "schedule_interview",
            EventTypes.INTERVIEW_SCHEDULED, "schedule_interview",
            EventTypes.OFFER, "receive_offer",
            EventTypes.REJECTION, "reject");

    private final ApplicationService applications;
    private final NotificationService notifications;
    private final UserRepository users;
    private final DomainEventBus bus;

    public ApplicationStatusUpdater(ApplicationService applications, NotificationService notifications, UserRepository users, DomainEventBus bus) {
        this.applications = applications;
        this.notifications = notifications;
        this.users = users;
        this.bus = bus;
    }

    @PostConstruct
    void subscribe() { bus.subscribe(Events.ApplicationEventDetected.class, this); }

    @Override
    public void handle(Events.ApplicationEventDetected event) {
        if (event.applicationId() == null) return;
        User user = users.findById(event.userId()).orElse(null);
        if (user == null) return;
        String action = ACTIONS.get(event.eventType());
        if (action == null) {
            notifications.add(user, Notification.Kind.EMAIL_EVENT, "An email about one of your applications needs your attention (" + event.eventType().toLowerCase().replace('_', ' ') + ").",
                    event.applicationId(), null, null);
            return;
        }
        try {
            applications.transition(user, event.applicationId(), action, StatusChange.EMAIL_EVENT, event.detectedEventId(), "from email: " + event.eventType(), null, null);
        } catch (InvalidTransitionException e) {
            notifications.add(user, Notification.Kind.REVIEW_NEEDED,
                    "An email says " + event.eventType().toLowerCase().replace('_', ' ') + " but the application is " + e.details().get("from_state") + ". Nothing was changed; please check it.",
                    event.applicationId(), null, null);
        }
    }
}
