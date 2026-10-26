import { useEffect, useState } from "react";
import { del, get, post, put, upload } from "../api";
import { Badge, Button, Card, ErrorBox, Field, Notice, useAction } from "../components/ui";
import { titleCase } from "../format";

const FACT_FORMS = {
  EXPERIENCE: [["title", "Job title", 1], ["organization", "Organization", 1], ["start_date", "Start (YYYY-MM)"],
    ["end_date", "End (blank = present)"], ["location", "Location"], ["bullets", "Bullets (one per line)", 0, "textarea"]],
  EDUCATION: [["institution", "Institution", 1], ["program", "Program", 1], ["credential", "Credential (e.g. BSc)"],
    ["start_date", "Start (YYYY-MM)"], ["end_date", "End (YYYY-MM)"]],
  PROJECT: [["name", "Project name", 1], ["description", "Description", 0, "textarea"],
    ["technologies", "Technologies (comma separated)"], ["url", "URL"]],
  SKILL: [["name", "Skill", 1], ["category", "Category"], ["proficiency", "Proficiency"]],
  CERTIFICATION: [["name", "Certification", 1], ["issuer", "Issuer"], ["issued_on", "Issued (YYYY-MM)"]],
};

function FactForm({ type, onSaved }) {
  const [data, setData] = useState({});
  const { busy, error, run } = useAction();
  const save = (e) => {
    e.preventDefault();
    run(async () => {
      await post("/profile/facts/", { fact_type: type, data });
      setData({});
      onSaved();
    });
  };
  return (
    <form className="fact-form" onSubmit={save}>
      <ErrorBox error={error} />
      <div className="grid-2">
        {FACT_FORMS[type].map(([key, label, required, kind]) => (
          <Field key={key} label={label + (required ? " *" : "")}>
            {kind === "textarea"
              ? <textarea rows={3} value={data[key] || ""} onChange={(e) => setData({ ...data, [key]: e.target.value })} />
              : <input value={data[key] || ""} onChange={(e) => setData({ ...data, [key]: e.target.value })} />}
          </Field>
        ))}
      </div>
      <Button kind="primary" busy={busy} type="submit">Add {titleCase(type)}</Button>
    </form>
  );
}

function FactRow({ fact, selected, onToggle, onDelete }) {
  const d = fact.data;
  const sub = [d.organization || d.institution || d.issuer, d.start_date && `${d.start_date} – ${d.end_date || "present"}`]
    .filter(Boolean).join(" · ");
  return (
    <li className={`fact ${fact.verified ? "" : "fact-pending"}`}>
      {!fact.verified && <input type="checkbox" checked={selected} onChange={onToggle} aria-label="select for verification" />}
      <div className="grow">
        <div className="row gap-s">
          <strong>{fact.label}</strong>
          <Badge>{titleCase(fact.fact_type)}</Badge>
          {fact.verified ? <Badge tone="good">Verified</Badge> : <Badge tone="warn">Needs review</Badge>}
          {fact.source === "RESUME_EXTRACTION" && <Badge>From resume</Badge>}
        </div>
        {sub && <div className="muted small">{sub}</div>}
        {d.bullets?.length > 0 && <ul className="bullets">{d.bullets.map((b, i) => <li key={i}>{b}</li>)}</ul>}
        {d.technologies?.length > 0 && <div className="muted small">{d.technologies.join(", ")}</div>}
        {fact.review_note && <div className="warn-text small">Fix before verifying: {fact.review_note}</div>}
      </div>
      <button className="icon-btn" title="Delete" onClick={onDelete}>✕</button>
    </li>
  );
}

function GoalForm({ goal, onSaved }) {
  const [g, setG] = useState(null);
  const { busy, error, run } = useAction();
  const [msg, setMsg] = useState("");
  useEffect(() => {
    setG({ goal_type: "COOP", work_term: "", work_model: "ANY", min_match_score: 60, ...goal,
      target_roles: (goal?.target_roles || []).join(", "), preferred_locations: (goal?.preferred_locations || []).join(", ") });
  }, [goal]);
  if (!g) return null;
  const set = (k) => (e) => setG({ ...g, [k]: e.target.value });
  const save = (e) => {
    e.preventDefault();
    run(async () => {
      const r = await put("/profile/goal/", g);
      setMsg(r.strategy_changed ? `Saved. Jobs are now scored with the ${titleCase(r.strategy_key)} strategy.` : "Saved.");
      onSaved();
    });
  };
  return (
    <form onSubmit={save}>
      <ErrorBox error={error} />
      {msg && <Notice tone="success">{msg}</Notice>}
      <div className="grid-2">
        <Field label="Goal type">
          <select value={g.goal_type} onChange={set("goal_type")}>
            <option value="COOP">Co-op</option><option value="INTERNSHIP">Internship</option>
            <option value="NEW_GRAD">New grad</option><option value="CAREER_SWITCH">Career switch</option>
          </select>
        </Field>
        <Field label="Work term" hint="Required for co-op, e.g. Summer 2027"><input value={g.work_term} onChange={set("work_term")} /></Field>
        <Field label="Target roles" hint="Comma separated"><input value={g.target_roles} onChange={set("target_roles")} /></Field>
        <Field label="Preferred locations"><input value={g.preferred_locations} onChange={set("preferred_locations")} /></Field>
        <Field label="Work model">
          <select value={g.work_model} onChange={set("work_model")}>
            {["ANY", "ONSITE", "HYBRID", "REMOTE"].map((m) => <option key={m} value={m}>{titleCase(m)}</option>)}
          </select>
        </Field>
        <Field label="Minimum match score"><input type="number" min="0" max="100" value={g.min_match_score} onChange={set("min_match_score")} /></Field>
      </div>
      <Button kind="primary" busy={busy} type="submit">Save goal</Button>
      {goal && <p className="muted small">Active strategy: <strong>{titleCase(goal.strategy_key)}</strong> · weights {Object.entries(goal.weights).map(([k, v]) => `${k} ${v}`).join(", ")}</p>}
    </form>
  );
}

