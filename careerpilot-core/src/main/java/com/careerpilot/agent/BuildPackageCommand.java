package com.careerpilot.agent;

import com.careerpilot.applications.Application;
import com.careerpilot.documents.ApplicationPackage;
import com.careerpilot.users.User;

import java.util.Map;

/** Not reversible: documents are versioned, so older versions stay available. */
public class BuildPackageCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("build_package", "Generate the tailored resume, cover letter and application package ZIP for a job.",
            "{\"type\":\"object\",\"required\":[\"job_id\"],\"properties\":{\"job_id\":" + JOB_ID_SCHEMA
                    + ",\"tone\":{\"type\":\"string\",\"enum\":[\"formal\",\"warm\"]}}}", true, false, 6000, BuildPackageCommand::new);

    public BuildPackageCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        Application app = svc.applications.getOrCreateForJob(user, jobId());
        ApplicationPackage pkg = svc.packages.buildPackage(user, app.getId(), false, String.valueOf(args.getOrDefault("tone", "formal")), run);
        setFocus(null, app.getId());
        return Observation.ok("package " + pkg.getFolderName() + ".zip ready" + (pkg.isNeedsReview() ? " (some content flagged for review)" : ""),
                map("package_id", pkg.getId().toString(), "application_id", app.getId().toString(), "needs_review", pkg.isNeedsReview()));
    }
}
