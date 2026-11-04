package com.careerpilot.notifications;

import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.EventListener;
import com.careerpilot.events.Events;
import com.careerpilot.users.User;
import com.careerpilot.users.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * F15 observer: turns domain events into notifications for the feed. Also the API the GUI and CLI use to read and clear it.
 */
@Service
@Transactional
public class NotificationService {
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final DomainEventBus bus;

    public NotificationService(NotificationRepository notifications, UserRepository users, DomainEventBus bus) {
        this.notifications = notifications;
        this.users = users;
        this.bus = bus;
    }

    @PostConstruct
    void subscribe() {
        bus.subscribe(Events.ApplicationStatusChanged.class, (EventListener<Events.ApplicationStatusChanged>) this::onStatusChanged);
        bus.subscribe(Events.DeadlineApproaching.class, (EventListener<Events.DeadlineApproaching>) this::onDeadline);
    }

    private void onStatusChanged(Events.ApplicationStatusChanged e) {
        User user = users.findById(e.userId()).orElse(null);
        if (user == null) return;
        boolean fromEmail = "EMAIL_EVENT".equals(e.source());
        add(user, fromEmail ? Notification.Kind.EMAIL_EVENT : Notification.Kind.STATUS_CHANGE,
                "Application moved " + e.fromState() + " to " + e.toState() + (fromEmail ? " from an email. You can undo it in the Tracker." : "."),
                e.applicationId(), null, null);
    }

    private void onDeadline(Events.DeadlineApproaching e) {
        User user = users.findById(e.userId()).orElse(null);
        if (user == null) return;
        add(user, Notification.Kind.DEADLINE, "Deadline in " + e.daysLeft() + " day(s) (" + e.deadline() + ") for a posting you are tracking.",
                e.applicationId(), e.jobId(), "deadline:" + e.jobId() + ":" + e.deadline());
    }

    /** Adds a notification unless one with the same dedupe key already exists. Returns null when it was a duplicate. */
    public Notification add(User user, Notification.Kind kind, String message, UUID applicationId, UUID jobId, String dedupeKey) {
        if (dedupeKey != null && notifications.existsByUserAndDedupeKey(user, dedupeKey)) return null;
        Notification n = new Notification();
        n.setUser(user);
        n.setKind(kind);
        n.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        n.setApplicationId(applicationId);
        n.setJobId(jobId);
        n.setDedupeKey(dedupeKey);
        return notifications.save(n);
    }

    @Transactional(readOnly = true)
    public List<Notification> list(User user) { return notifications.findByUserOrderByCreatedAtDesc(user); }

    @Transactional(readOnly = true)
    public long unreadCount(User user) { return notifications.countByUserAndReadFalse(user); }

    public void markAllRead(User user) {
        List<Notification> all = notifications.findByUserOrderByCreatedAtDesc(user);
        all.forEach(n -> n.setRead(true));
        notifications.saveAll(all);
    }
}
