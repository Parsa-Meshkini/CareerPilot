package com.careerpilot.cli;

import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.MockLlmProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real command line (picocli) against the real core services, an in-memory database and the mock LLM. */
class CliTest {
    static MockLlmProvider mock = new MockLlmProvider();
    static Path home;
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    StringWriter stderr = new StringWriter();

    @BeforeAll
    static void boot(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path dir) {
        home = dir;
        System.setProperty("careerpilot.home", dir.toString());
        System.setProperty("spring.datasource.url", "jdbc:h2:mem:cli-test;DB_CLOSE_DELAY=-1");
        System.setProperty("spring.config.import", "");
        System.setProperty("careerpilot.llm.provider", "mock");
        System.setProperty("careerpilot.storage-root", dir.resolve("storage").toString());
        CliRuntime.bean(LlmClient.class).useProvider(mock, ms -> { });
    }

    @AfterAll
    static void shutdown() { CliRuntime.shutdown(); }

    @BeforeEach
    void freshOutput() {
        Out.out = new PrintStream(stdout);
        Out.json = false;
    }

    int run(String... args) {
        stdout.reset();
        Out.json = false;
        stderr = new StringWriter();
        CommandLine cmd = CareerPilotCli.commandLine();
        cmd.setOut(new PrintWriter(stdout, true));
        cmd.setErr(new PrintWriter(stderr, true));
        return cmd.execute(args);
    }

    String out() { return stdout.toString(); }

    private int signUp() {
        return run("register", "--email", "cli-" + System.nanoTime() + "@yorku.ca", "--password", "password123", "--name", "CLI Student");
    }

    private void profile() {
        run("profile", "fact-add", "SKILL", "name=python");
        run("profile", "fact-add", "SKILL", "name=django");
        run("profile", "fact-add", "EXPERIENCE", "title=Developer Intern", "organization=Acme", "start_date=2025-05", "end_date=2025-08", "bullets=Built a REST API with Docker");
        run("profile", "goal-set", "--type", "COOP", "--term", "Summer 2027", "--roles", "backend");
    }

    @Test
    void registerWhoamiAndGoalFlow() {
        assertEquals(0, signUp());
        assertEquals(0, run("whoami"));
        assertTrue(out().contains("@yorku.ca"));
        assertEquals(0, run("profile", "fact-add", "SKILL", "name=python"));
        assertEquals(0, run("profile", "goal-set", "--type", "COOP", "--term", "Summer 2027", "--roles", "backend,sre"));
        assertEquals(0, run("profile", "goal-show"));
        assertTrue(out().contains("coop_internship"));
        assertEquals(0, run("profile", "show"));
        assertTrue(out().contains("No verified education yet"));
    }

    @Test
    void errorsAreReadableAndSetTheExitCode() {
        run("logout");
        assertEquals(3, run("whoami"));
        assertTrue(stderr.toString().contains("not signed in"));
        signUp();
        assertEquals(1, run("profile", "fact-add", "EXPERIENCE", "title=Dev"));
        assertTrue(stderr.toString().contains("organization: required"));
        assertEquals(2, run("profile", "fact-add"));
    }

    @Test
    void importScoreTrackAndUndo(@TempDir Path tmp) throws Exception {
        signUp();
        profile();
        Path csv = tmp.resolve("jobs.csv");
        Files.writeString(csv, "title,company,location,deadline,work_term,description\n"
                + "Backend Developer Co-op,Acme Corp,Toronto,2026-11-20,Summer 2027,\"We need Python and Django. Build REST APIs. " + "Python Django. ".repeat(25) + "\"\n"
                + "Retail Clerk,Shop,Toronto,,Fall 2027,Sell shoes.\n");
        assertEquals(0, run("jobs", "import-file", csv.toString()));
        assertTrue(out().contains("2 imported"));
        assertEquals(0, run("jobs", "score"));
        assertEquals(0, run("jobs", "list"));
        String listing = out();
        assertTrue(listing.indexOf("Backend Developer Co-op") < listing.indexOf("Retail Clerk"), "best match first:\n" + listing);

        String jobId = listing.lines().filter(l -> l.contains("Backend")).findFirst().orElseThrow().split("\\s+")[0];
        assertEquals(0, run("apps", "create", jobId));
        String appId = out().replaceAll("(?s).*\\((\\w{8})\\).*", "$1");
        assertEquals(0, run("apps", "act", appId, "submit"));
        assertEquals(1, run("apps", "act", appId, "accept"));
        assertTrue(stderr.toString().contains("cannot accept"));
        assertTrue(stderr.toString().contains("allowed"));
        assertEquals(0, run("apps", "act", appId, "reject"));
        String change = out().replaceAll("(?s).*undo with: careerpilot apps undo (\\w+)\\).*", "$1");
        assertEquals(0, run("apps", "undo", change));
        assertEquals(0, run("apps", "list", "--json"));
        assertTrue(out().contains("\"state\" : \"APPLIED\""));
    }

