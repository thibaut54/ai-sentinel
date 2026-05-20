"""Script jetable : couverture exacte du corpus baseline par les verdicts deja produits."""
import json
from collections import Counter, defaultdict

SRC = r'C:\Users\ThibautVuillaume\Workspace\ai-sentinel-fork\ai-sentinel\ai-sentinel\pii-reporting-api\target\corpus-data-sql-comparison\baseline\findings.jsonl'
VPATH = r'C:\Users\ThibautVuillaume\Workspace\ai-sentinel-fork\ai-sentinel\ai-sentinel\pii-reporting-api\target\llm-judge\baseline\verdicts.jsonl'

src_by_type = Counter()
with open(SRC, encoding='utf-8') as fh:
    for line in fh:
        s = line.strip()
        if not s or not s.startswith('{'):
            continue
        try:
            src_by_type[json.loads(s)['piiTypeDetected']] += 1
        except Exception:
            pass
total_src = sum(src_by_type.values())

judged_idx, errors_idx = [], []
by_type_judged, by_type_errors = Counter(), Counter()
verdicts_by_type = defaultdict(Counter)
with open(VPATH, encoding='utf-8') as fh:
    for line in fh:
        r = json.loads(line)
        t = r['piiTypeDetected']
        if r.get('error') is not None:
            errors_idx.append(r['idx'])
            by_type_errors[t] += 1
        else:
            judged_idx.append(r['idx'])
            by_type_judged[t] += 1
            verdicts_by_type[t][r['verdict']] += 1

n_judged = len(judged_idx)
n_errors = len(errors_idx)
all_touched_idx = judged_idx + errors_idx
max_idx = max(all_touched_idx)
min_idx = min(all_touched_idx)
n_touched = n_judged + n_errors
n_untouched = total_src - n_touched

tp = sum(c['TRUE_POSITIVE']  for c in verdicts_by_type.values())
fp = sum(c['FALSE_POSITIVE'] for c in verdicts_by_type.values())
un = sum(c['UNSURE']         for c in verdicts_by_type.values())

print("=" * 78)
print(f"COUVERTURE DU CORPUS BASELINE ({total_src} findings au total)")
print("=" * 78)
print(f"  Lignes envoyees au script        : {n_touched:5d} (idx {min_idx} a {max_idx})")
print(f"    -> Reellement jugees par Qwen  : {n_judged:5d}  <-- ECHANTILLON ANALYSE")
print(f"    -> Erreurs LM Studio (UNSURE)  : {n_errors:5d}  (verdict non fiable)")
print(f"  Lignes JAMAIS envoyees a Qwen    : {n_untouched:5d} (idx {max_idx+1} a {total_src-1})")
print()
print(f"TAUX FP SUR L'ECHANTILLON REELLEMENT ANALYSE PAR QWEN ({n_judged} findings) :")
print(f"  TRUE_POSITIVE   : {tp:5d}  ({100*tp/n_judged:5.2f}%)")
print(f"  FALSE_POSITIVE  : {fp:5d}  ({100*fp/n_judged:5.2f}%)  <-- TAUX FP REEL")
print(f"  UNSURE (vrais)  : {un:5d}  ({100*un/n_judged:5.2f}%)")
print()
print(f"COUVERTURE PAR piiType (tries par volume source decroissant) :")
print(f"{'piiType':28s} {'src':>5s} {'judged':>7s} {'errors':>7s} {'%judged':>8s}  TP/FP/UNS")
print("-" * 78)
for t in sorted(src_by_type, key=lambda x: -src_by_type[x]):
    src_n = src_by_type[t]
    j = by_type_judged.get(t, 0)
    e = by_type_errors.get(t, 0)
    pct = 100*j/src_n if src_n else 0
    v = verdicts_by_type[t]
    tpe, fpe, une_ = v['TRUE_POSITIVE'], v['FALSE_POSITIVE'], v['UNSURE']
    print(f"{t:28s} {src_n:5d} {j:7d} {e:7d} {pct:7.1f}%  {tpe}/{fpe}/{une_}")
