package com.careerpilot.agent;

import com.careerpilot.users.User;

import java.util.List;
import java.util.Map;

public class RetrieveContextCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("retrieve_context", "Load the student's verified profile summary, goal and relevant memories.",
            "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}", false, false, 0, RetrieveContextCommand::new);

    public RetrieveContextCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        CandidateContext ctx = svc.contexts.buildCandidateContext(user, false, true);
        String topic = String.valueOf(args.getOrDefault("topic", ""));
        if (topic.isBlank() && run != null) topic = run.getGoalText();
        List<String> memories = svc.memory.recall(user, topic, 5).stream().map(AgentMemoryEntry::getContent).toList();
        return Observation.ok(ctx.summaryText(), map("memories", memories, "verified_facts", ctx.facts.size()));
    }
}
