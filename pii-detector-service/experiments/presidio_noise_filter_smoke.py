"""Smoke test : le filtre _RegistryLanguageNoiseFilter installé par l'import de
presidio_detector supprime le bruit « Recognizer not added to registry » sans
toucher aux autres warnings du logger presidio-analyzer.

Usage (depuis pii-detector-service) :
    uv run --with toml python experiments/presidio_noise_filter_smoke.py

presidio_analyzer est stubé pour éviter d'installer spacy & co sur l'hôte ;
seul le comportement logging (pur stdlib) est sous test.
"""
import io
import logging
import sys
import types
from pathlib import Path

# Stub presidio_analyzer : presidio_detector n'en a besoin qu'à l'import.
fake_pkg = types.ModuleType("presidio_analyzer")
fake_pkg.AnalyzerEngine = object
fake_nlp = types.ModuleType("presidio_analyzer.nlp_engine")
fake_nlp.NlpEngineProvider = object
fake_nlp.NerModelConfiguration = object
fake_pkg.nlp_engine = fake_nlp
sys.modules["presidio_analyzer"] = fake_pkg
sys.modules["presidio_analyzer.nlp_engine"] = fake_nlp

sys.path.insert(0, str(Path(__file__).parent.parent))

from pii_detector.infrastructure.detector import presidio_detector  # noqa: E402,F401  (installe le filtre)

buf = io.StringIO()
handler = logging.StreamHandler(buf)
presidio_logger = logging.getLogger("presidio-analyzer")
presidio_logger.addHandler(handler)
presidio_logger.setLevel(logging.WARNING)
presidio_logger.propagate = False

presidio_logger.warning(
    "Recognizer not added to registry because language is not supported by registry - "
    "CreditCardRecognizer supported languages: pl, registry supported languages: en, fr, de, es, it"
)
presidio_logger.warning("real warning that must remain visible")

output = buf.getvalue()
assert "Recognizer not added to registry" not in output, f"bruit non filtré :\n{output}"
assert "real warning that must remain visible" in output, f"warning légitime perdu :\n{output}"
print("OK : bruit filtré, warnings légitimes conservés")
