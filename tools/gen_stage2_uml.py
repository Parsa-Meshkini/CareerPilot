#!/usr/bin/env python3
"""Generate the Stage 2 UMLet class diagrams (docs/stage2/uml/src/*.uxf) from the Java sources.

Every box lists signatures read from the real code; inheritance and 'uses' arrows are drawn only when the code says so
(extends/implements, or a field / constructor parameter of that type). Edit SPECS to change what a diagram shows, then run
    python tools/gen_stage2_uml.py && python tools/render_uml.py   (render_uml reads docs/uml/src only; see render_stage2)
"""
import re
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "stage2" / "uml" / "src"
SRC = {p.stem: p for p in ROOT.glob("careerpilot-*/src/main/java/**/*.java")}

CHAR_W, LINE_H, PAD = 7.4, 16, 20
SKIP_METHODS = {"toString", "equals", "hashCode"}


def parse(name):
    text = SRC[name].read_text(encoding="utf-8")
    decl = re.search(r"public\s+(?:final\s+|abstract\s+|static\s+)*(class|interface|record|enum)\s+" + name + r"([^{]*)\{", text, re.S)
    kind, rest = decl.group(1), decl.group(2)
    abstract = "abstract class" in decl.group(0)
    ext = re.search(r"\bextends\s+([A-Z]\w*)", rest)
    impl = re.search(r"\bimplements\s+([^{]+)", rest)
    parents = []
    if ext:
        parents.append(ext.group(1))
    if impl:
        parents += [re.sub(r"<.*", "", x.strip()) for x in re.split(r",(?![^<]*>)", impl.group(1))]
    if kind == "interface":
        parents = [re.sub(r"<.*", "", x.strip()) for x in re.split(r",(?![^<]*>)", rest.split("extends", 1)[1])] if "extends" in rest else []
    fields = re.findall(r"^\s+private\s+(?:final\s+)?([A-Z]\w*)(?:<[^>]*>)?\s+(\w+)\s*;", text, re.M)
    methods = []
    pattern = r"^\s+(?:public\s+)?(?:abstract\s+|static\s+|final\s+)*(?:<[^>]+>\s+)?([\w<>\[\],.? ]+?)\s+(\w+)\(([^)]*)\)\s*(?:throws [\w, ]+)?[{;]"
    for m in re.finditer(pattern, text, re.M):
        ret, mname, args = m.groups()
        if mname in SKIP_METHODS or ret.strip() in ("return", "new", "else") or mname[0].isupper():
            continue
        line = text[:m.start()].count("\n")
        window = text[max(0, m.start() - 200):m.start()]
        line_text = m.group(0)
        if kind != "interface" and "public" not in line_text:
            continue
        if re.match(r"(get|is|set)[A-Z]", mname) and kind != "interface":
            continue
        args = re.sub(r"@\w+(\([^)]*\))?\s*", "", args)
        args = re.sub(r"\s+", " ", args).strip()
        if len(args) > 46:
            args = args[:44] + ".."
        methods.append(mname + "(" + args + "): " + " ".join(ret.split()))
    return dict(name=name, kind=kind, abstract=abstract, parents=parents, fields=fields, methods=methods)


class Box:
    def __init__(self, name, col, row, show=None, note=None, maxm=6):
        self.info = parse(name)
        self.name, self.col, self.row, self.note = name, col, row, note
        lines = []
        i = self.info
        stereo = {"interface": "<<interface>>", "record": "<<record>>", "enum": "<<enum>>"}.get(i["kind"])
        if i["abstract"]:
            stereo = "<<abstract>>"
        if stereo:
            lines.append(stereo)
        lines.append("*" + name + "*" if not i["abstract"] else "/" + name + "/")
        if note:
            lines.append("<<" + note + ">>")
        lines.append("--")
        picked = i["methods"] if show is None else [m for m in i["methods"] if any(m.startswith(s + "(") for s in show)]
        picked = picked[:maxm]
        lines += ["+ " + m for m in picked] or [" "]
        self.text = "\n".join(lines)
        longest = max(len(l) for l in lines)
        self.w = max(170, int(longest * CHAR_W + 24))
        self.h = (len(lines) + 1) * LINE_H + 8
        self.x = self.y = 0


