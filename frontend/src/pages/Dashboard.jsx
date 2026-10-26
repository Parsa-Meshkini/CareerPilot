import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { get } from "../api";
import { ScoreBadge } from "../components/MatchCard";
import { Badge, Card, Empty, Notice } from "../components/ui";
import { deadlineLabel, titleCase, when } from "../format";

export default function Dashboard() {
  const [data, setData] = useState(null);
  useEffect(() => {
    Promise.all([get("/jobs/?shortlisted=true&sort=score"), get("/applications/"), get("/agent/runs/"), get("/profile/")])
      .then(([shortlist, apps, runs, profile]) => setData({ shortlist, apps, runs, profile }));
  }, []);
  if (!data) return <p className="muted">Loading…</p>;
  const waiting = data.runs.filter((r) => r.status.startsWith("AWAITING"));
  const triage = data.runs.find((r) => r.goal_type === "TRIAGE_BATCH" && r.status === "COMPLETED");
  const open = data.apps.filter((a) => ["SAVED", "PREPARED", "APPLIED", "INTERVIEW", "OFFER"].includes(a.state));
  const due = open.filter((a) => a.job.days_left !== null && a.job.days_left >= 0 && a.job.days_left <= 21)
    .sort((a, b) => a.job.days_left - b.job.days_left);
  const counts = open.reduce((m, a) => ({ ...m, [a.state]: (m[a.state] || 0) + 1 }), {});

  return (
    <div className="stack">
      <h1>Dashboard</h1>
      {data.profile.hints.length > 0 && <Notice>Getting started: {data.profile.hints.join(" · ")} <Link to="/profile">Open profile</Link></Notice>}
      {waiting.map((r) => (
        <Notice key={r.id} tone="warn">The agent is waiting for you: <Link to={`/agent/${r.id}`}>{r.goal}</Link></Notice>
      ))}
      <div className="stats">
        {["SAVED", "PREPARED", "APPLIED", "INTERVIEW", "OFFER"].map((s) => (
          <Link key={s} to="/applications" className="stat"><span className="stat-num">{counts[s] || 0}</span><span className="muted small">{titleCase(s)}</span></Link>
        ))}
      </div>
      <div className="grid-2 align-start">
        <Card title="Shortlist" actions={<Link to="/jobs" className="small">All jobs</Link>}>
          {triage && <p className="small muted">Last triage ({when(triage.created_at)}): {triage.summary} <Link to={`/agent/${triage.id}`}>View reasoning</Link></p>}
          {data.shortlist.length === 0 ? <Empty>No shortlist yet. Import a batch of jobs and the agent will triage it.</Empty> : (
            <ul className="run-list">{data.shortlist.slice(0, 8).map((j) => (
              <li key={j.id} className="row gap-m"><ScoreBadge score={j.score} />
                <div className="grow"><Link to={`/jobs/${j.id}`}>{j.title}</Link><div className="small muted">{j.company} · {deadlineLabel(j)}</div></div></li>
            ))}</ul>
          )}
        </Card>
        <Card title="Upcoming deadlines (21 days)">
          {due.length === 0 ? <Empty>Nothing due soon.</Empty> : (
            <ul className="run-list">{due.map((a) => (
              <li key={a.id} className="row space"><Link to={`/applications?open=${a.id}`}>{a.job.title} @ {a.job.company}</Link>
                <span className="row gap-s"><Badge>{titleCase(a.state)}</Badge><span className={a.job.days_left <= 7 ? "warn-text" : ""}>{deadlineLabel(a.job)}</span></span></li>
            ))}</ul>
          )}
        </Card>
      </div>
    </div>
  );
}
