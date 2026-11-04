package com.careerpilot.interviews;

import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.EventListener;
import com.careerpilot.events.Events;
import com.careerpilot.notifications.Notification;
import com.careerpilot.notifications.NotificationService;
import com.careerpilot.users.User;
import com.careerpilot.users.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** F17 observer: an application reaching INTERVIEW triggers a preparation plan automatically and tells the student. */
@Component
public class InterviewPrepTrigger implements EventListener<Events.ApplicationStatusChanged> {
    private final InterviewPrepService prep;
    private final NotificationService notifications;
    private final UserRepository users;
    private final DomainEventBus bus;

    public InterviewPrepTrigger(InterviewPrepService prep, NotificationService notifications, UserRepository users, DomainEventBus bus) {
        this.prep = prep;
        this.notifications = notifications;
        this.users = users;
        this.bus = bus;
    }

    @PostConstruct
    void subscribe() { bus.subscribe(Events.ApplicationStatusChanged.class, this); }

    @Override
    public void handle(Events.ApplicationStatusChanged e) {
        if (!"INTERVIEW".equals(e.toState()) || "INTERVIEW".equals(e.fromState())) return;
        User user = users.findById(e.userId()).orElse(null);
        if (user == null) return;
        InterviewPrepPlan plan = prep.preparePlan(user, e.applicationId());
        notifications.add(user, Notification.Kind.PREP_READY, "Your interview preparation plan is ready" + ("FALLBACK".equals(plan.getSource()) ? " (generic: the AI model was unavailable)." : "."),
                e.applicationId(), null, "prep:" + plan.getId());
    }
}
