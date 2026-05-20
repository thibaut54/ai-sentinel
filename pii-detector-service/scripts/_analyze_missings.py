"""Analyze which NVIDIA baseline entities our local Run A misses, and why."""
import json, sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pii_detector.infrastructure.text_processing.semantic_chunker import GlinerSubwordChunker

BASELINE = Path(r'C:/Users/ThibautVuillaume/Workspace/ai-sentinel-fork/ai-sentinel/ai-sentinel/pii-detector-service/tests/resources/gliner-parity-baseline-confluence.json')
TEXT_FILE = Path(r'C:/Users/ThibautVuillaume/Workspace/ai-sentinel-fork/ai-sentinel/ai-sentinel/my-files/confluence-pii-test-document-docanno.txt')

baseline = json.loads(BASELINE.read_text(encoding='utf-8'))
text = TEXT_FILE.read_text(encoding='utf-8')
labels = baseline['_meta']['labels']
nvidia_entities = baseline['entities']
print(f'NVIDIA baseline: {len(nvidia_entities)} entities, {len(labels)} labels, text={len(text)} chars')

print('Loading model...')
from gliner import GLiNER
model = GLiNER.from_pretrained('nvidia/gliner-PII')
tokenizer = model.data_processor.transformer_tokenizer
chunker = GlinerSubwordChunker(tokenizer=tokenizer, chunk_size=384, overlap=128)

# Run A with low threshold (0.0) to get every candidate the local model would emit
print('Running Run A locally with threshold=0.0 (all candidates)...')
local = []
for chunk in chunker.chunk_text(text):
    raw = model.predict_entities(chunk.text, labels, threshold=0.0, flat_ner=False)
    for r in raw:
        local.append({
            'text': r.get('text', ''),
            'label': r.get('label', ''),
            'start': int(r['start']) + chunk.start,
            'end': int(r['end']) + chunk.start,
            'score': float(r.get('score', 0.0)),
        })
print(f'Local Run A @ threshold=0.0: {len(local)} candidates')


def overlap(a, b):
    return max(a['start'], b['start']) < min(a['end'], b['end'])


results = []
for nv in nvidia_entities:
    candidates = [l for l in local if l['label'] == nv['label'] and overlap(nv, l)]
    if candidates:
        best = max(candidates, key=lambda x: x['score'])
        if best['score'] >= 0.8:
            status = 'matched_at_0.8'
        elif best['score'] >= 0.4:
            status = 'found_in_[0.4,0.8)'
        elif best['score'] > 0:
            status = 'found_in_(0,0.4)'
        else:
            status = 'found_zero_score'
        results.append({**nv, 'local_score': best['score'], 'local_text': best['text'], 'status': status})
    else:
        results.append({**nv, 'local_score': None, 'local_text': None, 'status': 'not_detected_at_all'})

stats = Counter(r['status'] for r in results)
print('\n=== Global stats ===')
for k, v in sorted(stats.items()):
    print(f'  {k}: {v}')

print('\n=== Missings breakdown by label x status ===')
by_label = Counter()
for r in results:
    if r['status'] == 'matched_at_0.8':
        continue
    by_label[(r['label'], r['status'])] += 1
for (label, status), count in sorted(by_label.items(), key=lambda x: -x[1]):
    print(f'  {label:<32} {status:<24} {count}')

print('\n=== All missings (full detail) ===')
print(f'{"label":<32} {"NV_score":>9} {"LOC_score":>10}  status')
print('-' * 110)
for r in results:
    if r['status'] == 'matched_at_0.8':
        continue
    loc_score = r['local_score']
    loc_str = f'{loc_score:.2f}' if loc_score is not None else 'n/a'
    base_text = r['text'][:25]
    loc_text = r['local_text'][:25] if r['local_text'] else '-'
    print(f'{r["label"]:<32} {r["score"]:>9.2f} {loc_str:>10}  {r["status"]:<22}  '
          f'baseline={base_text!r}  local={loc_text!r}')

# Score distribution of "found in [0.4, 0.8)" — these are recoverable by lowering threshold
recoverable = [r for r in results if r['status'] == 'found_in_[0.4,0.8)']
print(f'\n=== {len(recoverable)} entities recoverable by lowering threshold to 0.4 ===')
score_buckets = Counter()
for r in recoverable:
    s = r['local_score']
    if s >= 0.7: score_buckets['[0.7, 0.8)'] += 1
    elif s >= 0.6: score_buckets['[0.6, 0.7)'] += 1
    elif s >= 0.5: score_buckets['[0.5, 0.6)'] += 1
    else: score_buckets['[0.4, 0.5)'] += 1
for k, v in sorted(score_buckets.items()):
    print(f'  {k}: {v}')

# Truly missing (zero or no candidate)
truly_missing = [r for r in results if r['status'] in ('not_detected_at_all', 'found_zero_score', 'found_in_(0,0.4)')]
print(f'\n=== {len(truly_missing)} entities truly missing (no local candidate even at threshold=0) ===')
by_label = Counter(r['label'] for r in truly_missing)
for label, count in sorted(by_label.items(), key=lambda x: -x[1]):
    print(f'  {label}: {count}')

# Save full results for inspection
out_path = Path(__file__).parent.parent / 'doc' / 'gliner-missing-entities-analysis.json'
out_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'\nFull results written to: {out_path}')
