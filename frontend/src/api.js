// CliApiClient's browser twin: every GUI action goes through the same REST API as the CLI.
const TOKEN_KEY = "careerpilot.token";

export class ApiError extends Error {
  constructor(status, data) {
    super(errorMessage(data, status));
    this.status = status;
    this.data = data || {};
  }
}

export function errorMessage(data, status) {
  if (!data) return `Request failed (${status})`;
  if (typeof data === "string") return data;
  if (data.message) {
    const fields = data.fields ? Object.entries(data.fields).map(([k, v]) => `${k}: ${v}`).join(", ") : "";
    return fields ? `${data.message} (${fields})` : data.message;
  }
  if (data.detail) return data.detail;
  const first = Object.entries(data)[0];
  if (first) return `${first[0]}: ${Array.isArray(first[1]) ? first[1].join(" ") : first[1]}`;
  return `Request failed (${status})`;
}

export function getToken() {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}

export function setToken(token) {
  try { token ? localStorage.setItem(TOKEN_KEY, token) : localStorage.removeItem(TOKEN_KEY); } catch { /* private mode */ }
}

export async function api(method, path, body, { form = false } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Token ${token}`;
  let payload;
  if (form) payload = body;
  else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  const res = await fetch(`/api${path}`, { method, headers, body: payload });
  if (res.status === 401 && token) {
    setToken(null);
    window.dispatchEvent(new Event("careerpilot:logout"));
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

export const get = (p) => api("GET", p);
export const post = (p, b) => api("POST", p, b ?? {});
export const put = (p, b) => api("PUT", p, b);
export const patch = (p, b) => api("PATCH", p, b);
export const del = (p) => api("DELETE", p);
export const upload = (p, file, field = "file") => {
  const fd = new FormData();
  fd.append(field, file);
  return api("POST", p, fd, { form: true });
};

// Downloads go through fetch so the auth token is sent; then the blob is saved.
export async function download(path, filename) {
  const res = await fetch(`/api${path}`, { headers: { Authorization: `Token ${getToken()}` } });
  if (!res.ok) throw new ApiError(res.status, await res.json().catch(() => null));
  const url = URL.createObjectURL(await res.blob());
  const a = Object.assign(document.createElement("a"), { href: url, download: filename });
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
