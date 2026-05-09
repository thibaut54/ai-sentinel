"""
Benchmark integration test: effet du chunking sur la recall GLiNER (modele brut).

Probleme observe: notre pipeline de production via MultiPassGlinerDetector +
WhitespaceWordWindowChunker(380, 80) atteint une recall de ~23% face a la
baseline NVIDIA hosted (250 entites trouvees) sur le doc Confluence parity.

Ce test isole `nvidia/gliner-PII` charge directement (sans pipeline composite,
sans multi-pass, sans conflict resolver, sans post-filter) pour mesurer la
recall brute du modele en fonction de la strategie de chunking. Le but :
identifier la strategie qui maximise la recall sans modifier le pipeline.

Strategies comparees, chacune avec les 13 labels NVIDIA + threshold 0.5 :
- NO_CHUNKING : doc complet (illustre la truncation silencieuse de GLiNER a 384
  whitespace tokens)
- CHUNK_380_OVERLAP_80 : config actuelle WhitespaceWordWindowChunker
- CHUNK_384_OVERLAP_128 : config probable du playground NVIDIA hosted
- CHUNK_380_OVERLAP_190 : overlap 50%
- CHUNK_300_OVERLAP_100 : chunks plus petits + overlap dense
- CHUNK_200_OVERLAP_80 : chunks tres petits

Le test affiche un rapport par strategie avec recall agregee et per-label.
Aucune assertion stricte sauf un sanity check (no_chunking < best_chunking).

Usage :
    python -m pytest tests/integration/test_gliner_chunking_strategies_benchmark.py -v -s
"""
from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
MODEL_ID = "nvidia/gliner-PII"
THRESHOLD = 0.5

# Les 13 labels GLiNER que NVIDIA hosted a utilise pour produire la baseline.
# Ordre figé pour faciliter la lecture des rapports.
PARITY_LABELS: List[str] = [
    "customer_id",
    "api_key",
    "account_number",
    "swift_bic",
    "device_identifier",
    "certificate_license_number",
    "health_plan_beneficiary_number",
    "medical_record_number",
    "national_id",
    "password",
    "http_cookie",
    "ssn",
    "license_plate",
]

# ---------------------------------------------------------------------------
# Paths to fixtures
# ---------------------------------------------------------------------------
# pii-detector-service/tests/integration/<this file> -> repo root = parents[3]
REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS_PATH = (
    REPO_ROOT
    / "pii-reporting-api"
    / "src"
    / "test"
    / "resources"
    / "test-corpus"
    / "Miscellaneous"
    / "confluence-pii-test-document-docanno.txt"
)
BASELINE_PATH = (
    REPO_ROOT / "my-files" / "confluence-pii-test-document-nvidia-gliner-result.json"
)


# ---------------------------------------------------------------------------
# Helpers — chunking
# ---------------------------------------------------------------------------
def _whitespace_token_offsets(text: str) -> List[Tuple[int, int]]:
    """Retourne la liste des (start, end) de chaque token whitespace."""
    return [(m.start(), m.end()) for m in re.finditer(r"\S+", text)]


def chunk_by_whitespace_tokens(
    text: str, chunk_size: int, overlap: int
) -> List[Tuple[int, str]]:
    """
    Split `text` en fenetres de `chunk_size` tokens whitespace avec `overlap`.

    Returns:
        Liste de (start_offset_dans_text, chunk_text). Aligne sur les
        frontieres de tokens donc pas de coupure en plein mot.
    """
    if chunk_size <= overlap:
        raise ValueError(f"chunk_size ({chunk_size}) must be > overlap ({overlap})")

    tokens = _whitespace_token_offsets(text)
    if not tokens:
        return []
    if len(tokens) <= chunk_size:
        return [(tokens[0][0], text[tokens[0][0] : tokens[-1][1]])]

    chunks: List[Tuple[int, str]] = []
    step = chunk_size - overlap
    i = 0
    while i < len(tokens):
        slice_tokens = tokens[i : i + chunk_size]
        if not slice_tokens:
            break
        start = slice_tokens[0][0]
        end = slice_tokens[-1][1]
        chunks.append((start, text[start:end]))
        if i + chunk_size >= len(tokens):
            break
        i += step
    return chunks


