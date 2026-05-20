"""LLM-as-judge sur l'integralite d'un findings.jsonl produit par
CorpusDataSqlComparisonIT.

Itere par batch de N (defaut 20), parallelise sur W workers (defaut 8),
affiche le taux FP cumule a chaque batch. Produit :
  - verdicts.jsonl       : 1 ligne / finding (tous verdicts)
  - false_positives.jsonl: 1 ligne / FP avec contexte complet (machine)
  - false_positives.md   : FP groupes par fichier source, liens Confluence (humain)
  - summary.json         : stats finales (global + par piiType + par fichier)
  - progress.log         : trace batch-par-batch

Reprise : si verdicts.jsonl existe, les finding_id deja juges sont skippes.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

LM_URL_DEFAULT = "http://127.0.0.1:1234/v1/chat/completions"
MODEL_DEFAULT = "qwen3.6-35b-a3b"

VERDICT_SCHEMA = {
    "type": "object",
    "properties": {
        "verdict": {"type": "string", "enum": ["TRUE_POSITIVE", "FALSE_POSITIVE", "UNSURE"]},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "reason": {"type": "string", "maxLength": 300},
    },
    "required": ["verdict", "confidence", "reason"],
    "additionalProperties": False,
}

SYSTEM_PROMPT = """/no_think
Tu audites la PRECISION de classification d'un detecteur de PII.

QUESTION UNIQUE : la `value` extraite a-t-elle le FORMAT et la SEMANTIQUE
du `pii_type` revendique ? Tu ne juges PAS si c'est sensible, ni
production/test, ni public/prive (RFC 1918 OK), ni connu/exemple.

- TRUE_POSITIVE  : format et semantique du pii_type respectes.
- FALSE_POSITIVE : la value est d'un autre type (code projet en NATIONAL_ID,
  montant en BANK_ACCOUNT_NUMBER, acronyme en PASSWORD, nom de variable en
  SESSION_ID, chemin en API_KEY, etc.).
- UNSURE         : format ambigu (confidence < 0.6).

Exemples :
- "10.217.4.11" / IP_ADDRESS              -> TP (IPv4, RFC 1918 OK).
- "374111111111111" / CREDIT_CARD         -> TP (AmEx 15 ch.).
- "CH6930000011100005458" / IBAN          -> TP.
- "1A1zP1eP5QGefi2DMPTfTL..." / CRYPTO_WALLET -> TP.
- "756.3407.8913.03" / AVS_NUMBER         -> TP.
- "021 316 01 57" / PHONE_NUMBER          -> TP.
- "PCV-1189" / NATIONAL_ID                -> FP (code projet).
- "41780007878" / SOCIALNUM               -> FP (mobile, pas AVS).
- "92366499.59" / BANK_ACCOUNT_NUMBER     -> FP (montant).
- "DGAIC" / PASSWORD                      -> FP (acronyme).

