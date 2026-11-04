package com.careerpilot.notifications;

import com.careerpilot.IntegrationTest;
import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.MemoryManager;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.applications.StatusChange;
import com.careerpilot.integrations.DetectedEmailEvent;
import com.careerpilot.integrations.EmailSyncService;
import com.careerpilot.integrations.EmlFileAdapter;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.RawJobRecord;
import com.careerpilot.jobs.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventDrivenUpdatesTest extends IntegrationTest {
    @Autowired EmailSyncService sync;
    @Autowired ApplicationService applications;
    @Autowired JobImportService imports;
    @Autowired NotificationService notifications;
    @Autowired ReminderService reminders;
    @Autowired MemoryManager memory;

    Application acme;

    @BeforeEach
    void app() { acme = track("Acme Corp", "Backend Developer Co-op", null); }

    private Application track(String company, String title, LocalDate deadline) {
        RawJobRecord raw = new RawJobRecord(SourceType.MANUAL);
        raw.title = title;
        raw.company = company;
        raw.description = "Python. ".repeat(50);
        raw.fullDetail = true;
        if (deadline != null) raw.deadlineText = deadline.toString();
        return applications.createApplication(user, imports.importRecord(user, raw, null).job().getId(), "");
    }

    private void email(String subject, String body, String mid) {
        byte[] eml = ("From: hr@acme-corp.com\r\nTo: a@b.c\r\nSubject: " + subject + "\r\nDate: Mon, 02 Nov 2026 09:12:00 -0500\r\nMessage-ID: <" + mid
                + ">\r\nContent-Type: text/plain\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
        sync.importEmlFiles(user, List.of(new EmlFileAdapter.Upload(mid + ".eml", eml)));
    }

    @Test
    void confidentInterviewInvitationMovesTheApplicationSchedulesAndNotifies() {
        applications.transition(user, acme.getId(), "submit");
        email("Interview invitation: Backend Developer Co-op at Acme Corp",
                "Acme Corp would like to schedule an interview. Join us on November 12, 2026 at 2:30 PM via Zoom.", "m1");
        assertEquals("INTERVIEW", applications.get(user, acme.getId()).getStateName());
        var interviews = applications.interviews(user, acme.getId());
        assertEquals(1, interviews.size());
        assertEquals("zoom", interviews.get(0).getFormat());
        assertNotNull(interviews.get(0).getScheduledAt());
        List<Notification> feed = notifications.list(user);
        assertTrue(feed.stream().anyMatch(n -> n.getKind() == Notification.Kind.EMAIL_EVENT && n.getMessage().contains("undo")));
        assertTrue(feed.stream().anyMatch(n -> n.getKind() == Notification.Kind.INTERVIEW));
        StatusChange change = applications.timeline(user, acme.getId()).get(applications.timeline(user, acme.getId()).size() - 1);
        assertEquals(StatusChange.EMAIL_EVENT, change.getSource());
        assertNotNull(change.getEvidenceId());
        assertTrue(memory.all(user).stream().anyMatch(m -> m.getKind() == AgentMemoryEntry.Kind.EPISODIC && m.getContent().contains("APPLIED to INTERVIEW")));
    }

    @Test
    void anEmailThatIsIllegalForTheCurrentStateChangesNothingAndAsksForReview() {
        applications.transition(user, acme.getId(), "withdraw");
        // the matcher ignores terminal applications, so confirm the event by hand against the withdrawn one
        email("Update", "Unfortunately we are not moving forward with Acme Corp.", "m2");
        DetectedEmailEvent event = sync.listEvents(user, "NEEDS_REVIEW").get(0);
        sync.confirmEvent(user, event.getId(), acme.getId(), "OFFER");
        assertEquals("WITHDRAWN", applications.get(user, acme.getId()).getStateName());
        assertTrue(notifications.list(user).stream().anyMatch(n -> n.getKind() == Notification.Kind.REVIEW_NEEDED));
    }

    @Test
    void undoingAnEmailDrivenChangeIsPossible() {
        applications.transition(user, acme.getId(), "submit");
        email("Update from Acme Corp", "Unfortunately we are not moving forward. Acme Corp Backend Developer Co-op.", "m3");
        // low sender signal may route to review; confirm it so the status updater runs
        sync.listEvents(user, "NEEDS_REVIEW").forEach(e -> sync.confirmEvent(user, e.getId(), acme.getId(), null));
        assertEquals("REJECTED", applications.get(user, acme.getId()).getStateName());
        StatusChange last = applications.timeline(user, acme.getId()).stream().filter(c -> c.getAction().equals("reject")).findFirst().orElseThrow();
        applications.undoTransition(user, last.getId());
        assertEquals("APPLIED", applications.get(user, acme.getId()).getStateName());
    }

    @Test
    void deadlineRemindersAreCreatedOnceAndOnlyForOpenApplications() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        Application soon = track("Soon Inc", "QA Co-op", today.plusDays(3));
        track("Later Inc", "Ops Co-op", today.plusDays(30));
        Application submitted = track("Done Inc", "Dev Co-op", today.plusDays(2));
        applications.transition(user, submitted.getId(), "submit");
        assertEquals(1, reminders.checkDeadlines(user, 7, today));
        reminders.checkDeadlines(user, 7, today);  // a second run must not repeat the notification
        List<Notification> deadlines = notifications.list(user).stream().filter(n -> n.getKind() == Notification.Kind.DEADLINE).toList();
        assertEquals(1, deadlines.size());
        assertEquals(soon.getId(), deadlines.get(0).getApplicationId());
        assertEquals(1, notifications.unreadCount(user) - notifications.list(user).stream().filter(n -> n.getKind() != Notification.Kind.DEADLINE && !n.isRead()).count());
        notifications.markAllRead(user);
        assertEquals(0, notifications.unreadCount(user));
    }
}
