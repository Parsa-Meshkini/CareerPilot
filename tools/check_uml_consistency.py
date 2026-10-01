#!/usr/bin/env python3
"""CareerPilot UML consistency checker (Stage 1 design artefact, also run in CI).

Reads the UMLet sources (docs/uml/src/**/*.uxf) and checks that:
  1. every lifeline ":ClassName" used in a sequence diagram is a class declared
     (with members) in at least one class diagram;
  2. every synchronous message sent to such a lifeline names a method that the
     target class declares or inherits in a class diagram (methods are merged across
     CD-1..CD-7; inheritance is read from the generalization / realization arrows);
  3. every "Class.method()" listed in docs/stage1/traceability.csv exists;
  4. every method shown in the main class diagram (CD-1) also appears in a
     detailed class diagram.

Usage:  python tools/check_uml_consistency.py        (exit code 1 on any error)
"""
import csv
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UML = ROOT / "docs" / "uml" / "src"
TRACE = ROOT / "docs" / "stage1" / "traceability.csv"

METHOD = re.compile(r"^[/_]?[+\-#~]\s*(\w+)\s*\(")
TOLERANCE = 4  # px: how close a relation end must be to a class border


def elements(path):
    """Yield (id, (x, y, w, h), panel_text, additional_attributes) for every element of a UMLet .uxf file."""
    for e in ET.parse(path).getroot().iter("element"):
        c = e.find("coordinates")
        box = tuple(int(c.find(k).text) for k in ("x", "y", "w", "h"))
        yield (e.findtext("id") or e.findtext("type") or "", box,
               e.findtext("panel_attributes") or "", e.findtext("additional_attributes") or "")


def class_name(panel):
    """Name of a UMLClass element: first line that is not a <<stereotype>>, without *bold* / /italic/ markers."""
    for line in panel.splitlines():
        line = line.strip()
        if line and not line.startswith("<<"):
            return line.strip("*/_")
    return None


def parse_class_file(path):
    """Return ({class: set(methods)}, [(parent, child)]) for one UMLet class diagram."""
    result, boxes, relations = defaultdict(set), {}, []
    for kind, box, panel, extra in elements(path):
        if kind == "UMLClass":
            name = class_name(panel)
            boxes[name] = box
            result[name]  # declare even if no methods
            for line in panel.splitlines():
                m = METHOD.match(line.strip())
                if m:
                    result[name].add(m.group(1))
        elif kind == "Relation":
            lt = re.search(r"^lt=(.*)$", panel, flags=re.M)
            nums = [float(v) for v in extra.split(";") if v]
            pts = [(box[0] + nums[i], box[1] + nums[i + 1]) for i in range(0, len(nums) - 1, 2)]
            relations.append((lt.group(1) if lt else "-", pts))

    def class_at(p):
        for name, (x, y, w, h) in boxes.items():
            if x - TOLERANCE <= p[0] <= x + w + TOLERANCE and y - TOLERANCE <= p[1] <= y + h + TOLERANCE:
                return name
        return None

    inherits = []
    for lt, pts in relations:
        # "<<" / ">>" is UMLet's closed, hollow triangle: generalization or realization.
        at_start = re.match(r"<<[-.]", lt) is not None
        at_end = re.search(r"[-.]>>$", lt) is not None
        if at_start or at_end:
            parent, child = (pts[0], pts[-1]) if at_start else (pts[-1], pts[0])
            parent, child = class_at(parent), class_at(child)
            if parent and child:
                inherits.append((parent, child))
    return result, inherits


def load_classes():
    """Merge methods across class diagrams and add inherited methods
    (a subclass may receive calls to methods declared by its superclass)."""
    merged, per_file, parents = defaultdict(set), {}, defaultdict(set)
    for f in sorted((UML / "class").glob("*.uxf")):
        parsed, inherits = parse_class_file(f)
        per_file[f.name] = parsed
        for cls, methods in parsed.items():
            merged[cls] |= methods
        for parent, child in inherits:
            parents[child].add(parent)

    def inherited(cls, seen=()):
        out = set()
        for p in parents.get(cls, ()):
            if p not in seen:
                out |= merged.get(p, set()) | inherited(p, seen + (cls,))
        return out

    resolved = defaultdict(set)
    for cls in list(merged):
        resolved[cls] = merged[cls] | inherited(cls)
    return resolved, per_file