Reponds en JSON conforme au schema. "reason" : 1 phrase francais."""


def build_user_prompt(f: dict) -> str:
    ctx_before = (f.get("contextBefore") or "")[-300:]
    ctx_after = (f.get("contextAfter") or "")[:300]
    return (
        f"Finding a juger :\n"
        f"  pii_type     = {f['piiTypeDetected']}\n"
        f"  type_label   = {f.get('typeLabel', '')}\n"
        f"  value        = \"{f['value']}\"\n"
        f"  detector     = {f.get('detector', '')}\n"
        f"  score        = {f.get('score') or 0:.3f}\n"
        f"  page_title   = \"{f.get('pageTitle', '') or ''}\"\n"
        f"  is_attachment= {f.get('isAttachment', False)}\n"
        f"  source_file  = {f.get('relativePath', '')}\n"
        f"  context      = \"...{ctx_before} >>>{f['value']}<<< {ctx_after}...\"\n"
    )


def call_llm(lm_url: str, model: str, finding: dict, timeout: int = 120, retries: int = 1) -> dict:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(finding)},
        ],
        "temperature": 0.2,
        "top_p": 1.0,
        "max_tokens": 300,
        "seed": 42,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "pii_verdict", "schema": VERDICT_SCHEMA, "strict": True},
        },
    }
    data = json.dumps(payload).encode("utf-8")
    last_err = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(lm_url, data=data, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            msg = body["choices"][0]["message"]
            content = msg.get("content") or msg.get("reasoning_content") or ""
            return json.loads(content.strip())
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError) as e:
            last_err = e
            if attempt < retries:
                time.sleep(2)
    raise RuntimeError(f"call_llm failed after {retries+1} attempts: {last_err}")


def finding_id(idx: int, f: dict) -> str:
    """Identifiant deterministe : index + (start,end,piiTypeDetected,value tronque)."""
    return f"{idx:06d}|{f.get('start',0)}-{f.get('end',0)}|{f['piiTypeDetected']}|{(f['value'] or '')[:24]}"


def load_findings(src: Path) -> list[dict]:
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
    return findings


def load_resume(verdicts_path: Path) -> set[str]:
    if not verdicts_path.is_file():
        return set()
    done = set()
    with verdicts_path.open(encoding="utf-8") as fh:
        for line in fh:
            try:
                done.add(json.loads(line)["finding_id"])
            except (json.JSONDecodeError, KeyError):
                pass
    if done:
        print(f"[resume] {len(done)} findings deja juges, seront skippes")
    return done


def judge_one(args, idx: int, f: dict) -> dict:
    fid = finding_id(idx, f)
    t0 = time.time()
    try:
        v = call_llm(args.lm_url, args.model, f)
        err = None
    except Exception as e:
        v = {"verdict": "UNSURE", "confidence": 0.0, "reason": f"erreur appel: {e}"}
        err = str(e)
    return {
        "finding_id": fid,
        "idx": idx,
        "piiTypeDetected": f["piiTypeDetected"],
        "typeLabel": f.get("typeLabel"),
        "detector": f.get("detector"),
        "score": f.get("score"),
        "value": f["value"],
        "pageTitle": f.get("pageTitle"),
        "pageUrl": f.get("pageUrl"),
        "pageId": f.get("pageId"),
        "relativePath": f.get("relativePath"),
        "isAttachment": f.get("isAttachment", False),
        "piiTypeFolder": f.get("piiTypeFolder"),
        "contextBefore": (f.get("contextBefore") or "")[-300:],
        "contextAfter": (f.get("contextAfter") or "")[:300],
        "start": f.get("start"),
        "end": f.get("end"),
        "verdict": v["verdict"],
        "confidence": v["confidence"],
        "reason": v["reason"],
        "elapsed_s": round(time.time() - t0, 2),
        "error": err,
    }


def process_batch(args, batch_items: list[tuple[int, dict]]) -> list[dict]:
    """Soumet un batch en parallele, retourne les resultats dans l'ordre des soumissions."""
    results: dict[int, dict] = {}
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futures = {ex.submit(judge_one, args, idx, f): idx for idx, f in batch_items}
        for fut in as_completed(futures):
            idx = futures[fut]
            results[idx] = fut.result()
    return [results[idx] for idx, _ in batch_items]


