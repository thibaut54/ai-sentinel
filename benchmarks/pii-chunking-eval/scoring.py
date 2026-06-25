"""Value-level P/R/F1, ported from the Java ``ValueScorer`` / ``LabelCounts``.

Matches predicted ``(canonical, value)`` pairs against the gold spans'
``(canonical, value)`` as multisets, value normalised (trim, lower-case, collapse
whitespace). Two views:

* **strict**        — same canonical concept AND same value;
* **type-agnostic** — same value, label ignored (the gap to strict = typing errors).

Each gold/prediction is consumed at most once (multiset semantics). Predictions
are already in-scope (canonical non-null); out-of-scope ones were dropped during
merge. Counts are returned per (doc, config); the report micro-averages by summing
tp/fp/fn across docs before computing P/R/F1 (same as ``LabelCounts.add``).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Tuple

from corpora import GoldDoc
from merging import Prediction, normalize_value


@dataclass
class Counts:
    tp: int = 0
    fp: int = 0
    fn: int = 0

    def add(self, other: "Counts") -> None:
        self.tp += other.tp
        self.fp += other.fp
        self.fn += other.fn

    @property
    def support(self) -> int:
        return self.tp + self.fn

    @property
    def precision(self) -> float:
        d = self.tp + self.fp
        return 0.0 if d == 0 else self.tp / d

    @property
    def recall(self) -> float:
        d = self.tp + self.fn
        return 0.0 if d == 0 else self.tp / d

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return 0.0 if (p + r) == 0 else 2 * p * r / (p + r)

    def to_dict(self) -> dict:
        return {"tp": self.tp, "fp": self.fp, "fn": self.fn,
                "precision": round(self.precision, 4), "recall": round(self.recall, 4),
                "f1": round(self.f1, 4), "support": self.support}


@dataclass
class DocScore:
    strict: Counts
    type_agnostic: Counts
    per_label: Dict[str, Counts]

    def to_dict(self) -> dict:
        return {
            "strict": self.strict.to_dict(),
            "type_agnostic": self.type_agnostic.to_dict(),
            "per_label": {k: v.to_dict() for k, v in self.per_label.items()},
        }


def score_doc(gold: GoldDoc, preds: List[Prediction]) -> DocScore:
    gold_values = [(label, normalize_value(gold.text[s:e])) for (s, e, label) in gold.spans]
    pred_pairs = [(p.canonical, normalize_value(p.value)) for p in preds]

    strict = Counts()
    type_agnostic = Counts()
    per_label: Dict[str, Counts] = {}

    def bucket(label: str) -> Counts:
        return per_label.setdefault(label, Counts())

    # --- strict (label-aware) ---
    used = [False] * len(pred_pairs)
    for (label, gv) in gold_values:
        idx = _match(pred_pairs, used, gv, label, label_aware=True)
        if idx >= 0:
            used[idx] = True
            strict.tp += 1
            bucket(label).tp += 1
        else:
            strict.fn += 1
            bucket(label).fn += 1
    for i, taken in enumerate(used):
        if not taken:
            strict.fp += 1
            bucket(pred_pairs[i][0]).fp += 1

    # --- type-agnostic (value only) ---
    used = [False] * len(pred_pairs)
    for (_, gv) in gold_values:
        idx = _match(pred_pairs, used, gv, None, label_aware=False)
        if idx >= 0:
            used[idx] = True
            type_agnostic.tp += 1
        else:
            type_agnostic.fn += 1
    type_agnostic.fp += used.count(False)

    return DocScore(strict, type_agnostic, per_label)


def _match(pred_pairs: List[Tuple[str, str]], used: List[bool], value: str,
           label, label_aware: bool) -> int:
    for i, (canonical, pv) in enumerate(pred_pairs):
        if used[i] or pv != value:
            continue
        if label_aware and canonical != label:
            continue
        return i
    return -1
