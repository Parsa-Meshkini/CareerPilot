"""Skill vocabulary used by the deterministic fallback analysis and skill matching."""
import re

ALIASES = {
    "js": "javascript", "node": "node.js", "nodejs": "node.js", "ts": "typescript", "postgres": "postgresql",
    "k8s": "kubernetes", "golang": "go", "reactjs": "react", "react.js": "react", "vuejs": "vue",
    "py": "python", "ml": "machine learning", "aws cloud": "aws", "amazon web services": "aws",
    "gcp": "google cloud", "ci/cd": "ci/cd", "cicd": "ci/cd", "rest api": "rest", "restful": "rest",
    "c sharp": "c#", "dotnet": ".net", "ms excel": "excel", "nlp": "natural language processing",
}

DICTIONARY = sorted({
    "python", "java", "javascript", "typescript", "c", "c++", "c#", "go", "rust", "kotlin", "swift", "ruby", "php",
    "scala", "r", "matlab", "sql", "postgresql", "mysql", "mongodb", "redis", "sqlite", "html", "css", "react",
    "angular", "vue", "node.js", "django", "flask", "fastapi", "spring", "spring boot", ".net", "express",
    "rest", "graphql", "docker", "kubernetes", "aws", "azure", "google cloud", "terraform", "linux", "bash", "git",
    "ci/cd", "jenkins", "github actions", "pandas", "numpy", "scikit-learn", "tensorflow", "pytorch",
    "machine learning", "deep learning", "natural language processing", "data analysis", "tableau", "power bi",
    "excel", "spark", "hadoop", "airflow", "kafka", "junit", "pytest", "selenium", "agile", "scrum", "jira",
    "figma", "android", "ios", "unity", "networking", "security", "testing", "microservices", "llm",
}, key=len, reverse=True)


def canonical(skill):
    s = " ".join(str(skill).lower().strip().split())
    return ALIASES.get(s, s)


def find_skills(text):
    """Dictionary skills mentioned in free text, longest names first."""
    found, lowered = [], " " + (text or "").lower() + " "
    for skill in DICTIONARY:
        pattern = r"(?<![a-z0-9+#.])" + re.escape(skill) + r"(?![a-z0-9+#])"
        if re.search(pattern, lowered):
            found.append(skill)
    for alias, target in ALIASES.items():
        if target not in found and re.search(r"(?<![a-z0-9])" + re.escape(alias) + r"(?![a-z0-9])", lowered):
            found.append(target)
    return found
