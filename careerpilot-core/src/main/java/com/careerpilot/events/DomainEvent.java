package com.careerpilot.events;

import java.time.Instant;
import java.util.UUID;

/** Base type of everything published on the {@link DomainEventBus}. */
public interface DomainEvent {
    UUID userId();

    default Instant occurredAt() { return Instant.now(); }

    default String name() { return getClass().getSimpleName(); }
}
