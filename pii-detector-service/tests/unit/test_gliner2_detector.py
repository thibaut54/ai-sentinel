"""Unit tests for ``Gliner2Detector`` parsing / offsets / chunking / mapping.

The GLiNER2 model itself is NOT loaded here (heavy, network). We drive the
detector with a fake model whose ``extract`` returns the exact raw shape the POC
L0 captured on ``fastino/gliner2-large-v1``:

    [OrderedDict({label: [{"text","confidence","start","end"}, ...], ...})]

and assert: offset fidelity (``text[start:end] == entity``), chunk rebasing,
overlap dedup, label->pii_type mapping, per-type threshold filtering, schema
build from {label: description} (with NULL-description fallback to label), and
source=GLINER2 on every emitted entity.
"""
from __future__ import annotations

import logging
from collections import OrderedDict
from unittest.mock import MagicMock

import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.infrastructure.detector.gliner2_detector import Gliner2Detector


# ----------------------------------------------------------------------
# Fakes
# ----------------------------------------------------------------------

class _FakeSchema:
    def __init__(self):
        self.entities_arg = None

    def entities(self, schema_dict):
        self.entities_arg = schema_dict
        return self


class _FakeChunkResult:
    def __init__(self, text: str, start: int):
        self.text = text
        self.start = start
        self.token_count = len(text.split())


def _make_detector(extract_returns, chunks, runtime="pytorch"):
    """Build a Gliner2Detector with a fake model + fake chunker (no model load)."""
    det = Gliner2Detector.__new__(Gliner2Detector)
    det.logger = logging.getLogger("test.gliner2")
    det.threshold = 0.5
    det.runtime = runtime

    fake_model = MagicMock()
    fake_model.create_schema.return_value = _FakeSchema()
    if runtime == "fastgliner":
        # predict_entities() is called once per chunk -> side_effect list
        fake_model.predict_entities.side_effect = extract_returns
    else:
        # extract() is called once per chunk -> side_effect list
        fake_model.extract.side_effect = extract_returns
    det.model = fake_model

    fake_chunker = MagicMock()
    fake_chunker.chunk_text.return_value = chunks
    det.chunker = fake_chunker
    return det


# ----------------------------------------------------------------------
# Schema build
# ----------------------------------------------------------------------

class TestSchemaBuild:
    def test_Should_BuildSchemaFromLabelDescription_When_DescriptionPresent(self):
        configs = {
            "GLINER2:EMAIL": {
                "enabled": True, "detector": "GLINER2", "threshold": 0.5,
                "detector_label": "email", "detector_description": "adresse e-mail",
            }
        }
        det = Gliner2Detector.__new__(Gliner2Detector)
        det.logger = logging.getLogger("t")
        schema_dict, mapping, scoring = det._resolve_runtime_config(configs)
        assert schema_dict == {"email": "adresse e-mail"}
        assert mapping == {"email": "EMAIL"}
        assert scoring == {"EMAIL": 0.5}

    def test_Should_FallbackToLabel_When_DescriptionNullOrEmpty(self):
        configs = {
            "GLINER2:IBAN": {
                "enabled": True, "detector": "GLINER2", "threshold": 0.6,
                "detector_label": "iban", "detector_description": None,
            }
        }
        det = Gliner2Detector.__new__(Gliner2Detector)
        det.logger = logging.getLogger("t")
        schema_dict, _mapping, _scoring = det._resolve_runtime_config(configs)
        # NULL description -> fall back to the label itself (spec §4.3)
        assert schema_dict == {"iban": "iban"}

    def test_Should_IgnoreDisabledAndOtherDetectors_When_BuildingSchema(self):
        configs = {
            "GLINER2:EMAIL": {"enabled": False, "detector": "GLINER2",
                              "detector_label": "email", "threshold": 0.5},
            "GLINER:EMAIL": {"enabled": True, "detector": "GLINER",
                             "detector_label": "email", "threshold": 0.8},
        }
        det = Gliner2Detector.__new__(Gliner2Detector)
        det.logger = logging.getLogger("t")
        schema_dict, mapping, _scoring = det._resolve_runtime_config(configs)
        assert schema_dict == {}  # disabled GLINER2 + GLINER row ignored
        assert mapping == {}


# ----------------------------------------------------------------------
# Offsets / mapping / source
# ----------------------------------------------------------------------

