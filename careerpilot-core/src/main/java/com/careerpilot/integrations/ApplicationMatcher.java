package com.careerpilot.integrations;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds which open application an email is about, from the sender domain, company and role names. */
@Component
public class ApplicationMatcher {
    static final Set<String> TERMINAL = Set.of("ACCEPTED", "DECLINED", "REJECTED", "WITHDRAWN");
    static final Set<String> STOP = Set.of("inc", "corp", "corporation", "ltd", "llc", "limited", "the", "and", "company", "co", "group", "canada");
    static final double W_DOMAIN = 0.5, W_SUBJECT = 0.4, W_BODY = 0.3, W_TITLE = 0.2, AMBIGUITY_GAP = 0.1;

    public record MatchCandidate(UUID applicationId, double confidence, boolean ambiguous, List<Map<String, Object>> candidates) {
        static MatchCandidate none() { return new MatchCandidate(null, 0.0, false, List.of()); }
    }

    private final ApplicationRepository applications;

    public ApplicationMatcher(ApplicationRepository applications) { this.applications = applications; }

    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("[a-z0-9]+").matcher(text == null ? "" : text.toLowerCase());
        while (m.find()) if (m.group().length() >= 3 && !STOP.contains(m.group())) out.add(m.group());
        return out;
    }

    static double fractionIn(Set<String> wanted, Set<String> haystack) {
        if (wanted.isEmpty()) return 0.0;
        Set<String> common = new HashSet<>(wanted);
        common.retainAll(haystack);
        return (double) common.size() / wanted.size();
    }

    public MatchCandidate match(EmailMessage email, Map<String, Object> extracted) {
        String domain = email.senderDomain();
        boolean ats = RuleBasedClassifier.ATS_DOMAINS.stream().anyMatch(domain::endsWith);
        Set<String> domainTokens = ats ? Set.of() : tokens(domain.replace("-", " ").replace(".", " "));
        Set<String> subject = tokens(email.getSubject()), body = tokens(email.getExcerpt());
        Set<String> hinted = tokens(extracted == null ? "" : String.valueOf(extracted.getOrDefault("company", "")));
        record Scored(double score, Application app) {}
        List<Scored> scored = new ArrayList<>();
        for (Application app : applications.findByUser(email.getUser())) {
            if (TERMINAL.contains(app.getStateName())) continue;
            Set<String> company = tokens(app.getJob().getCompany());
            boolean domainHit = company.stream().anyMatch(domainTokens::contains);
            double score = domainHit ? W_DOMAIN : 0;
            // a company name the LLM read from the email counts like a mention in the subject
            score += W_SUBJECT * Math.max(fractionIn(company, subject), fractionIn(company, hinted));
            score += W_BODY * fractionIn(company, body);
            Set<String> subjectAndBody = new HashSet<>(subject);
            subjectAndBody.addAll(body);
            score += W_TITLE * fractionIn(tokens(app.getJob().getTitle()), subjectAndBody);
            if (score > 0) scored.add(new Scored(Math.round(Math.min(score, 1.0) * 100) / 100.0, app));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        if (scored.isEmpty()) return MatchCandidate.none();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(5, scored.size()))) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("application_id", s.app().getId().toString());
            c.put("label", s.app().getJob().getTitle() + " at " + s.app().getJob().getCompany());
            c.put("score", s.score());
            candidates.add(c);
        }
        Scored best = scored.get(0);
        boolean ambiguous = scored.size() > 1 && best.score() - scored.get(1).score() < AMBIGUITY_GAP;
        return new MatchCandidate(best.app().getId(), ambiguous ? Math.min(best.score(), 0.6) : best.score(), ambiguous, candidates);
    }
}
