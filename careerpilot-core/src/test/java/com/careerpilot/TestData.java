package com.careerpilot;

import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.FactType;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.RawJobRecord;
import com.careerpilot.jobs.SourceType;
import com.careerpilot.users.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Builders for the profile and job data most tests need (the Java twin of the old Python factories). */
@Component
public class TestData {
    @Autowired CandidateService candidates;
    @Autowired JobImportService imports;

    public void makeProfile(User user, String goalType, String workTerm, String roles, String... skills) {
        for (String s : skills) candidates.addFact(user, FactType.SKILL, Map.of("name", s));
        candidates.addFact(user, FactType.EXPERIENCE, Map.of("organization", "Acme", "title", "Developer Intern",
                "start_date", "2025-05", "end_date", "2025-08", "bullets", List.of("Built a REST API with Docker")));
        candidates.addFact(user, FactType.EDUCATION, Map.of("institution", "York University", "program", "Computer Science",
                "credential", "BSc", "start_date", "2023-09"));
        candidates.updateCareerGoal(user, Map.of("goal_type", goalType, "work_term", workTerm, "target_roles", roles));
    }

    public void makeProfile(User user) { makeProfile(user, "COOP", "Summer 2027", "backend", "python", "django", "sql"); }

    public JobPosting makeJob(User user, String title, String company, String workTerm, String description) {
        RawJobRecord raw = new RawJobRecord(SourceType.MANUAL);
        raw.title = title;
        raw.company = company;
        raw.workTerm = workTerm;
        raw.description = description;
        raw.fullDetail = true;
        raw.location = "Toronto";
        return imports.importRecord(user, raw, null).job();
    }

    public JobPosting makeJob(User user) {
        return makeJob(user, "Backend Developer Co-op", "Acme Corp", "Summer 2027",
                "We need Python, Django and AWS experience. Build REST APIs. ".repeat(10));
    }
}
