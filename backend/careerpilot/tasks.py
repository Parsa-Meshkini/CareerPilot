"""TaskQueue / BackgroundWorker infrastructure.

Stage 1 planned Celery + Redis. For Stage 2 the queue runs tasks in-process
(synchronously by default, or in a daemon thread when TASK_QUEUE_MODE=thread),
so the system runs with no broker. Callers only use ``enqueue``; swapping in
Celery later changes this module only.
"""
import logging
import os
import threading
import uuid
from dataclasses import dataclass, field

log = logging.getLogger(__name__)

_registry = {}


def task(name):
    """Register a function as a named background task."""

    def decorator(fn):
        _registry[name] = fn
        return fn

    return decorator


@dataclass
class TaskHandle:
    task_name: str
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    error: str | None = None


class BackgroundWorker:
    def run_task(self, task_name, args):
        fn = _registry.get(task_name)
        if fn is None:
            raise KeyError(f"unknown task {task_name}")
        return fn(**args)


class TaskQueue:
    def __init__(self, mode=None, worker=None):
        self.mode = mode or os.environ.get("TASK_QUEUE_MODE", "sync")
        self.worker = worker or BackgroundWorker()

    def enqueue(self, task_name, args):
        handle = TaskHandle(task_name)
        if self.mode == "thread":
            threading.Thread(target=self._safe_run, args=(handle, args), daemon=True).start()
        else:
            self._safe_run(handle, args)
        return handle

    def _safe_run(self, handle, args):
        try:
            self.worker.run_task(handle.task_name, args)
        except Exception as exc:  # a failing task must not crash the caller
            log.exception("task %s failed", handle.task_name)
            handle.error = str(exc)


task_queue = TaskQueue()
