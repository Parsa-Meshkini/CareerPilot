import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { get, post } from "../api";
import { Badge, Button, Card, Empty, ErrorBox, Field, Notice, useAction } from "../components/ui";
import { titleCase, when } from "../format";

const STATUS_TONE = { COMPLETED: "good", FAILED: "bad", ABORTED: "bad", AWAITING_APPROVAL: "warn", AWAITING_INPUT: "warn" };
const EXAMPLES = ["Prepare my application for the Acme backend co-op", "How well do I fit the Northwind data role?",
  "Triage my newest imported jobs"];

function GoalBox() {
  const [goal, setGoal] = useState("");
  const [goalType, setGoalType] = useState("");
  const { busy, error, run } = useAction();
  const navigate = useNavigate();
  const start = (e) => {
    e.preventDefault();
    run(async () => {
      const r = await post("/agent/runs/", { goal, goal_type: goalType || undefined });
      navigate(`/agent/${r.id}`);
    });
  };
  return (
    <form onSubmit={start}>
      <ErrorBox error={error} />
      <Field label="What should the agent do?">
        <textarea rows={2} required value={goal} onChange={(e) => setGoal(e.target.value)} placeholder={EXAMPLES[0]} />
      </Field>
      <div className="row gap-s">
        <select value={goalType} onChange={(e) => setGoalType(e.target.value)} style={{ width: "auto" }}>
          <option value="">Detect goal type</option><option value="PREPARE_APPLICATION">Prepare application</option>
          <option value="FREE_FORM">Free form</option>
        </select>
        <Button kind="primary" busy={busy} type="submit">Run agent</Button>
        {busy && <span className="muted small">Planning and running tools…</span>}
      </div>
      <div className="row gap-s small">{EXAMPLES.map((ex) => <button type="button" key={ex} className="tab" onClick={() => setGoal(ex)}>{ex}</button>)}</div>
    </form>
  );
}

function Pending({ run, onUpdate }) {
  const [reason, setReason] = useState("");
  const { busy, error, run: act } = useAction();
  const p = run.pending;
  if (!p?.type) return null;
  if (p.type === "approval") {
    const decide = (approve) => act(async () => onUpdate(await post(`/agent/runs/${run.id}/approve/`, { approve, reason })));
    return (
      <Card title="Approval needed" className="card-attention">
        <ErrorBox error={error} />
        <p>The agent wants to run step {p.step + 1}:</p>
        <p className="mono">{p.description}</p>
        <Field label="Reason (optional, if you reject)"><input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <div className="row gap-s">
          <Button kind="primary" busy={busy} onClick={() => decide(true)}>Approve</Button>
          <Button kind="danger" busy={busy} onClick={() => decide(false)}>Reject</Button>
        </div>
      </Card>
    );
  }
  const answer = (choice) => act(async () => onUpdate(await post(`/agent/runs/${run.id}/answer/`, { choice })));
  return (
    <Card title="The agent has a question" className="card-attention">
      <ErrorBox error={error} />
      <p>{p.question}</p>
      <div className="row gap-s">{p.options.map((o) => <Button key={o.id} busy={busy} onClick={() => answer(o.id)}>{o.label}</Button>)}
        {!p.options.some((o) => o.id === "stop") && <Button kind="danger" busy={busy} onClick={() => answer("stop")}>Stop</Button>}</div>
    </Card>
  );
}

