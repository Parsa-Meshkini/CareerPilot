package com.careerpilot.applications;

import com.careerpilot.IntegrationTest;
import com.careerpilot.common.ConflictException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationServiceTest extends IntegrationTest {
    @Autowired ApplicationService service;
    @Autowired DomainEventBus bus;
    Application app;

    @BeforeEach
    void createApp() { app = service.createApplication(user, data.makeJob(user).getId(), ""); }

    @Test
    void allowedActionsFollowTheStateMachine() {
        assertEquals(List.of("mark_prepared", "submit", "withdraw"), ApplicationState.of("SAVED").allowedActions());
        assertEquals(List.of("schedule_interview", "receive_offer", "reject", "withdraw"), ApplicationState.of("APPLIED").allowedActions());
        assertEquals(List.of("accept", "decline"), ApplicationState.of("OFFER").allowedActions());
        assertTrue(ApplicationState.of("WITHDRAWN").allowedActions().isEmpty());
        assertTrue(ApplicationState.of("ACCEPTED").terminal());
    }

    @Test
    void happyPathRecordsTimelineAndPublishesEvents() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(Events.ApplicationStatusChanged.class, e -> seen.add(e.fromState() + ">" + e.toState()));
        for (String a : List.of("mark_prepared", "submit", "schedule_interview", "receive_offer", "accept")) service.transition(user, app.getId(), a);
        Application fresh = service.get(user, app.getId());
        assertEquals("ACCEPTED", fresh.getStateName());
        assertNotNull(fresh.getAppliedAt());
        assertEquals(5, seen.size());
        assertEquals("PREPARED>APPLIED", seen.get(1));
        assertEquals(5, service.timeline(user, app.getId()).size());
    }

    @Test
    void illegalTransitionIsRejectedWithTheLegalOnes() {
        InvalidTransitionException e = assertThrows(InvalidTransitionException.class, () -> service.transition(user, app.getId(), "accept"));
        assertEquals(List.of("mark_prepared", "submit", "withdraw"), e.details().get("allowed_actions"));
        assertEquals(409, e.httpStatus());
    }

    @Test
    void onlyTheLatestChangeCanBeUndone() {
        StatusChange first = service.transition(user, app.getId(), "submit");
        StatusChange second = service.transition(user, app.getId(), "reject");
        assertThrows(ConflictException.class, () -> service.undoTransition(user, first.getId()));
        service.undoTransition(user, second.getId());
        assertEquals("APPLIED", service.get(user, app.getId()).getStateName());
        // the undo entry is now the latest change, so the earlier one stays locked
        assertThrows(ConflictException.class, () -> service.undoTransition(user, first.getId()));
    }

    @Test
    void staleVersionConflicts() {
        assertThrows(ConflictException.class,
                () -> service.transition(user, app.getId(), "submit", StatusChange.USER, null, "", 99L, null));
    }

    @Test
    void oneApplicationPerJob() {
        ConflictException e = assertThrows(ConflictException.class, () -> service.createApplication(user, app.getJob().getId(), ""));
        assertEquals(app.getId().toString(), e.details().get("application_id"));
    }

    @Test
    void interviewsAreIdempotentPerDetectedEmail() {
        service.transition(user, app.getId(), "submit");
        service.transition(user, app.getId(), "schedule_interview");
        java.util.UUID eventId = java.util.UUID.randomUUID();
        Interview a = service.addInterview(user, app.getId(), java.util.Map.of("format", "Video", "source_event_id", eventId.toString()));
        Interview b = service.addInterview(user, app.getId(), java.util.Map.of("format", "Video", "source_event_id", eventId.toString()));
        assertEquals(a.getId(), b.getId());
        assertEquals(1, service.interviews(user, app.getId()).size());
    }
}
