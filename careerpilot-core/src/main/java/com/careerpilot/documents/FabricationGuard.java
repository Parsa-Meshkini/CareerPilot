package com.careerpilot.documents;

import com.careerpilot.agent.CandidateContext;
import com.careerpilot.candidates.FactType;
import com.careerpilot.candidates.ProfileFact;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.Skills;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic check that generated text is supported by verified facts (F10). Prompts ask the model to cite fact ids,
 * but prompts are not a guarantee, so every claim is checked here before a document can be marked PASSED.
 */
public class FabricationGuard {
    private static final Pattern DEGREE = Pattern.compile(
            "\\b(b\\.?sc|bachelor'?s?|m\\.?sc|master'?s?|ph\\.?d|mba|b\\.?a|b\\.?eng|diploma|doctorate)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\$?\\d[\\d,]*(?:\\.\\d+)?\\s?(?:%|k|m|x)?(?![\\w])", Pattern.CASE_INSENSITIVE);

    private final CandidateContext ctx;
    private final Map<String, ProfileFact> facts;
    private final String jobText;
    private final Set<String> verifiedSkills = new HashSet<>();
    private final Set<String> orgNames = new HashSet<>();

    public FabricationGuard(CandidateContext ctx, JobPosting job) {
        this.ctx = ctx;
        this.facts = ctx.factIndex();  // verified facts of this user only
        this.jobText = job == null ? "" : (job.getTitle() + " " + job.getCompany() + " " + job.getDescription()).toLowerCase();
        for (ProfileFact f : ctx.factsOf(FactType.SKILL)) verifiedSkills.add(Skills.canonical(String.valueOf(f.getData().getOrDefault("name", ""))));
        for (ProfileFact f : ctx.facts)
            for (String key : List.of("organization", "institution", "issuer")) {
                Object o = f.getData().get(key);
                if (o != null && !o.toString().isBlank()) orgNames.add(o.toString().toLowerCase());
            }
    }

    public VerificationReport check(List<Claim> claims) {
        VerificationReport report = new VerificationReport();
        for (Claim claim : claims) {
            report.checked++;
            if (claim.kind() == Claim.Kind.SKILL) checkSkill(claim, report);
            else checkText(claim, report);
        }
        return report;
    }

    private void checkSkill(Claim claim, VerificationReport report) {
        String c = Skills.canonical(claim.text());
        if (!verifiedSkills.contains(c) && !ctx.skills.contains(c))
            report.violations.add(new Violation(claim.location(), claim.text(), "unverified_skill", "'" + claim.text() + "' is not in your verified skills"));
    }

    @SuppressWarnings("unchecked")
    private void checkText(Claim claim, VerificationReport report) {
        List<String> ids = claim.sourceFactIds() == null ? List.of() : claim.sourceFactIds();
        if (claim.kind() == Claim.Kind.BULLET && ids.isEmpty()) {
            report.violations.add(new Violation(claim.location(), claim.text(), "no_citation", "bullet cites no facts"));
            return;
        }
        List<String> unknown = ids.stream().filter(i -> !facts.containsKey(i)).toList();
        if (!unknown.isEmpty()) {
            report.violations.add(new Violation(claim.location(), claim.text(), "unknown_fact",
                    "cites facts that are not your verified facts: " + String.join(", ", unknown)));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            ProfileFact f = facts.get(id);
            sb.append(f.toContextText()).append(' ');
            if (f.getData().get("technologies") instanceof List<?> techs) techs.forEach(t -> sb.append(t).append(' '));
        }
        String support = sb.toString().toLowerCase();
        if (claim.kind() == Claim.Kind.PARAGRAPH) support += " " + jobText;  // paragraphs may use what the posting says
        String text = claim.text().toLowerCase();
        String supportNoCommas = support.replace(",", "");

        Matcher n = NUMBER.matcher(claim.text());
        while (n.find()) {
            String number = n.group().strip().toLowerCase().replace(",", "");
            if (!number.isEmpty() && !supportNoCommas.contains(number))
                report.violations.add(new Violation(claim.location(), claim.text(), "unsupported_number",
                        "'" + n.group().strip() + "' does not appear in the cited facts"));
        }
        List<String> supportSkills = Skills.find(support);
        for (String skill : Skills.find(claim.text())) {
            if (!support.contains(skill) && !supportSkills.contains(skill))
                report.violations.add(new Violation(claim.location(), claim.text(), "unsupported_skill",
                        "'" + skill + "' is not mentioned in the cited facts"));
        }
        for (String org : orgNames) {
            if (Pattern.compile("\\b" + Pattern.quote(org) + "\\b").matcher(text).find() && !support.contains(org))
                report.violations.add(new Violation(claim.location(), claim.text(), "wrong_organization",
                        "'" + org + "' is not part of the cited facts"));
        }
        Matcher d = DEGREE.matcher(claim.text());
        while (d.find()) {
            String degree = d.group(1).toLowerCase().replace(".", "");
            if (!support.replace(".", "").contains(degree))
                report.violations.add(new Violation(claim.location(), claim.text(), "unsupported_degree",
                        "'" + d.group(1) + "' is not in the cited facts"));
        }
    }
}
