package com.careerpilot.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Subject of the Observer pattern. Producers (job import, application service, email sync, candidate service)
 * publish events without knowing who reacts; observers subscribe per event type. One failing listener is logged
 * and does not stop the others.
 */
@Component
public class DomainEventBus {
    private static final Logger log = LoggerFactory.getLogger(DomainEventBus.class);
    private final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final List<DomainEvent> history = new CopyOnWriteArrayList<>();

    public <E extends DomainEvent> void subscribe(Class<E> type, EventListener<E> listener) {
        List<EventListener<?>> bucket = listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        if (!bucket.contains(listener)) bucket.add(listener);
    }

    public <E extends DomainEvent> void unsubscribe(Class<E> type, EventListener<E> listener) {
        List<EventListener<?>> bucket = listeners.get(type);
        if (bucket != null) bucket.remove(listener);
    }

    public int listenerCount(Class<? extends DomainEvent> type) {
        return listeners.getOrDefault(type, List.of()).size();
    }

    /** Delivers to every subscriber of the event's exact type; returns the exceptions raised by failing listeners. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Exception> publish(DomainEvent event) {
        history.add(event);
        if (history.size() > 100) history.remove(0);
        List<Exception> errors = new ArrayList<>();
        for (EventListener listener : listeners.getOrDefault(event.getClass(), List.of())) {
            try {
                listener.handle(event);
            } catch (Exception e) {
                log.error("listener {} failed on {}", listener.getClass().getSimpleName(), event.name(), e);
                errors.add(e);
            }
        }
        return errors;
    }

    public List<DomainEvent> history() { return List.copyOf(history); }
}
