"""Tableaux FP : global, par detecteur, par piiType."""
import json
from collections import Counter, defaultdict

VPATH = r'C:\Users\ThibautVuillaume\Workspace\ai-sentinel-fork\ai-sentinel\ai-sentinel\pii-reporting-api\target\llm-judge\baseline\verdicts.jsonl'

g_tp = g_fp = g_un = 0
by_det = defaultdict(lambda: Counter())
by_type = defaultdict(lambda: Counter())
by_det_type = defaultdict(lambda: Counter())  # (det, type) -> Counter

with open(VPATH, encoding='utf-8') as fh:
    for line in fh:
        r = json.loads(line)
        if r.get('error') is not None:
            continue
        v = r['verdict']
        det = r.get('detector') or 'UNKNOWN'
        t = r['piiTypeDetected']
        if v == 'TRUE_POSITIVE': g_tp += 1; by_det[det]['TP'] += 1; by_type[t]['TP'] += 1; by_det_type[(det,t)]['TP'] += 1
        elif v == 'FALSE_POSITIVE': g_fp += 1; by_det[det]['FP'] += 1; by_type[t]['FP'] += 1; by_det_type[(det,t)]['FP'] += 1
        else: g_un += 1; by_det[det]['UNSURE'] += 1; by_type[t]['UNSURE'] += 1; by_det_type[(det,t)]['UNSURE'] += 1

n = g_tp + g_fp + g_un

print("=" * 70)
print(f"GLOBAL (sur {n} verdicts reellement juges par Qwen)")
print("=" * 70)
print(f"  TRUE_POSITIVE   : {g_tp:5d}  ({100*g_tp/n:5.2f}%)")
print(f"  FALSE_POSITIVE  : {g_fp:5d}  ({100*g_fp/n:5.2f}%)  <-- TAUX FP GLOBAL")
print(f"  UNSURE          : {g_un:5d}  ({100*g_un/n:5.2f}%)")

print()
print("=" * 70)
print("PAR DETECTEUR")
print("=" * 70)
print(f"{'Detector':12s} {'N':>5s} {'TP':>5s} {'FP':>5s} {'UNSURE':>7s} {'FP%':>7s}")
print("-" * 50)
for det in sorted(by_det, key=lambda d: -(by_det[d]['TP']+by_det[d]['FP']+by_det[d]['UNSURE'])):
    c = by_det[det]
    nn = c['TP']+c['FP']+c['UNSURE']
    pct = 100*c['FP']/nn if nn else 0
    print(f"{det:12s} {nn:5d} {c['TP']:5d} {c['FP']:5d} {c['UNSURE']:7d} {pct:6.1f}%")

print()
print("=" * 70)
print("PAR piiType")
print("=" * 70)
print(f"{'piiType':28s} {'N':>5s} {'TP':>5s} {'FP':>5s} {'UNSURE':>7s} {'FP%':>7s}")
print("-" * 66)
for t in sorted(by_type, key=lambda x: -(by_type[x]['TP']+by_type[x]['FP']+by_type[x]['UNSURE'])):
    c = by_type[t]
    nn = c['TP']+c['FP']+c['UNSURE']
    pct = 100*c['FP']/nn if nn else 0
    print(f"{t:28s} {nn:5d} {c['TP']:5d} {c['FP']:5d} {c['UNSURE']:7d} {pct:6.1f}%")

print()
print("=" * 70)
print("PAR (DETECTEUR x piiType) -- top combinations par volume")
print("=" * 70)
print(f"{'Detector':10s} {'piiType':24s} {'N':>5s} {'TP':>5s} {'FP':>5s} {'FP%':>7s}")
print("-" * 64)
combos = sorted(by_det_type.items(), key=lambda kv: -(kv[1]['TP']+kv[1]['FP']+kv[1]['UNSURE']))
for (det, t), c in combos[:25]:
    nn = c['TP']+c['FP']+c['UNSURE']
    pct = 100*c['FP']/nn if nn else 0
    print(f"{det:10s} {t:24s} {nn:5d} {c['TP']:5d} {c['FP']:5d} {pct:6.1f}%")
