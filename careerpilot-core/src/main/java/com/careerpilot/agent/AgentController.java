package com.careerpilot.agent;

import com.careerpilot.common.AppProperties;
import com.careerpilot.common.ConflictException;
import com.careerpilot.common.NotFoundException;
import com.careerpilot.common.ValidationException;
import com.careerpilot.jobs.JobPostingRepository;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * F16: owns the agent run lifecycle (plan, act, observe, decide), its step and replan limits, and the human approvals.
 * The Planner proposes, the ToolManager acts, the Reasoner decides what happens after each observation.
 */
@Service
@Transactional
public class AgentController {
    static final Pattern FORBIDDEN = Pattern.compile("\\b(submit|send (it|the|an?)|apply (for|on behalf)|log ?in|sign in|email the)\\b", Pattern.CASE_INSENSITIVE);
    static final String REFUSAL = "CareerPilot never submits applications, sends email or logs into portals for you; "
            + "it prepared everything else so you can submit it yourself.";

    private final ToolManager tools;
    private final Planner planner;
    private final Reasoner reasoner;
    private final ApprovalPolicy policy;
    private final MemoryManager memory;
    private final ContextBuilder contexts;
    private final AgentRunRepository runs;
    private final JobPostingRepository jobs;
    private int runTokenBudget;

    public AgentController(ToolManager tools, Planner planner, Reasoner reasoner, ApprovalPolicy policy, MemoryManager memory,
                           ContextBuilder contexts, AgentRunRepository runs, JobPostingRepository jobs, AppProperties props) {
        this.tools = tools;
        this.planner = planner;
        this.reasoner = reasoner;
        this.policy = policy;
        this.memory = memory;
        this.contexts = contexts;
        this.runs = runs;
        this.jobs = jobs;
        this.runTokenBudget = props.getLlm().getRunTokenBudget();
    }

    /** Overrides the per-run token budget (configuration default: LLM_RUN_TOKEN_BUDGET). */
    public void setRunTokenBudget(int tokens) { this.runTokenBudget = tokens; }

    public static String classifyGoal(String text) {
        String t = text.toLowerCase();
        if (Pattern.compile("\\btriage|shortlist|sort (my|the) (jobs|batch)\\b").matcher(t).find()) return "TRIAGE_BATCH";
        if (Pattern.compile("\\b(prepare|package|resume|cover letter|tailor|apply|application)\\b").matcher(t).find()) return "PREPARE_APPLICATION";
        return "FREE_FORM";
    }

    // ---------------- entry points ----------------

    public AgentRun runGoal(User user, String goalText, String goalType, UUID jobId, Map<String, Object> context) {
        String goal = goalText == null ? "" : goalText.strip();
        if (goal.isEmpty()) throw new ValidationException("describe your goal").with("fields", Map.of("goal", "required"));
        String type = goalType != null ? goalType : classifyGoal(goal);
        if (!AgentRun.GOAL_TYPES.contains(type)) throw new ValidationException("unknown goal type " + type);
        AgentRun run = new AgentRun();
        run.setUser(user);
        run.setGoalText(goal.length() > 2000 ? goal.substring(0, 2000) : goal);
        run.setGoalType(type);
        run.setTokenBudget(runTokenBudget);
        run.setContext(new LinkedHashMap<>(context == null ? Map.of() : context));
        if (jobId != null) run.getContext().put("focus_job_id", jobs.owned(user, jobId).getId().toString());
        if (FORBIDDEN.matcher(goal).find()) run.getContext().put("refusal", REFUSAL);
        CandidateContext ctx = contexts.buildCandidateContext(user, false, false);
        List<String> memories = memory.recall(user, goal, 10).stream().map(AgentMemoryEntry::getContent).toList();
        run.getContext().put("memories_used", memories);
        Planner.Plan plan = planner.createPlan(run, ctx.summaryText(), memories);
        run.setPlan(new ArrayList<>(plan.steps()));
        run.setPlanSource(plan.source());
        run.setStatus(AgentRun.RUNNING);
        save(run);
        return advance(run);
    }

    public AgentRun runGoal(User user, String goalText) { return runGoal(user, goalText, null, null, null); }