def element(kind, x, y, w, h, text, extra=""):
    return f"""  <element>
    <id>{kind}</id>
    <coordinates>
      <x>{int(x)}</x>
      <y>{int(y)}</y>
      <w>{int(w)}</w>
      <h>{int(h)}</h>
    </coordinates>
    <panel_attributes>{escape(text)}</panel_attributes>
    <additional_attributes>{extra}</additional_attributes>
  </element>
"""


def edge_point(b, tx, ty):
    cx, cy = b.x + b.w / 2, b.y + b.h / 2
    dx, dy = tx - cx, ty - cy
    if dx == 0 and dy == 0:
        return cx, cy
    s = min((b.w / 2) / abs(dx) if dx else 1e9, (b.h / 2) / abs(dy) if dy else 1e9)
    return cx + dx * s, cy + dy * s


def relation(a, b, lt):
    """Line from box a to box b; lt arrow codes as in UMLet (first point = a)."""
    ax, ay = a.x + a.w / 2, a.y + a.h / 2
    bx, by = b.x + b.w / 2, b.y + b.h / 2
    p1, p2 = edge_point(a, bx, by), edge_point(b, ax, ay)
    x, y = min(p1[0], p2[0]), min(p1[1], p2[1])
    w, h = max(abs(p1[0] - p2[0]), 1), max(abs(p1[1] - p2[1]), 1)
    extra = f"{p1[0]-x:.0f};{p1[1]-y:.0f};{p2[0]-x:.0f};{p2[1]-y:.0f}"
    return element("Relation", x, y, w, h, "lt=" + lt + "\nlayer=-1", extra)


def build(fname, title, pattern_note, boxes, groups=(), uses_only=None, extra_rel=()):
    by = {b.name: b for b in boxes}
    colw, rowh = {}, {}
    for b in boxes:
        colw[b.col] = max(colw.get(b.col, 0), b.w)
        rowh[b.row] = max(rowh.get(b.row, 0), b.h)
    GAP_X, GAP_Y, X0, Y0 = 90, 70, 30, 110
    xs, acc = {}, X0
    for c in sorted(colw):
        xs[c] = acc
        acc += colw[c] + GAP_X
    ys, acc2 = {}, Y0
    for r in sorted(rowh):
        ys[r] = acc2
        acc2 += rowh[r] + GAP_Y
    for b in boxes:
        b.x = xs[b.col] + (colw[b.col] - b.w) / 2
        b.y = ys[b.row]
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n<diagram program="umlet" version="15.1">\n  <help_text>fontsize=12\n</help_text>\n  <zoom_level>10</zoom_level>\n']
    out.append(element("Text", 20, 10, max(acc - 60, 700), 60, f"*{title}*\nfontsize=16\n{pattern_note}\nstyle=wordwrap"))
    for gname, members in groups:
        ms = [by[m] for m in members if m in by]
        gx, gy = min(m.x for m in ms) - 12, min(m.y for m in ms) - 34
        gw, gh = max(m.x + m.w for m in ms) - gx + 12, max(m.y + m.h for m in ms) - gy + 12
        out.append(element("UMLPackage", gx, gy, gw, gh, gname + "\n--\nlayer=-10"))
    for b in boxes:
        out.append(element("UMLClass", b.x, b.y, b.w, b.h, b.text))
    drawn = set()
    for b in boxes:
        for p in b.info["parents"]:
            if p in by:
                lt = "<<." if by[p].info["kind"] == "interface" and b.info["kind"] != "interface" else "<<-"
                out.append(relation(by[p], b, lt))
                drawn.add((b.name, p))
    for b in boxes:
        for t, _ in b.info["fields"]:
            if t in by and t != b.name and (b.name, t) not in drawn and (uses_only is None or (b.name, t) in uses_only):
                out.append(relation(b, by[t], "->"))
                drawn.add((b.name, t))
    for a, c, lt in extra_rel:
        out.append(relation(by[a], by[c], lt))
    out.append("</diagram>\n")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / fname).write_text("".join(out), encoding="utf-8")
    print("wrote", fname, len(boxes), "classes")


