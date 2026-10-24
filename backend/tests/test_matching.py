import pytest

from agent.context import ContextBuilder, ProfileIncompleteError
from agent.llm.types import LLMProviderError
from jobs.analysis_service import JobAnalysisService
from jobs.matching import CareerSwitchStrategy, CoopInternshipStrategy, EntryLevelStrategy, MatchingService
from tests.factories import make_job, make_profile

ANALYSIS = {"required_skills": ["Python", "Django", "AWS"], "preferred_skills": ["Docker", "Kubernetes"],
            "responsibilities": ["Build REST APIs"], "seniority": "intern", "years_required": None,
            "keywords": ["rest"], "red_flags": [], "summary": "Backend co-op."}


def test_llm_analysis_is_saved_with_canonical_skills(user, mock_llm):
    job = make_job(user)
    mock_llm.script("job.analyze", ANALYSIS)
    a = JobAnalysisService().analyze_job(user, job.id)
    assert a.required_skills == ["python", "django", "aws"] and not a.is_fallback and a.model_used == "mock-1"


def test_keyword_fallback_when_llm_fails(user, mock_llm):
    job = make_job(user, description="Unpaid co-op. Must know Java and SQL. 5+ years experience. " * 8)
    mock_llm.script("job.analyze", LLMProviderError("down"))
    a = JobAnalysisService().analyze_job(user, job.id)
    assert a.is_fallback and {"java", "sql"} <= set(a.required_skills)
    assert "Unpaid position" in a.red_flags and "Student role asking for 5+ years" in a.red_flags


def test_strategy_selected_from_goal(user):
    svc = MatchingService()
    make_profile(user, goal_type="COOP")
    assert isinstance(svc.select_strategy(user.career_goal), CoopInternshipStrategy)
    make_profile(user, goal_type="NEW_GRAD")
    user.career_goal.refresh_from_db()
    assert isinstance(svc.select_strategy(user.career_goal), EntryLevelStrategy)


def test_deep_match_scores_explains_and_lists_gaps(user, mock_llm):
    make_profile(user)
    job = make_job(user)
    mock_llm.script("job.analyze", ANALYSIS)
    mock_llm.script("match.explain", {"explanation": "Strong backend fit.",
                                      "suggestions": [{"skill": "aws", "suggestion": "Deploy a Django app"}]})
    m = MatchingService().match_job(user, job.id, deep=True)
    assert m.strategy_key == "coop_internship" and m.is_deep_analysis
    assert set(m.matched_skills) >= {"python", "django", "docker"} and m.missing_skills == ["aws"]
    assert m.score >= 70 and m.explanation == "Strong backend fit."


def test_coop_work_term_mismatch_caps_score(user, mock_llm):
    make_profile(user)
    job = make_job(user, work_term="Fall 2027")
    mock_llm.script("job.analyze", ANALYSIS)
    m = MatchingService().match_job(user, job.id, deep=True)
    assert m.score <= 45 and "Work term mismatch" in m.constraint_notes[0]


def test_entry_level_penalizes_senior_roles(user, mock_llm):
    make_profile(user, goal_type="NEW_GRAD")
    job = make_job(user, title="Senior Backend Engineer", work_term="")
    mock_llm.script("job.analyze", {**ANALYSIS, "seniority": "senior", "years_required": 6})
    m = MatchingService().match_job(user, job.id, deep=True)
    assert m.breakdown["experience"] <= 0.2 and any("6+ years" in n for n in m.constraint_notes)


def test_career_switch_counts_grounded_transferable_skills_only(user, mock_llm):
    make_profile(user, goal_type="CAREER_SWITCH", skills=("excel",))
    job = make_job(user, title="Data Analyst", work_term="")
    ctx = ContextBuilder().build_candidate_context(user)
    exp_id = str(ctx.facts_of("EXPERIENCE")[0].id)
    mock_llm.script("job.analyze", {**ANALYSIS, "required_skills": ["sql", "tableau"], "preferred_skills": []})
    mock_llm.script("match.transferable", {"mappings": [
        {"required_skill": "sql", "fact_ids": [exp_id], "reason": "Built a REST API backed by a database"},
        {"required_skill": "tableau", "fact_ids": ["made-up-id"], "reason": "invented"}]})
    m = MatchingService().match_job(user, job.id, deep=True)
    assert m.strategy_key == "career_switch"
    assert [t["skill"] for t in m.transferable] == ["sql"]
    assert m.breakdown["skill"] == 0.25


def test_quick_match_uses_no_llm(user, mock_llm):
    make_profile(user, goal_type="CAREER_SWITCH")
    job = make_job(user)
    m = MatchingService().match_job(user, job.id, deep=False)
    assert not m.is_deep_analysis and mock_llm.calls == []


def test_profile_incomplete(user):
    job = make_job(user)
    with pytest.raises(ProfileIncompleteError):
        MatchingService().match_job(user, job.id)


def test_analyze_endpoint_and_list_sorting(api, user, mock_llm):
    make_profile(user)
    good = make_job(user)
    make_job(user, title="Sales Associate", company="Shop", description="Retail sales role. " * 30, work_term="")
    mock_llm.script("job.analyze", ANALYSIS)
    r = api.post(f"/api/jobs/{good.id}/analyze/")
    assert r.status_code == 200 and r.data["match"]["missing_skills"] == ["aws"]
    api.post("/api/jobs/score/")
    listed = api.get("/api/jobs/?sort=score").data
    assert listed[0]["title"] == "Backend Developer Co-op" and listed[0]["score"] > listed[1]["score"]
    assert len(api.get("/api/jobs/?min_score=60").data) == 1


def test_goal_change_marks_matches_outdated(api, user):
    make_profile(user)
    job = make_job(user)
    MatchingService().match_job(user, job.id)
    api.put("/api/profile/goal/", {"goal_type": "NEW_GRAD"}, format="json")
    assert api.get(f"/api/jobs/{job.id}/").data["match"]["outdated"] is True


def test_strategies_are_interchangeable(user):
    make_profile(user)
    job = make_job(user)
    ctx = ContextBuilder().build_candidate_context(user)
    view = MatchingService().quick_analysis(job)
    scores = {s.key(): s.score(ctx, job, view).score
              for s in (CoopInternshipStrategy(), EntryLevelStrategy(), CareerSwitchStrategy(use_llm=False))}
    assert len(scores) == 3 and all(0 <= v <= 100 for v in scores.values())