function ResumeImport({ onDone }) {
  const [status, setStatus] = useState(null);
  const { busy, error, run } = useAction();
  const pick = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    run(async () => {
      setStatus(await upload("/profile/resume/", file));
      onDone();
    });
    e.target.value = "";
  };
  const retry = () => run(async () => { setStatus(await post(`/profile/resume/${status.id}/retry/`)); onDone(); });
  return (
    <div>
      <p className="muted">Upload a PDF, DOCX or TXT resume (5 MB max). The AI proposes facts; nothing is used until you verify it.</p>
      <ErrorBox error={error} />
      <label className="btn btn-secondary file-btn">{busy ? "Reading resume…" : "Choose resume file"}
        <input type="file" accept=".pdf,.docx,.txt" onChange={pick} disabled={busy} hidden />
      </label>
      {status && (
        <Notice tone={status.parse_status === "PARSED" ? "success" : "error"}>
          {status.original_name}: {status.parse_status === "PARSED"
            ? `${status.extracted_count} facts extracted. Review them below.` : status.status_message}
          {status.parse_status === "FAILED_RETRYABLE" && <> <button className="link" onClick={retry}>Retry</button></>}
        </Notice>
      )}
    </div>
  );
}

export default function Profile() {
  const [data, setData] = useState(null);
  const [type, setType] = useState("EXPERIENCE");
  const [selected, setSelected] = useState([]);
  const [info, setInfo] = useState({});
  const { busy, error, run } = useAction();
  const load = () => get("/profile/").then((d) => { setData(d); setInfo(d.profile); });
  useEffect(() => { load(); }, []);
  if (!data) return <p className="muted">Loading profile…</p>;

  const pending = data.facts.filter((f) => !f.verified);
  const verified = data.facts.filter((f) => f.verified);
  const toggle = (id) => setSelected(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  const act = (path) => run(async () => { await post(path, { fact_ids: selected }); setSelected([]); load(); });
  const remove = (id) => run(async () => { await del(`/profile/facts/${id}/`); load(); });
  const saveInfo = (e) => { e.preventDefault(); run(async () => { await put("/profile/", info); load(); }); };

  return (
    <div className="stack">
      <h1>Profile <span className="muted h-sub">{data.verified_count} verified facts</span></h1>
      {data.hints.length > 0 && <Notice>{data.hints.join(" · ")}</Notice>}
      <ErrorBox error={error} />
      <div className="grid-2 align-start">
        <Card title="Career goal">
          <GoalForm goal={data.goal} onSaved={load} />
        </Card>
        <Card title="Import resume">
          <ResumeImport onDone={load} />
        </Card>
      </div>

      {pending.length > 0 && (
        <Card title={`Review extracted facts (${pending.length})`}
          actions={<>
            <Button onClick={() => setSelected(pending.map((f) => f.id))}>Select all</Button>
            <Button kind="primary" busy={busy} disabled={!selected.length} onClick={() => act("/profile/facts/verify/")}>Verify selected</Button>
            <Button kind="danger" disabled={!selected.length} onClick={() => act("/profile/facts/reject/")}>Reject selected</Button>
          </>}>
          <ul className="facts">{pending.map((f) => (
            <FactRow key={f.id} fact={f} selected={selected.includes(f.id)} onToggle={() => toggle(f.id)} onDelete={() => remove(f.id)} />
          ))}</ul>
        </Card>
      )}

      <div className="grid-2 align-start">
        <Card title="Verified facts">
          {verified.length === 0 ? <p className="muted">Nothing verified yet. Add facts or import a resume.</p> :
            <ul className="facts">{verified.map((f) => <FactRow key={f.id} fact={f} onDelete={() => remove(f.id)} />)}</ul>}
        </Card>
        <div className="stack">
          <Card title="Add a fact">
            <div className="tabs">{Object.keys(FACT_FORMS).map((t) => (
              <button key={t} className={t === type ? "tab active" : "tab"} onClick={() => setType(t)}>{titleCase(t)}</button>
            ))}</div>
            <FactForm key={type} type={type} onSaved={load} />
          </Card>
          <Card title="Contact details">
            <form onSubmit={saveInfo}>
              <div className="grid-2">
                {[["full_name", "Full name"], ["headline", "Headline"], ["email", "Email"], ["phone", "Phone"], ["location", "Location"]].map(([k, l]) => (
                  <Field key={k} label={l}><input value={info[k] || ""} onChange={(e) => setInfo({ ...info, [k]: e.target.value })} /></Field>
                ))}
                <Field label="Auto-triage imports">
                  <select value={info.auto_triage ? "yes" : "no"} onChange={(e) => setInfo({ ...info, auto_triage: e.target.value === "yes" })}>
                    <option value="yes">On: the agent shortlists new batches</option><option value="no">Off</option>
                  </select>
                </Field>
              </div>
              <Button kind="primary" busy={busy} type="submit">Save details</Button>
            </form>
          </Card>
        </div>
      </div>
    </div>
  );
}
