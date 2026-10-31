package com.careerpilot.integrations;

import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.common.AppProperties;
import com.careerpilot.common.ConflictException;
import com.careerpilot.common.ValidationException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F14: fetch, normalize, detect, match, then publish the event or queue it for the student's review. The class that
 * wires the Adapter (providers), Strategy (classifiers) and Observer (event bus) parts together.
 */
@Service
@Transactional
public class EmailSyncService {
    static final int MAX_EML_FILES = 50, MAX_EML_BYTES = 2_000_000;

    private final EmailProviderRegistry registry;
    private final EmailNormalizer normalizer;
    private final EmailEventDetector detector;
    private final ApplicationMatcher matcher;
    private final DomainEventBus bus;
    private final EmailAccountRepository accounts;
    private final EmailMessageRepository messages;
    private final DetectedEventRepository events;
    private final ApplicationRepository applications;
    private final EmlFileAdapter emlAdapter;
    private final double threshold;

    public EmailSyncService(EmailProviderRegistry registry, EmailNormalizer normalizer, EmailEventDetector detector, ApplicationMatcher matcher,
                            DomainEventBus bus, EmailAccountRepository accounts, EmailMessageRepository messages, DetectedEventRepository events,
                            ApplicationRepository applications, EmlFileAdapter emlAdapter, AppProperties props) {
        this.registry = registry;
        this.normalizer = normalizer;
        this.detector = detector;
        this.matcher = matcher;
        this.bus = bus;
        this.accounts = accounts;
        this.messages = messages;
        this.events = events;
        this.applications = applications;
        this.emlAdapter = emlAdapter;
        this.threshold = props.getEmail().getAutoApplyThreshold();
    }

    private EmailAccount accountFor(User user, String provider, String address) {
        return accounts.findByUserAndProviderAndAddress(user, provider, address).orElseGet(() -> {
            EmailAccount a = new EmailAccount();
            a.setUser(user);
            a.setProvider(provider);
            a.setAddress(address);
            return accounts.save(a);
        });
    }

    public EmailAccount connectMock(User user) { return accountFor(user, "MOCK", "demo@careerpilot.local"); }

    @Transactional(readOnly = true)
    public List<EmailAccount> listAccounts(User user) { return accounts.findByUser(user); }

    public Map<String, Object> syncAccount(User user, UUID accountId) {
        EmailAccount account = accounts.owned(user, accountId);
        if (!EmailAccount.ACTIVE.equals(account.getStatus()))
            throw new ConflictException("this email account needs to be reconnected").with("status", account.getStatus());
        List<RawEmail> raw = registry.get(account.getProvider()).fetchNewMessages(account, account.getLastSyncAt());
        Map<String, Object> summary = processAll(user, account, raw);
        account.setLastSyncAt(Instant.now());
        accounts.save(account);
        return summary;
    }

    /** Bad files are reported; the rest are processed. */
    public Map<String, Object> importEmlFiles(User user, List<EmlFileAdapter.Upload> files) {
        if (files.isEmpty()) throw new ValidationException("no files were uploaded");
        if (files.size() > MAX_EML_FILES) throw new ValidationException("at most " + MAX_EML_FILES + " files per upload");
        if (files.stream().anyMatch(f -> f.data().length > MAX_EML_BYTES)) throw new ValidationException("a file is larger than 2 MB");
        EmailAccount account = accountFor(user, "EML", "uploaded-files");
        EmlFileAdapter.Loaded loaded = emlAdapter.load(account, files);
        Map<String, Object> summary = processAll(user, account, emlAdapter.fetchNewMessages(account, null));
        summary.put("rejected_files", loaded.bad().stream().map(b -> Map.of("file", b.filename(), "reason", b.reason())).toList());
        return summary;
    }

    @Transactional(readOnly = true)
    public List<DetectedEmailEvent> listEvents(User user, String status) {
        return status == null || status.isBlank() ? events.findByUserOrderByCreatedAtDesc(user)
                : events.findByUserAndReviewStatusOrderByCreatedAtDesc(user, status.toUpperCase());
    }

    @Transactional(readOnly = true)
    public DetectedEmailEvent getEvent(User user, UUID id) { return events.owned(user, id); }

