package com.careerpilot.notifications;

import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.MemoryManager;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.EventListener;
import com.careerpilot.events.Events;
import com.careerpilot.users.User;
import com.careerpilot.users.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** F15 observer: writes each status change to agent memory. These outcomes are the raw material of F19 analytics. */
@Component
public class MemoryRecorder implements EventListener<Events.ApplicationStatusChanged> {
    private final MemoryManager memory;
    private final ApplicationRepository applications;
    private final UserRepository users;
    private final DomainEventBus bus;

    public MemoryRecorder(MemoryManager memory, ApplicationRepository applications, UserRepository users, DomainEventBus bus) {
        this.memory = memory;
        this.applications = applications;
        this.users = users;
        this.bus = bus;
    }

    @PostConstruct
    void subscribe() { bus.subscribe(Events.ApplicationStatusChanged.class, this); }

    @Override
    public void handle(Events.ApplicationStatusChanged e) {
        User user = users.findById(e.userId()).orElse(null);
        Application app = applications.findById(e.applicationId()).orElse(null);
        if (user == null || app == null) return;
        double importance = java.util.List.of("OFFER", "REJECTED", "INTERVIEW", "ACCEPTED").contains(e.toState()) ? 0.7 : 0.4;
        memory.remember(user, AgentMemoryEntry.Kind.EPISODIC,
                app.getJob().getTitle() + " at " + app.getJob().getCompany() + " moved " + e.fromState() + " to " + e.toState() + " (" + e.source() + ")",
                e.applicationId(), importance);
    }
}