    @Test
    void packageAndAgentApprovalFlow(@TempDir Path tmp) throws Exception {
        signUp();
        profile();
        Path jobs = tmp.resolve("jobs.json");
        Files.writeString(jobs, "[{\"title\":\"Backend Developer Co-op\",\"company\":\"Acme Corp\",\"work_term\":\"Summer 2027\",\"location\":\"Toronto\","
                + "\"description\":\"" + "We need Python and Django. Build REST APIs. ".repeat(10) + "\"}]");
        assertEquals(0, run("jobs", "import-file", jobs.toString()));
        run("jobs", "list");
        String jobId = out().lines().filter(l -> l.contains("Backend")).findFirst().orElseThrow().split("\\s+")[0];

        run("profile", "fact-list");
        String expId = out().lines().filter(l -> l.contains("EXPERIENCE")).findFirst().orElseThrow().split("\\s+")[0];
        String fullExp = null;
        run("--json", "profile", "fact-list");
        fullExp = out().lines().filter(l -> l.contains("\"id\"")).map(l -> l.replaceAll(".*\"(\\w{8}-[\\w-]+)\".*", "$1")).filter(i -> i.startsWith(expId)).findFirst().orElseThrow();

        mock.script("job.analyze", Map.of("required_skills", List.of("python", "django"), "preferred_skills", List.of(), "responsibilities", List.of(),
                "seniority", "intern", "keywords", List.of("rest"), "red_flags", List.of()));
        mock.script("match.explain", Map.of("explanation", "Good fit.", "suggestions", List.of()));
        mock.script("resume.tailor", Map.of("entries", List.of(Map.of("fact_id", fullExp, "bullets", List.of(Map.of("text", "Built a REST API with Docker", "source_fact_ids", List.of(fullExp))))), "skills", List.of("python")));
        mock.script("cover_letter.tailor", Map.of("greeting", "Dear Hiring Manager,", "closing", "Sincerely,",
                "paragraphs", List.of(Map.of("text", "I built a REST API with Docker at Acme.", "source_fact_ids", List.of(fullExp)))));

        // the agent pauses before the side-effecting step and waits for the student
        mock.script("agent.plan", Map.of("steps", List.of(
                Map.of("tool", "match_job", "args", Map.of("job_id", jobId.length() == 8 ? "@focus_job" : jobId), "intent", "check fit"),
                Map.of("tool", "build_package", "args", Map.of("job_id", "@focus_job"), "intent", "prepare documents"))));
        assertEquals(0, run("agent", "run", "Prepare my application for the Acme backend co-op", "--job", jobId));
        assertTrue(out().contains("Approval needed"), out());
        String runId = out().lines().filter(l -> l.startsWith("Run ")).findFirst().orElseThrow().split("\\s+")[1];
        assertEquals(0, run("agent", "approve", runId));
        assertTrue(out().contains("COMPLETED"), out());

        run("apps", "list");
        String appId = out().lines().filter(l -> l.contains("PREPARED")).findFirst().orElseThrow().split("\\s+")[0];
        Path zip = tmp.resolve("package.zip");
        assertEquals(0, run("docs", "package", appId, "--out", zip.toString()));
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertEquals(7, z.size());
        }
    }

    @Test
    void emailUploadAndReview(@TempDir Path tmp) throws Exception {
        signUp();
        Path eml = tmp.resolve("reject.eml");
        Files.writeString(eml, "From: jobs@example.org\r\nSubject: Update\r\nDate: Mon, 02 Nov 2026 09:12:00 -0500\r\nMessage-ID: <cli1@x>\r\n\r\nUnfortunately we are not moving forward.");
        assertEquals(0, run("email", "upload", eml.toString()));
        assertTrue(out().contains("1 waiting for your review"));
        assertEquals(0, run("email", "events"));
        assertTrue(out().contains("REJECTION"));
        String id = out().lines().filter(l -> l.contains("REJECTION")).findFirst().orElseThrow().split("\\s+")[0];
        assertEquals(1, run("email", "confirm", id));
        assertTrue(stderr.toString().contains("choose which application"));
        assertEquals(0, run("email", "dismiss", id));
    }

    @Test
    void llmCheckReportsTheModel() {
        signUp();
        assertEquals(0, run("llm-check"));
        assertTrue(out().contains("mock-1"));
        assertTrue(Files.exists(home.resolve("session")));
    }
}
