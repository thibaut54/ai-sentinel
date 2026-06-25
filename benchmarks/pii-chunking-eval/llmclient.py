"""OpenAI-compatible client for the Ministral PII extractor (via LM Studio).

Ported from the proven Java ``LlmExtractorClient`` so results stay comparable to
the existing Ministral benchmarks:

* HTTP/1.1 and **no proxy** — LM Studio's embedded server hangs on the HTTP/2
  cleartext upgrade and must never be routed through the corporate proxy.
* ``temperature=0``, ``stream=false``, ``response_format`` json_schema forcing a
  bare ``[{text,label}]`` array (grammar-constrained, kills prose/markdown).
* Tolerant parsing: first balanced JSON array, several key spellings.

It additionally reads ``usage`` (prompt/completion tokens) and measures latency,
which the chunking benchmark needs, and reports server-side truncation by
comparing the locally counted prompt tokens against ``usage.prompt_tokens``.
"""
from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import List, Optional, Set, Tuple


@dataclass
class CallResult:
    entities: List[Tuple[str, str]]  # (value, label), pre-canonicalisation
    prompt_tokens: int = 0
    completion_tokens: int = 0
    latency_ms: float = 0.0
    ok: bool = True
    error: Optional[str] = None
    json_array_found: bool = True
    truncated: bool = False

    def as_cache(self) -> dict:
        return {
            "entities": [list(e) for e in self.entities],
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "latency_ms": self.latency_ms,
            "ok": self.ok,
            "error": self.error,
            "json_array_found": self.json_array_found,
        }

    @classmethod
    def from_cache(cls, d: dict) -> "CallResult":
        return cls(
            entities=[tuple(e) for e in d.get("entities", [])],
            prompt_tokens=d.get("prompt_tokens", 0),
            completion_tokens=d.get("completion_tokens", 0),
            latency_ms=d.get("latency_ms", 0.0),
            ok=d.get("ok", True),
            error=d.get("error"),
            json_array_found=d.get("json_array_found", True),
        )


_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # no proxy


def _is_timeout(exc: BaseException) -> bool:
    """True for a read/connect timeout (socket.timeout is TimeoutError on 3.10+)."""
    return isinstance(exc, TimeoutError) or isinstance(getattr(exc, "reason", None), TimeoutError)


class LlmClient:
    def __init__(self, base_url: str, model: str, max_tokens: int, timeout_s: float,
                 retries: int, json_schema: bool = True, system_prompt: Optional[str] = None):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.max_tokens = max_tokens
        self.timeout_s = timeout_s
        self.retries = max(1, retries)
        self.json_schema = json_schema
        self.system_prompt = system_prompt

    def list_models(self, timeout_s: float = 10.0) -> Set[str]:
        req = urllib.request.Request(self.base_url + "/models", method="GET")
        with _OPENER.open(req, timeout=timeout_s) as resp:
            body = resp.read().decode("utf-8", "replace")
        data = json.loads(body).get("data", [])
        return {n.get("id", "") for n in data if n.get("id")}

    def extract(self, text: str, local_prompt_tokens: int = 0) -> CallResult:
        body = self._build_body(text).encode("utf-8")
        last_err = None
        for attempt in range(1, self.retries + 1):
            t0 = time.perf_counter()
            try:
                req = urllib.request.Request(
                    self.base_url + "/chat/completions", data=body, method="POST",
                    headers={"Content-Type": "application/json"})
                with _OPENER.open(req, timeout=self.timeout_s) as resp:
                    raw = resp.read().decode("utf-8", "replace")
                latency_ms = (time.perf_counter() - t0) * 1000.0
                return self._parse_response(raw, latency_ms, local_prompt_tokens)
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
                last_err = f"{type(exc).__name__}: {exc}"
                if _is_timeout(exc):
                    # The request reached the model; it is simply slower than the
                    # timeout. Retrying just repeats the same slow inference, so
                    # fail fast (a too-large chunk would otherwise waste N×timeout).
                    break
            except Exception as exc:  # noqa: BLE001
                last_err = f"{type(exc).__name__}: {exc}"
            if attempt < self.retries:
                time.sleep(min(2.0 * attempt, 10.0))
        return CallResult(entities=[], ok=False, error=last_err, json_array_found=False)

    def _build_body(self, text: str) -> str:
        body = {
            "model": self.model,
            "temperature": 0,
            "stream": False,
            "max_tokens": self.max_tokens,
            "messages": [],
        }
        if self.json_schema:
            body["response_format"] = _entity_array_schema()
        if self.system_prompt:
            body["messages"].append({"role": "system", "content": self.system_prompt})
        body["messages"].append({"role": "user", "content": text})
        return json.dumps(body)

    def _parse_response(self, raw: str, latency_ms: float, local_prompt_tokens: int) -> CallResult:
        doc = json.loads(raw)
        usage = doc.get("usage", {}) or {}
        prompt_tokens = int(usage.get("prompt_tokens", 0) or 0)
        completion_tokens = int(usage.get("completion_tokens", 0) or 0)
        msg = (doc.get("choices") or [{}])[0].get("message", {}) or {}
        content = msg.get("content") or msg.get("reasoning_content") or ""
        array = first_json_array(content)
        entities = parse_entities(array) if array else []
        truncated = bool(local_prompt_tokens) and prompt_tokens > 0 and \
            prompt_tokens < 0.9 * local_prompt_tokens
        return CallResult(
            entities=entities, prompt_tokens=prompt_tokens, completion_tokens=completion_tokens,
            latency_ms=latency_ms, ok=True, json_array_found=array is not None, truncated=truncated)


