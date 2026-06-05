"""Parse the IT seed SQL into the ``pii_type_configs`` dict the detectors expect.

Replicates the exact output shape of
``DatabaseConfigAdapter.fetch_pii_type_configs(detector=None)``:
  - composite key ``"<DETECTOR>:<PII_TYPE>"`` -> entry (always unique)
  - plain key ``"<PII_TYPE>"`` -> entry (last detector wins, same as adapter)

Entry shape::

    {"enabled": bool, "threshold": float, "detector": str, "category": str,
     "country_code": None, "detector_label": str|None,
     "detector_description": str|None}

Also extracts the global detector flags + default threshold from the
``pii_detection_config`` INSERT.

Standalone (stdlib only) so the bench harness never needs Postgres.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Dict, List, Optional, Tuple


def _split_tuples(values_blob: str) -> List[str]:
    """Split the VALUES blob into individual ``(...)`` tuple strings.

    Scans char-by-char tracking quote state (with ``''`` escapes) and paren
    depth, so commas inside quoted strings never split a tuple.
    """
    tuples: List[str] = []
    depth = 0
    in_quote = False
    current: List[str] = []
    i = 0
    while i < len(values_blob):
        ch = values_blob[i]
        if in_quote:
            current.append(ch)
            if ch == "'":
                # '' is an escaped quote: stay inside the string.
                if i + 1 < len(values_blob) and values_blob[i + 1] == "'":
                    current.append("'")
                    i += 1
                else:
                    in_quote = False
        else:
            if ch == "'":
                in_quote = True
                current.append(ch)
            elif ch == '(':
                depth += 1
                if depth == 1:
                    current = []
                else:
                    current.append(ch)
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    tuples.append(''.join(current))
                else:
                    current.append(ch)
            elif depth >= 1:
                current.append(ch)
        i += 1
    return tuples


def _split_fields(tuple_blob: str) -> List[str]:
    """Split one tuple body on top-level commas (quote-aware)."""
    fields: List[str] = []
    in_quote = False
    depth = 0
    current: List[str] = []
    i = 0
    while i < len(tuple_blob):
        ch = tuple_blob[i]
        if in_quote:
            current.append(ch)
            if ch == "'":
                if i + 1 < len(tuple_blob) and tuple_blob[i + 1] == "'":
                    current.append("'")
                    i += 1
                else:
                    in_quote = False
        elif ch == "'":
            in_quote = True
            current.append(ch)
        elif ch == '(':
            depth += 1
            current.append(ch)
        elif ch == ')':
            depth -= 1
            current.append(ch)
        elif ch == ',' and depth == 0:
            fields.append(''.join(current).strip())
            current = []
        else:
            current.append(ch)
        i += 1
    if current:
        fields.append(''.join(current).strip())
    return fields


def _parse_scalar(raw: str):
    raw = raw.strip()
    upper = raw.upper()
    if upper == 'NULL':
        return None
    if upper == 'TRUE':
        return True
    if upper == 'FALSE':
        return False
    if raw.startswith("'") and raw.endswith("'"):
        return raw[1:-1].replace("''", "'")
    try:
        return float(raw) if '.' in raw else int(raw)
    except ValueError:
        return raw  # CURRENT_TIMESTAMP etc.


_INSERT_RE = re.compile(
    r"INSERT\s+INTO\s+(?P<table>\w+)\s*"
    r"(?:\((?P<cols>[^)]*)\))?\s*"
    r"VALUES\s*(?P<values>.*?)(?:ON\s+CONFLICT[^;]*)?;",
    re.IGNORECASE | re.DOTALL,
)


def parse_seed(seed_path: Path) -> Tuple[dict, float, Dict[str, dict]]:
    """Return ``(detector_flags, default_threshold, pii_type_configs)``."""
    sql = seed_path.read_text(encoding='utf-8')
    # Strip line comments so VALUES blobs stay clean.
    sql = '\n'.join(
        line for line in sql.splitlines() if not line.lstrip().startswith('--')
    )

    detector_flags: dict = {}
    default_threshold = 0.5
    configs: Dict[str, dict] = {}

    for match in _INSERT_RE.finditer(sql):
        table = match.group('table').lower()
        cols = [c.strip() for c in (match.group('cols') or '').split(',') if c.strip()]
        tuples = _split_tuples(match.group('values'))

        if table == 'pii_detection_config':
            row = dict(zip(cols, (_parse_scalar(f) for f in _split_fields(tuples[0]))))
            detector_flags = {
                'gliner_enabled': bool(row.get('gliner_enabled', False)),
                'presidio_enabled': bool(row.get('presidio_enabled', False)),
                'regex_enabled': bool(row.get('regex_enabled', False)),
                'openmed_enabled': bool(row.get('openmed_enabled', False)),
                'gliner2_enabled': bool(row.get('gliner2_enabled', False)),
                'llm_judge_enabled': bool(row.get('llm_judge_enabled', False)),
            }
            default_threshold = float(row.get('default_threshold', 0.5))
            continue

        if table != 'pii_type_config':
            continue

        for tup in tuples:
            row = dict(zip(cols, (_parse_scalar(f) for f in _split_fields(tup))))
            pii_type = row.get('pii_type')
            detector = row.get('detector')
            if not pii_type or not detector:
                continue
            entry = {
                'enabled': bool(row.get('enabled', False)),
                'threshold': float(row.get('threshold', 0.5)),
                'detector': detector,
                'category': row.get('category'),
                'country_code': row.get('country_code'),
                'detector_label': row.get('detector_label'),
                'detector_description': row.get('detector_description'),
            }
            configs[pii_type] = entry              # plain key (adapter parity)
            configs[f"{detector}:{pii_type}"] = entry  # composite key

    return detector_flags, default_threshold, configs


def enabled_labels(configs: Dict[str, dict], detector: str) -> Dict[str, Optional[str]]:
    """Convenience: ``{detector_label: description}`` of enabled rows (debug aid)."""
    prefix = f"{detector}:"
    out: Dict[str, Optional[str]] = {}
    for key, cfg in configs.items():
        if key.startswith(prefix) and cfg.get('enabled') and cfg.get('detector_label'):
            out[cfg['detector_label']] = cfg.get('detector_description')
    return out


if __name__ == '__main__':
    import json
    import sys

    flags, threshold, cfgs = parse_seed(Path(sys.argv[1]))
    print(json.dumps({
        'detector_flags': flags,
        'default_threshold': threshold,
        'n_config_keys': len(cfgs),
        'gliner2_enabled_labels': enabled_labels(cfgs, 'GLINER2'),
    }, indent=2, ensure_ascii=False))
