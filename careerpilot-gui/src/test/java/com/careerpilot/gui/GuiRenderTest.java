package com.careerpilot.gui;

import com.careerpilot.CareerPilotCore;
import com.careerpilot.agent.AgentController;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.FactType;
import com.careerpilot.integrations.EmailSyncService;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.MatchingService;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.MockLlmProvider;
import com.careerpilot.users.AuthService;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds every page of the real main window against seeded data and writes a PNG of each to target/screenshots.
 * Skipped when no display is available (CI).
 */
class GuiRenderTest {
    @Test
    void everyPageRendersWithSeededData() throws Exception {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "needs a display");
        System.setProperty("spring.datasource.url", "jdbc:h2:mem:gui-test;DB_CLOSE_DELAY=-1");
        System.setProperty("spring.config.import", "");
        System.setProperty("careerpilot.llm.provider", "mock");
        System.setProperty("careerpilot.storage-root", "target/gui-storage");
        var ctx = new SpringApplicationBuilder(CareerPilotCore.class).web(WebApplicationType.NONE).headless(false).logStartupInfo(false)
                .properties("logging.level.root=ERROR").run();
        AppContext app = new AppContext(ctx);
        MockLlmProvider mock = new MockLlmProvider();
        ctx.getBean(LlmClient.class).useProvider(mock, ms -> { });
        mock.script("job.analyze", Map.of("required_skills", List.of("python", "django", "aws"), "preferred_skills", List.of("docker"), "responsibilities", List.of("Build REST APIs"),
                "seniority", "intern", "keywords", List.of("rest"), "red_flags", List.of(), "summary", "Backend co-op."));
        mock.script("match.explain", Map.of("explanation", "Strong backend fit; AWS is the main gap.", "suggestions", List.of(Map.of("skill", "aws", "suggestion", "Deploy a small Django app to AWS."))));

        var user = ctx.getBean(AuthService.class).register("gui@my.yorku.ca", "password123", "Gui Student");
        app.signIn(user);
        CandidateService candidates = ctx.getBean(CandidateService.class);
        candidates.updateProfile(user, Map.of("full_name", "Gui Student", "headline", "CS student", "location", "Toronto"));
        for (String s : List.of("python", "django", "sql")) candidates.addFact(user, FactType.SKILL, Map.of("name", s));
        candidates.addFact(user, FactType.EXPERIENCE, Map.of("organization", "Acme", "title", "Developer Intern", "start_date", "2025-05", "end_date", "2025-08",
                "bullets", List.of("Built a REST API with Docker")));
        candidates.updateCareerGoal(user, Map.of("goal_type", "COOP", "work_term", "Summer 2027", "target_roles", "backend"));
        JobImportService imports = ctx.getBean(JobImportService.class);
        imports.createBatch(user, "file", Map.of("csv", "title,company,location,deadline,work_term,description\n"
                + "Backend Developer Co-op,Acme Corp,Toronto,2026-11-20,Summer 2027,\"We need Python, Django and AWS. " + "Build REST APIs. ".repeat(20) + "\"\n"
                + "Data Analyst Intern,Northwind,Remote,2026-12-01,Summer 2027,\"SQL and Excel. " + "Analyse data. ".repeat(20) + "\"\n"));
        JobPosting job = imports.listJobs(user, "Backend", null, null, false).get(0);
        ctx.getBean(MatchingService.class).matchJob(user, job.getId(), true);
        ctx.getBean(ApplicationService.class).createApplication(user, job.getId(), "");
        ctx.getBean(EmailSyncService.class).syncAccount(user, ctx.getBean(EmailSyncService.class).connectMock(user).getId());
        AgentController agent = ctx.getBean(AgentController.class);
        mock.script("agent.plan", Map.of("steps", List.of(Map.of("tool", "search_jobs", "args", Map.of("query", "acme backend"), "intent", "find it"),
                Map.of("tool", "build_package", "args", Map.of("job_id", "@focus_job"), "intent", "prepare documents"))));
        agent.runGoal(user, "Prepare my application for the Acme backend co-op");

        Path out = Path.of("target", "screenshots");
        Files.createDirectories(out);
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        assertTrue(started.await(20, TimeUnit.SECONDS));

        AtomicReference<MainView> main = new AtomicReference<>();
        runFx(() -> {
            MainView view = new MainView(app, () -> { });
            Scene scene = new Scene(view, 1180, 760);
            scene.getStylesheets().add(getClass().getResource("/gui/styles.css").toExternalForm());
            new javafx.stage.Stage().setScene(scene);
            main.set(view);
        });
        for (String page : List.of("Dashboard", "Profile", "Jobs", "Tracker", "Agent", "Email", "Settings")) {
            runFx(() -> main.get().open(page));
            Thread.sleep(900);  // background loads finish
            runFx(() -> {
                if (page.equals("Jobs")) selectFirst(main.get());
                WritableImage img = main.get().snapshot(null, null);
                try {
                    javax.imageio.ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out.resolve(page.toLowerCase() + ".png").toFile());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(Files.size(out.resolve(page.toLowerCase() + ".png")) > 5_000, page + " rendered");
        }
        ctx.close();
    }

    private static void selectFirst(MainView main) {
        javafx.scene.Node center = main.getCenter();
        center.lookupAll(".table-view").stream().findFirst().ifPresent(t -> ((javafx.scene.control.TableView<?>) t).getSelectionModel().selectFirst());
    }

    private static void runFx(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
