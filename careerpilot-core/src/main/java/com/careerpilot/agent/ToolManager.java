package com.careerpilot.agent;

import com.careerpilot.common.DomainException;
import com.careerpilot.common.Json;
import com.careerpilot.common.NotFoundException;
import com.careerpilot.common.ValidationException;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The Command pattern's invoker: tool catalog, argument validation, execution with audit logging, and undo. */
@Component
@Transactional
public class ToolManager {
    private final AgentServices services;
    private final AgentActionRepository actions;
    private final Map<String, CommandType> catalog = new LinkedHashMap<>();
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public ToolManager(AgentServices services, AgentActionRepository actions) {
        this.services = services;
        this.actions = actions;
        for (CommandType t : List.of(SearchJobsCommand.TYPE, RetrieveContextCommand.TYPE, AnalyzeJobCommand.TYPE, MatchJobCommand.TYPE,
                QuickScoreJobsCommand.TYPE, SelectForDeepAnalysisCommand.TYPE, DeepMatchJobsCommand.TYPE, RankJobsCommand.TYPE,
                BuildPackageCommand.TYPE, UpdateApplicationStatusCommand.TYPE))
            register(t);
    }

    public void register(CommandType type) { catalog.put(type.name(), type); }

    public Map<String, CommandType> catalog() { return catalog; }

    public List<Map<String, Object>> listTools() { return catalog.values().stream().map(CommandType::spec).toList(); }

    /** Replaces the @focus_job style placeholders with values from the run's working memory. */
    public Map<String, Object> resolveArgs(Map<String, Object> args, AgentRun run) {
        Map<String, Object> ctx = run == null ? Map.of() : run.getContext();
        Map<String, Object> out = new LinkedHashMap<>();
        if (args == null) return out;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object v = e.getValue();
            if (AgentCommand.FOCUS_JOB.equals(v)) v = ctx.get("focus_job_id");
            else if (AgentCommand.FOCUS_APP.equals(v)) v = ctx.get("focus_application_id");
            else if (AgentCommand.BATCH_JOBS.equals(v)) v = ctx.getOrDefault("batch_job_ids", List.of());
            else if (AgentCommand.DEEP_SELECTION.equals(v)) v = ctx.getOrDefault("deep_ids", List.of());
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    /** Returns the validation message, or null when the arguments satisfy the tool's schema. */
    public String schemaError(String tool, Map<String, Object> args) {
        CommandType type = catalog.get(tool);
        if (type == null) return "unknown tool '" + tool + "'";
        Set<ValidationMessage> errors = schemas.getSchema(type.argsSchema()).validate(Json.MAPPER.valueToTree(args));
        return errors.isEmpty() ? null : errors.iterator().next().getMessage();
    }

    public AgentCommand createCommand(Map<String, Object> step, User user, AgentRun run) {
        @SuppressWarnings("unchecked") Map<String, Object> raw = (Map<String, Object>) step.get("args");
        Map<String, Object> args = resolveArgs(raw, run);
        String tool = String.valueOf(step.get("tool"));
        String error = schemaError(tool, args);
        if (error != null) throw new ValidationException("bad arguments for " + tool + ": " + error);
        return catalog.get(tool).factory().create(services, user, args, run);
    }

    /** Runs one command. Tool bugs never crash the run: they come back as a failed observation the reasoner can handle. */
    public Observation execute(AgentCommand command, int stepIndex, AgentActionRecord[] recordOut) {
        Observation obs;
        try {
            obs = command.execute();
        } catch (DomainException e) {
            obs = Observation.failed(command.name() + " failed", e.getMessage());
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            obs = Observation.failed(command.name() + " failed", msg.length() > 300 ? msg.substring(0, 300) : msg);
        }
        AgentActionRecord r = new AgentActionRecord();
        r.setUser(command.user());
        r.setRun(command.run());
        r.setStepIndex(stepIndex);
        r.setCommandName(command.name());
        r.setArguments(command.arguments());
        r.setDescription(command.describe());
        r.setObservationSummary(obs.summary);
        r.setObservation(obs.toMap());
        r.setReversible(command.type().reversible() && obs.ok);
        r.setUndoData(obs.undoData);
        r.setStatus(obs.ok ? AgentActionRecord.EXECUTED : AgentActionRecord.FAILED);
        actions.save(r);
        if (recordOut != null && recordOut.length > 0) recordOut[0] = r;
        return obs;
    }

    public Observation execute(AgentCommand command, int stepIndex) { return execute(command, stepIndex, null); }

    public AgentActionRecord recordRejection(AgentCommand command, int stepIndex, String reason) {
        AgentActionRecord r = new AgentActionRecord();
        r.setUser(command.user());
        r.setRun(command.run());
        r.setStepIndex(stepIndex);
        r.setCommandName(command.name());
        r.setArguments(command.arguments());
        r.setDescription(command.describe());
        r.setStatus(AgentActionRecord.REJECTED);
        r.setObservationSummary("rejected by the student" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        return actions.save(r);
    }

    public AgentActionRecord undo(User user, UUID recordId) {
        AgentActionRecord record = actions.findByIdAndUser(recordId, user).orElseThrow(() -> new NotFoundException("action not found"));
        if (!record.isReversible() || !AgentActionRecord.EXECUTED.equals(record.getStatus()))
            throw new DomainException("not_reversible", 400, "this action cannot be undone");
        catalog.get(record.getCommandName()).factory().create(services, user, record.getArguments(), record.getRun()).undo(record.getUndoData());
        record.markUndone();
        return actions.save(record);
    }

    @Transactional(readOnly = true)
    public List<AgentActionRecord> actionsOf(AgentRun run) { return actions.findByRunOrderByExecutedAtAsc(run); }
}