    public AgentRun resumeRun(User user, UUID runId, boolean approve, String reason) {
        AgentRun run = active(user, runId, AgentRun.AWAITING_APPROVAL);
        int index = ((Number) run.getPending().get("step")).intValue();
        Map<String, Object> step = run.getPlan().get(index);
        run.setPending(new LinkedHashMap<>());
        run.setStatus(AgentRun.RUNNING);
        if (approve) {
            step.put("approved", true);
            save(run);
            return advance(run);
        }
        AgentCommand cmd = tools.createCommand(step, user, run);
        tools.recordRejection(cmd, index, reason);
        step.put("status", "rejected");
        memory.remember(user, AgentMemoryEntry.Kind.PREFERENCE,
                "Rejected agent step " + cmd.describe() + " for goal '" + run.getGoalText() + "'" + (reason == null || reason.isBlank() ? "" : ": " + reason),
                null, 0.6);
        run.recordDecision(index, run.getReplanCount() < Reasoner.MAX_REPLANS ? Reasoner.REPLAN : Reasoner.FINISH, "the student rejected this step", "rules");
        if (run.getReplanCount() < Reasoner.MAX_REPLANS) {
            Observation obs = Observation.failed("student rejected the step", "rejected: " + (reason == null || reason.isBlank() ? "no reason given" : reason));
            if (replan(run, index, obs)) return advance(run);
        }
        return finish(run, "");
    }

    @SuppressWarnings("unchecked")
    public AgentRun answer(User user, UUID runId, String choice) {
        AgentRun run = active(user, runId, AgentRun.AWAITING_INPUT);
        List<Map<String, Object>> options = (List<Map<String, Object>>) run.getPending().getOrDefault("options", List.of());
        if (choice.equals("stop")) return finish(run, "Stopped at your request.");
        boolean offered = options.stream().anyMatch(o -> choice.equals(o.get("id")));
        if (!offered && !choice.equals("continue")) throw new ValidationException("pick one of the offered options");
        if (offered && !choice.equals("continue")) run.getContext().put("focus_job_id", choice);
        int step = ((Number) run.getPending().get("step")).intValue();
        run.getPlan().get(step).put("status", "done");
        run.setCurrentStep(step + 1);
        run.setPending(new LinkedHashMap<>());
        run.setStatus(AgentRun.RUNNING);
        save(run);
        return advance(run);
    }

    public AgentActionRecord undoAction(User user, UUID recordId) { return tools.undo(user, recordId); }

    @Transactional(readOnly = true)
    public AgentRun getRun(User user, UUID runId) { return runs.owned(user, runId); }

    @Transactional(readOnly = true)
    public List<AgentRun> listRuns(User user) { return runs.findByUserOrderByCreatedAtDesc(user); }

    @Transactional(readOnly = true)
    public List<AgentActionRecord> actions(User user, UUID runId) { return tools.actionsOf(runs.owned(user, runId)); }

    // ---------------- loop ----------------

    private AgentRun advance(AgentRun run) {
        while (run.getCurrentStep() < run.getPlan().size()) {
            int index = run.getCurrentStep();
            Map<String, Object> step = run.getPlan().get(index);
            if (run.getStepCount() >= Planner.MAX_STEPS) return abort(run, "step limit (" + Planner.MAX_STEPS + ") reached");
            Observation obs;
            AgentCommand cmd = null;
            try {
                cmd = tools.createCommand(step, run.getUser(), run);
            } catch (ValidationException e) {
                obs = Observation.failed(step.get("tool") + " could not start", e.getMessage());
            }
            if (cmd != null) {
                if (policy.requiresApproval(cmd, run) && !Boolean.TRUE.equals(step.get("approved"))) {
                    int tokens = cmd.type().estimatedTokens();
                    run.awaitApproval(index, cmd.describe() + " - " + cmd.type().description() + (tokens > 0 ? " (~" + tokens + " tokens)" : ""));
                    save(run);
                    return run;
                }
                obs = tools.execute(cmd, index);
                run.setStepCount(run.getStepCount() + 1);
            } else {
                obs = Observation.failed(step.get("tool") + " could not start", "invalid arguments");
            }
            int attempts = (step.get("attempts") instanceof Number n ? n.intValue() : 0) + 1;
            step.put("attempts", attempts);
            step.put("observation", obs.ok ? obs.summary : obs.summary + ": " + obs.error);
            Decision decision = reasoner.evaluate(run, index, step, obs);
            run.recordDecision(index, decision.action(), decision.rationale(), decision.source());
            step.put("status", obs.ok ? "done" : "failed");
            switch (decision.action()) {
                case Reasoner.RETRY -> {
                    save(run);
                    continue;
                }
                case Reasoner.CONTINUE -> run.setCurrentStep(run.getCurrentStep() + 1);
                case Reasoner.ASK_USER -> {
                    List<Map<String, Object>> options = obs.candidates.isEmpty()
                            ? List.of(Map.of("id", "continue", "label", "Continue anyway"), Map.of("id", "stop", "label", "Stop here"))
                            : obs.candidates;
                    run.askUser(index, decision.rationale(), options);
                    save(run);
                    return run;
                }
                case Reasoner.REPLAN -> {
                    if (!replan(run, index, obs)) return finish(run, "Could not find a better plan; stopping with what was done.");
                }
                case Reasoner.ABORT -> {
                    return abort(run, decision.rationale());
                }
                default -> run.setCurrentStep(run.getPlan().size());  // FINISH
            }
            save(run);
        }
        return finish(run, "");
    }