def _entity_array_schema() -> dict:
    item = {
        "type": "object",
        "properties": {"text": {"type": "string"}, "label": {"type": "string"}},
        "required": ["text", "label"],
    }
    return {
        "type": "json_schema",
        "json_schema": {"name": "pii_entities", "strict": True,
                        "schema": {"type": "array", "items": item}},
    }


def first_json_array(s: str) -> Optional[str]:
    """First balanced top-level JSON array substring, or None (quote-aware)."""
    start = s.find("[")
    while start >= 0:
        depth = 0
        in_str = False
        esc = False
        for i in range(start, len(s)):
            c = s[i]
            if in_str:
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    in_str = False
                continue
            if c == '"':
                in_str = True
            elif c == "[":
                depth += 1
            elif c == "]":
                depth -= 1
                if depth == 0:
                    return s[start:i + 1]
        start = s.find("[", start + 1)
    return None


def parse_entities(array: str) -> List[Tuple[str, str]]:
    out: List[Tuple[str, str]] = []
    try:
        arr = json.loads(array)
    except json.JSONDecodeError:
        return out
    if not isinstance(arr, list):
        return out
    for e in arr:
        if not isinstance(e, dict):
            continue
        value = _first_text(e, "text", "value", "entity", "span")
        label = _first_text(e, "label", "type", "entity_type", "category")
        if value and label:
            out.append((value, label))
    return out


def _first_text(node: dict, *keys: str) -> Optional[str]:
    for k in keys:
        v = node.get(k)
        if isinstance(v, str) and v.strip():
            return v
    return None


# ---------------------------------------------------------------------------
# Offline stub — exercises the full pipeline (parse/map/merge/score/report)
# without a live endpoint. Uses cheap regexes so detections are non-trivial.
# ---------------------------------------------------------------------------
_STUB_PATTERNS: List[Tuple[re.Pattern, str]] = [
    (re.compile(r"\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b"), "iban"),
    (re.compile(r"\b(?:\d[ -]?){13,19}\b"), "credit_card_number"),
    (re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b"), "ip_address"),
    (re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b"), "email"),
    (re.compile(r"\bCHE-\d{3}\.\d{3}\.\d{3}\b"), "tax_number"),
]


@dataclass
class StubClient:
    """Drop-in for :class:`LlmClient` that fabricates detections offline."""
    model: str = "stub"
    latency_ms: float = 5.0
    _seen: dict = field(default_factory=dict)

    def list_models(self, timeout_s: float = 10.0) -> Set[str]:
        return {self.model}

    def extract(self, text: str, local_prompt_tokens: int = 0) -> CallResult:
        ents: List[Tuple[str, str]] = []
        for pat, label in _STUB_PATTERNS:
            for m in pat.finditer(text):
                ents.append((m.group(0), label))
        return CallResult(entities=ents, prompt_tokens=local_prompt_tokens,
                          completion_tokens=len(ents) * 8, latency_ms=self.latency_ms, ok=True)
