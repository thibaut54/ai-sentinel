"""Genere un summary depuis un verdicts.jsonl partiel (apres interruption).

Distingue les vrais verdicts du modele des fallbacks d'erreur (UNSURE
avec confidence=0.0 et error!=null) pour ne pas polluer les stats.
Optionnellement, ecrit verdicts.jsonl.clean (sans les erreurs) pour
permettre une reprise propre.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--clean", action="store_true",
                        help="ecrit verdicts.jsonl.clean (sans les erreurs) "
                             "pour permettre une reprise qui re-juge les errors")
    args = parser.parse_args()

    out = Path(args.out_dir)
    v_path = out / "verdicts.jsonl"
    if not v_path.is_file():
        print(f"[ERR] {v_path} introuvable", file=sys.stderr)
        return 1

    total = 0
    tp = 0
    fp = 0
    real_unsure = 0
    errors = 0
    by_type = defaultdict(lambda: {"TP": 0, "FP": 0, "UNSURE": 0, "ERR": 0})
    by_file = defaultdict(lambda: {"TP": 0, "FP": 0, "UNSURE": 0, "ERR": 0, "ATTACH": False})
    clean_rows = []
    error_rows = []

    with v_path.open(encoding="utf-8") as fh:
        for line in fh:
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            total += 1
            t = r["piiTypeDetected"]
            f = r.get("relativePath") or "?"
            is_err = r.get("error") is not None
            v = r["verdict"]
            if is_err:
                errors += 1
                by_type[t]["ERR"] += 1
                by_file[f]["ERR"] += 1
                error_rows.append(r)
                continue
            clean_rows.append(r)
            by_file[f]["ATTACH"] = r.get("isAttachment", False)
            if v == "TRUE_POSITIVE":
                tp += 1
                by_type[t]["TP"] += 1
                by_file[f]["TP"] += 1
            elif v == "FALSE_POSITIVE":
                fp += 1
                by_type[t]["FP"] += 1
                by_file[f]["FP"] += 1
            else:
                real_unsure += 1
                by_type[t]["UNSURE"] += 1
                by_file[f]["UNSURE"] += 1

    n_judged = total - errors
    fp_rate = 100.0 * fp / n_judged if n_judged else 0.0

    print(f"\n{'='*70}\nVERDICTS DEJA PRODUITS (partiel)\n{'='*70}")
    print(f"  Total lignes dans verdicts.jsonl   : {total:5d}")
    print(f"  -> Vraiment juges par le LLM       : {n_judged:5d}")
    print(f"  -> Erreurs masquees en UNSURE      : {errors:5d}  ({100*errors/total:.1f}%)")
    print(f"\nSUR LES {n_judged} VRAIS VERDICTS :")
    print(f"  TRUE_POSITIVE   : {tp:5d}  ({100*tp/n_judged:.1f}%)")
    print(f"  FALSE_POSITIVE  : {fp:5d}  ({100*fp/n_judged:.1f}%)  <-- TAUX FP REEL")
    print(f"  UNSURE (vrais)  : {real_unsure:5d}  ({100*real_unsure/n_judged:.1f}%)")

    print(f"\nBREAKDOWN PAR piiType (top 20)")
    print(f"{'piiType':30s} {'TP':>5s} {'FP':>5s} {'UNS':>5s} {'ERR':>5s} {'FP%':>6s}")
    rows = sorted(by_type.items(), key=lambda kv: -(kv[1]["TP"]+kv[1]["FP"]+kv[1]["UNSURE"]))
    for t, c in rows[:20]:
        n = c["TP"] + c["FP"] + c["UNSURE"]
        rate = 100*c["FP"]/n if n else 0
        print(f"{t:30s} {c['TP']:5d} {c['FP']:5d} {c['UNSURE']:5d} {c['ERR']:5d} {rate:5.1f}%")

    print(f"\nTOP 15 FICHIERS PAR NOMBRE DE FP")
    files_by_fp = sorted(by_file.items(), key=lambda kv: -kv[1]["FP"])[:15]
    for f, c in files_by_fp:
        kind = "ATT" if c["ATTACH"] else "PAGE"
        print(f"  [{kind}] FP={c['FP']:3d} TP={c['TP']:3d} : {f}")

    summary = {
        "status": "partial",
        "total_lines": total,
        "real_verdicts": n_judged,
        "errors_masked_as_unsure": errors,
        "true_positive": tp,
        "false_positive": fp,
        "unsure_real": real_unsure,
        "fp_rate_pct_on_real_verdicts": round(fp_rate, 2),
        "by_pii_type": {t: dict(c) for t, c in by_type.items()},
        "top_files_by_fp": {f: dict(c) for f, c in files_by_fp},
    }
    sum_path = out / "summary_partial.json"
    sum_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[done] summary partiel: {sum_path}")

    if args.clean and errors > 0:
        bak = out / "verdicts.jsonl.bak"
        v_path.rename(bak)
        with v_path.open("w", encoding="utf-8") as fh:
            for r in clean_rows:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        err_path = out / "verdicts.jsonl.errors"
        with err_path.open("w", encoding="utf-8") as fh:
            for r in error_rows:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"[clean] {bak.name} (backup) + {v_path.name} ({len(clean_rows)} verdicts)")
        print(f"[clean] {err_path.name} ({len(error_rows)} erreurs)")
        print(f"[clean] -> relance le script principal, il re-jugera les {errors} findings en erreur")
    return 0


if __name__ == "__main__":
    sys.exit(main())