def B(*a, **k):
    return Box(*a, **k)


def main():
    build("cd-s2-01_agent_command_facade.uxf", "CD-S2-1 Agent: Command and Facade (F09, F16)",
          "AgentController is the facade over the plan-act-observe loop. Each tool is an AgentCommand that ToolManager runs and can undo.",
          [B("AgentController", 1, 0, ["runGoal", "resumeRun", "approve", "cancel"], "Facade"),
           B("Planner", 0, 0, ["createPlan", "replan"]), B("PlaybookLibrary", 0, 1, None, "fallback plans per goal type", 4),
           B("Reasoner", 2, 0, ["evaluate"]), B("ApprovalPolicy", 2, 1, ["requiresApproval"]),
           B("ToolManager", 1, 1, ["execute", "undo", "register"], "Invoker"),
           B("AgentCommand", 1, 2, None, "Command"),
           B("MatchJobCommand", 0, 3), B("RankJobsCommand", 1, 3), B("UpdateApplicationStatusCommand", 2, 3), B("PrepareInterviewCommand", 3, 3),
           B("MemoryManager", 3, 1, ["remember", "recall"], "Memory"), B("AgentRun", 3, 0, None, "entity", 4)],
          groups=[("agent package", ["AgentController", "Planner", "PlaybookLibrary", "Reasoner", "ApprovalPolicy", "ToolManager", "AgentCommand",
                                     "MatchJobCommand", "RankJobsCommand", "UpdateApplicationStatusCommand", "PrepareInterviewCommand", "MemoryManager", "AgentRun"])])

    build("cd-s2-02_application_state_observer.uxf", "CD-S2-2 Application lifecycle State and Observer event bus (F13, F15)",
          "ApplicationState subclasses own the legal transitions. DomainEventBus delivers events to EventListener implementations without the publisher knowing them.",
          [B("Application", 1, 0, None, "Context", 5), B("ApplicationState", 1, 1, None, "State"),
           B("SavedState", 0, 2), B("AppliedState", 1, 2), B("RejectedState", 2, 2), B("OfferState", 3, 2),
           B("ApplicationService", 0, 0, ["transition", "undoTransition", "addInterview"]),
           B("DomainEventBus", 0, 4, ["publish", "subscribe"], "Subject"), B("EventListener", 1, 4, None, "Observer"),
           B("ApplicationStatusUpdater", 0, 5), B("InterviewScheduler", 1, 5), B("MemoryRecorder", 2, 5), B("InterviewPrepTrigger", 3, 5)],
          groups=[("applications", ["Application", "ApplicationState", "SavedState", "AppliedState", "RejectedState", "OfferState", "ApplicationService"]),
                  ("events and listeners", ["DomainEventBus", "EventListener", "ApplicationStatusUpdater", "InterviewScheduler", "MemoryRecorder", "InterviewPrepTrigger"])],
          extra_rel=[("DomainEventBus", "EventListener", "->")])

    build("cd-s2-03_strategy_adapter.uxf", "CD-S2-3 Strategy and Adapter (F03, F04, F05, F08, F14)",
          "Match scoring and email classification are interchangeable strategies; job sources, mail providers and LLM vendors sit behind adapter interfaces.",
          [B("MatchingService", 1, 0, ["matchJob", "selectStrategy", "rankJobs"], "Context"),
           B("MatchScoringStrategy", 0, 1, None, "Strategy"), B("CoopInternshipStrategy", 0, 2), B("EntryLevelStrategy", 1, 2), B("CareerSwitchStrategy", 2, 2),
           B("EmailClassificationStrategy", 3, 1, None, "Strategy"), B("RuleBasedClassifier", 3, 2), B("LlmEmailClassifier", 4, 2), B("HybridClassifier", 5, 2),
           B("JobSourceAdapter", 0, 4, None, "Target"), B("ManualJobImportAdapter", 0, 5), B("YorkBrowserImportAdapter", 1, 5), B("MockJobSourceAdapter", 2, 5),
           B("EmailProviderAdapter", 3, 4, None, "Target"), B("GmailAdapter", 3, 5, ["fetchNewMessages"]), B("EmlFileAdapter", 4, 5, ["fetchNewMessages"]), B("MockEmailAdapter", 5, 5, ["fetchNewMessages"])],
          groups=[("scoring strategies (jobs)", ["MatchingService", "MatchScoringStrategy", "CoopInternshipStrategy", "EntryLevelStrategy", "CareerSwitchStrategy"]),
                  ("classification strategies (integrations)", ["EmailClassificationStrategy", "RuleBasedClassifier", "LlmEmailClassifier", "HybridClassifier"]),
                  ("adapters", ["JobSourceAdapter", "ManualJobImportAdapter", "YorkBrowserImportAdapter", "MockJobSourceAdapter", "EmailProviderAdapter", "GmailAdapter", "EmlFileAdapter", "MockEmailAdapter"])],
          extra_rel=[("MatchingService", "MatchScoringStrategy", "->")])

    build("cd-s2-04_llm_facade_templates.uxf", "CD-S2-4 LLM Facade, provider Adapter and document Template Method (F02, F07, F10, F11)",
          "LlmClient hides prompts, validation, budgets and vendors behind one call. DocumentGenerator fixes the generation steps; subclasses fill in the parts that differ.",
          [B("LlmClient", 1, 0, ["generateStructured"], "Facade"), B("LlmProvider", 0, 1, None, "Adapter target"),
           B("GeminiProvider", 0, 2), B("OpenAiCompatibleProvider", 1, 2), B("MockLlmProvider", 2, 2),
           B("PromptRegistry", 2, 0, ["get"]), B("ResponseValidator", 2, 1, ["validate"]), B("TokenBudget", 3, 0), B("UsageTracker", 3, 1),
           B("DocumentGenerator", 1, 4, None, "Template Method"), B("ResumeGenerator", 0, 5), B("CoverLetterGenerator", 1, 5),
           B("FabricationGuard", 2, 4, ["check"], "Guard"), B("DocumentRenderer", 3, 4, None, "", 4)],
          groups=[("llm package", ["LlmClient", "LlmProvider", "GeminiProvider", "OpenAiCompatibleProvider", "MockLlmProvider", "PromptRegistry", "ResponseValidator", "TokenBudget", "UsageTracker"]),
                  ("documents package", ["DocumentGenerator", "ResumeGenerator", "CoverLetterGenerator", "FabricationGuard", "DocumentRenderer"])])

    build("cd-s2-05_interviews_analytics_notifications.uxf", "CD-S2-5 Period 3 features: notifications, interview preparation, mock interview, analytics (F15, F17, F18, F19)",
          "These services share ContextBuilder, FabricationGuard, LlmClient and MemoryManager with the rest of the system, and are triggered by the domain event bus.",
          [B("NotificationService", 0, 0, ["notify", "list", "markRead"]), B("ReminderService", 1, 0, ["scanDeadlines"]),
           B("InterviewPrepService", 0, 1, ["preparePlan"]), B("MockInterviewService", 1, 1, ["startSession", "submitAnswer", "finishSession"]),
           B("AnalyticsService", 0, 2, ["getInsights", "acceptAdjustment", "rejectAdjustment", "revertAdjustment"]),
           B("OutcomeAnalyzer", 1, 2, ["computeStats"]), B("StrategyAdvisor", 2, 2, ["propose"]),
           B("InterviewPrepPlan", 2, 1, None, "entity", 4), B("MockInterviewSession", 3, 1, None, "entity", 4), B("StrategyAdjustment", 3, 2, None, "entity", 4),
           B("FabricationGuard", 2, 0, ["check"]), B("LlmClient", 3, 0, ["generateStructured"]), B("MemoryManager", 2, 3, ["remember", "recall"]), B("ContextBuilder", 3, 3, None, "", 3)],
          groups=[("notifications", ["NotificationService", "ReminderService"]), ("interviews", ["InterviewPrepService", "MockInterviewService", "InterviewPrepPlan", "MockInterviewSession"]),
                  ("analytics", ["AnalyticsService", "OutcomeAnalyzer", "StrategyAdvisor", "StrategyAdjustment"])])


if __name__ == "__main__":
    main()