    private boolean replan(AgentRun run, int index, Observation obs) {
        List<Map<String, Object>> completed = new ArrayList<>();
        for (Map<String, Object> s : run.getPlan().subList(0, index + 1))
            completed.add(Map.of("tool", s.get("tool"), "result", s.getOrDefault("observation", "")));
        Planner.Replan replan = planner.replan(run, obs.toMap(), completed);
        if (replan == null) return false;
        run.setReplanCount(run.getReplanCount() + 1);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("replaced_after_step", index);
        history.put("old", new ArrayList<>(run.getPlan().subList(index + 1, run.getPlan().size())));
        history.put("rationale", replan.rationale());
        run.getPlanHistory().add(history);
        List<Map<String, Object>> newPlan = new ArrayList<>(run.getPlan().subList(0, index + 1));  // completed actions are never re-executed
        newPlan.addAll(replan.steps());
        run.setPlan(newPlan);
        run.setCurrentStep(index + 1);
        save(run);
        return true;
    }

    private AgentRun abort(AgentRun run, String reason) {
        run.fail(reason + ". Partial result: " + summary(run), AgentRun.ABORTED);
        save(run);
        remember(run);
        return run;
    }

    private AgentRun finish(AgentRun run, String note) {
        String summary = summary(run);
        if (!note.isEmpty()) summary = summary + " " + note;
        if (run.getContext().get("refusal") != null) summary = summary + " " + run.getContext().get("refusal");
        run.complete(summary.strip());
        save(run);
        remember(run);
        return run;
    }

    @SuppressWarnings("unchecked")
    private String summary(AgentRun run) {
        List<AgentActionRecord> done = tools.actionsOf(run).stream().filter(a -> AgentActionRecord.EXECUTED.equals(a.getStatus())).toList();
        if (run.getGoalType().equals("TRIAGE_BATCH")) {
            AgentActionRecord rank = null;
            for (AgentActionRecord a : done) if (a.getCommandName().equals("rank_jobs")) rank = a;
            if (rank != null) {
                Map<String, Object> data = (Map<String, Object>) rank.getObservation().getOrDefault("data", Map.of());
                List<Map<String, Object>> shortlist = (List<Map<String, Object>>) data.getOrDefault("shortlist", List.of());
                StringBuilder top = new StringBuilder();
                for (Map<String, Object> s : shortlist.stream().limit(5).toList()) top.append(top.length() > 0 ? "; " : "").append(s.get("label")).append(" (").append(s.get("score")).append(")");
                String text = rank.getObservationSummary() + "." + (top.length() > 0 ? " Top: " + top + "." : "");
                Object so = run.getContext().get("summary_only_candidates");
                if (so instanceof Number n && n.intValue() > 0)
                    text += " " + n + " promising postings are summary-only; open them and import again for full detail.";
                return text;
            }
        }
        if (done.isEmpty()) return "No actions were taken.";
        StringBuilder sb = new StringBuilder("Done: ");
        for (int i = 0; i < done.size(); i++) sb.append(i > 0 ? "; " : "").append(done.get(i).getCommandName()).append(": ").append(done.get(i).getObservationSummary());
        return sb + ".";
    }

    private void remember(AgentRun run) {
        String text = "Agent run '" + (run.getGoalText().length() > 120 ? run.getGoalText().substring(0, 120) : run.getGoalText()) + "' ("
                + run.getStatus().toLowerCase() + "): " + (run.getSummary().isEmpty() ? run.getError() : run.getSummary());
        memory.remember(run.getUser(), AgentMemoryEntry.Kind.EPISODIC, text.length() > 600 ? text.substring(0, 600) : text, run.getId(), 0.4);
    }

    private AgentRun active(User user, UUID runId, String status) {
        AgentRun run = runs.findByIdAndUser(runId, user).orElseThrow(() -> new NotFoundException("agent run not found"));
        if (!run.getStatus().equals(status)) throw new ConflictException("run is " + run.getStatus() + ", not " + status);
        return run;
    }

    private void save(AgentRun run) {
        run.touch();
        runs.save(run);
    }
}
