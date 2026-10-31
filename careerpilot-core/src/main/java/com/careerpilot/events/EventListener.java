package com.careerpilot.events;

/** Observer interface. Implementations subscribe to one event type and react without the producer knowing them. */
public interface EventListener<E extends DomainEvent> {
    void handle(E event);
}
