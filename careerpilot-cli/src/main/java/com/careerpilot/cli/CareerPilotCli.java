package com.careerpilot.cli;

import com.careerpilot.common.DomainException;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.llm.UsageTracker;
import com.careerpilot.users.User;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

/** Entry point of the command-line interface. Every command runs the same core services as the GUI. */
@Command(name = "careerpilot", mixinStandardHelpOptions = true, version = "CareerPilot 2.0.0",
        description = "Adaptive AI career and application management agent. CareerPilot prepares and tracks; you always submit.",
        subcommands = {AuthCommands.Register.class, AuthCommands.Login.class, AuthCommands.Logout.class, AuthCommands.Whoami.class,
                AuthCommands.ChangePassword.class, ProfileCommands.Profile.class, JobCommands.Jobs.class, AppCommands.Apps.class,
                AppCommands.Docs.class, AgentCommands.Agent.class, EmailCommands.Email.class, CareerPilotCli.LlmCheck.class})
public class CareerPilotCli implements Callable<Integer> {
    @Option(names = "--json", scope = CommandLine.ScopeType.INHERIT, description = "print machine readable JSON where supported")
    void setJson(boolean on) { Out.json = on; }

    @Override public Integer call() {
        new CommandLine(this).usage(Out.out);
        return 0;
    }

    @Command(name = "llm-check", description = "Check that the configured AI model answers, and show today's token use.")
    static class LlmCheck implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            try {
                var result = CliRuntime.bean(LlmClient.class).generateStructured(u, "system.ping", Map.of("word", "careerpilot"));
                Out.line("Model %s answered (%d tokens, %d attempt(s)).", result.model(), result.tokens(), result.attempts());
            } catch (LlmException e) {
                Out.line("The AI model is not reachable: %s", e.getMessage());
                Out.line("Check LLM_PROVIDER, LLM_API_KEY and LLM_MODEL in .env.");
                return 1;
            }
            Out.line("Tokens used in the last 24 hours: %d", CliRuntime.bean(UsageTracker.class).usedToday(u));
            return 0;
        }
    }

    public static CommandLine commandLine() {
        CommandLine cmd = new CommandLine(new CareerPilotCli());
        cmd.setExecutionExceptionHandler((ex, c, parse) -> {
            if (ex instanceof DomainException d) {
                c.getErr().println("error: " + d.getMessage());
                Object fields = d.details().get("fields");
                if (fields instanceof Map<?, ?> m) m.forEach((k, v) -> c.getErr().println("  " + k + ": " + v));
                if (d.details().get("allowed_actions") != null) c.getErr().println("  allowed: " + d.details().get("allowed_actions"));
                return d.httpStatus() == 401 ? 3 : 1;
            }
            if (ex instanceof IllegalArgumentException) {
                c.getErr().println("error: " + ex.getMessage());
                return 2;
            }
            c.getErr().println("unexpected error: " + ex);
            return 70;
        });
        cmd.setParameterExceptionHandler((ex, args) -> {
            ex.getCommandLine().getErr().println("error: " + ex.getMessage());
            ex.getCommandLine().usage(ex.getCommandLine().getErr());
            return 2;
        });
        return cmd;
    }

    public static void main(String[] args) {
        int code = commandLine().execute(args);
        CliRuntime.shutdown();
        System.exit(code);
    }
}