LIFELINE = re.compile(r"^obj=(:?)(.+?)~(\w+)")
MESSAGE = re.compile(r"^(\w+)->>>(\w+)(?:\s*\+)?\s*:\s*(?:\d+:)?\s*([^;]*)")


def check_sequences(classes):
    errors, checked = [], 0
    for f in sorted((UML / "sequence").glob("*.uxf")):
        alias_to_class = {}
        text = "\n".join(panel for kind, _, panel, _ in elements(f) if kind == "UMLSequenceAllInOne")
        for line in text.splitlines():
            p = LIFELINE.match(line)
            if p and p.group(1) == ":":
                cls = p.group(2).strip()
                alias_to_class[p.group(3)] = cls
                if cls not in classes:
                    errors.append(f"{f.name}: lifeline :{cls} is not declared in any class diagram")
                continue
            m = MESSAGE.match(line)
            if not m:
                continue
            target, text = m.group(2), m.group(3).replace("\\n", "").strip()  # "\n" = line wrap inside a label
            if target not in alias_to_class:
                continue  # actor or <<external>> system
            text = re.sub(r"^\[[^\]]*\]\s*", "", text)  # strip guard
            if text.startswith("<<create>>"):
                continue
            name = re.match(r"(\w+)\s*\(", text)
            cls = alias_to_class[target]
            if not name:
                errors.append(f"{f.name}: message to :{cls} is not a method call: '{text}'")
                continue
            checked += 1
            if name.group(1) not in classes.get(cls, set()):
                errors.append(f"{f.name}: {cls}.{name.group(1)}() is not declared in the class diagrams")
    return errors, checked


def check_traceability(classes):
    errors, checked = [], 0
    if not TRACE.exists():
        return [f"missing {TRACE}"], 0
    with TRACE.open(encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            for cls in [c.strip() for c in row["Classes"].split(";") if c.strip()]:
                if cls not in classes:
                    errors.append(f"traceability {row['Feature']}: class {cls} not in class diagrams")
            for ref in [r.strip() for r in row["Key Methods"].split(";") if r.strip()]:
                m = re.match(r"(\w+)\.(\w+)\(\)", ref)
                if not m:
                    errors.append(f"traceability {row['Feature']}: bad method ref '{ref}'")
                    continue
                checked += 1
                if m.group(2) not in classes.get(m.group(1), set()):
                    errors.append(f"traceability {row['Feature']}: {ref} not declared")
    return errors, checked


def check_main_vs_detail(per_file):
    errors = []
    main = per_file.get("cd01_main_class_diagram.uxf", {})
    detail = defaultdict(set)
    for name, parsed in per_file.items():
        if name.startswith("cd01"):
            continue
        for cls, methods in parsed.items():
            detail[cls] |= methods
    for cls, methods in main.items():
        for meth in methods:
            if meth not in detail.get(cls, set()):
                errors.append(f"CD-1 shows {cls}.{meth}() but no detailed diagram declares it")
    return errors


def main():
    classes, per_file = load_classes()
    seq_errors, n_msgs = check_sequences(classes)
    tr_errors, n_refs = check_traceability(classes)
    md_errors = check_main_vs_detail(per_file)
    errors = seq_errors + tr_errors + md_errors
    print(f"classes: {len(classes)}  methods: {sum(len(v) for v in classes.values())}")
    print(f"sequence messages checked: {n_msgs}  traceability method refs checked: {n_refs}")
    for e in errors:
        print("ERROR:", e)
    print("OK - UML artefacts are mutually consistent" if not errors else f"{len(errors)} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
