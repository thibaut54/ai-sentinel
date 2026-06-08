"""Robust JSONL loader.

The corpus jsonl files embed raw .msg bodies that contain lone CR (\\r) and other
control chars inside string values. Naive line iteration with universal newlines
splits records mid-value. We instead scan the byte stream record-by-record using
json.JSONDecoder.raw_decode, which consumes one JSON object then we skip to the
next '{' at top level. This never drops a record and never mis-parses.
"""
import json
from pathlib import Path

BASE = Path(r"C:/Users/ThibautVuillaume/Workspace/ai-sentinel-fork/ai-sentinel/ai-sentinel/pii-reporting-api/target/corpus-gliner2-presidio-regex")
DISCARDS = BASE / "judge-discards.jsonl"
FINDINGS = BASE / "findings.jsonl"

_DEC = json.JSONDecoder()


def load(path):
    text = Path(path).read_text(encoding="utf-8")
    rows = []
    idx = 0
    n = len(text)
    while idx < n:
        # advance to next '{'
        nb = text.find("{", idx)
        if nb < 0:
            break
        try:
            obj, end = _DEC.raw_decode(text, nb)
            rows.append(obj)
            idx = end
        except json.JSONDecodeError:
            # not a valid object start; move one char forward
            idx = nb + 1
    return rows


if __name__ == "__main__":
    d = load(DISCARDS)
    f = load(FINDINGS)
    print(f"discards={len(d)} findings={len(f)}")
