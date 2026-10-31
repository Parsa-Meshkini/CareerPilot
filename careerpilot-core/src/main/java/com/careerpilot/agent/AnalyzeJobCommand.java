package com.careerpilot.agent;

import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.users.User;

import java.util.Map;

public class AnalyzeJobCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("analyze_job", "Run AI analysis of a posting (skills, responsibilities, seniority, red flags).",
            "{\"type\":\"object\",\"required\":[\"job_id\"],\"properties\":{\"job_id\":" + JOB_ID_SCHEMA + "}}", false, false, 2500, AnalyzeJobCommand::new);

    public AnalyzeJobCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        JobAnalysis a = svc.analysis.analyzeJob(user, jobId(), run);
        String note = a.isFallback() ? " (keyword fallback)" : "";
        String skills = String.join(", ", a.getRequiredSkills().stream().limit(6).toList());
        return Observation.ok("requires " + (skills.isEmpty() ? "no clear skills" : skills) + note,
                map("required_skills", a.getRequiredSkills(), "red_flags", a.getRedFlags(), "partial", a.isPartial()));
    }
}
