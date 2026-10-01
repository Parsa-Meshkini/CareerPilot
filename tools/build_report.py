#!/usr/bin/env python3
"""Assemble docs/stage1/CareerPilot_Stage1_Report.md from docs/stage1/parts/*.md.

Section 8 (traceability) is generated from docs/stage1/traceability.csv so the table in the
report can never drift from the CSV that tools/check_uml_consistency.py verifies.
Optional: --pdf renders a PDF (requires pandoc + playwright/chromium).
"""
import csv
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STAGE1 = ROOT / "docs" / "stage1"
PARTS = STAGE1 / "parts"
OUT_MD = STAGE1 / "CareerPilot_Stage1_Report.md"


def traceability_section() -> str:
    rows = list(csv.DictReader((STAGE1 / "traceability.csv").open(encoding="utf-8")))
    check = subprocess.run([sys.executable, str(ROOT / "tools" / "check_uml_consistency.py")],
                           capture_output=True, text=True)
    lines = ["\n---\n", "# 8. Feature-to-Design Traceability\n",
             "## 8.1 Traceability Table\n",
             "Format as requested in Task 3. The machine-readable source is `docs/stage1/traceability.csv`.\n",
             "| Feature | Description | Type | Related Use Case | Classes | Key Methods | Sequence Diagram | Design Pattern(s) | Tier |",
             "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        classes = ", ".join(c.strip() for c in r["Classes"].split(";"))
        methods = ", ".join(m.strip() for m in r["Key Methods"].split(";"))
        lines.append(f"| {r['Feature']} | {r['Name']} | {r['Type']} | {r['Use Case'].replace(';', ',')} | {classes} | "
                     f"{methods} | {r['Sequence Diagram'].replace(';', ',')} | {r['Design Pattern(s)'].replace(';', ',')} | {r['Priority']} |")
    # coverage matrices
    ucs = sorted({u.strip() for r in rows for u in r["Use Case"].split(";")})
    sds = sorted({s.strip() for r in rows for s in r["Sequence Diagram"].split(";")})
    lines += ["\n### Coverage matrix — features × use cases\n",
              "| Feature | " + " | ".join(ucs) + " |", "|---|" + "---|" * len(ucs)]
    for r in rows:
        mine = {u.strip() for u in r["Use Case"].split(";")}
        lines.append(f"| {r['Feature']} | " + " | ".join("●" if u in mine else "" for u in ucs) + " |")
    lines += ["\n### Coverage matrix — features × sequence diagrams\n",
              "| Feature | " + " | ".join(sds) + " |", "|---|" + "---|" * len(sds)]
    for r in rows:
        mine = {s.strip() for s in r["Sequence Diagram"].split(";")}
        lines.append(f"| {r['Feature']} | " + " | ".join("●" if s in mine else "" for s in sds) + " |")
    pats = ["Adapter", "Strategy", "State", "Observer", "Command", "Template Method", "Facade"]
    lines += ["\n### Coverage matrix — patterns × features\n",
              "| Pattern | Features |", "|---|---|"]
    for p in pats:
        fs = [r["Feature"] for r in rows if p.lower() in r["Design Pattern(s)"].lower()]
        lines.append(f"| {p} | {', '.join(fs)} |")
    lines += ["\n## 8.2 Automated Consistency Verification\n",
              "`tools/check_uml_consistency.py` parses all UMLet (`.uxf`) sources and this table and checks that (1) every "
              "sequence-diagram lifeline is a declared class, (2) every message sent to a class lifeline is a method "
              "that class declares or inherits in the class diagrams, (3) every class and `Class.method()` in the "
              "traceability table exists, and (4) every method shown on the main class diagram exists in a detailed "
              "diagram. It runs in CI on every push. Output at the time of submission:\n",
              "```text", check.stdout.strip(), "```\n"]
    if check.returncode != 0:
        print(check.stdout)
        raise SystemExit("UML consistency check failed - fix before building the report")
    return "\n".join(lines) + "\n"


def build_md() -> None:
    parts = sorted(PARTS.glob("*.md"))
    chunks = []
    for p in parts:
        chunks.append(p.read_text(encoding="utf-8"))
        if p.name.startswith("07_"):
            chunks.append(traceability_section())
    OUT_MD.write_text("\n".join(chunks), encoding="utf-8")
    print(f"wrote {OUT_MD.relative_to(ROOT)} ({OUT_MD.stat().st_size // 1024} KB)")


def build_pdf() -> None:
    html = STAGE1 / "_report.html"
    css = STAGE1 / "parts" / "report.css"
    subprocess.run(["pandoc", str(OUT_MD), "-f", "markdown-auto_identifiers+gfm_auto_identifiers", "-t", "html5", "-s",
                    "--metadata", "title=CareerPilot Stage 1 Report", "-c", css.name,
                    "-o", str(html)], check=True, cwd=STAGE1)
    # copy css next to the html so relative link resolves
    (STAGE1 / css.name).write_text(css.read_text(encoding="utf-8"), encoding="utf-8")
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page()
        pg.goto(html.resolve().as_uri())
        pg.wait_for_load_state("networkidle")
        pg.pdf(path=str(STAGE1 / "CareerPilot_Stage1_Report.pdf"), format="Letter", print_background=True,
               margin={"top": "16mm", "bottom": "16mm", "left": "14mm", "right": "14mm"},
               display_header_footer=True, header_template="<span></span>",
               footer_template='<div style="font-size:8px;width:100%;text-align:center;color:#666">'
                               'CareerPilot — EECS 3311 Stage 1 — <span class="pageNumber"></span>/<span class="totalPages"></span></div>')
        b.close()
    html.unlink()
    (STAGE1 / css.name).unlink()
    print("wrote docs/stage1/CareerPilot_Stage1_Report.pdf")


if __name__ == "__main__":
    build_md()
    if "--pdf" in sys.argv:
        build_pdf()