class TestDetectOffsets:
    def test_Should_ProduceAccurateOffsetsAndSource_When_SingleChunk(self):
        text = "Bonjour, je m'appelle Jean Dupont et mon email est jean.dupont@example.ch."
        raw = [OrderedDict({
            "person_name": [{"text": "Jean Dupont", "confidence": 1.0, "start": 22, "end": 33}],
            "email": [{"text": "jean.dupont@example.ch", "confidence": 0.99, "start": 51, "end": 73}],
            "phone_number": [],
        })]
        det = _make_detector([raw], [_FakeChunkResult(text, 0)])
        configs = {
            "GLINER2:PERSON_NAME": {"enabled": True, "detector": "GLINER2",
                                    "detector_label": "person_name", "threshold": 0.5},
            "GLINER2:EMAIL": {"enabled": True, "detector": "GLINER2",
                              "detector_label": "email", "threshold": 0.5},
        }
        entities = det.detect_pii(text, threshold=0.3, pii_type_configs=configs)

        assert len(entities) == 2
        for entity in entities:
            assert entity.source is DetectorSource.GLINER2
            assert text[entity.start:entity.end] == entity.text
        by_type = {e.pii_type: e for e in entities}
        assert by_type["PERSON_NAME"].text == "Jean Dupont"
        assert by_type["EMAIL"].text == "jean.dupont@example.ch"

    def test_Should_DropUnmappedLabels_When_LabelNotInConfig(self):
        text = "secret token xyz"
        raw = [OrderedDict({"unknown_label": [{"text": "xyz", "confidence": 0.9, "start": 13, "end": 16}]})]
        det = _make_detector([raw], [_FakeChunkResult(text, 0)])
        configs = {"GLINER2:EMAIL": {"enabled": True, "detector": "GLINER2",
                                     "detector_label": "email", "threshold": 0.5}}
        entities = det.detect_pii(text, pii_type_configs=configs)
        assert entities == []

    def test_Should_ParseEntities_When_RawWrappedInEntitiesEnvelope(self):
        # Real gliner2 (>=1.3.1) shape: {"entities": [OrderedDict({label: [...]})]}.
        # The POC L0 capture had NO envelope (a bare list), so production silently
        # returned 0 detections against the installed library — this guards the regression.
        text = "Bonjour, je m'appelle Jean Dupont et mon email est jean.dupont@example.ch."
        raw = {"entities": [OrderedDict({
            "person_name": [{"text": "Jean Dupont", "confidence": 1.0, "start": 22, "end": 33}],
            "email": [{"text": "jean.dupont@example.ch", "confidence": 0.99, "start": 51, "end": 73}],
        })]}
        det = _make_detector([raw], [_FakeChunkResult(text, 0)])
        configs = {
            "GLINER2:PERSON_NAME": {"enabled": True, "detector": "GLINER2",
                                    "detector_label": "person_name", "threshold": 0.5},
            "GLINER2:EMAIL": {"enabled": True, "detector": "GLINER2",
                              "detector_label": "email", "threshold": 0.5},
        }
        entities = det.detect_pii(text, threshold=0.3, pii_type_configs=configs)
        by_type = {e.pii_type: e for e in entities}
        assert set(by_type) == {"PERSON_NAME", "EMAIL"}
        assert by_type["EMAIL"].text == "jean.dupont@example.ch"
        assert text[by_type["EMAIL"].start:by_type["EMAIL"].end] == "jean.dupont@example.ch"


# ----------------------------------------------------------------------
# Chunking + overlap dedup
# ----------------------------------------------------------------------

class TestChunkingRecombination:
    def test_Should_RebaseOffsetsAndDedup_When_MultipleOverlappingChunks(self):
        # Original text; two chunks overlap so the same IBAN is emitted twice.
        iban = "CH9300762011623852957"  # 21 chars
        text = "A" * 100 + iban + "B" * 100
        iban_start_global = 100
        iban_end_global = iban_start_global + len(iban)  # 121
        # Chunk 0 starts at 0, sees IBAN at local 100..121.
        chunk0 = _FakeChunkResult(text[:130], 0)
        # Chunk 1 starts at 80 (overlap), sees IBAN at local 20..41.
        chunk1 = _FakeChunkResult(text[80:], 80)
        raw0 = [OrderedDict({"iban": [{"text": iban,
                                       "confidence": 0.95, "start": 100, "end": 121}]})]
        raw1 = [OrderedDict({"iban": [{"text": iban,
                                       "confidence": 0.95, "start": 20, "end": 41}]})]
        det = _make_detector([raw0, raw1], [chunk0, chunk1])
        configs = {"GLINER2:IBAN": {"enabled": True, "detector": "GLINER2",
                                    "detector_label": "iban", "threshold": 0.5}}
        entities = det.detect_pii(text, pii_type_configs=configs)

        # Both chunks map to the SAME global span -> deduplicated to one entity.
        assert len(entities) == 1
        assert entities[0].start == iban_start_global
        assert entities[0].end == iban_end_global
        assert text[entities[0].start:entities[0].end] == iban


# ----------------------------------------------------------------------
# fast_gliner runtime (flat span dicts from predict_entities)
# ----------------------------------------------------------------------

