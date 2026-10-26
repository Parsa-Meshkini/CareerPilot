import { Badge, Chips } from "./ui";
import { scoreTone, titleCase } from "../format";

export function ScoreBadge({ score }) {
  return <span className={`score score-${scoreTone(score)}`}>{score ?? "–"}</span>;
}

export function Breakdown({ breakdown }) {
  return (
    <div className="bars">
      {Object.entries(breakdown || {}).map(([k, v]) => (
        <div key={k} className="bar-row">
          <span className="bar-label">{titleCase(k)}</span>
          <div className="bar"><div className="bar-fill" style={{ width: `${Math.round(v * 100)}%` }} /></div>
          <span className="bar-val">{Math.round(v * 100)}</span>
        </div>
      ))}
    </div>
  );
}

export default function MatchCard({ match }) {
  if (!match) return <p className="muted">Not scored yet.</p>;
  return (
    <div className="stack-s">
      <div className="row gap-m">
        <ScoreBadge score={match.score} />
        <div>
          <div><strong>{titleCase(match.strategy_key)}</strong> strategy</div>
          <div className="row gap-s">
            {match.is_deep_analysis ? <Badge tone="accent">Deep analysis</Badge> : <Badge>Quick score</Badge>}
            {match.outdated && <Badge tone="warn">Outdated: goal changed</Badge>}
          </div>
        </div>
      </div>
      <Breakdown breakdown={match.breakdown} />
      {match.constraint_notes?.length > 0 && <div className="alert alert-warn">{match.constraint_notes.join(" · ")}</div>}
      <div><div className="field-label">Matched skills</div><Chips items={match.matched_skills} tone="good" /></div>
      <div><div className="field-label">Missing skills</div><Chips items={match.missing_skills} tone="bad" /></div>
      {match.transferable?.length > 0 && (
        <div><div className="field-label">Transferable from your experience</div>
          <ul className="bullets">{match.transferable.map((t) => <li key={t.skill}><strong>{t.skill}</strong>: {t.reason}</li>)}</ul></div>
      )}
      {match.explanation && <p className="explain">{match.explanation}</p>}
      {match.suggestions?.length > 0 && (
        <div><div className="field-label">How to close the gaps</div>
          <ul className="bullets">{match.suggestions.map((s, i) => <li key={i}><strong>{s.skill}</strong>: {s.suggestion}</li>)}</ul></div>
      )}
    </div>
  );
}
