// Turns what the page reader found into the payload CareerPilot's York adapter expects.
// Pure functions only (no DOM access), so they can be tested with node. The DOM walker is in page_reader.js.

export const EXTRACTOR_VERSION = "york-list-3";

const clean = (s) => String(s ?? "").replace(/\s+/g, " ").trim();

/** tables: [{headers: string[], rows: [{cells: string[], url?: string}]}] -> records for POST /api/jobs/batches */
export function recordsFromTables(tables) {
  const records = [];
  for (const table of tables) {
    const headers = table.headers.map(clean);
    for (const row of table.rows) {
      const fields = {};
      headers.forEach((h, i) => {
        const value = clean(row.cells[i]);
        if (h && value) fields[h] = value;
      });
      if (Object.keys(fields).length) records.push({ url: row.url || "", fields });
    }
  }
  return records;
}

/** pairs: [[label, value]] from a single posting page, plus its description text. */
export function recordFromDetail(pairs, url, detailText) {
  const fields = {};
  for (const [label, value] of pairs) {
    const l = clean(label).replace(/:$/, "");
    const v = clean(value);
    if (l && v) fields[l] = v;
  }
  if (!Object.keys(fields).length) return null;
  return { url: url || "", fields, detail_text: clean(detailText) ? String(detailText).trim() : "" };
}

const flatten = (x) => (Array.isArray(x) ? x.flatMap(flatten) : x && x["@graph"] ? flatten(x["@graph"]) : [x]);

/** schema.org JobPosting blocks (used by most real job boards) -> records. */
export function recordsFromJsonLd(blocks) {
  const out = [];
  for (const b of flatten(blocks || [])) {
    const type = [].concat(b?.["@type"] || []).join(" ");
    if (!/JobPosting/i.test(type)) continue;
    const loc = [].concat(b.jobLocation || [])[0]?.address || {};
    const place = typeof loc === "string" ? loc : [loc.addressLocality, loc.addressRegion].filter(Boolean).join(", ");
    const org = typeof b.hiringOrganization === "string" ? b.hiringOrganization : b.hiringOrganization?.name;
    const salary = b.baseSalary?.value;
    const fields = {
      "Job Title": b.title, Organization: org, "Job Location": place || (b.jobLocationType === "TELECOMMUTE" ? "Remote" : ""),
      "Application Deadline": b.validThrough ? String(b.validThrough).slice(0, 10) : "", "Job Type": [].concat(b.employmentType || []).join(", "),
      "Job ID": b.identifier?.value || (typeof b.identifier === "string" ? b.identifier : ""),
      Salary: salary ? `${salary.minValue ?? salary.value ?? ""}${salary.maxValue ? "-" + salary.maxValue : ""} ${b.baseSalary?.currency || ""}`.trim() : "",
      "Work Arrangement": b.jobLocationType === "TELECOMMUTE" ? "Remote" : "",
    };
    const description = clean(String(b.description || "").replace(/<[^>]+>/g, " "));
    const cleaned = Object.fromEntries(Object.entries(fields).filter(([, v]) => clean(v)));
    if (Object.keys(cleaned).length) out.push({ url: b.url || "", fields: cleaned, detail_text: description });
  }
  return out;
}

/** Repeated job cards (title heading + company / location elements) -> records. */
export function recordsFromCards(cards) {
  const seen = new Set();
  const out = [];
  for (const c of cards || []) {
    const title = clean(c.title);
    if (!title || title.length > 150) continue;
    const key = `${title}|${clean(c.company)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const fields = { "Job Title": title, Organization: clean(c.company), "Job Location": clean(c.location), "Application Deadline": clean(c.deadline) };
    out.push({ url: c.url || "", fields: Object.fromEntries(Object.entries(fields).filter(([, v]) => v)) });
  }
  return out.filter((r) => r.fields.Organization);   // a card without a company is probably navigation, not a posting
}

/** Last resort for a single posting page: title from the heading, company from a "Company:" line or the site name. */
export function recordFromPlainPage(page) {
  const text = String(page.description || "");
  const line = (label) => clean((text.match(new RegExp(`(?:^|\\n)\\s*${label}\\s*[:\\-]\\s*([^\\n]+)`, "i")) || [])[1]);
  let title = clean(page.h1) || clean(page.ogTitle) || clean(page.pageTitle);
  let company = line("(?:company|employer|organization|organisation)") || clean(page.siteName);
  const at = title.match(/^(.*?)\s+(?:at|@|-|\|)\s+(.+)$/i);
  if (at && !company) { title = clean(at[1]); company = clean(at[2]); }
  if (!title || !company) return null;
  const fields = { "Job Title": title, Organization: company, "Job Location": line("(?:location|city)"), "Application Deadline": line("(?:deadline|closing date|apply by|application deadline)"),
    "Work Term": line("(?:work term|term)"), Salary: line("(?:salary|compensation|pay)") };
  return { url: page.url || "", fields: Object.fromEntries(Object.entries(fields).filter(([, v]) => v)), detail_text: text.trim() };
}

/** Tries every strategy in order and reports which one worked, so the popup can say how the page was read. */
export function extractAll(page) {
  const strategies = [
    ["table", () => recordsFromTables(page.tables || [])],
    ["schema.org data", () => recordsFromJsonLd(page.jsonld)],
    ["job cards", () => recordsFromCards(page.cards)],
    ["label/value pairs", () => { const r = recordFromDetail(page.pairs || [], page.url, page.description); return r ? [r] : []; }],
    ["page text", () => { const r = recordFromPlainPage(page); return r ? [r] : []; }],
  ];
  for (const [name, run] of strategies) {
    const records = run().filter((r) => clean(r.fields["Job Title"] || r.fields["Title"] || r.fields["Position Title"] || r.fields["Job title"]) || Object.keys(r.fields).length > 1);
    if (records.length) return { method: name, records };
  }
  return { method: "none", records: [] };
}

export function buildPayload(records) {
  return { extractor_version: EXTRACTOR_VERSION, records };
}
