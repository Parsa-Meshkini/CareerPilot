package com.careerpilot.cli;

import com.careerpilot.agent.AgentActionRecord;
import com.careerpilot.agent.AgentController;
import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.AgentRun;
import com.careerpilot.agent.MemoryManager;
import com.careerpilot.users.User;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** F16 goal-driven agent, with the approval, question and undo steps a human controls. */
public final class AgentCommands {
    private AgentCommands() {}

    static AgentController agent() { return CliRuntime.bean(AgentController.class); }

    static UUID runId(User u, String text) { return Ids.resolve(text, agent().listRuns(u), AgentRun::getId); }

    @Command(name = "agent", description = "Give the agent a goal; it plans, acts and asks before anything with side effects (F16).",
            subcommands = {Run.class, Approve.class, Reject.class, Answer.class, Runs.class, ShowRun.class, UndoAction.class, Memory.class})
    public static class Agent implements Callable<Integer> {
        @Override public Integer call() { new picocli.CommandLine(this).usage(Out.out); return 0; }
    }

    static void print(AgentRun run, User u) {
        if (Out.json) {
            Out.json(Map.of("id", run.getId().toString(), "status", run.getStatus(), "goal_type", run.getGoalType(), "plan_source", run.getPlanSource(),
                    "summary", run.getSummary(), "pending", run.getPending(), "decisions", run.getDecisions()));
            return;
        }
        Out.line("Run %s [%s] %s plan (%d tokens)", Out.shortId(run.getId()), run.getStatus(), run.getPlanSource(), run.getTokensUsed());
        List<Map<String, Object>> plan = run.getPlan();
        for (int i = 0; i < plan.size(); i++) {
            Map<String, Object> s = plan.get(i);
            Out.line("  %d. %-26s %-8s %s", i + 1, s.get("tool"), s.getOrDefault("status", i == run.getCurrentStep() ? "next" : "todo"), s.getOrDefault("observation", s.get("intent")));
        }
        for (AgentActionRecord a : agent().actions(u, run.getId()))
            if (a.isReversible() && AgentActionRecord.EXECUTED.equals(a.getStatus()))
                Out.line("  undoable action %s: %s", Out.shortId(a.getId()), a.getDescription());
        run.getDecisions().stream().reduce((a, b) -> b).ifPresent(d -> Out.line("  last decision: %s (%s) %s", d.get("action"), d.get("source"), d.get("rationale")));
        switch (run.getStatus()) {
            case AgentRun.AWAITING_APPROVAL -> Out.line("Approval needed: %s\n  -> careerpilot agent approve %s   or   careerpilot agent reject %s",
                    run.getPending().get("description"), Out.shortId(run.getId()), Out.shortId(run.getId()));
            case AgentRun.AWAITING_INPUT -> {
                Out.line("Question: %s", run.getPending().get("question"));
                for (Object o : (List<?>) run.getPending().get("options")) Out.line("  - %s: %s", ((Map<?, ?>) o).get("id"), ((Map<?, ?>) o).get("label"));
                Out.line("  -> careerpilot agent answer %s <option id>", Out.shortId(run.getId()));
            }
            default -> {
                if (!run.getSummary().isEmpty()) Out.line("Result: %s", run.getSummary());
                if (!run.getError().isEmpty()) Out.line("Stopped: %s", run.getError());
            }
        }
    }

    @Command(name = "run", description = "Run a goal, e.g. careerpilot agent run \"Prepare my application for the Acme backend co-op\".")
    static class Run implements Callable<Integer> {
        @Parameters(index = "0") String goal;
        @Option(names = "--job", description = "focus job id") String job;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            UUID jobId = job == null ? null : JobCommands.jobId(u, job);
            print(agent().runGoal(u, goal, null, jobId, null), u);
            return 0;
        }
    }

    @Command(name = "approve", description = "Approve the step the agent is waiting on.")
    static class Approve implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            print(agent().resumeRun(u, runId(u, id), true, ""), u);
            return 0;
        }
    }

    @Command(name = "reject", description = "Reject the pending step; the agent replans and remembers your preference.")
    static class Reject implements Callable<Integer> {
        @Parameters(index = "0") String id;
        @Option(names = "--reason", defaultValue = "") String reason;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            print(agent().resumeRun(u, runId(u, id), false, reason), u);
            return 0;
        }
    }

    @Command(name = "answer", description = "Answer a question the agent asked (an option id, continue or stop).")
    static class Answer implements Callable<Integer> {
        @Parameters(index = "0") String id;
        @Parameters(index = "1") String choice;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            print(agent().answer(u, runId(u, id), choice), u);
            return 0;
        }
    }

    @Command(name = "runs", description = "List agent runs.")
    static class Runs implements Callable<Integer> {
        @Override public Integer call() {
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (AgentRun r : agent().listRuns(CliRuntime.currentUser())) {
                rows.add(List.of(Out.shortId(r.getId()), r.getStatus(), r.getGoalType(), r.getGoalText()));
                js.add(Map.of("id", r.getId().toString(), "status", r.getStatus(), "goal", r.getGoalText()));
            }
            Out.table(List.of("ID", "STATUS", "TYPE", "GOAL"), rows, js);
            return 0;
        }
    }

    @Command(name = "show", description = "Show a run's plan, decisions and result.")
    static class ShowRun implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            print(agent().getRun(u, runId(u, id)), u);
            return 0;
        }
    }

    @Command(name = "undo", description = "Undo a reversible action the agent took.")
    static class UndoAction implements Callable<Integer> {
        @Parameters(index = "0", description = "action id as printed by 'agent show'") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            UUID full = Ids.resolve(id, agent().listRuns(u).stream().flatMap(r -> agent().actions(u, r.getId()).stream()).toList(), AgentActionRecord::getId);
            Out.line("Action is now %s.", agent().undoAction(u, full).getStatus());
            return 0;
        }
    }

    @Command(name = "memory", description = "Show what the agent remembers about you.")
    static class Memory implements Callable<Integer> {
        @Override public Integer call() {
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (AgentMemoryEntry m : CliRuntime.bean(MemoryManager.class).all(CliRuntime.currentUser())) {
                rows.add(List.of(m.getKind().name(), m.getContent()));
                js.add(Map.of("kind", m.getKind().name(), "content", m.getContent()));
            }
            Out.table(List.of("KIND", "MEMORY"), rows, js);
            return 0;
        }
    }
}
