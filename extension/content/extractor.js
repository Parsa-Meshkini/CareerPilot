// Turns the tables and label/value pairs of a job-board page into the payload CareerPilot's York adapter expects.
// Pure functions only (no DOM access), so they can be tested with node. The DOM walker is in page_reader.js.

export const EXTRACTOR_VERSION = "york-list-2";

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

export function buildPayload(records) {
  return { extractor_version: EXTRACTOR_VERSION, records };
}
