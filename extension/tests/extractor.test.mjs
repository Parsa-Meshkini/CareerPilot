import test from "node:test";
import assert from "node:assert/strict";
import { buildPayload, extractAll, recordFromDetail, recordsFromCards, recordsFromJsonLd, recordsFromTables } from "../content/extractor.js";
import { describeBatch } from "../api/client.js";

test("table rows become labelled field records and skip empty cells", () => {
  const records = recordsFromTables([{
    headers: ["Job ID", "Job Title", "Organization", "Application Deadline"],
    rows: [{ cells: ["1101", " Backend  Developer Co-op ", "Acme Corp", ""], url: "https://x/p?id=1101" },
           { cells: ["", "", "", ""], url: "" }],
  }]);
  assert.deepEqual(records, [{ url: "https://x/p?id=1101", fields: { "Job ID": "1101", "Job Title": "Backend Developer Co-op", Organization: "Acme Corp" } }]);
});

test("a detail page keeps its description as detail_text", () => {
  const r = recordFromDetail([["Job Title:", "Data Analyst"], ["Organization:", "Northwind"], ["", "ignored"]], "https://x/p?id=2", " We analyse data. ");
  assert.equal(r.fields["Job Title"], "Data Analyst");
  assert.equal(r.detail_text, "We analyse data.");
  assert.equal(recordFromDetail([], "u", "text"), null);
});

test("payload carries the extractor version and the batch summary is readable", () => {
  assert.equal(buildPayload([]).extractor_version, "york-list-3");
  assert.equal(describeBatch({ imported: 2, duplicates: 1, failed: 1, results: [{ outcome: "FAILED", reason: "missing title" }] }),
    "2 imported, 1 already saved, 1 skipped (missing title)");
});

test("schema.org JobPosting data becomes a record with its description", () => {
  const r = recordsFromJsonLd([{ "@type": "JobPosting", title: "DevOps", description: "<p>Run things</p>", hiringOrganization: { name: "Globex" },
    validThrough: "2026-12-15T23:59", jobLocation: { address: { addressLocality: "Ottawa", addressRegion: "ON" } } }]);
  assert.equal(r.length, 1);
  assert.equal(r[0].fields.Organization, "Globex");
  assert.equal(r[0].fields["Application Deadline"], "2026-12-15");
  assert.equal(r[0].fields["Job Location"], "Ottawa, ON");
  assert.equal(r[0].detail_text, "Run things");
});

test("cards without a company are ignored as navigation", () => {
  const r = recordsFromCards([{ title: "Intern", company: "Hooli", location: "Remote" }, { title: "Home", company: "" }, { title: "Intern", company: "Hooli" }]);
  assert.equal(r.length, 1);
});

test("extractAll picks the first strategy that works and a plain page works as a last resort", () => {
  assert.equal(extractAll({ tables: [{ headers: ["Job Title", "Organization"], rows: [{ cells: ["A", "B"] }] }] }).method, "table");
  const plain = extractAll({ tables: [], pairs: [], h1: "Software Intern", description: "Company: Umbrella\nLocation: Toronto\nDeadline: Dec 12, 2026\nlots of text", url: "u" });
  assert.equal(plain.method, "page text");
  assert.equal(plain.records[0].fields.Organization, "Umbrella");
  assert.equal(extractAll({ tables: [], pairs: [], h1: "About us", description: "nothing" }).method, "none");
});

test("every extracted posting keeps a link back to its page", () => {
  const detail = extractAll({ tables: [], jsonld: [{ "@type": "JobPosting", title: "DevOps", hiringOrganization: { name: "Globex" } }], url: "https://globex.example/jobs/7" });
  assert.equal(detail.records[0].url, "https://globex.example/jobs/7");
  const row = extractAll({ tables: [{ headers: ["Job Title", "Organization"], rows: [{ cells: ["A", "B"], url: "https://x/p/1" }, { cells: ["C", "D"] }] }], url: "https://x/list" });
  assert.deepEqual(row.records.map((r) => r.url), ["https://x/p/1", "https://x/list"]);
});
