import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { del, get, patch, post } from "../api";
import MatchCard from "../components/MatchCard";
import { Badge, Button, Card, Chips, ErrorBox, Notice, useAction } from "../components/ui";
import { deadlineLabel, titleCase } from "../format";

export default function JobDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [job, setJob] = useState(null);
  const [app, setApp] = useState(null);
  const { busy, error, run } = useAction();
  const load = () => {
    get(`/jobs/${id}/`).then(setJob);
    get("/applications/").then((apps) => setApp(apps.find((a) => a.job.id === id) || null));
  };
  useEffect(load, [id]);
  if (!job) return <p className="muted">{error ? error.message : "Loading…"}</p>;
  const a = job.analysis;

  const analyze = () => run(async () => { await post(`/jobs/${id}/analyze/`); load(); });
  const quick = () => run(async () => { await post(`/jobs/${id}/match/`, { deep: false }); load(); });
  const track = () => run(async () => { const r = await post("/applications/", { job_id: id }); navigate(`/applications?open=${r.id}`); });
  const dismiss = () => run(async () => { await patch(`/jobs/${id}/`, { dismissed: !job.dismissed }); load(); });
  const remove = () => run(async () => { if (confirm("Delete this posting?")) { await del(`/jobs/${id}/`); navigate("/jobs"); } });

  return (
    <div className="stack">
      <div className="row space">
        <div>
          <Link to="/jobs" className="small">← Jobs</Link>
          <h1>{job.title}</h1>
          <div className="muted">{job.company} · {job.location || "Location n/a"} · {titleCase(job.work_model)} · {deadlineLabel(job)}</div>
          <div className="row gap-s">
            <Badge>{titleCase(job.source_type)}</Badge>
            <Badge tone={job.completeness === "FULL" ? "good" : "warn"}>{job.completeness === "FULL" ? "Full detail" : "Summary only"}</Badge>
            {job.work_term && <Badge>{job.work_term}</Badge>}
            {job.shortlisted && <Badge tone="accent">Shortlisted: {job.shortlist_reason}</Badge>}
          </div>
        </div>
        <div className="row gap-s">
          {app ? <Link className="btn btn-secondary" to={`/applications?open=${app.id}`}>Application: {titleCase(app.state)}</Link>
            : <Button kind="primary" busy={busy} onClick={track}>Track application</Button>}
          <Button onClick={dismiss}>{job.dismissed ? "Restore" : "Not interested"}</Button>
          <Button kind="danger" onClick={remove}>Delete</Button>
        </div>
      </div>
      <ErrorBox error={error} />
      {job.completeness === "SUMMARY" && <Notice tone="warn">Only a summary was imported. Open the full posting and import it again for a better analysis.</Notice>}

      <div className="grid-2 align-start">
        <Card title="Match & skill gap" actions={<>
          <Button busy={busy} onClick={quick}>Quick score</Button>
          <Button kind="primary" busy={busy} onClick={analyze}>{a ? "Re-analyze with AI" : "Analyze with AI"}</Button>
        </>}>
          <MatchCard match={job.match} />
        </Card>
        <Card title="AI analysis">
          {!a ? <p className="muted">Not analyzed yet. "Analyze with AI" extracts required skills, responsibilities, seniority and red flags.</p> : (
            <div className="stack-s">
              <div className="row gap-s">
                {a.is_fallback ? <Badge tone="warn">Keyword fallback (AI unavailable)</Badge> : <Badge tone="accent">{a.model_used}</Badge>}
                {a.seniority && <Badge>{titleCase(a.seniority)}</Badge>}
                {a.years_required != null && <Badge>{a.years_required}+ years</Badge>}
                {a.partial && <Badge tone="warn">Partial</Badge>}
              </div>
              {a.summary && <p>{a.summary}</p>}
              <div><div className="field-label">Required skills</div><Chips items={a.required_skills} /></div>
              <div><div className="field-label">Preferred skills</div><Chips items={a.preferred_skills} /></div>
              {a.responsibilities?.length > 0 && <div><div className="field-label">Responsibilities</div>
                <ul className="bullets">{a.responsibilities.map((r, i) => <li key={i}>{r}</li>)}</ul></div>}
              {a.red_flags?.length > 0 && <div><div className="field-label">Red flags</div><Chips items={a.red_flags} tone="bad" /></div>}
            </div>
          )}
        </Card>
      </div>

      <Card title="Posting">
        <div className="row gap-m small muted">
          {job.salary_text && <span>Salary: {job.salary_text}</span>}
          {job.required_documents?.length > 0 && <span>Documents: {job.required_documents.join(", ")}</span>}
          {(job.application_url || job.source_url) && <a href={job.application_url || job.source_url} target="_blank" rel="noreferrer">Open original posting ↗</a>}
        </div>
        <pre className="description">{job.description || "No description imported."}</pre>
      </Card>
    </div>
  );
}
