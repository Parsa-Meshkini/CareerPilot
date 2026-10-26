import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { download, get, patch, post, put } from "../api";
import { Badge, Button, Card, Empty, ErrorBox, Field, Notice, useAction } from "../components/ui";
import { actionLabel, deadlineLabel, titleCase, when } from "../format";

const COLUMNS = [["SAVED", "Saved"], ["PREPARED", "Prepared"], ["APPLIED", "Applied"], ["INTERVIEW", "Interview"],
  ["OFFER", "Offer"], ["CLOSED", "Closed"]];
const CLOSED = ["ACCEPTED", "DECLINED", "REJECTED", "WITHDRAWN"];

function DocumentEditor({ doc, onSaved, onCancel }) {
  const [content, setContent] = useState(JSON.parse(JSON.stringify(doc.content)));
  const { busy, error, run } = useAction();
  const save = () => run(async () => { await put(`/documents/${doc.id}/`, { content }); onSaved(); });
  const isResume = doc.doc_type === "RESUME";
  const setBullet = (ei, bi, text) => {
    const c = { ...content, entries: content.entries.map((e, i) => i !== ei ? e : {
      ...e, bullets: e.bullets.map((b, j) => j !== bi ? b : { ...b, text }) }) };
    setContent(c);
  };
  const setPara = (pi, text) => setContent({ ...content, paragraphs: content.paragraphs.map((p, i) => i !== pi ? p : { ...p, text }) });
  return (
    <div className="editor">
      <ErrorBox error={error} />
      <p className="small muted">Edits are checked by the fabrication guard again and saved as a new version.</p>
      {isResume ? content.entries.map((e, ei) => e.bullets.map((b, bi) => (
        <textarea key={`${ei}-${bi}`} rows={2} value={b.text} onChange={(ev) => setBullet(ei, bi, ev.target.value)} />
      ))) : content.paragraphs.map((p, pi) => (
        <textarea key={pi} rows={4} value={p.text} onChange={(ev) => setPara(pi, ev.target.value)} />
      ))}
      <div className="row gap-s"><Button kind="primary" busy={busy} onClick={save}>Save new version</Button><Button onClick={onCancel}>Cancel</Button></div>
    </div>
  );
}

function DocumentItem({ doc, onChange }) {
  const [editing, setEditing] = useState(false);
  const [open, setOpen] = useState(false);
  const { error, run } = useAction();
  const rep = doc.verification_report || {};
  const name = doc.doc_type === "RESUME" ? "Resume" : "Cover_Letter";
  return (
    <li className="doc">
      <div className="row space">
        <div className="row gap-s">
          <strong>{titleCase(doc.doc_type)}</strong><span className="muted small">v{doc.version}</span>
          <Badge tone={doc.verification_status === "PASSED" ? "good" : "warn"}>
            {doc.verification_status === "PASSED" ? "Verified against your facts" : "Needs review"}</Badge>
          {doc.stale && <Badge tone="bad">Stale: a cited fact was deleted</Badge>}
        </div>
        <div className="row gap-s">
          {["pdf", "docx"].map((f) => <button key={f} className="link small" onClick={() => run(() => download(`/documents/${doc.id}/download/${f}/`, `${name}_v${doc.version}.${f}`))}>{f.toUpperCase()}</button>)}
          <button className="link small" onClick={() => setOpen(!open)}>{open ? "Hide" : "Details"}</button>
          <button className="link small" onClick={() => setEditing(true)}>Edit</button>
        </div>
      </div>
      <ErrorBox error={error} />
      {open && (
        <div className="small stack-s">
          <div className="muted">Checked {rep.checked} claims{rep.regenerated ? " · regenerated once with guard feedback" : ""}{rep.edited ? " · edited by you" : ""}</div>
          {rep.violations?.length > 0 && <ul className="violations">{rep.violations.map((v, i) => (
            <li key={i}><Badge tone="bad">{titleCase(v.rule)}</Badge> {v.detail}{v.text && <div className="muted">“{v.text}”</div>}</li>
          ))}</ul>}
          {doc.doc_type === "RESUME" ? doc.content.entries?.map((e, i) => (
            <ul key={i} className="bullets">{e.bullets.map((b, j) => <li key={j}>{b.text} <span className="muted tiny">[{b.source_fact_ids.length} fact{b.source_fact_ids.length === 1 ? "" : "s"}]</span></li>)}</ul>
          )) : doc.content.paragraphs?.map((p, i) => <p key={i}>{p.text}</p>)}
        </div>
      )}
      {editing && <DocumentEditor doc={doc} onCancel={() => setEditing(false)} onSaved={() => { setEditing(false); onChange(); }} />}
    </li>
  );
}

