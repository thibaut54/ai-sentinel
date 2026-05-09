"""
Integration test: validation du chunking GLiNER apres bascule vers semchunk.

But du test : verifier que la chaine reelle de prod
``GLiNERDetector.load_model()`` -> ``_initialize_text_chunker()`` ->
``GlinerSubwordChunker(384, 128, tokenizer HF)`` -> ``predict_chunked()``
detecte bien les PII attendues sur un long document.

Pourquoi ce test :
- ``test_gliner_nvidia_parity.py`` charge GLiNER en raw sans chunking,
  donc ne couvre pas notre code de chunking.
- ``test_gliner_chunking_strategies_benchmark.py`` instancie le chunker
  via un helper qui bypass GLiNERDetector.
- Avant le changement (WhitespaceWordWindowChunker(380, 80)), la recall
  agregee s'effondrait a ~23% car 380 mots francais peuvent valoir
  600+ subword tokens, declenchant la troncation silencieuse de GLiNER.
- Apres bascule sur GlinerSubwordChunker(384 subword tokens, 128 overlap),
  on attend une recall remontee a >= 66% (barre prod du test parity).

Le test compare deux modes sur le meme corpus :
- raw : ``model.predict_entities(text, labels, threshold)`` direct,
  qui tronque silencieusement a max_len=384 subwords.
- chunked : ``detector.predict_chunked(text, labels, threshold)`` qui
  passe par notre nouveau chunker.
La recall chunked doit etre strictement superieure a la recall raw.

Usage:
    pytest tests/integration/test_gliner_detector_chunking_recall.py -s -m integration
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

import pytest

# pii-detector-service/tests/integration/<this file> -> service root = parents[2]
SERVICE_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(SERVICE_ROOT))

# pii-detector-service/<this> -> repo root = parents[3]
REPO_ROOT = Path(__file__).resolve().parents[3]

MODEL_ID = "nvidia/gliner-PII"
THRESHOLD = 0.5
EXPECTED_CHUNK_SIZE = 384
EXPECTED_OVERLAP = 128

# Memes labels que le bench parity (extraits du dataset Nemotron-PII fine-tune).
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

# Modele GLiNER local pre-telecharge dans le repo (evite le download HF
# qui crashe sur Windows quand ~/.cache/huggingface/hub n'existe pas).
LOCAL_MODEL_PATH = (
    SERVICE_ROOT / "models" / "gliner-pii-onnx" / "pytorch"
)


def _resolve_model_id() -> str:
    """
    Retourne le chemin du modele local s'il existe, sinon le model_id HF.

    Si le HF cache (~/.cache/huggingface/hub) n'existe pas, le download HF
    plante avec WinError 3 sur Windows. Le modele local pre-telecharge dans
    pii-detector-service/models/gliner-pii-onnx/pytorch/ contient les memes
    poids (microsoft/deberta-v3-large, max_len=384) que nvidia/gliner-PII,
    et `GLiNER.from_pretrained` accepte un path local.
    """
    if LOCAL_MODEL_PATH.exists():
        return str(LOCAL_MODEL_PATH)
    # Fallback: pre-cree le cache HF pour eviter le crash makedirs sur Windows.
    cache_root = Path.home() / ".cache" / "huggingface" / "hub"
    cache_root.mkdir(parents=True, exist_ok=True)
    return MODEL_ID


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _spans_overlap(a_start: int, a_end: int, b_start: int, b_end: int) -> bool:
    """True iff [a_start, a_end) and [b_start, b_end) share at least one char."""
    return a_start < b_end and b_start < a_end


def _baseline_label_counts(baseline_entities: List[Dict[str, Any]]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for e in baseline_entities:
        counts[e["label"]] = counts.get(e["label"], 0) + 1
    return counts


def _detected_label_counts(entities: List[Dict[str, Any]]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for e in entities:
        label = e.get("label", "")
        counts[label] = counts.get(label, 0) + 1
    return counts


def _aggregate_recall(
    detected: List[Dict[str, Any]], baseline_counts: Dict[str, int]
) -> Tuple[float, int, int]:
    """Compute recall = sum(min(detected_per_label, baseline_per_label)) / sum(baseline)."""
    detected_counts = _detected_label_counts(detected)
    baseline_total = sum(baseline_counts.values())
    matched = sum(
        min(detected_counts.get(label, 0), expected)
        for label, expected in baseline_counts.items()
    )
    recall = matched / baseline_total if baseline_total else 0.0
    return recall, matched, baseline_total


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def corpus() -> str:
    if not CORPUS_PATH.exists():
        pytest.skip(f"Corpus introuvable: {CORPUS_PATH}")
    return CORPUS_PATH.read_text(encoding="utf-8")


@pytest.fixture(scope="module")
def baseline_entities() -> List[Dict[str, Any]]:
    """Parse la baseline NVIDIA wrappee dans une chat-completion response."""
    if not BASELINE_PATH.exists():
        pytest.skip(f"Baseline NVIDIA introuvable: {BASELINE_PATH}")
    raw = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    inner = json.loads(raw["choices"][0]["message"]["content"])
    return inner.get("entities", [])


@pytest.fixture(scope="module")
def detector():
    """
    Instancie le vrai GLiNERDetector de prod, model loade, chunker initialise.

    Utilise le modele local quand il existe pour eviter le download HF
    (crashe sur Windows quand ~/.cache/huggingface/hub n'existe pas).
    """
    from pii_detector.application.config.detection_policy import DetectionConfig
    from pii_detector.infrastructure.detector.gliner_detector import GLiNERDetector

    config = DetectionConfig(model_id=_resolve_model_id(), threshold=THRESHOLD)
    det = GLiNERDetector(config=config)
    det.load_model()
    return det


@pytest.fixture(scope="module")
def raw_model():
    """Charge le modele GLiNER en raw pour comparer sans chunking."""
    from gliner import GLiNER
    return GLiNER.from_pretrained(_resolve_model_id())


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
@pytest.mark.integration
@pytest.mark.slow
class TestGlinerDetectorChunkingRecall:
    """
    Verifie que GLiNERDetector + GlinerSubwordChunker delivre une recall
    elevee contre la baseline NVIDIA hosted, et qu'il ameliore strictement
    sur l'inference raw (sans chunking, qui tronque a max_len=384).
    """

    def test_Should_WireGlinerSubwordChunker_When_LoadModelCompletes(self, detector):
        """
        Le wiring de prod doit instancier GlinerSubwordChunker (pas l'ancien
        WhitespaceWordWindowChunker) avec exactement chunk_size=384, overlap=128
        et un tokenizer HF non-None.
        """
        from pii_detector.infrastructure.text_processing.semantic_chunker import (
            GlinerSubwordChunker,
        )

        assert detector.semantic_chunker is not None
        assert isinstance(detector.semantic_chunker, GlinerSubwordChunker), (
            f"Expected GlinerSubwordChunker, got {type(detector.semantic_chunker).__name__}"
        )

        info = detector.semantic_chunker.get_chunk_info()
        assert info["chunk_size"] == EXPECTED_CHUNK_SIZE
        assert info["overlap"] == EXPECTED_OVERLAP
        assert info["library"] == "semchunk"
        assert info["available"] is True
        assert info["tokenizer"], "tokenizer name should be reported"

    def test_Should_ProduceMultipleChunks_When_DocumentExceedsMaxLen(
        self, detector, corpus
    ):
        """
        Sanity : le corpus parity (>5k chars FR) doit produire >= 2 chunks.
        Si on n'a qu'un chunk, c'est que le chunker ne s'est pas declenche
        et le test de recall plus bas n'aurait aucune valeur.
        """
        chunks = detector.semantic_chunker.chunk_text(corpus)
        assert len(chunks) >= 2, (
            f"Corpus de {len(corpus)} chars devrait produire >= 2 chunks, "
            f"got {len(chunks)}. Verifier chunk_size={EXPECTED_CHUNK_SIZE}."
        )
        # Contrat d'offsets : chunks[i].text == corpus[chunks[i].start:chunks[i].end]
        for i, chunk in enumerate(chunks):
            assert corpus[chunk.start:chunk.end] == chunk.text, (
                f"Chunk #{i} offsets do not match source text. "
                f"start={chunk.start} end={chunk.end}"
            )

    def test_Should_DetectEntitiesViaPredictChunked_When_TraversingProductionPipeline(
        self, detector, corpus
    ):
        """
        ``predict_chunked`` doit retourner des entites avec offsets re-alignes
        sur le texte original (pas sur le chunk local).
        """
        entities = detector.predict_chunked(corpus, PARITY_LABELS, THRESHOLD)

        assert entities, "predict_chunked should return at least one entity on parity corpus"

        for e in entities:
            assert "start" in e and "end" in e and "label" in e
            assert 0 <= e["start"] < e["end"] <= len(corpus), (
                f"Entity offsets out of bounds: {e}"
            )
            # Le texte rapporte par GLiNER doit correspondre au slice corpus.
            # On verifie une egalite approximative (GLiNER strip parfois la
            # ponctuation finale, donc on tolere un sous-string).
            slice_text = corpus[e["start"]:e["end"]]
            assert e["text"].strip() in slice_text or slice_text.strip() in e["text"], (
                f"Entity text/offset mismatch: text={e['text']!r} slice={slice_text!r}"
            )

    def test_Should_ImproveRecallStrictly_When_ComparedToRawNoChunking(
        self, detector, raw_model, corpus, baseline_entities
    ):
        """
        Le test cle : comparer la recall via predict_chunked (notre code) vs
        l'appel raw (GLiNER.predict_entities, qui tronque silencieusement a
        max_len=384). Le chunking doit ameliorer strictement la recall.
        """
        baseline_counts = _baseline_label_counts(baseline_entities)

        raw_entities = raw_model.predict_entities(corpus, PARITY_LABELS, THRESHOLD)
        chunked_entities = detector.predict_chunked(corpus, PARITY_LABELS, THRESHOLD)

        raw_recall, raw_matched, total = _aggregate_recall(raw_entities, baseline_counts)
        chunked_recall, chunked_matched, _ = _aggregate_recall(
            chunked_entities, baseline_counts
        )

        print(
            f"\n[RECALL] baseline_total={total} "
            f"raw={raw_matched} ({raw_recall * 100:.1f}%) "
            f"chunked={chunked_matched} ({chunked_recall * 100:.1f}%)"
        )

        assert chunked_recall > raw_recall, (
            f"Chunking should strictly improve recall over raw inference. "
            f"raw_recall={raw_recall:.3f} chunked_recall={chunked_recall:.3f}. "
            f"Si l'ecart est nul, le chunker ne s'est pas declenche ou le "
            f"corpus est trop court pour exposer la troncation."
        )

    def test_Should_ReachProductionRecallBar_When_UsingChunkedInference(
        self, detector, corpus, baseline_entities
    ):
        """
        Cible: recall >= 66% via predict_chunked. Avant le changement
        (WhitespaceWordWindowChunker(380, 80) en mots whitespace), la recall
        plafonnait a ~23% sur ce meme corpus. La nouvelle barre 66% aligne
        sur la barre du test parity NVIDIA et garantit qu'aucune regression
        future ne nous ramene en-dessous.
        """
        baseline_counts = _baseline_label_counts(baseline_entities)
        chunked_entities = detector.predict_chunked(corpus, PARITY_LABELS, THRESHOLD)

        recall, matched, total = _aggregate_recall(chunked_entities, baseline_counts)

        print(
            f"\n[RECALL_BAR] matched={matched}/{total} recall={recall * 100:.1f}% "
            f"(target>=66%)"
        )

        assert recall >= 0.66, (
            f"Recall {recall * 100:.1f}% < 66% sur le corpus parity. "
            f"Le diagnostic chunking subword peut etre incorrect, ou une "
            f"regression a ete introduite. Verifier les logs PARITY_DEBUG."
        )

    def test_Should_CoverAllBaselineLabels_When_DetectingViaPredictChunked(
        self, detector, corpus, baseline_entities
    ):
        """
        Chaque label que NVIDIA hosted a produit doit produire >= 1 entite
        localement. Une couverture < 100% indique un label dont le chunker
        coupe systematiquement le contexte necessaire au modele.
        """
        baseline_labels: Set[str] = {e["label"] for e in baseline_entities}
        chunked_entities = detector.predict_chunked(corpus, PARITY_LABELS, THRESHOLD)
        local_labels: Set[str] = {e["label"] for e in chunked_entities}

        missing = sorted(baseline_labels - local_labels)
        print(
            f"\n[COVERAGE] baseline_labels={len(baseline_labels)} "
            f"local_labels={len(local_labels)} missing={missing}"
        )

        assert not missing, (
            f"Labels presents dans la baseline NVIDIA mais absents localement: "
            f"{missing}. Possible si le chunker isole un label dans un chunk "
            f"trop court pour le reconnaitre."
        )


if __name__ == "__main__":
    pytest.main([__file__, "-s", "-v", "-m", "integration"])
