"""Échantillon LLM-as-judge sur baseline/findings.jsonl via LM Studio.

Stratifie N findings sur les piiType disponibles, interroge LM Studio en JSON
Schema strict, agrège FP + taux. Sortie console + fichier JSONL.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

LM_URL = "http://127.0.0.1:1234/v1/chat/completions"
MODEL = "qwen3.6-35b-a3b"

VERDICT_SCHEMA = {
    "type": "object",
    "properties": {
        "verdict": {"type": "string", "enum": ["TRUE_POSITIVE", "FALSE_POSITIVE", "UNSURE"]},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "reason": {"type": "string", "maxLength": 200},
    },
    "required": ["verdict", "confidence", "reason"],
    "additionalProperties": False,
}

SYSTEM_PROMPT = """/no_think
Tu es auditeur de la PRECISION de classification d'un detecteur de PII.
On te donne UN finding extrait d'un document interne (francais).

LA SEULE QUESTION : la valeur extraite (`value`) correspond-elle bien au
TYPE revendique par le detecteur (`pii_type`), au sens du format et de la
semantique de ce type ?

Tu ne juges PAS si c'est un "vrai PII" (production vs test), ni si c'est
sensible ou public, ni si c'est prive (RFC 1918) vs public. Tu juges
uniquement le TYPE-MATCH.

- TRUE_POSITIVE  = la value a bien le format/semantique du pii_type.
- FALSE_POSITIVE = la value est d'un autre type (code projet classe
                   NATIONAL_ID, montant classe BANK_ACCOUNT_NUMBER,
                   acronyme classe PASSWORD, nom de variable classe
                   SESSION_ID, chemin classe API_KEY, etc.).
- UNSURE         = format ambigu (confidence < 0.6).

Exemples :
- "10.217.4.11" / IP_ADDRESS         -> TP (IPv4 valide, RFC 1918 OK).
- "374111111111111" / CREDIT_CARD    -> TP (format AmEx).
- "CH6930000011100005458" / IBAN     -> TP.
- "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa" / CRYPTO_WALLET -> TP.
- "PCV-1189" / NATIONAL_ID           -> FP (code projet, pas un NID).
- "41780007878" / SOCIALNUM          -> FP (mobile suisse, pas AVS).
- "DGAIC" / PASSWORD                 -> FP (acronyme).

