package com.careerpilot.candidates;

import com.careerpilot.IntegrationTest;
import com.careerpilot.common.NotFoundException;
import com.careerpilot.common.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CandidateServiceTest extends IntegrationTest {
    @Autowired CandidateService candidates;

    private static final String RESUME = "Jane Doe\nEducation\nYork University BSc Computer Science 2023-2027\n"
            + "Experience\nSoftware Intern at Acme Corp 2025-05 to 2025-08\n- Built REST APIs in Java\nSkills: Java, Python, SQL";

    @Test
    void manualFactsAreVerifiedImmediately() {
        ProfileFact f = candidates.addFact(user, FactType.SKILL, Map.of("name", "Java"));
        assertTrue(f.isVerified());
        assertEquals("Skill: Java", f.toContextText());
    }

    @Test
    void invalidFactReportsFieldErrors() {
        ValidationException e = assertThrows(ValidationException.class, () ->
                candidates.addFact(user, FactType.EXPERIENCE, Map.of("title", "Dev", "start_date", "2025-09", "end_date", "2025-01")));
        @SuppressWarnings("unchecked") Map<String, String> fields = (Map<String, String>) e.details().get("fields");
        assertEquals("required", fields.get("organization"));
        assertEquals("end date is before start date", fields.get("end_date"));
    }

    @Test
    void factsAreTenantScoped() {
        ProfileFact f = candidates.addFact(user, FactType.SKILL, Map.of("name", "Java"));
        var other = auth.register("other@yorku.ca", "password123", "Other");
        assertThrows(NotFoundException.class, () -> candidates.getFact(other, f.getId()));
    }

    @Test
    void resumeImportExtractsUnverifiedFactsAndReviewFlow() {
        mock.script("resume.extract_facts", Map.of("facts", List.of(
                Map.of("fact_type", "SKILL", "data", Map.of("name", "Java")),
                Map.of("fact_type", "EXPERIENCE", "data", Map.of("title", "Software Intern", "organization", "Acme Corp",
                        "start_date", "2025-05", "end_date", "2025-08", "bullets", List.of("Built REST APIs in Java"))),
                Map.of("fact_type", "EDUCATION", "data", Map.of("institution", "York University", "program", "CS", "start_date", "September 2023"))
        )));
        UploadedResume r = candidates.importResume(user, "resume.txt", RESUME.getBytes());
        assertEquals(UploadedResume.ParseStatus.PARSED, r.getParseStatus());
        assertEquals(3, r.getExtractedCount());

        List<ProfileFact> pending = candidates.listFacts(user, false);
        assertEquals(3, pending.size());
        ProfileFact bad = pending.stream().filter(ProfileFact::isNeedsReview).findFirst().orElseThrow();
        assertTrue(bad.getReviewNote().contains("start_date"));
        assertThrows(ValidationException.class, () -> candidates.verifyFacts(user, List.of(bad.getId()), null));
        assertEquals(1, candidates.verifyFacts(user, List.of(bad.getId()), Map.of(bad.getId(), Map.of("start_date", "2023-09"))));
        assertTrue(candidates.getFact(user, bad.getId()).isVerified());
    }

    @Test
    void scannedPdfWithoutTextFailsWithoutCallingTheLlm() {
        UploadedResume r = candidates.importResume(user, "scan.txt", "tiny".getBytes());
        assertEquals(UploadedResume.ParseStatus.FAILED, r.getParseStatus());
        assertTrue(mock.calls.isEmpty());
    }

    @Test
    void unsupportedFileTypeIsRejected() {
        assertThrows(ValidationException.class, () -> candidates.importResume(user, "resume.exe", new byte[10]));
    }

    @Test
    void careerGoalSelectsStrategyAndResetsWeightsWhenItChanges() {
        assertThrows(ValidationException.class, () -> candidates.updateCareerGoal(user, Map.of("goal_type", "COOP")));
        CareerGoal goal = candidates.updateCareerGoal(user, Map.of("goal_type", "COOP", "work_term", "Summer 2027",
                "target_roles", "Backend Developer, SRE"));
        assertEquals("coop_internship", goal.strategyKey());
        assertEquals(List.of("Backend Developer", "SRE"), goal.getTargetRoles());
        assertEquals(0.45, goal.weights().get("skill"), 1e-9);

        CareerGoal switched = candidates.updateCareerGoal(user, Map.of("goal_type", "NEW_GRAD"));
        assertEquals("entry_level", switched.strategyKey());
        assertEquals(0.25, switched.weights().get("experience"), 1e-9);
    }

    @Test
    void weightAdjustmentsAreBounded() {
        StrategyWeights w = GoalType.defaultsFor("coop_internship");
        w.applyAdjustment("skill", 0.50);
        assertEquals(0.5, w.get("skill"), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> w.applyAdjustment("skill", 0.9));
    }

    @Test
    void completenessHintsGuideTheStudent() {
        assertTrue(candidates.completenessHints(user).contains("Set a career goal so jobs can be scored"));
        assertNotNull(UUID.randomUUID());
    }
}
