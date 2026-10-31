package com.careerpilot.candidates;

import com.careerpilot.common.NotFoundException;
import com.careerpilot.common.StorageService;
import com.careerpilot.common.TaskQueue;
import com.careerpilot.common.ValidationException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.llm.LlmException;
import com.careerpilot.llm.LlmProviderException;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** F01 profile and verified facts, F02 resume import, F03 career goal and strategy. */
@Service
@Transactional
public class CandidateService {
    private final CandidateProfileRepository profiles;
    private final ProfileFactRepository facts;
    private final CareerGoalRepository goals;
    private final UploadedResumeRepository resumes;
    private final StorageService storage;
    private final TaskQueue tasks;
    private final ResumeParser parser;
    private final DomainEventBus bus;

    public CandidateService(CandidateProfileRepository profiles, ProfileFactRepository facts, CareerGoalRepository goals,
                            UploadedResumeRepository resumes, StorageService storage, TaskQueue tasks, ResumeParser parser,
                            DomainEventBus bus) {
        this.profiles = profiles;
        this.facts = facts;
        this.goals = goals;
        this.resumes = resumes;
        this.storage = storage;
        this.tasks = tasks;
        this.parser = parser;
        this.bus = bus;
    }

    // ---- F01 profile and facts ----

    public CandidateProfile getProfile(User user) {
        return profiles.findByUser(user).orElseGet(() -> {
            CandidateProfile p = new CandidateProfile();
            p.setUser(user);
            p.setEmail(user.getEmail());
            return profiles.save(p);
        });
    }

    @SuppressWarnings("unchecked")
    public CandidateProfile updateProfile(User user, Map<String, Object> data) {
        CandidateProfile p = getProfile(user);
        if (data.containsKey("full_name")) p.setFullName(String.valueOf(data.get("full_name")));
        if (data.containsKey("headline")) p.setHeadline(String.valueOf(data.get("headline")));
        if (data.containsKey("summary")) p.setSummary(String.valueOf(data.get("summary")));
        if (data.containsKey("location")) p.setLocation(String.valueOf(data.get("location")));
        if (data.containsKey("email")) p.setEmail(String.valueOf(data.get("email")));
        if (data.containsKey("phone")) p.setPhone(String.valueOf(data.get("phone")));
        if (data.containsKey("links")) p.setLinks(asList(data.get("links")));
        if (data.containsKey("auto_triage")) p.setAutoTriage(Boolean.parseBoolean(String.valueOf(data.get("auto_triage"))));
        p.setUpdatedAt(Instant.now());
        return profiles.save(p);
    }

    public ProfileFact addFact(User user, FactType type, Map<String, Object> data, FactSource source) {
        ProfileFact fact = new ProfileFact();
        fact.setUser(user);
        fact.setFactType(type);
        fact.setData(FactValidator.validate(type, data));
        fact.setSource(source);
        // facts the student types in are verified on save; extracted ones need explicit review
        fact.setVerified(source == FactSource.MANUAL);
        return facts.save(fact);
    }

    public ProfileFact addFact(User user, FactType type, Map<String, Object> data) {
        return addFact(user, type, data, FactSource.MANUAL);
    }

    public ProfileFact updateFact(User user, UUID factId, Map<String, Object> data) {
        ProfileFact fact = facts.owned(user, factId);
        Map<String, Object> merged = new java.util.LinkedHashMap<>(fact.getData());
        merged.putAll(data);
        fact.setData(FactValidator.validate(fact.getFactType(), merged));
        if (fact.getSource() == FactSource.MANUAL) fact.setVerified(true);
        return facts.save(fact);
    }

    public void deleteFact(User user, UUID factId) {
        facts.delete(facts.owned(user, factId));
        bus.publish(new Events.ProfileFactDeleted(user.getId(), factId));
    }

    @Transactional(readOnly = true)
    public List<ProfileFact> listFacts(User user, Boolean verified) {
        return verified == null ? facts.findByUser(user) : facts.findByUserAndVerified(user, verified);
    }

    @Transactional(readOnly = true)
    public ProfileFact getFact(User user, UUID id) { return facts.owned(user, id); }

    /** Marks the given facts verified, applying any per-fact edits first. Returns the count. */
    public int verifyFacts(User user, List<UUID> ids, Map<UUID, Map<String, Object>> edits) {
        List<ProfileFact> found = facts.findByUserAndIdIn(user, ids);
        if (found.size() != ids.stream().distinct().count()) throw new ValidationException("some fact ids were not found");
        for (ProfileFact fact : found) {
            Map<String, Object> edit = edits == null ? null : edits.get(fact.getId());
            if (edit != null) {
                Map<String, Object> merged = new java.util.LinkedHashMap<>(fact.getData());
                merged.putAll(edit);
                fact.setData(FactValidator.validate(fact.getFactType(), merged));
            } else {
                FactValidator.validate(fact.getFactType(), fact.getData());  // refuse to verify an unfixed extraction
            }
            fact.verify();
        }
        facts.saveAll(found);
        return found.size();
    }

    public int rejectFacts(User user, List<UUID> ids) {
        List<ProfileFact> pending = facts.findByUserAndIdIn(user, ids).stream().filter(f -> !f.isVerified()).toList();
        facts.deleteAll(pending);
        return pending.size();
    }