Reponds UNIQUEMENT en JSON conforme au schema. "reason" : 1-2 phrases."""


def build_user_prompt(f: dict) -> str:
    ctx_before = (f.get("contextBefore") or "")[-300:]
    ctx_after = (f.get("contextAfter") or "")[:300]
    return (
        f"Finding a juger :\n"
        f"  pii_type    = {f['piiType']}\n"
        f"  type_label  = {f.get('typeLabel', '')}\n"
        f"  value       = \"{f['value']}\"\n"
        f"  detector    = {f.get('detector', '')}\n"
        f"  score       = {f.get('score', 0):.3f}\n"
        f"  file        = {f['file']}\n"
        f"  context     = \"...{ctx_before} >>>{f['value']}<<< {ctx_after}...\"\n"
    )


def call_llm(finding: dict, timeout: int = 120) -> dict:
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(finding)},
        ],
        "temperature": 0.2,
        "top_p": 1.0,
        "max_tokens": 250,
        "seed": 42,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "pii_verdict", "schema": VERDICT_SCHEMA, "strict": True},
        },
        "chat_template_kwargs": {"enable_thinking": False},
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(LM_URL, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    msg = body["choices"][0]["message"]
    content = msg.get("content") or msg.get("reasoning_content") or ""
    return json.loads(content.strip())


def stratified_sample(findings: list[dict], n_per_type: int, seed: int = 42) -> list[dict]:
    random.seed(seed)
    by_type = defaultdict(list)
    for f in findings:
        by_type[f["piiType"]].append(f)
    picked = []
    for t, items in by_type.items():
        random.shuffle(items)
        picked.extend(items[:n_per_type])
    random.shuffle(picked)
    return picked


def is_attachment(file_path: str) -> bool:
    name = file_path.lower()
    return not (name.endswith(".html") or name.endswith(".htm"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=r"C:\Users\ThibautVuillaume\Workspace\ai-sentinel-fork\ai-sentinel\ai-sentinel\pii-reporting-api\target\data-sql-comparison\baseline\findings.jsonl")
    parser.add_argument("--n-per-type", type=int, default=2, help="findings par piiType")
    parser.add_argument("--max-total", type=int, default=20, help="cap dur sur l'echantillon")
    parser.add_argument("--out", default=r"C:\Users\ThibautVuillaume\Workspace\ai-sentinel-fork\ai-sentinel\ai-sentinel\pii-reporting-api\target\llm-judge-sample.jsonl")
    args = parser.parse_args()

    src = Path(args.input)
    if not src.is_file():
        print(f"[ERR] fichier introuvable : {src}", file=sys.stderr)
        return 1

    findings, skipped = [], 0
    with src.open(encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if not s or not s.startswith("{"):
                skipped += 1
                continue
            try:
                findings.append(json.loads(s))
            except json.JSONDecodeError:
                skipped += 1
    print(f"[info] {len(findings)} findings parses ({skipped} lignes ignorees) dans {src.name}")

    sample = stratified_sample(findings, args.n_per_type)[: args.max_total]
    print(f"[info] echantillon stratifie : {len(sample)} findings sur {len(set(f['piiType'] for f in sample))} types")

    results = []
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    with out_path.open("w", encoding="utf-8") as out:
        for i, f in enumerate(sample, 1):
            t_call = time.time()
            try:
                verdict = call_llm(f)
                err = None
            except Exception as e:
                verdict = {"verdict": "UNSURE", "confidence": 0.0, "reason": f"erreur appel: {e}"}
                err = str(e)
            dt = time.time() - t_call
            row = {
                "i": i,
                "piiType": f["piiType"],
                "detector": f.get("detector"),
                "score": f.get("score"),
                "value": f["value"],
                "file": f["file"],
                "is_attachment": is_attachment(f["file"]),
                "verdict": verdict["verdict"],
                "confidence": verdict["confidence"],
                "reason": verdict["reason"],
                "elapsed_s": round(dt, 2),
                "error": err,
            }
            results.append(row)
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
            print(f"  [{i:2d}/{len(sample)}] {dt:5.1f}s  {row['verdict']:15s}  conf={row['confidence']:.2f}  {row['piiType']:25s}  value='{row['value'][:35]}'")

    total_dt = time.time() - t0
    print(f"\n[info] termine en {total_dt:.1f}s -> {out_path}")

    summarize(results)
    return 0


def summarize(results: list[dict]) -> None:
    total = len(results)
    by_verdict = defaultdict(int)
    by_type = defaultdict(lambda: {"TP": 0, "FP": 0, "UNSURE": 0})
    fps = []
    for r in results:
        v = r["verdict"]
        by_verdict[v] += 1
        key = {"TRUE_POSITIVE": "TP", "FALSE_POSITIVE": "FP", "UNSURE": "UNSURE"}[v]
        by_type[r["piiType"]][key] += 1
        if v == "FALSE_POSITIVE":
            fps.append(r)

    print("\n========================================================================")
    print("RESUME GLOBAL")
    print("========================================================================")
    print(f"  Total juge        : {total}")
    print(f"  TRUE_POSITIVE     : {by_verdict['TRUE_POSITIVE']:3d}  ({100*by_verdict['TRUE_POSITIVE']/total:.1f}%)")
    print(f"  FALSE_POSITIVE    : {by_verdict['FALSE_POSITIVE']:3d}  ({100*by_verdict['FALSE_POSITIVE']/total:.1f}%)  <-- TAUX FP")
    print(f"  UNSURE            : {by_verdict['UNSURE']:3d}  ({100*by_verdict['UNSURE']/total:.1f}%)")

    print("\nBREAKDOWN PAR piiType")
    print("------------------------------------------------------------------------")
    print(f"{'piiType':30s} {'TP':>4s} {'FP':>4s} {'UNSURE':>7s} {'FP%':>7s}")
    for t in sorted(by_type):
        tp, fp, un = by_type[t]["TP"], by_type[t]["FP"], by_type[t]["UNSURE"]
        n = tp + fp + un
        rate = 100 * fp / n if n else 0
        print(f"{t:30s} {tp:4d} {fp:4d} {un:7d} {rate:6.1f}%")

    print("\nLISTE DES FAUX POSITIFS")
    print("------------------------------------------------------------------------")
    if not fps:
        print("  (aucun)")
    for r in fps:
        kind = "ATTACH" if r["is_attachment"] else "PAGE "
        print(f"  [{kind}] {r['piiType']:20s} value='{r['value'][:40]}'  conf={r['confidence']:.2f}")
        print(f"           file   : {r['file']}")
        print(f"           reason : {r['reason']}")


if __name__ == "__main__":
    sys.exit(main())
