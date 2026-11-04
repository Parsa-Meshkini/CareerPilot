package com.careerpilot.notifications;

import com.careerpilot.applications.ApplicationService;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.EventListener;
import com.careerpilot.events.Events;
import com.careerpilot.integrations.EventTypes;
import com.careerpilot.users.User;
import com.careerpilot.users.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** F15 observer: creates an Interview record (once per detected email) when an interview invitation or confirmation arrives. */
@Component
public class InterviewScheduler implements EventListener<Events.ApplicationEventDetected> {
    private final ApplicationService applications;
    private final NotificationService notifications;
    private final UserRepository users;
    private final DomainEventBus bus;

    public InterviewScheduler(ApplicationService applications, NotificationService notifications, UserRepository users, DomainEventBus bus) {
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
        if (!event.eventType().equals(EventTypes.INTERVIEW_INVITATION) && !event.eventType().equals(EventTypes.INTERVIEW_SCHEDULED)) return;
        User user = users.findById(event.userId()).orElse(null);
        if (user == null) return;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source_event_id", event.detectedEventId().toString());
        details.put("format", String.valueOf(event.extractedData().getOrDefault("format", "")));
        Object when = event.extractedData().get("scheduled_at");
        if (when != null && !when.toString().isBlank()) {
            try {
                details.put("scheduled_at", LocalDateTime.parse(when.toString()).atZone(ZoneId.of("America/Toronto")).toInstant());
            } catch (RuntimeException ignored) {
                // an unparseable time is simply left blank for the student to fill in
            }
        }
        details.put("details_text", "Detected from an email" + (when == null ? "." : " (" + when + ")."));
        applications.addInterview(user, event.applicationId(), details);
        notifications.add(user, Notification.Kind.INTERVIEW, "Interview " + (when == null ? "invitation detected" : "on " + when)
                + (details.get("format").toString().isEmpty() ? "" : " (" + details.get("format") + ")") + ".", event.applicationId(), null, null);
    }
}
