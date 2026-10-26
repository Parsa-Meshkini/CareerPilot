import { useState } from "react";
import { useAuth } from "../auth";
import { Button, ErrorBox, Field, useAction } from "../components/ui";

export default function Login() {
  const { signIn } = useAuth();
  const [mode, setMode] = useState("login");
  const [form, setForm] = useState({ email: "", password: "", name: "" });
  const { busy, error, run } = useAction();
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = (e) => {
    e.preventDefault();
    run(() => signIn(mode, form.email, form.password, form.name));
  };

  return (
    <div className="login-wrap">
      <form className="card login" onSubmit={submit}>
        <div className="brand brand-lg">Career<span>Pilot</span></div>
        <p className="muted">Your AI job-search agent. It prepares, tracks and recommends. You stay in control and submit every application yourself.</p>
        <ErrorBox error={error} />
        {mode === "register" && <Field label="Name"><input value={form.name} onChange={set("name")} /></Field>}
        <Field label="Email"><input type="email" required value={form.email} onChange={set("email")} /></Field>
        <Field label="Password" hint={mode === "register" ? "At least 8 characters" : null}>
          <input type="password" required minLength={mode === "register" ? 8 : 1} value={form.password} onChange={set("password")} />
        </Field>
        <Button kind="primary" busy={busy} type="submit">{mode === "login" ? "Sign in" : "Create account"}</Button>
        <button type="button" className="link" onClick={() => setMode(mode === "login" ? "register" : "login")}>
          {mode === "login" ? "New here? Create an account" : "Have an account? Sign in"}
        </button>
      </form>
    </div>
  );
}
