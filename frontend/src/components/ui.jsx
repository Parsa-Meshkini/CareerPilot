import { useState } from "react";

export function Card({ title, actions, children, className = "" }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="row gap-s">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function Button({ kind = "secondary", busy, children, ...props }) {
  return (
    <button className={`btn btn-${kind}`} disabled={busy || props.disabled} {...props}>
      {busy ? "Working…" : children}
    </button>
  );
}

export function Field({ label, hint, children }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Badge({ tone = "muted", children }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function ErrorBox({ error }) {
  if (!error) return null;
  return <div className="alert alert-error" role="alert">{error.message || String(error)}</div>;
}

export function Notice({ children, tone = "info" }) {
  return <div className={`alert alert-${tone}`}>{children}</div>;
}

export function Empty({ children }) {
  return <p className="empty">{children}</p>;
}

export function Chips({ items, tone }) {
  if (!items?.length) return <span className="muted">none</span>;
  return <div className="chips">{items.map((i) => <Badge key={i} tone={tone}>{i}</Badge>)}</div>;
}

// Runs an async action, tracking busy state and errors in one place.
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const run = async (fn) => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(e);
      return undefined;
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, run, setError };
}
