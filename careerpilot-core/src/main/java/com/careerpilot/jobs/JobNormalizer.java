package com.careerpilot.jobs;

import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Cleans a RawJobRecord into a JobPosting (F06): trims, parses the deadline, detects the work model, fingerprints. */
@Component
public class JobNormalizer {
    static final int FULL_MIN_CHARS = 300;
    private static final List<DateTimeFormatter> FORMATS = List.of(
            "yyyy-MM-dd", "yyyy/MM/dd", "MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy", "d MMM yyyy", "M/d/yyyy",
            "d/M/yyyy", "MMMM d yyyy", "MMM d yyyy").stream().map(p -> DateTimeFormatter.ofPattern(p, Locale.ENGLISH)).toList();

    public record Result(JobPosting job, List<String> warnings) {}

    public static LocalDate parseDeadline(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = text.strip().replaceAll("(\\d)(st|nd|rd|th)\\b", "$1");
        cleaned = cleaned.replaceAll("(?i)T\\d{1,2}:\\d{2}.*$", "");
        cleaned = Pattern.compile("\\s+(at\\s+)?\\d{1,2}:\\d{2}.*$", Pattern.CASE_INSENSITIVE).matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("^[A-Za-z]+day,?\\s+", "");
        for (DateTimeFormatter f : FORMATS) {
            try {
                return LocalDate.parse(cleaned, f);
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }

    public static String detectWorkModel(String... texts) {
        StringBuilder sb = new StringBuilder();
        for (String t : texts) if (t != null && !t.isBlank()) sb.append(t).append(' ');
        String blob = sb.toString().toLowerCase();
        if (blob.contains("hybrid")) return "HYBRID";
        if (Pattern.compile("\\bremote\\b|work from home|wfh").matcher(blob).find()) return "REMOTE";
        if (Pattern.compile("on-?site|in[- ]person|in office").matcher(blob).find()) return "ONSITE";
        return "UNKNOWN";
    }

    public static String clean(String text) {
        String t = (text == null ? "" : text).replace("\r\n", "\n").replace(' ', ' ');
        t = t.replaceAll("[ \\t]+", " ");
        return t.replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    public Result normalize(RawJobRecord raw, User user) {
        JobPosting job = new JobPosting();
        job.setUser(user);
        job.setSourceType(raw.sourceType);
        job.setExternalId(raw.externalId == null || raw.externalId.isBlank() ? null : raw.externalId);
        job.setSourceUrl(cut(raw.sourceUrl, 500));
        job.setTitle(cut(clean(raw.title), 200));
        job.setCompany(cut(clean(raw.company), 200));
        job.setLocation(cut(clean(raw.location), 200));
        job.setJobType(cut(clean(raw.jobType), 60));
        job.setSalaryText(cut(clean(raw.salaryText), 120));
        job.setWorkTerm(cut(clean(raw.workTerm), 60));
        job.setDescription(clean(raw.description));
        job.setApplicationUrl(cut(raw.applicationUrl, 500));
        job.setContact(cut(clean(raw.contact), 200));
        job.setRequiredDocuments(new ArrayList<>(raw.requiredDocuments));
        job.setDeadlineText(cut(clean(raw.deadlineText), 80));
        job.setDeadline(parseDeadline(job.getDeadlineText()));
        List<String> warnings = new ArrayList<>(raw.warnings);
        if (!job.getDeadlineText().isEmpty() && job.getDeadline() == null)
            warnings.add("could not parse deadline '" + job.getDeadlineText() + "'");
        job.setWorkModel(detectWorkModel(raw.workModelText, job.getLocation(),
                cut(job.getDescription(), 2000)));
        boolean full = raw.fullDetail && job.getDescription().length() >= FULL_MIN_CHARS;
        job.setCompleteness(full ? JobPosting.Completeness.FULL : JobPosting.Completeness.SUMMARY);
        job.computeFingerprint();
        return new Result(job, warnings);
    }
}
