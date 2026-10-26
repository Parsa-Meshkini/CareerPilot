import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { get, post, upload } from "../api";
import MatchCard, { ScoreBadge } from "../components/MatchCard";
import { Badge, Button, Card, Empty, ErrorBox, Field, Notice, useAction } from "../components/ui";
import { deadlineLabel, titleCase, when } from "../format";

function PasteImport({ onImported }) {
  const [form, setForm] = useState({ text: "", url: "", source_type: "MANUAL", title: "", company: "" });
  const [needs, setNeeds] = useState(null);
  const [result, setResult] = useState(null);
  const { busy, error, run, setError } = useAction();
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const submit = (e) => {
    e.preventDefault();
    run(async () => {
      try {
        const r = await post("/jobs/import/manual/", form);
        setResult(r);
        setNeeds(null);
        setForm({ text: "", url: "", source_type: "MANUAL", title: "", company: "" });
        onImported();
      } catch (err) {
        if (err.data?.fields) { setNeeds(Object.keys(err.data.fields)); setError(err); return; }
        throw err;
      }
    });
  };
  return (
    <form onSubmit={submit}>
      <ErrorBox error={error} />
      {result && <Notice tone="success">{result.duplicate ? "Already in your jobs: " : "Imported: "}
        <Link to={`/jobs/${result.job.id}`}>{result.job.title} @ {result.job.company}</Link>
        {result.warnings?.length > 0 && <div className="small">{result.warnings.join(" · ")}</div>}</Notice>}
      <Field label="Posting text" hint="Paste the full posting from LinkedIn, a company site or anywhere else. The AI fills in the fields.">
        <textarea rows={7} required value={form.text} onChange={set("text")} />
      </Field>
      <div className="grid-2">
        <Field label="Posting URL"><input value={form.url} onChange={set("url")} /></Field>
        <Field label="Source">
          <select value={form.source_type} onChange={set("source_type")}>
            <option value="MANUAL">Company site / other</option><option value="LINKEDIN">LinkedIn (pasted)</option>
          </select>
        </Field>
        {needs?.includes("title") && <Field label="Title *"><input value={form.title} onChange={set("title")} /></Field>}
        {needs?.includes("company") && <Field label="Company *"><input value={form.company} onChange={set("company")} /></Field>}
      </div>
      <Button kind="primary" busy={busy} type="submit">Import posting</Button>
    </form>
  );
}

function FileImport({ onImported }) {
  const { busy, error, run } = useAction();
  const [batch, setBatch] = useState(null);
  const pick = (e) => {
    const f = e.target.files?.[0];
    if (f) run(async () => { setBatch(await upload("/jobs/import/file/", f)); onImported(); });
    e.target.value = "";
  };
  return (
    <div>
      <p className="muted small">JSON list or CSV with columns title, company, location, deadline, description, url…</p>
      <ErrorBox error={error} />
      <label className="btn btn-secondary file-btn">{busy ? "Importing…" : "Choose JSON / CSV file"}
        <input type="file" accept=".json,.csv" hidden onChange={pick} /></label>
      {batch && <Notice tone={batch.failed ? "warn" : "success"}>{batch.imported} imported, {batch.duplicates} duplicates, {batch.failed} failed</Notice>}
    </div>
  );
}

function Batches({ batches, onChange }) {
  const { busy, error, run } = useAction();
  const navigate = useNavigate();
  if (!batches.length) return <p className="muted small">No imports yet. Use the browser extension on Experience York, or import a file.</p>;
  const triage = (b) => run(async () => { const r = await post(`/jobs/batches/${b.id}/triage/`); onChange(); navigate(`/agent/${r.id}`); });
  return (
    <div>
      <ErrorBox error={error} />
      <table><thead><tr><th>When</th><th>Source</th><th>Result</th><th /></tr></thead><tbody>
        {batches.slice(0, 6).map((b) => (
          <tr key={b.id}>
            <td>{when(b.created_at)}</td><td>{titleCase(b.source_type)}</td>
            <td><Badge tone={b.status === "COMPLETED" ? "good" : "warn"}>{titleCase(b.status)}</Badge>{" "}
              <span className="small muted">{b.imported} new · {b.duplicates} merged · {b.failed} failed</span></td>
            <td><Button className="btn btn-small" busy={busy} disabled={!b.imported} onClick={() => triage(b)}>Triage</Button></td>
          </tr>
        ))}
      </tbody></table>
    </div>
  );
}

