package com.careerpilot.integrations;

import com.careerpilot.IntegrationTest;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.common.ConflictException;
import com.careerpilot.common.NotFoundException;
import com.careerpilot.common.ValidationException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.RawJobRecord;
import com.careerpilot.jobs.SourceType;
import com.careerpilot.llm.LlmProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailTest extends IntegrationTest {
    @Autowired EmailSyncService sync;
    @Autowired EmailNormalizer normalizer;
    @Autowired EmlFileAdapter emlAdapter;
    @Autowired MockEmailAdapter mockAdapter;
    @Autowired EmailProviderRegistry registry;
    @Autowired RuleBasedClassifier rules;
    @Autowired LlmEmailClassifier llmClassifier;
    @Autowired HybridClassifier hybrid;
    @Autowired EmailEventDetector detector;
    @Autowired ApplicationMatcher matcher;
    @Autowired ApplicationService applications;
    @Autowired JobImportService imports;
    @Autowired DetectedEventRepository events;
    @Autowired EmailMessageRepository messages;
    @Autowired EmailAccountRepository accounts;
    @Autowired DomainEventBus bus;

    Application acme;
    final List<Events.ApplicationEventDetected> published = new ArrayList<>();

    @BeforeEach
    void setup() {
        acme = app("Acme Corp", "Backend Developer Co-op");
        bus.subscribe(Events.ApplicationEventDetected.class, published::add);
    }

    private Application app(String company, String title) {
        RawJobRecord raw = new RawJobRecord(SourceType.MANUAL);
        raw.title = title;
        raw.company = company;
        raw.description = "Python. ".repeat(50);
        raw.fullDetail = true;
        JobPosting job = imports.importRecord(user, raw, null).job();
        return applications.createApplication(user, job.getId(), "");
    }

    private static byte[] eml(String subject, String body, String sender, String mid) {
        return ("From: " + sender + "\r\nTo: ana@example.com\r\nSubject: " + subject + "\r\nDate: Mon, 02 Nov 2026 09:12:00 -0500\r\n"
                + "Message-ID: <" + mid + ">\r\nContent-Type: text/plain\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] eml(String subject, String body) { return eml(subject, body, "hr@acme-corp.com", "m1"); }

    private EmailMessage mail(String subject, String body, String sender) {
        EmailAccount account = accounts.findByUserAndProviderAndAddress(user, "MOCK", "t@x.com").orElseGet(() -> {
            EmailAccount a = new EmailAccount();
            a.setUser(user);
            a.setProvider("MOCK");
            a.setAddress("t@x.com");
            return accounts.save(a);
        });
        EmailMessage m = new EmailMessage();
        m.setUser(user);
        m.setAccount(account);
        m.setProviderMessageId(subject);
        m.setSender(sender);
        m.setSubject(subject);
        m.setExcerpt(body);
        return m;
    }

    private EmailMessage mail(String subject, String body) { return mail(subject, body, "HR <hr@acme-corp.com>"); }

    // ---- adapters and normalizer ----

    @Test
    void emlAdapterParsesHeadersAndHandsOverOnce() {
        EmailAccount account = new EmailAccount();
        account.setUser(user);
        account.setProvider("EML");
        account = accounts.save(account);
        byte[] ok = ("From: Jane Doe <jane@acme-corp.com>\r\nTo: ana@example.com\r\nSubject: Interview  invitation\r\n"
                + "Date: Mon, 02 Nov 2026 09:12:00 -0500\r\nMessage-ID: <abc123@acme-corp.com>\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\nHello Ana,\r\nWe would like to interview you.\r\n> quoted earlier text\r\n").getBytes();
        EmlFileAdapter.Loaded loaded = emlAdapter.load(account, List.of(new EmlFileAdapter.Upload("a.eml", ok), new EmlFileAdapter.Upload("junk.eml", "just some text".getBytes())));
        assertEquals(List.of("abc123@acme-corp.com"), loaded.good().stream().map(RawEmail::providerMessageId).toList());
        assertEquals("junk.eml", loaded.bad().get(0).filename());
        assertEquals(1, emlAdapter.fetchNewMessages(account, null).size());
        assertTrue(emlAdapter.fetchNewMessages(account, null).isEmpty());  // handed over once

        EmailMessage msg = normalizer.normalize(loaded.good().get(0), account);
        assertEquals("Jane Doe <jane@acme-corp.com>", msg.getSender());
        assertEquals("acme-corp.com", msg.senderDomain());
        assertEquals("Interview invitation", msg.getSubject());
        assertTrue(msg.getExcerpt().contains("interview you"));
        assertFalse(msg.getExcerpt().contains("quoted earlier"));
    }

    @Test
    void normalizerStripsHtmlAndCapsTheExcerpt() {
        EmailAccount account = new EmailAccount();
        account.setUser(user);
        account.setProvider("EML");
        account = accounts.save(account);
        byte[] html = ("From: hr@acme-corp.com\r\nSubject: Offer\r\nDate: Mon, 02 Nov 2026 09:12:00 -0500\r\nContent-Type: text/html; charset=utf-8\r\n\r\n"
                + "<html><style>p{}</style><p>We are <b>pleased</b> to offer you &amp; welcome you.</p></html>").getBytes();
        RawEmail raw = emlAdapter.load(account, List.of(new EmlFileAdapter.Upload("o.eml", html))).good().get(0);
        assertEquals("We are pleased to offer you & welcome you.", normalizer.normalize(raw, account).getExcerpt());
        RawEmail big = emlAdapter.load(account, List.of(new EmlFileAdapter.Upload("b.eml", eml("Big", "x".repeat(10000))))).good().get(0);
        assertEquals(EmailMessage.EXCERPT_LIMIT, normalizer.normalize(big, account).getExcerpt().length());
    }

    @Test
    void mockAdapterServesTheSampleInboxAndTheRegistryResolvesByName() {
        EmailAccount account = new EmailAccount();
        account.setUser(user);
        account.setProvider("MOCK");
        account.setAddress("demo@example.com");
        account = accounts.save(account);
        List<RawEmail> sample = mockAdapter.fetchNewMessages(account, null);
        assertEquals(3, sample.size());
        assertEquals("demo@example.com", sample.get(0).headers().get("To"));
        assertEquals("MOCK", registry.get("MOCK").providerName());
        assertThrows(ValidationException.class, () -> registry.get("GMAIL"));
    }

    // ---- classification strategies ----

    @ParameterizedTest
    @CsvSource({"Update,Unfortunately we are not moving forward.,REJECTION", "Your offer,We are pleased to offer you the role.,OFFER",
            "Interview invitation,We would like to schedule an interview.,INTERVIEW_INVITATION", "Thanks,Thank you for applying to Acme.,APPLICATION_RECEIVED",
            "Next step,Please complete the online assessment.,INFO_REQUESTED", "Sale,30% off shoes,NOT_RELEVANT"})
    void ruleClassifierEventTypes(String subject, String body, String expected) {
        assertEquals(expected, rules.classify(mail(subject, body), user).eventType());
    }

    @Test
    void atsSendersGetABoostAndSchedulesAreExtracted() {
        Classification c = rules.classify(mail("Interview invitation", "Join us on November 12, 2026 at 2:30 PM via Zoom.", "no-reply@greenhouse.io"), user);
        assertEquals(0.93, c.confidence(), 1e-9);
        assertEquals(Map.of("scheduled_at", "2026-11-12T14:30", "format", "zoom"), c.extractedData());
    }

    @Test
    void scheduleExtractionFormats() {
        assertEquals("2026-11-03T09:15", RuleBasedClassifier.extractSchedule("on 2026-11-03 09:15 sharp", 2026));
        assertEquals("2026-12-01T00:00", RuleBasedClassifier.extractSchedule("Dec 1st at 12 am", 2026));
        assertEquals("", RuleBasedClassifier.extractSchedule("Feb 31 at 3pm", 2026));
        assertEquals("", RuleBasedClassifier.extractSchedule("sometime next week", 2026));
    }

    @Test
    void hybridSkipsTheLlmWhenRulesAreConfident() {
        hybrid.classify(mail("Update", "Unfortunately, no."), user);
        assertTrue(mock.calls.isEmpty());
    }

    @Test
    void hybridAsksTheLlmWhenRulesAreUnsureAndFallsBackOffline() {
        mock.script("email.classify", Map.of("event_type", "OFFER", "confidence", 0.95, "company", "Acme Corp"));
        Classification c = hybrid.classify(mail("Re: your application", "Great news inside."), user);
        assertEquals("OFFER", c.eventType());
        assertEquals(0.95, c.confidence(), 1e-9);
        assertEquals("llm", c.source());
        assertEquals(Map.of("company", "Acme Corp"), c.extractedData());

        mock.replace("email.classify", new LlmProviderException("down", false));
        Classification weak = hybrid.classify(mail("Checking in", "Just checking in on things."), user);
        assertEquals("FOLLOW_UP", weak.eventType());
        assertEquals("rules", weak.source());
    }

    @Test
    void theDetectorStrategyCanBeSwappedAtRuntime() {
        EmailMessage email = mail("Update", "Unfortunately, no.");
        EmailClassificationStrategy original = detector.strategy();
        try {
            detector.setStrategy(rules);
            assertEquals("REJECTION", detector.detect(email, user).getEventType());
            mock.script("email.classify", Map.of("event_type", "NOT_RELEVANT", "confidence", 0.9));
            detector.setStrategy(llmClassifier);
            assertNull(detector.detect(email, user));
        } finally {
            detector.setStrategy(original);
        }
    }

    // ---- matcher ----

    @Test
    void matcherScoresSenderDomainCompanyAndTitle() {
        Application other = app("Globex", "Analyst");
        var match = matcher.match(mail("Interview: Backend Developer Co-op at Acme Corp", "Acme Corp would like to meet."), Map.of());
        assertEquals(acme.getId(), match.applicationId());
        assertTrue(match.confidence() >= 0.85);
        assertFalse(match.ambiguous());
        assertFalse(match.candidates().stream().anyMatch(c -> c.get("application_id").equals(other.getId().toString())));
    }

    @Test
    void twoApplicationsAtTheSameCompanyAreFlaggedAmbiguous() {
        app("Acme Corp", "Data Intern");
        var match = matcher.match(mail("Update from Acme Corp", "Acme Corp: hello."), Map.of());
        assertTrue(match.ambiguous());
        assertTrue(match.confidence() <= 0.6);
        assertEquals(2, match.candidates().size());
    }

    @Test
    void terminalApplicationsAreIgnored() {
        applications.transition(user, acme.getId(), "withdraw");
        assertNull(matcher.match(mail("Acme Corp interview", "Acme Corp"), Map.of()).applicationId());
    }

    // ---- sync pipeline ----

    @Test
    void aConfidentEventIsPublishedToTheEventBus() {
        var summary = sync.importEmlFiles(user, List.of(new EmlFileAdapter.Upload("a.eml",
                eml("Interview invitation: Backend Developer Co-op at Acme Corp", "Acme Corp: we would like to schedule an interview."))));
        assertEquals(1, summary.get("new"));
        assertEquals(1, summary.get("published"));
        assertEquals(0, summary.get("needs_review"));
        DetectedEmailEvent event = events.findByUser(user).get(0);
        assertEquals("PUBLISHED", event.getReviewStatus());
        assertEquals(acme.getId(), event.getApplication().getId());
        assertEquals(List.of("INTERVIEW_INVITATION"), published.stream().map(Events.ApplicationEventDetected::eventType).toList());
    }

    @Test
    void anUncertainEventGoesToReviewAndConfirmationPublishes() {
        var summary = sync.importEmlFiles(user, List.of(new EmlFileAdapter.Upload("a.eml",
                eml("Update", "Unfortunately we are not moving forward.", "jobs@example.org", "m2"))));
        assertEquals(1, summary.get("needs_review"));
        assertTrue(published.isEmpty());
        DetectedEmailEvent event = events.findByUser(user).get(0);
        assertEquals("NEEDS_REVIEW", event.getReviewStatus());
        assertNull(event.getApplication());
        ValidationException e = assertThrows(ValidationException.class, () -> sync.confirmEvent(user, event.getId(), null, null));
        assertTrue(e.getMessage().contains("choose which application"));
        DetectedEmailEvent confirmed = sync.confirmEvent(user, event.getId(), acme.getId(), "REJECTION");
        assertEquals("CONFIRMED", confirmed.getReviewStatus());
        assertEquals(1, published.size());
        assertEquals(acme.getId(), published.get(0).applicationId());
        assertThrows(ConflictException.class, () -> sync.dismissEvent(user, event.getId()));
    }

    @Test
    void irrelevantMailKeepsOnlyItsIdAndIsNotReprocessed() {
        var files = List.of(new EmlFileAdapter.Upload("s.eml", eml("Big sale", "Shoes 30% off", "promo@shop.example", "promo1")));
        var first = sync.importEmlFiles(user, files);
        var again = sync.importEmlFiles(user, files);
        assertEquals(1, first.get("not_relevant"));
        assertEquals(1, again.get("duplicates"));
        assertEquals(0, again.get("new"));
        EmailMessage msg = messages.findByUser(user).get(0);
        assertEquals("", msg.getExcerpt());
        assertEquals("", msg.getSubject());
        assertFalse(msg.isRelevant());
        assertTrue(events.findByUser(user).isEmpty());
    }

    @Test
    void mockInboxSyncEndToEnd() {
        EmailAccount account = sync.connectMock(user);
        var summary = sync.syncAccount(user, account.getId());
        assertEquals(3, summary.get("fetched"));
        assertEquals(1, summary.get("not_relevant"));
        assertEquals(1, summary.get("published"));     // the Acme interview matches the tracked Acme application
        assertEquals(1, summary.get("needs_review"));  // the Northwind rejection names a company that is not tracked
        assertNotNull(accounts.findById(account.getId()).orElseThrow().getLastSyncAt());
        assertEquals(3, sync.syncAccount(user, account.getId()).get("duplicates"));
    }

    @Test
    void accountsNeedingReauthAreNotSyncedAndUploadsAreValidated() {
        EmailAccount account = sync.connectMock(user);
        account.setStatus(EmailAccount.REAUTH_REQUIRED);
        accounts.save(account);
        assertTrue(assertThrows(ConflictException.class, () -> sync.syncAccount(user, account.getId())).getMessage().contains("reconnected"));
        assertThrows(ValidationException.class, () -> sync.importEmlFiles(user, List.of()));
        var out = sync.importEmlFiles(user, List.of(new EmlFileAdapter.Upload("bad.eml", "not an email".getBytes())));
        assertEquals(0, out.get("new"));
        assertEquals("bad.eml", ((List<Map<String, Object>>) out.get("rejected_files")).get(0).get("file"));
    }

    @Test
    void eventsAndAccountsAreTenantScoped() {
        sync.importEmlFiles(user, List.of(new EmlFileAdapter.Upload("a.eml", eml("Update", "Unfortunately, no.", "x@example.org", "m3"))));
        DetectedEmailEvent event = events.findByUser(user).get(0);
        var stranger = auth.register("stranger-" + java.util.UUID.randomUUID() + "@yorku.ca", "password123", "Stranger");
        assertTrue(sync.listEvents(stranger, null).isEmpty());
        assertThrows(NotFoundException.class, () -> sync.dismissEvent(stranger, event.getId()));
    }
}