class TestFastglinerRuntime:
    CONFIGS = {
        "GLINER2:PERSON_NAME": {"enabled": True, "detector": "GLINER2",
                                "detector_label": "person_name", "threshold": 0.5},
        "GLINER2:EMAIL": {"enabled": True, "detector": "GLINER2",
                          "detector_label": "email", "threshold": 0.5},
    }

    def test_Should_MapFlatSpans_When_FastglinerRuntime(self):
        text = "Bonjour, je m'appelle Jean Dupont et mon email est jean.dupont@example.ch."
        raw = [
            {"text": "Jean Dupont", "label": "person_name", "score": 0.99,
             "start": 22, "end": 33},
            {"text": "jean.dupont@example.ch", "label": "email", "score": 0.97,
             "start": 51, "end": 73},
        ]
        det = _make_detector([raw], [_FakeChunkResult(text, 0)], runtime="fastgliner")
        entities = det.detect_pii(text, threshold=0.5, pii_type_configs=self.CONFIGS)

        assert len(entities) == 2
        for entity in entities:
            assert entity.source is DetectorSource.GLINER2
            assert text[entity.start:entity.end] == entity.text
        by_type = {e.pii_type: e for e in entities}
        assert by_type["PERSON_NAME"].text == "Jean Dupont"
        assert by_type["EMAIL"].text == "jean.dupont@example.ch"
        # fast_gliner gets a plain label list, never the {label: desc} dict.
        called_with = det.model.predict_entities.call_args[0][1]
        assert called_with == ["person_name", "email"]

    def test_Should_FilterByEffectiveThreshold_When_FastglinerRuntime(self):
        # gline-rs pre-filters at 0.5 internally; the detector re-filters for
        # effective thresholds ABOVE that floor.
        text = "Marie Curie"
        raw = [{"text": "Marie Curie", "label": "person_name", "score": 0.60,
                "start": 0, "end": 11}]
        det = _make_detector([raw], [_FakeChunkResult(text, 0)], runtime="fastgliner")
        entities = det.detect_pii(text, threshold=0.70, pii_type_configs=self.CONFIGS)
        assert entities == []  # 0.60 < 0.70 effective threshold

    def test_Should_RebaseAndDedup_When_FastglinerMultiChunk(self):
        iban = "CH9300762011623852957"
        text = "A" * 100 + iban + "B" * 100
        chunk0 = _FakeChunkResult(text[:130], 0)
        chunk1 = _FakeChunkResult(text[80:], 80)
        raw0 = [{"text": iban, "label": "iban", "score": 0.95, "start": 100, "end": 121}]
        raw1 = [{"text": iban, "label": "iban", "score": 0.95, "start": 20, "end": 41}]
        det = _make_detector([raw0, raw1], [chunk0, chunk1], runtime="fastgliner")
        configs = {"GLINER2:IBAN": {"enabled": True, "detector": "GLINER2",
                                    "detector_label": "iban", "threshold": 0.5}}
        entities = det.detect_pii(text, pii_type_configs=configs)

        assert len(entities) == 1
        assert entities[0].start == 100
        assert text[entities[0].start:entities[0].end] == iban


# ----------------------------------------------------------------------
# Per-type threshold filtering + empty config
# ----------------------------------------------------------------------

class TestThresholdFiltering:
    def test_Should_DropBelowPerTypeThreshold_When_ScoreTooLow(self):
        text = "Marie Curie"
        raw = [OrderedDict({"person_name": [{"text": "Marie Curie", "confidence": 0.40,
                                             "start": 0, "end": 11}]})]
        det = _make_detector([raw], [_FakeChunkResult(text, 0)])
        configs = {"GLINER2:PERSON_NAME": {"enabled": True, "detector": "GLINER2",
                                           "detector_label": "person_name", "threshold": 0.80}}
        entities = det.detect_pii(text, threshold=0.3, pii_type_configs=configs)
        assert entities == []  # 0.40 < 0.80 per-type threshold

    def test_Should_ReturnEmpty_When_NoEnabledConfigs(self):
        det = _make_detector([[OrderedDict({})]], [_FakeChunkResult("x", 0)])
        entities = det.detect_pii("x", pii_type_configs={})
        assert entities == []


# ----------------------------------------------------------------------
# Masking (pure) + empty text
# ----------------------------------------------------------------------

class TestMaskingAndEdgeCases:
    def test_Should_ReturnEmpty_When_TextIsEmpty(self):
        det = Gliner2Detector.__new__(Gliner2Detector)
        det.logger = logging.getLogger("t")
        det.model = MagicMock()
        assert det.detect_pii("") == []

    def test_Should_MaskEntities_When_ApplyMasksCalled(self):
        from pii_detector.domain.entity.pii_entity import PIIEntity
        text = "Email jean@x.ch end"
        ent = PIIEntity(text="jean@x.ch", pii_type="EMAIL", type_label="EMAIL",
                        start=6, end=15, score=0.9, source=DetectorSource.GLINER2)
        masked = Gliner2Detector._apply_masks(text, [ent])
        assert masked == "Email [EMAIL] end"
