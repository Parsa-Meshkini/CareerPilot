package com.careerpilot.jobs;

import java.util.ArrayList;
import java.util.List;

/** The single shape every source adapter produces and JobImportService consumes. */
public class RawJobRecord {
    public SourceType sourceType;
    public String title = "", company = "", location = "", description = "";
    public String externalId;
    public String sourceUrl = "", applicationUrl = "", deadlineText = "", workTerm = "", jobType = "";
    public String workModelText = "", salaryText = "", contact = "";
    public List<String> requiredDocuments = new ArrayList<>();
    public boolean fullDetail;
    public List<String> warnings = new ArrayList<>();

    public RawJobRecord(SourceType sourceType) { this.sourceType = sourceType; }
}
