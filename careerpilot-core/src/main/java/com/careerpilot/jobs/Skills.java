package com.careerpilot.jobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Skill vocabulary used by the deterministic fallback analysis and skill matching. */
public final class Skills {
    private Skills() {}

    public static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("js", "javascript"), Map.entry("node", "node.js"), Map.entry("nodejs", "node.js"), Map.entry("ts", "typescript"),
            Map.entry("postgres", "postgresql"), Map.entry("k8s", "kubernetes"), Map.entry("golang", "go"), Map.entry("reactjs", "react"),
            Map.entry("react.js", "react"), Map.entry("vuejs", "vue"), Map.entry("py", "python"), Map.entry("ml", "machine learning"),
            Map.entry("aws cloud", "aws"), Map.entry("amazon web services", "aws"), Map.entry("gcp", "google cloud"),
            Map.entry("cicd", "ci/cd"), Map.entry("rest api", "rest"), Map.entry("restful", "rest"), Map.entry("c sharp", "c#"),
            Map.entry("dotnet", ".net"), Map.entry("ms excel", "excel"), Map.entry("nlp", "natural language processing"));

    public static final List<String> DICTIONARY = Set.of(
            "python", "java", "javascript", "typescript", "c", "c++", "c#", "go", "rust", "kotlin", "swift", "ruby", "php",
            "scala", "r", "matlab", "sql", "postgresql", "mysql", "mongodb", "redis", "sqlite", "html", "css", "react",
            "angular", "vue", "node.js", "django", "flask", "fastapi", "spring", "spring boot", ".net", "express",
            "rest", "graphql", "docker", "kubernetes", "aws", "azure", "google cloud", "terraform", "linux", "bash", "git",
            "ci/cd", "jenkins", "github actions", "pandas", "numpy", "scikit-learn", "tensorflow", "pytorch",
            "machine learning", "deep learning", "natural language processing", "data analysis", "tableau", "power bi",
            "excel", "spark", "hadoop", "airflow", "kafka", "junit", "pytest", "selenium", "agile", "scrum", "jira",
            "figma", "android", "ios", "unity", "networking", "security", "testing", "microservices", "llm")
            .stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder())).toList();

    public static String canonical(String skill) {
        String s = String.join(" ", (skill == null ? "" : skill).toLowerCase().strip().split("\\s+"));
        return ALIASES.getOrDefault(s, s);
    }

    /** Dictionary skills mentioned in free text, longest names first. */
    public static List<String> find(String text) {
        List<String> found = new ArrayList<>();
        String lowered = " " + (text == null ? "" : text.toLowerCase()) + " ";
        for (String skill : DICTIONARY) {
            if (Pattern.compile("(?<![a-z0-9+#.])" + Pattern.quote(skill) + "(?![a-z0-9+#])").matcher(lowered).find()) found.add(skill);
        }
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            if (!found.contains(alias.getValue())
                    && Pattern.compile("(?<![a-z0-9])" + Pattern.quote(alias.getKey()) + "(?![a-z0-9])").matcher(lowered).find())
                found.add(alias.getValue());
        }
        return found;
    }
}
