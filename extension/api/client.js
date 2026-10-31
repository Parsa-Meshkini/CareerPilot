// Minimal CareerPilot REST client used by the popup. The token is kept in chrome.storage.local, never in the page.

export async function login(server, email, password) {
  const res = await fetch(`${server}/api/auth/login`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email, password }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(body.message || "Sign-in failed");
  return body.token;
}

export async function postBatch(server, token, payload) {
  const res = await fetch(`${server}/api/jobs/batches`, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ source: "york", payload }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(body.message || `Import failed (${res.status})`);
  return body;
}

export function describeBatch(batch) {
  const parts = [`${batch.imported} imported`];
  if (batch.duplicates) parts.push(`${batch.duplicates} already saved`);
  if (batch.failed) parts.push(`${batch.failed} skipped (${batch.results.filter((r) => r.outcome === "FAILED").map((r) => r.reason).join("; ")})`);
  return parts.join(", ");
}
