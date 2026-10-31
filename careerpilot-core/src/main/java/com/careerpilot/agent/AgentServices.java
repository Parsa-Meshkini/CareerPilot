package com.careerpilot.agent;

import com.careerpilot.applications.ApplicationService;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.documents.PackageAssembler;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobMatchRepository;
import com.careerpilot.jobs.JobPostingRepository;
import com.careerpilot.jobs.MatchingService;
import org.springframework.stereotype.Component;

/** The receivers that commands act on, gathered so a command only needs this one dependency. */
@Component
public class AgentServices {
    public final JobImportService imports;
    public final JobPostingRepository jobs;
    public final JobMatchRepository matches;
    public final JobAnalysisService analysis;
    public final MatchingService matching;
    public final ApplicationService applications;
    public final PackageAssembler packages;
    public final ContextBuilder contexts;
    public final MemoryManager memory;
    public final CandidateService candidates;

    public AgentServices(JobImportService imports, JobPostingRepository jobs, JobMatchRepository matches, JobAnalysisService analysis,
                         MatchingService matching, ApplicationService applications, PackageAssembler packages, ContextBuilder contexts,
                         MemoryManager memory, CandidateService candidates) {
        this.imports = imports;
        this.jobs = jobs;
        this.matches = matches;
        this.analysis = analysis;
        this.matching = matching;
        this.applications = applications;
        this.packages = packages;
        this.contexts = contexts;
        this.memory = memory;
        this.candidates = candidates;
    }
}