    @Transactional(readOnly = true)
    public List<String> completenessHints(User user) {
        List<ProfileFact> verified = listFacts(user, true);
        List<String> hints = new ArrayList<>();
        for (Map.Entry<FactType, String> e : Map.of(FactType.EDUCATION, "education", FactType.EXPERIENCE, "experience",
                FactType.PROJECT, "projects", FactType.SKILL, "skills").entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            if (verified.stream().noneMatch(f -> f.getFactType() == e.getKey())) hints.add("No verified " + e.getValue() + " yet");
        }
        if (!listFacts(user, false).isEmpty()) hints.add("Some extracted facts are waiting for your review");
        if (goals.findByUser(user).isEmpty()) hints.add("Set a career goal so jobs can be scored");
        return hints;
    }

    // ---- F02 resume import ----

    public UploadedResume importResume(User user, String filename, byte[] data) {
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')).toLowerCase() : "";
        if (!ResumeParser.SUPPORTED.containsKey(ext)) throw new ValidationException("only PDF, DOCX or TXT resumes are supported");
        if (data.length > ResumeParser.MAX_BYTES) throw new ValidationException("resume must be 5 MB or smaller");
        String key = storage.put(storage.newKey(user, "resumes", filename), data);
        UploadedResume resume = new UploadedResume();
        resume.setUser(user);
        resume.setStorageKey(key);
        resume.setOriginalName(filename.length() > 200 ? filename.substring(0, 200) : filename);
        resume.setMimeType(ResumeParser.SUPPORTED.get(ext));
        resumes.save(resume);
        tasks.enqueue("candidates.parse_resume", () -> parseResume(resume.getId()));
        return resumes.findById(resume.getId()).orElse(resume);
    }

    /** Runs as a background task: extract text, ask the LLM for facts, store them unverified. */
    public UploadedResume parseResume(UUID resumeId) {
        UploadedResume resume = resumes.findById(resumeId).orElseThrow(() -> new NotFoundException("resume not found"));
        try {
            String text = parser.extractText(storage.get(resume.getStorageKey()), resume.getMimeType());
            List<ProfileFact> extracted = parser.extractFacts(resume.getUser(), text);
            facts.saveAll(extracted);
            resume.markParsed(extracted.size());
        } catch (ResumeParser.NoTextException e) {
            resume.markFailed(e.getMessage(), false);
        } catch (LlmProviderException e) {
            resume.markFailed("AI service unavailable, retry later (" + e.getMessage() + ")", e.isRetryable());
        } catch (LlmException e) {
            resume.markFailed("AI extraction failed: " + e.getMessage(), true);
        } catch (Exception e) {  // corrupt files and similar
            resume.markFailed("could not read file: " + e.getMessage(), false);
        }
        return resumes.save(resume);
    }

    @Transactional(readOnly = true)
    public UploadedResume getResume(User user, UUID id) { return resumes.owned(user, id); }

    // ---- F03 career goal and strategy ----

    @Transactional(readOnly = true)
    public CareerGoal getCareerGoal(User user) { return goals.findByUser(user).orElse(null); }

    /** Creates or updates the goal. Returns it; {@code strategyChanged} is reported on the published event. */
    public CareerGoal updateCareerGoal(User user, Map<String, Object> data) {
        String goalName = data.get("goal_type") == null ? null : data.get("goal_type").toString();
        GoalType goalType;
        try {
            goalType = GoalType.valueOf(goalName == null ? "" : goalName);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown goal type '" + goalName + "'").with("fields", Map.of("goal_type", "unknown"));
        }
        String workTerm = data.get("work_term") == null ? "" : data.get("work_term").toString().strip();
        if (goalType == GoalType.COOP && workTerm.isEmpty())
            throw new ValidationException("work term is required for co-op goals").with("fields", Map.of("work_term", "required"));
        int score;
        try {
            score = Integer.parseInt(String.valueOf(data.getOrDefault("min_match_score", 60)));
        } catch (NumberFormatException e) {
            score = -1;
        }
        if (score < 0 || score > 100)
            throw new ValidationException("min_match_score must be 0-100").with("fields", Map.of("min_match_score", "0-100"));

        CareerGoal goal = goals.findByUser(user).orElse(null);
        boolean isNew = goal == null;
        if (isNew) {
            goal = new CareerGoal();
            goal.setUser(user);
        }
        String previousKey = isNew ? null : goal.strategyKey();
        goal.setGoalType(goalType);
        goal.setTargetRoles(asList(data.get("target_roles")));
        goal.setPreferredLocations(asList(data.get("preferred_locations")));
        goal.setWorkTerm(workTerm);
        goal.setWorkModel(data.get("work_model") == null ? CareerGoal.WorkModel.ANY : CareerGoal.WorkModel.valueOf(data.get("work_model").toString()));
        goal.setMinMatchScore(score);
        boolean strategyChanged = !goal.strategyKey().equals(previousKey);
        // a different algorithm starts from its own defaults (Stage 1 F03)
        if (strategyChanged) goal.setWeights(GoalType.defaultsFor(goal.strategyKey()));
        goal.setUpdatedAt(Instant.now());
        goals.save(goal);
        bus.publish(new Events.CareerGoalChanged(user.getId(), strategyChanged));
        return goal;
    }

    static List<String> asList(Object value) {
        if (value instanceof String s) return Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).collect(Collectors.toList());
        if (value instanceof Iterable<?> it) {
            List<String> out = new ArrayList<>();
            for (Object o : it) if (o != null && !o.toString().isBlank()) out.add(o.toString().strip());
            return out;
        }
        return new ArrayList<>();
    }
}
