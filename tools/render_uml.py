#!/usr/bin/env python3
"""Render every UMLet diagram in docs/uml/src to PNG and SVG in docs/uml/rendered.

Requires Java and UMLet (https://www.umlet.com). Point UMLET_JAR at umlet.jar, or unpack
UMLet into ./Umlet (ignored by git).

Usage:  python tools/render_uml.py [diagram-name ...]
"""
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "docs" / "uml" / "src"
OUT = ROOT / "docs" / "uml" / "rendered"
UMLET_JAR = Path(os.environ.get("UMLET_JAR", ROOT / "Umlet" / "umlet.jar"))
# UMLet writes the Java logical font "Dialog" into SVGs; browsers do not know it.
SVG_FONT = "'Lucida Grande', 'Helvetica Neue', Helvetica, Arial, sans-serif"


def render(uxf: Path, fmt: str) -> None:
    target = OUT / uxf.stem
    done = subprocess.run(["java", "-Djava.awt.headless=true", "-jar", str(UMLET_JAR), "-action=convert",
                           f"-format={fmt}", f"-filename={uxf}", f"-output={target}"],
                          capture_output=True, text=True)
    if "Conversion finished" not in done.stdout + done.stderr:
        raise SystemExit(f"UMLet failed on {uxf.name}:\n{done.stdout}{done.stderr}")
    if fmt == "svg":
        svg = target.with_suffix(".svg")
        svg.write_text(svg.read_text(encoding="utf-8").replace("'Dialog'", SVG_FONT), encoding="utf-8")


def main() -> None:
    if not UMLET_JAR.exists():
        raise SystemExit(f"umlet.jar not found at {UMLET_JAR} (set UMLET_JAR)")
    wanted = set(sys.argv[1:])
    for uxf in sorted(SRC.rglob("*.uxf")):
        if wanted and uxf.stem not in wanted:
            continue
        for fmt in ("png", "svg"):
            render(uxf, fmt)
        print(f"rendered {uxf.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