def write_fp_md_entry(md_fh, r: dict) -> None:
    md_fh.write(f"### {r['piiTypeDetected']} — `{r['value']}` "
                f"(conf {r['confidence']:.2f})\n\n")
    kind = "Attachment" if r["isAttachment"] else "Page"
    md_fh.write(f"- **{kind}** : `{r['relativePath']}`\n")
    if r.get("pageTitle"):
        md_fh.write(f"- **Page Confluence** : {r['pageTitle']}\n")
    if r.get("pageUrl"):
        md_fh.write(f"- **URL** : {r['pageUrl']}\n")
    md_fh.write(f"- **Detector** : {r.get('detector')} (score {r.get('score') or 0:.3f})\n")
    md_fh.write(f"- **PII folder** : `{r.get('piiTypeFolder')}`\n")
    md_fh.write(f"- **Raison du juge** : {r['reason']}\n\n")
    ctx_before = (r["contextBefore"] or "").replace("\n", " ")
    ctx_after = (r["contextAfter"] or "").replace("\n", " ")
    md_fh.write(f"```\n...{ctx_before}\n>>> {r['value']} <<<\n{ctx_after}...\n```\n\n---\n\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="findings.jsonl du test corpus")
    parser.add_argument("--out-dir", required=True, help="dossier de sortie (sera cree)")
    parser.add_argument("--lm-url", default=LM_URL_DEFAULT)
    parser.add_argument("--model", default=MODEL_DEFAULT)
    parser.add_argument("--batch-size", type=int, default=20)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0, help="0 = pas de limite")
    args = parser.parse_args()

    src = Path(args.input)
    if not src.is_file():
        print(f"[ERR] introuvable : {src}", file=sys.stderr)
        return 1
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    verdicts_path = out_dir / "verdicts.jsonl"
    fp_jsonl_path = out_dir / "false_positives.jsonl"
    fp_md_path = out_dir / "false_positives.md"
    progress_path = out_dir / "progress.log"
    summary_path = out_dir / "summary.json"

    findings = load_findings(src)
    if args.limit > 0:
        findings = findings[: args.limit]
        print(f"[info] limit={args.limit} -> traite {len(findings)} findings")

    already_done = load_resume(verdicts_path)
    todo = [(i, f) for i, f in enumerate(findings) if finding_id(i, f) not in already_done]
    print(f"[info] {len(todo)} findings a juger (sur {len(findings)})")

    # Ouverture en append pour permettre reprise.
    v_fh = verdicts_path.open("a", encoding="utf-8")
    fp_fh = fp_jsonl_path.open("a", encoding="utf-8")
    md_fh = fp_md_path.open("a", encoding="utf-8")
    prog_fh = progress_path.open("a", encoding="utf-8")

    if fp_md_path.stat().st_size == 0:
        md_fh.write(f"# Faux positifs detectes par LLM-as-judge\n\n")
        md_fh.write(f"Source : `{src.name}` | Modele : `{args.model}`\n\n---\n\n")

    # Compteurs cumules (recharges depuis l'existant pour coherence reprise).
    total_seen = 0
    total_tp = 0
    total_fp = 0
    total_unsure = 0
    if verdicts_path.stat().st_size > 0:
        with verdicts_path.open(encoding="utf-8") as fh:
            for line in fh:
                try:
                    r = json.loads(line)
                    total_seen += 1
                    if r["verdict"] == "TRUE_POSITIVE":
                        total_tp += 1
                    elif r["verdict"] == "FALSE_POSITIVE":
                        total_fp += 1
                    else:
                        total_unsure += 1
                except (json.JSONDecodeError, KeyError):
                    pass

    by_type_fp: dict[str, dict[str, int]] = defaultdict(lambda: {"TP": 0, "FP": 0, "UNSURE": 0})
    by_file_fp: dict[str, int] = defaultdict(int)

    print(f"\n{'batch':>5s} {'time':>6s} {'cum':>6s} {'TP':>4s} {'FP':>4s} {'UN':>4s} {'FP%cum':>7s}  notes")
    print("-" * 80)
    t_global = time.time()
    batch_no = 0
    for start in range(0, len(todo), args.batch_size):
        batch_no += 1
        batch = todo[start : start + args.batch_size]
        t_batch = time.time()
        results = process_batch(args, batch)
        batch_tp = sum(1 for r in results if r["verdict"] == "TRUE_POSITIVE")
        batch_fp = sum(1 for r in results if r["verdict"] == "FALSE_POSITIVE")
        batch_un = len(results) - batch_tp - batch_fp

        for r in results:
            v_fh.write(json.dumps(r, ensure_ascii=False) + "\n")
            total_seen += 1
            if r["verdict"] == "FALSE_POSITIVE":
                total_fp += 1
                by_type_fp[r["piiTypeDetected"]]["FP"] += 1
                by_file_fp[r["relativePath"] or "?"] += 1
                fp_fh.write(json.dumps(r, ensure_ascii=False) + "\n")
                write_fp_md_entry(md_fh, r)
            elif r["verdict"] == "TRUE_POSITIVE":
                total_tp += 1
                by_type_fp[r["piiTypeDetected"]]["TP"] += 1
            else:
                total_unsure += 1
                by_type_fp[r["piiTypeDetected"]]["UNSURE"] += 1
        v_fh.flush(); fp_fh.flush(); md_fh.flush()

        cum_fp_rate = 100.0 * total_fp / total_seen if total_seen else 0.0
        dt = time.time() - t_batch
        line = (f"{batch_no:5d} {dt:5.1f}s {total_seen:6d} "
                f"{batch_tp:4d} {batch_fp:4d} {batch_un:4d} {cum_fp_rate:6.1f}%  "
                f"({len(results)} findings juges)")
        print(line)
        prog_fh.write(line + "\n"); prog_fh.flush()

    t_total = time.time() - t_global
    print("-" * 80)
    print(f"[done] {total_seen} findings juges en {t_total:.0f}s "
          f"-> {out_dir}")

    summary = {
        "input": str(src),
        "model": args.model,
        "total": total_seen,
        "true_positive": total_tp,
        "false_positive": total_fp,
        "unsure": total_unsure,
        "fp_rate_pct": round(100.0 * total_fp / total_seen, 2) if total_seen else 0.0,
        "by_pii_type": dict(by_type_fp),
        "top_files_by_fp": dict(sorted(by_file_fp.items(), key=lambda kv: -kv[1])[:30]),
        "duration_s": round(t_total, 1),
        "workers": args.workers,
        "batch_size": args.batch_size,
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[done] summary : {summary_path}")

    v_fh.close(); fp_fh.close(); md_fh.close(); prog_fh.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
