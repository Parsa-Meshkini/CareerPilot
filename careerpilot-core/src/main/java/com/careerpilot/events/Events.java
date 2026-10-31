package com.careerpilot.events;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The concrete domain events. Each is a plain immutable record. */
public final class Events {
    private Events() {}

    public record JobsImported(UUID userId, UUID batchId, List<UUID> jobIds, String sourceType) implements DomainEvent {}

    public record ApplicationStatusChanged(UUID userId, UUID applicationId, UUID changeId, String fromState, String toState,
                                           String source) implements DomainEvent {}

    public record ApplicationEventDetected(UUID userId, UUID detectedEventId, String eventType, UUID applicationId,
                                           double confidence, Map<String, Object> extractedData) implements DomainEvent {}

    public record DeadlineApproaching(UUID userId, UUID jobId, UUID applicationId, LocalDate deadline, int daysLeft) implements DomainEvent {}

    /** The student changed their career goal; scores computed under the old goal are no longer valid. */
    public record CareerGoalChanged(UUID userId, boolean strategyChanged) implements DomainEvent {}

    /** A profile fact was deleted; generated documents that cited it must be flagged stale. */
    public record ProfileFactDeleted(UUID userId, UUID factId) implements DomainEvent {}
}