    public DetectedEmailEvent confirmEvent(User user, UUID eventId, UUID applicationId, String eventType) {
        DetectedEmailEvent event = events.owned(user, eventId);
        if (!DetectedEmailEvent.NEEDS_REVIEW.equals(event.getReviewStatus()))
            throw new ConflictException("this event was already resolved").with("review_status", event.getReviewStatus());
        if (eventType != null && !eventType.isBlank()) {
            if (!EventTypes.ALL.contains(eventType) || eventType.equals(EventTypes.NOT_RELEVANT))
                throw new ValidationException("unknown event type " + eventType);
            event.setEventType(eventType);
        }
        if (applicationId != null) event.setApplication(applications.owned(user, applicationId));
        if (event.getApplication() == null) throw new ValidationException("choose which application this email is about");
        event.setReviewStatus(DetectedEmailEvent.CONFIRMED);
        event.setConfidence(1.0);  // the student has decided
        event.setMatchConfidence(1.0);
        events.save(event);
        publish(event);
        return event;
    }

    public DetectedEmailEvent dismissEvent(User user, UUID eventId) {
        DetectedEmailEvent event = events.owned(user, eventId);
        if (!DetectedEmailEvent.NEEDS_REVIEW.equals(event.getReviewStatus()))
            throw new ConflictException("this event was already resolved").with("review_status", event.getReviewStatus());
        event.setReviewStatus(DetectedEmailEvent.DISMISSED);
        return events.save(event);
    }

    public void disconnect(User user, UUID accountId) {
        EmailAccount account = accounts.owned(user, accountId);
        events.deleteByEmail_Account(account);
        messages.deleteByAccount(account);
        accounts.delete(account);
    }

    // ---- pipeline ----

    private Map<String, Object> processAll(User user, EmailAccount account, List<RawEmail> rawEmails) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fetched", rawEmails.size());
        for (String k : List.of("new", "duplicates", "not_relevant", "published", "needs_review")) summary.put(k, 0);
        List<String> eventIds = new ArrayList<>();
        for (RawEmail raw : rawEmails) {
            if (messages.existsByAccountAndProviderMessageId(account, raw.providerMessageId())) {
                summary.merge("duplicates", 1, (a, b) -> (Integer) a + 1);
                continue;
            }
            Outcome outcome = processEmail(user, account, raw);
            summary.merge("new", 1, (a, b) -> (Integer) a + 1);
            summary.merge(outcome.name, 1, (a, b) -> (Integer) a + 1);
            if (outcome.event != null) eventIds.add(outcome.event.getId().toString());
        }
        summary.put("event_ids", eventIds);
        return summary;
    }

    private record Outcome(String name, DetectedEmailEvent event) {}

    private Outcome processEmail(User user, EmailAccount account, RawEmail raw) {
        EmailMessage email = normalizer.normalize(raw, account);
        DetectedEmailEvent event = detector.detect(email, user);
        if (event == null) {
            email.discardContent();  // not job related: keep only the id so it is not fetched again
            email.setSubject("");
            email.setSender("");
            messages.save(email);
            return new Outcome("not_relevant", null);
        }
        messages.save(email);
        event.setEmail(email);
        ApplicationMatcher.MatchCandidate match = matcher.match(email, event.getExtractedData());
        event.setApplication(match.applicationId() == null ? null : applications.getReferenceById(match.applicationId()));
        event.setMatchConfidence(match.confidence());
        event.setCandidates(match.candidates());
        event.setReviewStatus(event.requiresReview(threshold) ? DetectedEmailEvent.NEEDS_REVIEW : DetectedEmailEvent.PUBLISHED);
        events.save(event);
        if (DetectedEmailEvent.PUBLISHED.equals(event.getReviewStatus())) {
            publish(event);
            return new Outcome("published", event);
        }
        return new Outcome("needs_review", event);
    }

    private void publish(DetectedEmailEvent event) {
        bus.publish(new Events.ApplicationEventDetected(event.getUser().getId(), event.getId(), event.getEventType(),
                event.getApplication() == null ? null : event.getApplication().getId(), event.getConfidence(), event.getExtractedData()));
    }
}