# ---------------------------------------------------------------------------
# Helpers — GLiNER inference
# ---------------------------------------------------------------------------
def predict_no_chunking(
    model, text: str, labels: List[str], threshold: float
) -> List[Dict]:
    """Passe le texte complet a GLiNER. Tronque silencieusement a 384 tokens."""
    raw = model.predict_entities(text, labels, threshold=threshold)
    return [
        {
            "text": e["text"],
            "label": e["label"],
            "start": int(e["start"]),
            "end": int(e["end"]),
            "score": float(e.get("score", 0.0)),
        }
        for e in raw
    ]


def _iou(a: Dict, b: Dict) -> float:
    """Intersection over Union sur les offsets de deux entites (memes labels)."""
    inter_start = max(a["start"], b["start"])
    inter_end = min(a["end"], b["end"])
    if inter_start >= inter_end:
        return 0.0
    inter = inter_end - inter_start
    union = (a["end"] - a["start"]) + (b["end"] - b["start"]) - inter
    return inter / union if union > 0 else 0.0


def dedup_overlapping_per_label(
    entities: List[Dict], iou_threshold: float = 0.0
) -> Tuple[List[Dict], int]:
    """
    Dedup : pour chaque label, deux entites qui se chevauchent (any overlap)
    sont considerees comme duplicatas. On garde la plus longue, puis le meilleur
    score en cas d'egalite. Les occurrences a positions strictement distinctes
    (pas meme un caractere de chevauchement) sont preservees — ex: meme mot de
    passe ecrit en haut et en bas du document.

    Cette logique est alignee sur la prod : voir
    `DetectionMerger._resolve_overlaps_for_type` (`_check_overlap` returns 'none'
    iff `e1.end <= e2.start or e2.end <= e1.start`). Tout autre cas (containment
    ou partial) est traite comme un duplicat.

    `iou_threshold` = 0.0 par defaut (= any overlap). Mettre > 0 pour etre plus
    tolerant aux entites adjacentes mais distinctes.

    Returns:
        (entites_dedupliquees, nombre_de_doublons_retires)
    """
    by_label: Dict[str, List[Dict]] = {}
    for e in entities:
        by_label.setdefault(e["label"], []).append(e)

    out: List[Dict] = []
    duplicates_removed = 0
    for ents in by_label.values():
        # Trier longueur desc, puis score desc -> on garde les meilleurs candidats en premier
        ents_sorted = sorted(
            ents,
            key=lambda x: (-(x["end"] - x["start"]), -x["score"]),
        )
        kept: List[Dict] = []
        for current in ents_sorted:
            duplicate = False
            for k in kept:
                # "any overlap" : meme un seul caractere de chevauchement compte
                if k["start"] < current["end"] and current["start"] < k["end"]:
                    if iou_threshold <= 0.0 or _iou(current, k) >= iou_threshold:
                        duplicate = True
                        break
            if duplicate:
                duplicates_removed += 1
                continue
            kept.append(current)
        out.extend(kept)
    return out, duplicates_removed