function Compare({ ids, onClose }) {
  const [rows, setRows] = useState(null);
  const { error, run } = useAction();
  useEffect(() => { run(async () => setRows(await post("/jobs/compare/", { job_ids: ids }))); }, [ids.join()]);
  return (
    <Card title="Compare" actions={<Button onClick={onClose}>Close</Button>}>
      <ErrorBox error={error} />
      {rows && <div className="compare" style={{ gridTemplateColumns: `repeat(${rows.length}, minmax(0, 1fr))` }}>
        {rows.map(({ job, match }) => (
          <div key={job.id} className="compare-col">
            <Link to={`/jobs/${job.id}`}><strong>{job.title}</strong></Link>
            <div className="muted small">{job.company} · {deadlineLabel(job)}</div>
            <MatchCard match={match} />
          </div>
        ))}
      </div>}
    </Card>
  );
}

export default function Jobs() {
  const [jobs, setJobs] = useState(null);
  const [batches, setBatches] = useState([]);
  const [filters, setFilters] = useState({ q: "", min_score: "", shortlisted: "", sort: "score" });
  const [tab, setTab] = useState("paste");
  const [picked, setPicked] = useState([]);
  const [comparing, setComparing] = useState(null);
  const { busy, error, run } = useAction();
  const navigate = useNavigate();

  const load = () => {
    const qs = new URLSearchParams(Object.entries(filters).filter(([, v]) => v !== "")).toString();
    get(`/jobs/?${qs}`).then(setJobs);
    get("/jobs/batches/list/").then(setBatches);
  };
  useEffect(load, [filters]);
  const set = (k) => (e) => setFilters({ ...filters, [k]: e.target.value });
  const toggle = (id) => setPicked(picked.includes(id) ? picked.filter((x) => x !== id) : [...picked, id].slice(-4));
  const scoreAll = () => run(async () => { await post("/jobs/score/"); load(); });

  return (
    <div className="stack">
      <h1>Jobs</h1>
      <div className="grid-2 align-start">
        <Card title="Add jobs">
          <div className="tabs">
            <button className={tab === "paste" ? "tab active" : "tab"} onClick={() => setTab("paste")}>Paste text</button>
            <button className={tab === "file" ? "tab active" : "tab"} onClick={() => setTab("file")}>JSON / CSV file</button>
          </div>
          {tab === "paste" ? <PasteImport onImported={load} /> : <FileImport onImported={load} />}
        </Card>
        <Card title="Recent imports"><Batches batches={batches} onChange={load} /></Card>
      </div>

      {comparing && <Compare ids={comparing} onClose={() => setComparing(null)} />}

      <Card title={`Your postings${jobs ? ` (${jobs.length})` : ""}`} actions={<>
        <Button busy={busy} onClick={scoreAll}>Quick-score all</Button>
        <Button kind="primary" disabled={picked.length < 2} onClick={() => setComparing(picked)}>Compare {picked.length || ""}</Button>
      </>}>
        <ErrorBox error={error} />
        <div className="filters">
          <input placeholder="Search title, company, location" value={filters.q} onChange={set("q")} />
          <select value={filters.min_score} onChange={set("min_score")}>
            <option value="">Any score</option><option value="50">50+</option><option value="65">65+</option><option value="80">80+</option>
          </select>
          <select value={filters.shortlisted} onChange={set("shortlisted")}>
            <option value="">All</option><option value="true">Shortlisted</option>
          </select>
          <select value={filters.sort} onChange={set("sort")}>
            <option value="score">Sort by score</option><option value="">Newest first</option>
          </select>
        </div>
        {!jobs ? <p className="muted">Loading…</p> : jobs.length === 0 ? <Empty>No postings yet.</Empty> : (
          <table><thead><tr><th /><th>Score</th><th>Job</th><th>Location</th><th>Deadline</th><th>Source</th></tr></thead><tbody>
            {jobs.map((j) => (
              <tr key={j.id} className="clickable" onClick={() => navigate(`/jobs/${j.id}`)}>
                <td onClick={(e) => e.stopPropagation()}><input type="checkbox" checked={picked.includes(j.id)} onChange={() => toggle(j.id)} aria-label="compare" /></td>
                <td><ScoreBadge score={j.score} /></td>
                <td><strong>{j.title}</strong><div className="muted small">{j.company}</div>
                  <div className="row gap-s">
                    {j.shortlisted && <Badge tone="accent" title={j.shortlist_reason}>Shortlisted</Badge>}
                    {j.completeness === "SUMMARY" && <Badge>Summary only</Badge>}
                    {j.merged_sources?.length > 1 && <Badge>Merged from {j.merged_sources.length} sources</Badge>}
                    {j.match_outdated && <Badge tone="warn">Rescore</Badge>}
                  </div></td>
                <td>{j.location || "–"}<div className="muted small">{titleCase(j.work_model)}</div></td>
                <td className={j.days_left !== null && j.days_left <= 7 ? "warn-text" : ""}>{deadlineLabel(j)}</td>
                <td>{titleCase(j.source_type)}</td>
              </tr>
            ))}
          </tbody></table>
        )}
      </Card>
    </div>
  );
}