function Documents({ app }) {
  const [docs, setDocs] = useState([]);
  const [packages, setPackages] = useState([]);
  const [tone, setTone] = useState("formal");
  const { busy, error, run } = useAction();
  const load = () => {
    get(`/documents/applications/${app.id}/`).then(setDocs);
    get(`/documents/packages/applications/${app.id}/`).then(setPackages);
  };
  useEffect(load, [app.id]);
  const latest = (t) => docs.find((d) => d.doc_type === t);
  const gen = (kind) => run(async () => { await post(`/documents/applications/${app.id}/${kind}/`, { tone }); load(); });
  const pack = (regenerate) => run(async () => { await post(`/documents/packages/applications/${app.id}/`, { tone, regenerate }); load(); app.reload(); });
  return (
    <div className="stack-s">
      <ErrorBox error={error} />
      <div className="row gap-s">
        <Button busy={busy} onClick={() => gen("resume")}>Generate resume</Button>
        <Button busy={busy} onClick={() => gen("cover_letter")}>Generate cover letter</Button>
        <select value={tone} onChange={(e) => setTone(e.target.value)} style={{ width: "auto" }}>
          <option value="formal">Formal tone</option><option value="warm">Warm tone</option>
        </select>
      </div>
      {busy && <Notice>The AI is writing and the fabrication guard is checking every line. This can take a few seconds.</Notice>}
      <ul className="docs">{[latest("RESUME"), latest("COVER_LETTER")].filter(Boolean).map((d) => <DocumentItem key={d.id} doc={d} onChange={load} />)}</ul>
      <div className="package">
        <div className="row space">
          <strong>Application package</strong>
          <div className="row gap-s">
            <Button kind="primary" busy={busy} onClick={() => pack(false)}>Build package ZIP</Button>
            {packages.length > 0 && <Button busy={busy} onClick={() => pack(true)}>Rebuild with new documents</Button>}
          </div>
        </div>
        {packages.slice(0, 3).map((p) => (
          <div key={p.id} className="row space small">
            <span>{p.folder_name}.zip · {when(p.created_at)} {p.needs_review && <Badge tone="warn">Review flagged content</Badge>}</span>
            <button className="link" onClick={() => download(`/documents/packages/${p.id}/download/`, `${p.folder_name}.zip`)}>Download</button>
          </div>
        ))}
        <p className="tiny muted">Resume, cover letter, job description, checklist and info file. CareerPilot never submits it. You do.</p>
      </div>
    </div>
  );
}

function Drawer({ id, onClose, onChange }) {
  const [app, setApp] = useState(null);
  const [notes, setNotes] = useState("");
  const { busy, error, run } = useAction();
  const load = () => get(`/applications/${id}/`).then((a) => { setApp(a); setNotes(a.notes); });
  useEffect(() => { load(); }, [id]);
  if (!app) return null;
  const refresh = () => { load(); onChange(); };
  const act = (action) => run(async () => { await post(`/applications/${id}/transition/`, { action, version: app.version }); refresh(); });
  const undo = () => run(async () => { await post(`/applications/changes/${app.undoable_change_id}/undo/`); refresh(); });
  const saveNotes = () => run(async () => { await patch(`/applications/${id}/`, { notes }); refresh(); });
  const last = app.timeline.find((c) => !c.reverted);

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside className="drawer" onClick={(e) => e.stopPropagation()}>
        <div className="row space">
          <div><Link to={`/jobs/${app.job.id}`}><h2>{app.job.title}</h2></Link>
            <div className="muted small">{app.job.company} · {deadlineLabel(app.job)}</div></div>
          <button className="icon-btn" onClick={onClose} aria-label="close">✕</button>
        </div>
        <ErrorBox error={error} />
        <div className="row gap-s"><Badge tone="accent">{titleCase(app.state)}</Badge>
          {app.applied_at && <span className="small muted">Applied {when(app.applied_at)}</span>}</div>
        <div className="row gap-s">
          {app.allowed_actions.length === 0 && <span className="muted small">Final state: no further actions.</span>}
          {app.allowed_actions.map((a) => <Button key={a} busy={busy} onClick={() => act(a)}>{actionLabel(a)}</Button>)}
          {last && <Button kind="danger" busy={busy} onClick={undo}>Undo “{actionLabel(last.action)}”</Button>}
        </div>
        <Card title="Documents"><Documents app={{ ...app, reload: refresh }} /></Card>
        <Card title="Timeline">
          {app.timeline.length === 0 ? <p className="muted small">No changes yet.</p> : (
            <ul className="timeline">{app.timeline.map((c) => (
              <li key={c.id} className={c.reverted && c.action !== "undo" ? "reverted" : ""}>
                <span className="small">{when(c.created_at)}</span>{" "}
                <strong>{c.from_state} → {c.to_state}</strong>{" "}
                <Badge tone={c.source === "AGENT" ? "accent" : c.source === "EMAIL_EVENT" ? "warn" : "muted"}>{titleCase(c.source)}</Badge>
                {c.reason && <span className="muted small"> {c.reason}</span>}
              </li>
            ))}</ul>
          )}
        </Card>
        <Field label="Notes"><textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <Button busy={busy} onClick={saveNotes}>Save notes</Button>
      </aside>
    </div>
  );
}

export default function Tracker() {
  const [apps, setApps] = useState(null);
  const [params, setParams] = useSearchParams();
  const open = params.get("open");
  const load = () => get("/applications/").then(setApps);
  useEffect(() => { load(); }, []);
  if (!apps) return <p className="muted">Loading…</p>;
  const column = (key) => apps.filter((a) => (key === "CLOSED" ? CLOSED.includes(a.state) : a.state === key));
  return (
    <div className="stack">
      <h1>Application tracker</h1>
      {apps.length === 0 ? <Empty>No applications yet. Open a job and click “Track application”.</Empty> : (
        <div className="kanban">
          {COLUMNS.map(([key, label]) => (
            <div key={key} className="kanban-col">
              <div className="kanban-head">{label} <span className="muted">{column(key).length}</span></div>
              {column(key).map((a) => (
                <button key={a.id} className="kanban-card" onClick={() => setParams({ open: a.id })}>
                  <strong>{a.job.title}</strong>
                  <span className="muted small">{a.job.company}</span>
                  <span className="row gap-s small">
                    {key === "CLOSED" && <Badge>{titleCase(a.state)}</Badge>}
                    <span className={a.job.days_left !== null && a.job.days_left <= 7 ? "warn-text" : "muted"}>{deadlineLabel(a.job)}</span>
                  </span>
                </button>
              ))}
            </div>
          ))}
        </div>
      )}
      {open && <Drawer id={open} onClose={() => setParams({})} onChange={load} />}
    </div>
  );
}
