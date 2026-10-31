package com.careerpilot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Background work without a broker. Stage 1 planned Celery + Redis; Stage 2 runs tasks in-process,
 * synchronously by default or on a worker thread. Callers only use {@link #enqueue}.
 */
@Component
public class TaskQueue {
    private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "careerpilot-task");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean async = false;

    public void setAsync(boolean async) { this.async = async; }

    public void enqueue(String name, Runnable task, Consumer<Exception> onError) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("task {} failed", name, e);
                if (onError != null) onError.accept(e);
            }
        };
        if (async) executor.submit(guarded); else guarded.run();
    }

    public void enqueue(String name, Runnable task) { enqueue(name, task, null); }
}