def predict_with_semchunk(
    model,
    text: str,
    labels: List[str],
    threshold: float,
    chunk_size: int,
    overlap: int,
) -> Tuple[List[Dict], int]:
    """
    Run GLiNER en utilisant GlinerSubwordChunker (semchunk + tokenizer GLiNER).

    Contrairement a `predict_with_chunking` qui compte les whitespace tokens,
    cette strategie compte les SUBWORD tokens du tokenizer HF de GLiNER. C'est
    aligne avec ce que GLiNER consomme reellement, donc plus fiable pour rester
    sous max_len=384.

    Returns:
        (entites_dedupliquees, nombre_de_chunks_produits)
    """
    from pii_detector.infrastructure.text_processing.semantic_chunker import (
        GlinerSubwordChunker,
    )

    # Recupere le tokenizer HF interne du modele GLiNER charge.
    data_processor = getattr(model, "data_processor", None)
    tokenizer = (
        getattr(data_processor, "transformer_tokenizer", None)
        if data_processor is not None
        else None
    )
    if tokenizer is None:
        from transformers import AutoTokenizer
        tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)

    chunker = GlinerSubwordChunker(
        tokenizer=tokenizer,
        chunk_size=chunk_size,
        overlap=overlap,
    )
    chunk_results = chunker.chunk_text(text)

    seen_exact: set = set()
    raw_entities: List[Dict] = []
    for chunk_result in chunk_results:
        raw = model.predict_entities(chunk_result.text, labels, threshold=threshold)
        for e in raw:
            adj_start = int(e["start"]) + chunk_result.start
            adj_end = int(e["end"]) + chunk_result.start
            key = (adj_start, adj_end, e["label"])
            if key in seen_exact:
                continue
            seen_exact.add(key)
            raw_entities.append(
                {
                    "text": e["text"],
                    "label": e["label"],
                    "start": adj_start,
                    "end": adj_end,
                    "score": float(e.get("score", 0.0)),
                }
            )
    deduped, dropped_by_overlap = dedup_overlapping_per_label(
        raw_entities, iou_threshold=0.0
    )
    print(
        f"  [SEMCHUNK_DEDUP] raw_after_exact_dedup={len(raw_entities)}  "
        f"after_overlap_dedup={len(deduped)}  overlap_dropped={dropped_by_overlap}"
    )
    return deduped, len(chunk_results)


def predict_with_chunking(
    model,
    text: str,
    labels: List[str],
    threshold: float,
    chunk_size: int,
    overlap: int,
) -> List[Dict]:
    """
    Run GLiNER en chunking whitespace-token, avec re-alignement des offsets et
    deduplication tolerante (IoU >= 0.5 par label, garde le plus long).

    Le compte de doublons retires par IoU est imprime en INFO pour visualiser
    l'effet du chunking sur les bornes flottantes.
    """
    chunks = chunk_by_whitespace_tokens(text, chunk_size, overlap)
    # Premiere passe : dedup exact (start, end, label) sur les chunks chevauchants
    seen_exact: set = set()
    raw_entities: List[Dict] = []
    for chunk_offset, chunk_text in chunks:
        raw = model.predict_entities(chunk_text, labels, threshold=threshold)
        for e in raw:
            adj_start = int(e["start"]) + chunk_offset
            adj_end = int(e["end"]) + chunk_offset
            key = (adj_start, adj_end, e["label"])
            if key in seen_exact:
                continue
            seen_exact.add(key)
            raw_entities.append(
                {
                    "text": e["text"],
                    "label": e["label"],
                    "start": adj_start,
                    "end": adj_end,
                    "score": float(e.get("score", 0.0)),
                }
            )
    # Deuxieme passe : dedup par chevauchement "any overlap" (aligne sur la
    # logique prod DetectionMerger._resolve_overlaps_for_type). Un IoU strict
    # 0.5 laissait passer trop de doublons quand les chunks coupent un span
    # ou que les bornes flottent.
    deduped, dropped_by_overlap = dedup_overlapping_per_label(
        raw_entities, iou_threshold=0.0
    )
    print(
        f"  [DEDUP] raw_after_exact_dedup={len(raw_entities)}  "
        f"after_overlap_dedup={len(deduped)}  overlap_dropped={dropped_by_overlap}"
    )
    return deduped


