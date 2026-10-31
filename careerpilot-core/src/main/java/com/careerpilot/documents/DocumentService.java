package com.careerpilot.documents;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.common.StorageService;
import com.careerpilot.common.ValidationException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.llm.TokenBudget;
import com.careerpilot.users.User;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** F10/F11 entry point: picks the generator for a document kind, lists, reads and re-verifies edited documents. */
@Service
@Transactional
public class DocumentService {
    private final ResumeGenerator resume;
    private final CoverLetterGenerator coverLetter;
    private final ApplicationRepository applications;
    private final GeneratedDocumentRepository documents;
    private final StorageService storage;
    private final DomainEventBus bus;

    public DocumentService(ResumeGenerator resume, CoverLetterGenerator coverLetter, ApplicationRepository applications,
                           GeneratedDocumentRepository documents, StorageService storage, DomainEventBus bus) {
        this.resume = resume;
        this.coverLetter = coverLetter;
        this.applications = applications;
        this.documents = documents;
        this.storage = storage;
        this.bus = bus;
    }

    /** Observer: documents that cite a deleted fact are flagged stale so they are regenerated next time. */
    @PostConstruct
    void subscribe() {
        bus.subscribe(Events.ProfileFactDeleted.class, event -> {
            for (GeneratedDocument doc : documents.findAll()) {
                if (doc.getUser().getId().equals(event.userId()) && !doc.isStale() && doc.getCitedFactIds().contains(event.factId().toString())) {
                    doc.setStale(true);
                    documents.save(doc);
                }
            }
        });
    }

    private DocumentGenerator generator(String kind) {
        return switch (kind == null ? "" : kind) {
            case "resume" -> resume;
            case "cover_letter" -> coverLetter;
            default -> throw new ValidationException("unknown document type '" + kind + "'");
        };
    }

    public GeneratedDocument generate(User user, UUID applicationId, String kind, TokenBudget run, Map<String, Object> options) {
        Application app = applications.owned(user, applicationId);
        return generator(kind).generate(user, app, run, options);
    }

    public GeneratedDocument generate(User user, UUID applicationId, String kind) {
        return generate(user, applicationId, kind, null, Map.of());
    }

    @Transactional(readOnly = true)
    public List<GeneratedDocument> list(User user, UUID applicationId) {
        return documents.findByApplicationOrderByCreatedAtDesc(applications.owned(user, applicationId));
    }

    @Transactional(readOnly = true)
    public GeneratedDocument get(User user, UUID documentId) { return documents.owned(user, documentId); }

    public GeneratedDocument saveEdit(User user, UUID documentId, Map<String, Object> content) {
        GeneratedDocument doc = documents.owned(user, documentId);
        DocumentGenerator g = GeneratedDocument.RESUME.equals(doc.getDocType()) ? resume : coverLetter;
        return g.regenerateFromEdit(user, doc, content);
    }

    @Transactional(readOnly = true)
    public byte[] file(User user, UUID documentId, String format) {
        GeneratedDocument doc = documents.owned(user, documentId);
        Object key = doc.getFiles().get(format);
        if (key == null) throw new ValidationException("no " + format + " file for this document");
        return storage.get(key.toString());
    }
}