export function RunView() {
  const { id } = useParams();
  const [run, setRun] = useState(null);
  const { error, run: act } = useAction();
  const load = () => get(`/agent/runs/${id}/`).then(setRun);
  useEffect(() => { load(); }, [id]);
  if (!run) return <p className="muted">Loading run…</p>;
  const undo = (a) => act(async () => { await post(`/agent/actions/${a.id}/undo/`); load(); });

  return (
    <div className="stack">
      <div>
        <Link to="/agent" className="small">← Agent workspace</Link>
        <h1>{run.goal}</h1>
        <div className="row gap-s">
          <Badge tone={STATUS_TONE[run.status] || "accent"}>{titleCase(run.status)}</Badge>
          <Badge>{titleCase(run.goal_type)}</Badge>
          <Badge>Plan: {run.plan_source === "LLM" ? "AI planner" : "Playbook"}</Badge>
          <span className="small muted">{run.step_count} steps · {run.replan_count} replans · {run.tokens_used}/{run.token_budget} tokens</span>
        </div>
      </div>
      <ErrorBox error={error} />
      <Pending run={run} onUpdate={setRun} />
      {run.summary && <Notice tone="success">{run.summary}</Notice>}
      {run.error && <Notice tone="error">{run.error}</Notice>}

      <div className="grid-2 align-start">
        <Card title="Plan">
          {run.notes && <p className="small muted">{run.notes}</p>}
          <ol className="plan">{run.plan.map((s, i) => (
            <li key={i} className={`plan-step step-${s.status || "pending"} ${run.pending?.step === i ? "step-current" : ""}`}>
              <div className="row gap-s"><strong>{s.tool}</strong>
                <Badge tone={s.status === "done" ? "good" : s.status === "failed" || s.status === "rejected" ? "bad" : "muted"}>{s.status || "pending"}</Badge></div>
              <div className="small muted">{s.intent}</div>
              {s.observation && <div className="small">→ {s.observation}</div>}
            </li>
          ))}</ol>
          {run.plan_history.length > 0 && <div className="small muted">Replanned {run.plan_history.length}×: {run.plan_history.map((h) => h.rationale).join(" · ")}</div>}
        </Card>
        <Card title="Reasoning trace">
          {run.decisions.length === 0 ? <p className="muted small">No decisions yet.</p> : (
            <ul className="trace">{run.decisions.map((d, i) => (
              <li key={i}><Badge tone={d.action === "CONTINUE" || d.action === "FINISH" ? "good" : "warn"}>{d.action}</Badge>{" "}
                <span className="small">step {d.step + 1}: {d.rationale}</span>{d.source === "llm" && <Badge tone="accent">AI reflection</Badge>}</li>
            ))}</ul>
          )}
          {run.memories_used?.length > 0 && <>
            <div className="field-label" style={{ marginTop: 10 }}>Memories recalled</div>
            <ul className="bullets small">{run.memories_used.map((m, i) => <li key={i}>{m}</li>)}</ul></>}
        </Card>
      </div>

      <Card title="Action log">
        {run.actions.length === 0 ? <p className="muted small">No actions yet.</p> : (
          <table><thead><tr><th>Step</th><th>Tool</th><th>Result</th><th>Status</th><th /></tr></thead><tbody>
            {run.actions.map((a) => (
              <tr key={a.id}><td>{a.step_index + 1}</td><td className="mono small">{a.description}</td><td className="small">{a.observation}</td>
                <td><Badge tone={a.status === "EXECUTED" ? "good" : "warn"}>{titleCase(a.status)}</Badge></td>
                <td>{a.reversible && <Button className="btn btn-small" onClick={() => undo(a)}>Undo</Button>}</td></tr>
            ))}
          </tbody></table>
        )}
      </Card>
    </div>
  );
}

export default function Agent() {
  const [runs, setRuns] = useState(null);
  const [memory, setMemory] = useState([]);
  const [pref, setPref] = useState("");
  const { busy, error, run } = useAction();
  const load = () => { get("/agent/runs/").then(setRuns); get("/agent/memory/").then(setMemory); };
  useEffect(load, []);
  const addPref = (e) => { e.preventDefault(); run(async () => { await post("/agent/memory/", { content: pref }); setPref(""); load(); }); };
  return (
    <div className="stack">
      <h1>Agent workspace</h1>
      <Card><GoalBox /></Card>
      <div className="grid-2 align-start">
        <Card title="Recent runs">
          {!runs ? <p className="muted">Loading…</p> : runs.length === 0 ? <Empty>No runs yet.</Empty> : (
            <ul className="run-list">{runs.map((r) => (
              <li key={r.id}><Link to={`/agent/${r.id}`}>{r.goal}</Link>
                <div className="row gap-s small"><Badge tone={STATUS_TONE[r.status] || "accent"}>{titleCase(r.status)}</Badge>
                  <span className="muted">{titleCase(r.goal_type)} · {when(r.created_at)}</span></div></li>
            ))}</ul>
          )}
        </Card>
        <Card title="What the agent remembers">
          <ErrorBox error={error} />
          <form onSubmit={addPref} className="row gap-s">
            <input className="grow" placeholder="Add a preference, e.g. Not interested in QA roles" value={pref} onChange={(e) => setPref(e.target.value)} />
            <Button busy={busy} type="submit" disabled={!pref.trim()}>Add</Button>
          </form>
          <ul className="run-list">{memory.slice(0, 12).map((m) => (
            <li key={m.id}><Badge>{titleCase(m.kind)}</Badge> <span className="small">{m.content}</span></li>
          ))}</ul>
        </Card>
      </div>
    </div>
  );
}
