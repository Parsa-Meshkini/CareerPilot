package com.careerpilot.notifications;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * F15: finds deadlines of postings the student is still working on (saved, prepared) and publishes DeadlineApproaching.
 * Run it from the CLI, the GUI refresh or a scheduler; the notification layer de-duplicates per posting and deadline.
 */
@Service
@Transactional
public class ReminderService {
    static final Set<String> OPEN = Set.of("SAVED", "PREPARED");
    private final ApplicationRepository applications;
    private final DomainEventBus bus;

    public ReminderService(ApplicationRepository applications, DomainEventBus bus) {
        this.applications = applications;
        this.bus = bus;
    }

    /** Publishes one event per application whose deadline is within {@code withinDays}; returns how many were found. */
    public int checkDeadlines(User user, int withinDays, LocalDate today) {
        int found = 0;
        for (Application a : applications.findByUser(user)) {
            LocalDate deadline = a.getJob().getDeadline();
            if (deadline == null || !OPEN.contains(a.getStateName())) continue;
            long days = ChronoUnit.DAYS.between(today, deadline);
            if (days < 0 || days > withinDays) continue;
            found++;
            bus.publish(new Events.DeadlineApproaching(user.getId(), a.getJob().getId(), a.getId(), deadline, (int) days));
        }
        return found;
    }

    public int checkDeadlines(User user, int withinDays) { return checkDeadlines(user, withinDays, LocalDate.now()); }
}
