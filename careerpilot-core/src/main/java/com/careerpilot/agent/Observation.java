package com.careerpilot.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a tool call returned to the agent loop. */
public class Observation {
    public final boolean ok;
    public final String summary;
    public final Map<String, Object> data;
    /** Several plausible objects: the agent asks the student which one is meant. */
    public final List<Map<String, Object>> candidates;
    public final String error;
    /** The rules cannot decide what to do next; the reasoner may reflect with the LLM. */
    public boolean needsJudgement;
    public final Map<String, Object> undoData;

    public Observation(boolean ok, String summary, Map<String, Object> data, List<Map<String, Object>> candidates, String error,
                       Map<String, Object> undoData) {
        this.ok = ok;
        this.summary = summary;
        this.data = data == null ? new LinkedHashMap<>() : data;
        this.candidates = candidates == null ? new ArrayList<>() : candidates;
        this.error = error == null ? "" : error;
        this.undoData = undoData == null ? new LinkedHashMap<>() : undoData;
    }

    public static Observation ok(String summary, Map<String, Object> data) { return new Observation(true, summary, data, null, "", null); }

    public static Observation ok(String summary, Map<String, Object> data, Map<String, Object> undoData) {
        return new Observation(true, summary, data, null, "", undoData);
    }

    public static Observation choices(String summary, Map<String, Object> data, List<Map<String, Object>> candidates) {
        return new Observation(true, summary, data, candidates, "", null);
    }

    public static Observation failed(String summary, String error) { return new Observation(false, summary, null, null, error, null); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("summary", summary);
        m.put("data", data);
        m.put("candidates", candidates);
        m.put("error", error);
        m.put("needs_judgement", needsJudgement);
        return m;
    }
}
