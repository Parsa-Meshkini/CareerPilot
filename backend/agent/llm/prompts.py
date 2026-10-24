"""Versioned prompt templates loaded from backend/agent/prompts/*.json.

Each file holds {key, version, system, template, schema}. Templates use
$placeholders (string.Template) so JSON braces in prompts need no escaping.
"""
import json
from dataclasses import dataclass
from pathlib import Path
from string import Template

PROMPT_DIR = Path(__file__).resolve().parent.parent / "prompts"


@dataclass(frozen=True)
class PromptTemplate:
    key: str
    version: int
    system: str
    template: str
    schema: dict | None = None

    def render(self, variables):
        body = Template(self.template).substitute({k: _to_text(v) for k, v in variables.items()})
        if self.schema is not None:
            body += ("\n\nRespond with a single JSON object only, matching this JSON schema:\n<schema>"
                     + json.dumps(self.schema) + "</schema>")
        return body


def _to_text(value):
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, indent=1, default=str)
    return "" if value is None else str(value)


class PromptRegistry:
    def __init__(self, directory=PROMPT_DIR):
        self.directory = Path(directory)
        self._cache = {}

    def get(self, key):
        if key not in self._cache:
            path = self.directory / f"{key}.json"
            if not path.exists():
                raise KeyError(f"no prompt template '{key}'")
            data = json.loads(path.read_text())
            self._cache[key] = PromptTemplate(data["key"], data["version"], data["system"],
                                              data["template"], data.get("schema"))
        return self._cache[key]

    def keys(self):
        return sorted(p.stem for p in self.directory.glob("*.json"))
