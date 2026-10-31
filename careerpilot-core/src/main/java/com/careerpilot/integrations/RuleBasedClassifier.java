package com.careerpilot.integrations;

import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.careerpilot.integrations.EventTypes.*;

/** Sender-domain and subject/body patterns. Fast, free and explainable (deterministic strategy). */
@Component
public class RuleBasedClassifier implements EmailClassificationStrategy {
    public static final List<String> ATS_DOMAINS = List.of("greenhouse.io", "lever.co", "myworkday.com", "workday.com", "icims.com",
            "smartrecruiters.com", "ashbyhq.com", "taleo.net", "jobvite.com", "bamboohr.com");

    public record Rule(String eventType, Pattern pattern, double confidence) {
        static Rule of(String type, double confidence, String regex) { return new Rule(type, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), confidence); }
    }

    /** Ordered from most to least decisive: the first rule that matches wins. */
    public static final List<Rule> DEFAULT_RULES = List.of(
            Rule.of(OFFER, 0.9, "pleased to offer|delighted to offer|offer of employment|offer letter|extend (you )?an offer"),
            Rule.of(REJECTION, 0.92, "unfortunately|not (be )?moving forward|not move forward|regret to inform|not been selected|other candidates|will not be proceeding|decided not to"),
            Rule.of(INTERVIEW_SCHEDULED, 0.88, "interview (is )?(confirmed|scheduled)|confirm(ing)? your interview"),
            Rule.of(INTERVIEW_INVITATION, 0.88, "invite you to (an )?(interview|a call|a chat)|interview invitation|schedule (an|your) interview|availability for (an )?interview|would like to (set up|arrange|interview)"),
            Rule.of(INFO_REQUESTED, 0.8, "online assessment|coding (challenge|assessment)|additional information|please (provide|submit|upload|complete)|transcript"),
            Rule.of(APPLICATION_RECEIVED, 0.9, "thank you for (applying|your application)|application (was )?received|we('ve| have) received your application|received your application"),
            Rule.of(FOLLOW_UP, 0.6, "following up|checking in|just checking"));

    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
    private static final Pattern DATE_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})\\b");
    private static final Pattern DATE_TEXT = Pattern.compile("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? (\\d{1,2})(?:st|nd|rd|th)?"
            + "(?:,? (\\d{4}))?(?:,? at| @|,)? (\\d{1,2})(?::(\\d{2}))? ?(am|pm)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMAT = Pattern.compile("\\b(zoom|teams|google meet|video|phone|on-?site|in[- ]person)\\b", Pattern.CASE_INSENSITIVE);

    private final List<Rule> rules;

    public RuleBasedClassifier() { this(DEFAULT_RULES); }

    public RuleBasedClassifier(List<Rule> rules) { this.rules = rules; }

    @Override
    public Classification classify(EmailMessage email, User user) {
        String haystack = email.getSubject() + "\n" + email.getExcerpt();
        for (Rule rule : rules) {
            if (rule.pattern().matcher(haystack).find()) {
                double confidence = rule.confidence();
                if (ATS_DOMAINS.stream().anyMatch(email.senderDomain()::endsWith)) confidence = Math.min(0.97, confidence + 0.05);  // an ATS sent it
                return new Classification(rule.eventType(), Math.round(confidence * 100) / 100.0, details(rule, email), "rules");
            }
        }
        return new Classification(NOT_RELEVANT, 0.5, "rules");
    }

    private static Map<String, Object> details(Rule rule, EmailMessage email) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (rule.eventType().equals(INTERVIEW_INVITATION) || rule.eventType().equals(INTERVIEW_SCHEDULED)) {
            int year = email.getReceivedAt() != null ? email.getReceivedAt().atZone(ZoneOffset.UTC).getYear() : LocalDateTime.now().getYear();
            String when = extractSchedule(email.getSubject() + "\n" + email.getExcerpt(), year);
            if (!when.isEmpty()) data.put("scheduled_at", when);
            Matcher fmt = FORMAT.matcher(email.getExcerpt());
            if (fmt.find()) data.put("format", fmt.group(1).toLowerCase());
        }
        return data;
    }

    /** Finds the first interview date/time in the text. Returns an ISO string without time zone, or "". */
    public static String extractSchedule(String text, int defaultYear) {
        int y, mo, d, h, mi;
        Matcher m = DATE_ISO.matcher(text);
        if (m.find()) {
            y = Integer.parseInt(m.group(1)); mo = Integer.parseInt(m.group(2)); d = Integer.parseInt(m.group(3));
            h = Integer.parseInt(m.group(4)); mi = Integer.parseInt(m.group(5));
        } else {
            m = DATE_TEXT.matcher(text);
            if (!m.find()) return "";
            mo = MONTHS.indexOf(m.group(1).toLowerCase()) + 1;
            d = Integer.parseInt(m.group(2));
            y = m.group(3) != null ? Integer.parseInt(m.group(3)) : defaultYear;
            h = Integer.parseInt(m.group(4)) % 12 + (m.group(6).equalsIgnoreCase("pm") ? 12 : 0);
            mi = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
        }
        try {
            return LocalDateTime.of(y, mo, d, h, mi).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        } catch (java.time.DateTimeException e) {
            return "";
        }
    }
}
