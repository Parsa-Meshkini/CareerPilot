import test from "node:test";
import assert from "node:assert/strict";
import { buildPayload, recordFromDetail, recordsFromTables } from "../content/extractor.js";
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
  assert.equal(buildPayload([]).extractor_version, "york-list-2");
  assert.equal(describeBatch({ imported: 2, duplicates: 1, failed: 1, results: [{ outcome: "FAILED", reason: "missing title" }] }),
    "2 imported, 1 already saved, 1 skipped (missing title)");
});
