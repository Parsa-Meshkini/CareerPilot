package com.careerpilot.cli;

import com.careerpilot.applications.Application;
import com.careerpilot.interviews.InterviewPrepPlan;
import com.careerpilot.interviews.InterviewPrepService;
import com.careerpilot.interviews.MockInterviewService;
import com.careerpilot.interviews.MockInterviewSession;
import com.careerpilot.users.User;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** F17 interview preparation and F18 mock interview. */
public final class InterviewCommands {
    private InterviewCommands() {}

    static InterviewPrepService prep() { return CliRuntime.bean(InterviewPrepService.class); }

    static MockInterviewService mock() { return CliRuntime.bean(MockInterviewService.class); }

    @Command(name = "interview", description = "Prepare for an interview and practise with a mock interview (F17, F18).",
            subcommands = {Prepare.class, Show.class, Mock.class, Answer.class, Finish.class, Sessions.class})
    public static class Interview implements Callable<Integer> {
        @Override public Integer call() { new picocli.CommandLine(this).usage(Out.out); return 0; }
    }

    static void print(InterviewPrepPlan p) {
        if (Out.json) {
            Out.json(Map.of("source", p.getSource(), "version", p.getVersion(), "content", p.getContent(), "notes", p.getNotes()));
            return;
        }
        Out.line("Preparation plan v%d (%s)", p.getVersion(), p.getSource());
        for (Object o : (List<?>) p.getContent().getOrDefault("questions", List.of())) {
            Map<?, ?> q = (Map<?, ?>) o;
            Out.line("  [%s] %s", q.get("category"), q.get("question"));
            Out.line("      why: %s", q.get("rationale"));
        }
        for (Object o : (List<?>) p.getContent().getOrDefault("star_stories", List.of())) {
            Map<?, ?> s = (Map<?, ?>) o;
            Out.line("  STAR: %s", s.get("title"));
            Out.line("      S: %s\n      T: %s\n      A: %s\n      R: %s", s.get("situation"), s.get("task"), s.get("action"), s.get("result"));
        }
        for (Object c : (List<?>) p.getContent().getOrDefault("checklist", List.of())) Out.line("  [ ] %s", c);
        if (!p.getNotes().isEmpty()) Out.line("Note: %s", p.getNotes());
    }

    @Command(name = "prepare", description = "Build a preparation plan: likely questions, STAR stories from your verified facts, checklist.")
    static class Prepare implements Callable<Integer> {
        @Parameters(index = "0", description = "application id") String app;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            print(prep().preparePlan(u, AppCommands.appId(u, app)));
            return 0;
        }
    }

    @Command(name = "show", description = "Show the latest preparation plan for an application.")
    static class Show implements Callable<Integer> {
        @Parameters(index = "0") String app;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            InterviewPrepPlan p = prep().latest(u, AppCommands.appId(u, app));
            if (p == null) Out.line("No plan yet. Run: careerpilot interview prepare %s", app);
            else print(p);
            return 0;
        }
    }

    private static void printTurn(MockInterviewSession s) {
        Map<String, Object> pending = s.pendingTurn();
        if (pending != null) Out.line("Question %s of %d: %s", pending.get("n"), MockInterviewSession.MAX_QUESTIONS, pending.get("question"));
    }

    @Command(name = "mock", description = "Start a text mock interview; answer with: careerpilot interview answer <session> \"...\" or use --interactive.")
    static class Mock implements Callable<Integer> {
        @Parameters(index = "0") String app;
        @Option(names = "--interactive", description = "answer the questions here, one line each") boolean interactive;

        @Override public Integer call() throws Exception {
            User u = CliRuntime.currentUser();
            MockInterviewSession s = mock().startSession(u, AppCommands.appId(u, app));
            Out.line("Mock interview %s started.", Out.shortId(s.getId()));
            printTurn(s);
            if (!interactive) {
                Out.line("Answer with: careerpilot interview answer %s \"your answer\"", Out.shortId(s.getId()));
                return 0;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            while (s.getStatus() == MockInterviewSession.Status.IN_PROGRESS) {
                Out.out.print("> ");
                Out.out.flush();
                String line = in.readLine();
                if (line == null || line.equals("/quit")) break;
                MockInterviewService.AnswerResult r = mock().submitAnswer(u, s.getId(), line);
                Out.line("Feedback: %s", r.feedback());
                s = r.session();
                if (r.finished()) {
                    summary(s);
                    return 0;
                }
                printTurn(s);
            }
            if (s.getStatus() == MockInterviewSession.Status.IN_PROGRESS) summary(mock().finishSession(u, s.getId()));
            return 0;
        }
    }

    static void summary(MockInterviewSession s) {
        Out.line("Summary: %s", s.getSummary().get("overall"));
        Out.line("Average scores: %s (overall %s/5)", s.getSummary().get("averages"), s.getSummary().get("overall_score"));
        ((List<?>) s.getSummary().getOrDefault("improvements", List.of())).forEach(i -> Out.line("  improve: %s", i));
    }

    @Command(name = "answer", description = "Answer the pending mock-interview question.")
    static class Answer implements Callable<Integer> {
        @Parameters(index = "0") String session;
        @Parameters(index = "1") String answer;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            UUID id = Ids.resolve(session, allSessions(u), MockInterviewSession::getId);
            MockInterviewService.AnswerResult r = mock().submitAnswer(u, id, answer);
            Out.line("%s%s", r.scored() ? "Feedback: " : "", r.feedback());
            if (r.finished()) summary(r.session());
            else printTurn(r.session());
            return 0;
        }
    }

    @Command(name = "finish", description = "End a mock interview now and get the summary.")
    static class Finish implements Callable<Integer> {
        @Parameters(index = "0") String session;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            summary(mock().finishSession(u, Ids.resolve(session, allSessions(u), MockInterviewSession::getId)));
            return 0;
        }
    }

    static List<MockInterviewSession> allSessions(User u) {
        return CliRuntime.bean(com.careerpilot.applications.ApplicationService.class).listApplications(u, null).stream()
                .flatMap(a -> mock().listForApplication(u, a.getId()).stream()).toList();
    }

    @Command(name = "sessions", description = "List mock interview sessions.")
    static class Sessions implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            List<List<String>> rows = new java.util.ArrayList<>();
            List<Map<String, Object>> js = new java.util.ArrayList<>();
            for (MockInterviewSession s : allSessions(u)) {
                Application a = s.getApplication();
                rows.add(List.of(Out.shortId(s.getId()), s.getStatus().name(), s.getTurns().size() + " turns", a.getJob().getTitle() + " at " + a.getJob().getCompany()));
                js.add(Map.of("id", s.getId().toString(), "status", s.getStatus().name(), "turns", s.getTurns().size()));
            }
            Out.table(List.of("ID", "STATUS", "TURNS", "APPLICATION"), rows, js);
            return 0;
        }
    }
}
