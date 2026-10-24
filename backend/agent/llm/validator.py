import json
import re

import jsonschema

from .types import LLMValidationError

_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.I)


class ResponseValidator:
    """Parses model output as JSON and checks it against the prompt's schema."""

    def parse(self, text):
        cleaned = _FENCE.sub("", text.strip())
        try:
            return json.loads(cleaned)
        except ValueError:
            # models sometimes wrap JSON in prose; take the outermost object
            start, end = cleaned.find("{"), cleaned.rfind("}")
            if start != -1 and end > start:
                try:
                    return json.loads(cleaned[start:end + 1])
                except ValueError:
                    pass
            raise LLMValidationError("model output is not valid JSON")

    def validate(self, text, schema):
        data = self.parse(text)
        if schema is not None:
            try:
                jsonschema.validate(data, schema)
            except jsonschema.ValidationError as exc:
                path = "/".join(str(p) for p in exc.absolute_path) or "(root)"
                raise LLMValidationError(f"schema violation at {path}: {exc.message}") from exc
        return data