# ---------------------------------------------------------------------------
# Helpers — comparaison baseline
# ---------------------------------------------------------------------------
def compute_metrics(
    detected: List[Dict], baseline_counts: Dict[str, int], baseline_total: int
) -> Dict:
    """Calcule recall agregee et per-label vs baseline NVIDIA."""
    detected_per_label: Dict[str, int] = {}
    for e in detected:
        detected_per_label[e["label"]] = detected_per_label.get(e["label"], 0) + 1

    matched_total = 0
    per_label: Dict[str, Dict] = {}
    for label, expected in baseline_counts.items():
        d = detected_per_label.get(label, 0)
        matched = min(d, expected)
        matched_total += matched
        per_label[label] = {
            "expected": expected,
            "detected": d,
            "matched": matched,
            "recall": d / expected if expected else 0.0,
        }

    extras = {
        label: count
        for label, count in detected_per_label.items()
        if label not in baseline_counts
    }
    aggregate_recall = matched_total / baseline_total if baseline_total else 0.0

    return {
        "total_detected": len(detected),
        "matched_total": matched_total,
        "baseline_total": baseline_total,
        "aggregate_recall": aggregate_recall,
        "per_label": per_label,
        "extras": extras,
    }


def print_report(
    strategy_name: str,
    num_chunks: int,
    elapsed_s: float,
    metrics: Dict,
    dropped_by_iou: int = 0,
) -> None:
    """Imprime un rapport tabulaire lisible."""
    bar = "=" * 80
    print(f"\n{bar}")
    print(
        f"  {strategy_name}  | chunks={num_chunks}  elapsed={elapsed_s:.1f}s  "
        f"iou_dedup_dropped={dropped_by_iou}"
    )
    print(bar)
    print(
        f"  Total detected: {metrics['total_detected']}  |  "
        f"Matched (parity labels): {metrics['matched_total']}/{metrics['baseline_total']}  |  "
        f"Aggregate recall: {metrics['aggregate_recall'] * 100:.1f}%"
    )
    print(f"\n  {'label':<34} {'expected':>9} {'detected':>9} {'matched':>9} {'recall':>8}")
    print(f"  {'-' * 34} {'-' * 9} {'-' * 9} {'-' * 9} {'-' * 8}")
    for label in PARITY_LABELS:
        m = metrics["per_label"].get(label, {"expected": 0, "detected": 0, "matched": 0, "recall": 0.0})
        print(
            f"  {label:<34} {m['expected']:>9} {m['detected']:>9} {m['matched']:>9} "
            f"{m['recall'] * 100:>7.1f}%"
        )
    if metrics["extras"]:
        print(f"\n  Extras (labels hors baseline): {metrics['extras']}")


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def model():
    """Charge nvidia/gliner-PII une seule fois pour toute la classe de tests."""
    if not CORPUS_PATH.exists():
        pytest.skip(f"Corpus introuvable: {CORPUS_PATH}")
    if not BASELINE_PATH.exists():
        pytest.skip(f"Baseline NVIDIA introuvable: {BASELINE_PATH}")
    from gliner import GLiNER

    print(f"\n[SETUP] Loading {MODEL_ID}...")
    t0 = time.time()
    m = GLiNER.from_pretrained(MODEL_ID)
    print(f"[SETUP] Model loaded in {time.time() - t0:.1f}s")
    return m


@pytest.fixture(scope="module")
def corpus() -> str:
    return CORPUS_PATH.read_text(encoding="utf-8")


@pytest.fixture(scope="module")
def baseline_counts() -> Dict[str, int]:
    """Parse la baseline NVIDIA et retourne {label: count}."""
    raw = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    # Le contenu est une string JSON wrappee dans la response chat-completion
    inner = json.loads(raw["choices"][0]["message"]["content"])
    counts: Dict[str, int] = {}
    for entity in inner.get("entities", []):
        label = entity["label"]
        counts[label] = counts.get(label, 0) + 1
    return counts


