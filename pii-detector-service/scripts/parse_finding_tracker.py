"""
Parse les logs ``[FINDING_TRACKER]`` produits par le pipeline pii-detector
et imprime un tableau de bisection.

Logs instrumentes (couvre 100% des etapes ou le compteur de findings change) :

    composite_detector.py :
        COMPOSITE_REGEX_RAW              count=N
        COMPOSITE_PRESIDIO_RAW           count=N
        COMPOSITE_MINISTRAL_RAW          count=N
        COMPOSITE_AFTER_MERGE            in=N out=M dropped=K

    detection_merger.py :
        MERGER_AFTER_DEDUP               in=N out=M dropped=K
        MERGER_AFTER_OVERLAP             in=N out=M dropped=K

    pii_service.py :
        GRPC_AFTER_DETECTION             count=N
        GRPC_AFTER_TYPE_CONFIG_FILTER    in=N out=M dropped=K
        GRPC_FINAL_RESPONSE              count=N

Usage :
    # Apres un run avec logs captures dans un fichier
    python scripts/parse_finding_tracker.py logs.txt

    # Stdin
    docker compose logs pii-detector | python scripts/parse_finding_tracker.py -

    # Filtrer sur un seul request_id
    python scripts/parse_finding_tracker.py logs.txt --request-id req_1234567890
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import OrderedDict
from pathlib import Path
from typing import Iterable

# Pattern matchant l'une des deux formes :
#  [FINDING_TRACKER] [request_id] step=NAME count=N
#  [FINDING_TRACKER] step=NAME in=N out=M dropped=K
PATTERN = re.compile(
    r"\[FINDING_TRACKER\]"
    r"(?:\s*\[(?P<request_id>[^\]]+)\])?"
    r"\s+step=(?P<step>\w+)"
    r"(?:\s+count=(?P<count>\d+))?"
    r"(?:\s+in=(?P<in_>\d+)\s+out=(?P<out>\d+)(?:\s+dropped=(?P<dropped>-?\d+))?)?"
)

# Ordre canonique d'affichage (du debut a la fin du pipeline)
CANONICAL_ORDER = [
    "COMPOSITE_REGEX_RAW",
    "COMPOSITE_PRESIDIO_RAW",
    "COMPOSITE_MINISTRAL_RAW",
    "COMPOSITE_AFTER_MERGE",
    "MERGER_AFTER_DEDUP",
    "MERGER_AFTER_OVERLAP",
    "GRPC_AFTER_DETECTION",
    "GRPC_AFTER_TYPE_CONFIG_FILTER",
    "GRPC_FINAL_RESPONSE",
]


def parse_lines(lines: Iterable[str]) -> list[dict]:
    """Extrait les events FINDING_TRACKER d'un flux de lignes."""
    events: list[dict] = []
    for line in lines:
        m = PATTERN.search(line)
        if not m:
            continue
        events.append({
            "request_id": m.group("request_id"),
            "step": m.group("step"),
            "count": int(m.group("count")) if m.group("count") else None,
            "in_": int(m.group("in_")) if m.group("in_") else None,
            "out": int(m.group("out")) if m.group("out") else None,
            "dropped": int(m.group("dropped")) if m.group("dropped") else None,
        })
    return events


def group_by_request(events: list[dict]) -> "OrderedDict[str, list[dict]]":
    """Groupe les events par request_id (None pour les composants stateless)."""
    groups: "OrderedDict[str, list[dict]]" = OrderedDict()
    for ev in events:
        rid = ev.get("request_id") or "<no-request-id>"
        groups.setdefault(rid, []).append(ev)
    return groups


def display_request_table(rid: str, events: list[dict]) -> None:
    """Affiche un tableau de bisection pour un seul request_id."""
    print(f"\n{'=' * 90}")
    print(f"REQUEST_ID = {rid}  ({len(events)} events)")
    print("=" * 90)
    print(f"{'#':<3} {'step':<35} {'in':>8} {'out':>8} {'dropped':>9} {'recall%':>8}")
    print("-" * 90)

    # Recall calcule par rapport au premier `in` ou `count` rencontre dans l'ordre canonique
    by_step = {ev["step"]: ev for ev in events if ev["step"]}
    initial = None
    for canonical in CANONICAL_ORDER:
        if canonical in by_step:
            ev = by_step[canonical]
            initial = ev["count"] if ev["count"] is not None else ev["in_"]
            break
    if initial is None:
        initial = 0

    idx = 1
    last_count: int | None = None
    for canonical in CANONICAL_ORDER:
        ev = by_step.get(canonical)
        if not ev:
            continue
        if ev["count"] is not None:
            in_str, out_str, drop_str = "  --  ", str(ev["count"]), "  --  "
            cur = ev["count"]
        else:
            in_str = str(ev["in_"]) if ev["in_"] is not None else "?"
            out_str = str(ev["out"]) if ev["out"] is not None else "?"
            dropped = ev["dropped"]
            if dropped is None and ev["in_"] is not None and ev["out"] is not None:
                dropped = ev["in_"] - ev["out"]
            drop_str = (f"{dropped:+d}" if dropped is not None else "  --  ")
            cur = ev["out"] if ev["out"] is not None else None
        recall = (100.0 * cur / initial) if (cur is not None and initial > 0) else 0.0
        marker = ""
        if last_count is not None and cur is not None:
            delta = cur - last_count
            if delta < 0 and abs(delta) >= 5:  # chute notable
                marker = "  <-- chute"
        print(f"{idx:<3} {canonical:<35} {in_str:>8} {out_str:>8} {drop_str:>9} {recall:>7.1f}%{marker}")
        last_count = cur
        idx += 1

    # Steps inconnus (non canonical)
    extras = [ev for ev in events if ev["step"] not in CANONICAL_ORDER]
    if extras:
        print("-" * 90)
        print("Steps non canoniques :")
        for ev in extras:
            payload = (
                f"count={ev['count']}" if ev["count"] is not None
                else f"in={ev['in_']} out={ev['out']} dropped={ev['dropped']}"
            )
            print(f"     {ev['step']:<35} {payload}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("file", help='Fichier de logs ou "-" pour stdin')
    parser.add_argument("--request-id", help="Filtre sur un seul request_id")
    args = parser.parse_args()

    if args.file == "-":
        events = parse_lines(sys.stdin)
    else:
        path = Path(args.file)
        if not path.exists():
            print(f"Fichier introuvable : {path}", file=sys.stderr)
            return 2
        with path.open("r", encoding="utf-8", errors="replace") as f:
            events = parse_lines(f)

    if not events:
        print("Aucun event [FINDING_TRACKER] trouve dans les logs.", file=sys.stderr)
        return 1

    groups = group_by_request(events)
    if args.request_id:
        if args.request_id not in groups:
            print(f"request_id {args.request_id} non trouve. Disponibles : {list(groups)}", file=sys.stderr)
            return 1
        groups = OrderedDict([(args.request_id, groups[args.request_id])])

    for rid, ev_list in groups.items():
        display_request_table(rid, ev_list)

    print(f"\nTotal : {len(events)} events parses sur {len(groups)} request(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