@pytest.fixture(scope="module")
def baseline_total(baseline_counts) -> int:
    return sum(baseline_counts.values())


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
@pytest.mark.integration
@pytest.mark.slow
class TestChunking:
    """
    Compare plusieurs strategies de chunking sur le meme doc parity, sur le meme
    modele charge en local, sans aucun pipeline. Le but est de mesurer l'impact
    pur du chunking sur la recall GLiNER.
    """

    # Stockage partage pour le sanity check final entre tests.
    _aggregate_recall_by_strategy: Dict[str, float] = {}

    def _record(self, name: str, recall: float) -> None:
        TestChunking._aggregate_recall_by_strategy[name] = recall

    def test_baseline_summary(self, baseline_counts, baseline_total):
        """Imprime la repartition de la baseline NVIDIA pour reference."""
        print(f"\n[BASELINE] NVIDIA total entities: {baseline_total}")
        for label in PARITY_LABELS:
            print(f"[BASELINE]   {label:<34} = {baseline_counts.get(label, 0)}")
        assert baseline_total > 0

    def test_no_chunking(self, model, corpus, baseline_counts, baseline_total):
        """Pas de chunking. GLiNER tronque silencieusement a 384 tokens whitespace."""
        t0 = time.time()
        detected = predict_no_chunking(model, corpus, PARITY_LABELS, THRESHOLD)
        elapsed = time.time() - t0
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("NO_CHUNKING (full doc, GLiNER truncates at ~384 tokens)", 1, elapsed, metrics)
        self._record("no_chunking", metrics["aggregate_recall"])

    def test_chunk_380_overlap_80(self, model, corpus, baseline_counts, baseline_total):
        """Strategie de prod: WhitespaceWordWindowChunker(380, 80)."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=380, overlap=80
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 380, 80))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_380_OVERLAP_80 (current prod config)", num_chunks, elapsed, metrics)
        self._record("chunk_380_overlap_80", metrics["aggregate_recall"])

    def test_chunk_384_overlap_128(self, model, corpus, baseline_counts, baseline_total):
        """Config probable du playground NVIDIA hosted (chunk_length=384, overlap=128)."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=384, overlap=128
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 384, 128))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_384_OVERLAP_128 (NVIDIA hosted-like)", num_chunks, elapsed, metrics)
        self._record("chunk_384_overlap_128", metrics["aggregate_recall"])

    def test_chunk_380_overlap_190(self, model, corpus, baseline_counts, baseline_total):
        """50% overlap. Plus de passes mais plus de contexte preserve."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=380, overlap=190
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 380, 190))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_380_OVERLAP_190 (50% overlap)", num_chunks, elapsed, metrics)
        self._record("chunk_380_overlap_190", metrics["aggregate_recall"])

    def test_chunk_300_overlap_100(self, model, corpus, baseline_counts, baseline_total):
        """Chunks plus petits, overlap dense."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=300, overlap=100
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 300, 100))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_300_OVERLAP_100", num_chunks, elapsed, metrics)
        self._record("chunk_300_overlap_100", metrics["aggregate_recall"])

    def test_chunk_200_overlap_80(self, model, corpus, baseline_counts, baseline_total):
        """Chunks tres petits, simulent un focus zoom sur chaque section."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=200, overlap=80
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 200, 80))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_200_OVERLAP_80", num_chunks, elapsed, metrics)
        self._record("chunk_200_overlap_80", metrics["aggregate_recall"])

    def test_chunk_150_overlap_50(self, model, corpus, baseline_counts, baseline_total):
        """Chunks 150 tokens, overlap 33% — entre 200 et 100."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=150, overlap=50
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 150, 50))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_150_OVERLAP_50", num_chunks, elapsed, metrics)
        self._record("chunk_150_overlap_50", metrics["aggregate_recall"])

    def test_chunk_100_overlap_40(self, model, corpus, baseline_counts, baseline_total):
        """Chunks 100 tokens, overlap 40%. Suggestion utilisateur."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=100, overlap=40
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 100, 40))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_100_OVERLAP_40", num_chunks, elapsed, metrics)
        self._record("chunk_100_overlap_40", metrics["aggregate_recall"])

    def test_chunk_100_overlap_50(self, model, corpus, baseline_counts, baseline_total):
        """Chunks 100 tokens, overlap 50%. Compare 100/40 vs 100/50 a meme chunk size."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=100, overlap=50
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 100, 50))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_100_OVERLAP_50 (50% overlap)", num_chunks, elapsed, metrics)
        self._record("chunk_100_overlap_50", metrics["aggregate_recall"])

    def test_semchunk_384_overlap_128(self, model, corpus, baseline_counts, baseline_total):
        """
        Strategie cible: GlinerSubwordChunker(384, 128) — semchunk + tokenizer HF GLiNER.

        Mesure les SUBWORD tokens (vrai compte GLiNER) au lieu des whitespace
        tokens (mots). Doit egaler ou depasser CHUNK_384_OVERLAP_128 en recall
        car aucun chunk ne peut depasser silencieusement max_len=384.
        """
        t0 = time.time()
        detected, num_chunks = predict_with_semchunk(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=384, overlap=128
        )
        elapsed = time.time() - t0
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report(
            "SEMCHUNK_384_OVERLAP_128 (semchunk + GLiNER tokenizer, target prod config)",
            num_chunks, elapsed, metrics,
        )
        self._record("semchunk_384_overlap_128", metrics["aggregate_recall"])

    def test_chunk_50_overlap_20(self, model, corpus, baseline_counts, baseline_total):
        """Chunks 50 tokens, overlap 40%. Limite basse : potentiellement perd trop de contexte."""
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, THRESHOLD, chunk_size=50, overlap=20
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 50, 20))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report("CHUNK_50_OVERLAP_20 (extreme small)", num_chunks, elapsed, metrics)
        self._record("chunk_50_overlap_20", metrics["aggregate_recall"])

    def test_summary_and_sanity_check(self):
        """Synthese finale de toutes les strategies et sanity check."""
        recalls = TestChunking._aggregate_recall_by_strategy
        if not recalls:
            pytest.skip("Aucun resultat collecte (autres tests skipped ou failed).")

        bar = "=" * 80
        print(f"\n{bar}\n  RECAP — recall agregee par strategie\n{bar}")
        for name, recall in sorted(recalls.items(), key=lambda x: -x[1]):
            print(f"  {name:<32} {recall * 100:>6.1f}%")
        print(bar)

        # Sanity check: au moins UNE strategie de chunking doit battre no_chunking.
        no_chunk = recalls.get("no_chunking", 0.0)
        best_chunked = max(
            (v for k, v in recalls.items() if k != "no_chunking"), default=0.0
        )
        assert best_chunked > no_chunk, (
            f"Le chunking n'apporte rien: best_chunked={best_chunked:.2%} "
            f"no_chunking={no_chunk:.2%}. Verifier le pipeline."
        )


# ---------------------------------------------------------------------------
# Threshold sweep — meme strategie de chunking, threshold variable
# ---------------------------------------------------------------------------
@pytest.mark.integration
@pytest.mark.slow
class TestThreshold:
    """
    Mesure l'impact du threshold a chunking constant (config prod 380/80).
    Permet de voir si baisser le threshold de 0.5 a 0.3 recupere significativement.
    """

    @pytest.mark.parametrize("threshold", [0.30, 0.40, 0.50, 0.60])
    def test_threshold_sweep(
        self, model, corpus, baseline_counts, baseline_total, threshold
    ):
        t0 = time.time()
        detected = predict_with_chunking(
            model, corpus, PARITY_LABELS, threshold, chunk_size=380, overlap=80
        )
        elapsed = time.time() - t0
        num_chunks = len(chunk_by_whitespace_tokens(corpus, 380, 80))
        metrics = compute_metrics(detected, baseline_counts, baseline_total)
        print_report(
            f"CHUNK_380_OVERLAP_80 @ threshold={threshold:.2f}",
            num_chunks,
            elapsed,
            metrics,
        )


# ---------------------------------------------------------------------------
# Standalone runner
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    pytest.main([__file__, "-s", "-v", "-m", "integration"])
