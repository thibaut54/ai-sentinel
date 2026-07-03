"""
Unit tests for ``MinistralDetector``.

Ministral-PII is a generative LLM extractor served behind an OpenAI-compatible
``/chat/completions`` endpoint. It returns a JSON array of ``{text, label}``
objects which the detector maps to canonical ``pii_type`` and locates back in the
source to recover character offsets. All tests here mock the HTTP client so no
remote endpoint is contacted.

Coverage focuses on:
- label -> canonical pii_type mapping
- global offset rebasing across multiple chunks
- source == DetectorSource.MINISTRAL and judge_status default (NOT_AUDITED)
- per-chunk HTTP failure does not crash detect_pii (fail-open partial)
- tolerant JSON-array parsing (markdown fences / surrounding prose)
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, patch

import httpx
import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.judge_status import JudgeStatus
from pii_detector.infrastructure.detector.ministral_detector import (
    MinistralDetector,
    _LabelResolver,
    _normalize_label,
)
from pii_detector.infrastructure.text_processing.semantic_chunker import (
    FallbackChunker,
    MinistralTokenChunker,
)


class _FakeEncoding:
    def __init__(self, offsets):
        self.offsets = offsets


class _WhitespaceFakeTokenizer:
    """Whitespace tokenizer with real char offsets (mimics tokenizers.Tokenizer)."""

    def encode(self, text, add_special_tokens=True):  # noqa: ARG002
        return _FakeEncoding([(m.start(), m.end()) for m in re.finditer(r"\S+", text)])


def _chat_response(pairs: List[Dict[str, str]], as_text: Optional[str] = None) -> MagicMock:
    """Build a fake httpx response whose body is an OpenAI chat completion.

    ``pairs`` is rendered as the JSON array content unless ``as_text`` is given
    (used to exercise the tolerant parser with fences / prose).
    """
    import json

    content = as_text if as_text is not None else json.dumps(pairs)
    response = MagicMock()
    response.raise_for_status = MagicMock()
    response.json = MagicMock(return_value={
        "choices": [{"message": {"content": content}}]
    })
    return response


def _build_detector(post_side_effect) -> MinistralDetector:
    """Wire a detector whose httpx client ``post`` is mocked.

    ``post_side_effect`` may be a single response, a list (one per chunk call),
    or a callable raising to simulate failures.
    """
    detector = MinistralDetector()
    client = MagicMock()
    client.post = MagicMock(side_effect=post_side_effect)
    detector._client = client
    return detector


def _configs(*pairs: tuple) -> Dict[str, Any]:
    """Build MINISTRAL pii_type_configs keyed by ``MINISTRAL:<type>``.

    Each ``pair`` is ``(pii_type, detector_label, threshold)``.
    """
    out: Dict[str, Any] = {}
    for pii_type, detector_label, threshold in pairs:
        out[f"MINISTRAL:{pii_type}"] = {
            "enabled": True,
            "threshold": threshold,
            "detector": "MINISTRAL",
            "detector_label": detector_label,
        }
    return out


class TestMinistralDetectorBasics:
    def test_Should_HaveEnvDrivenModelId_When_DefaultsApply(self):
        detector = MinistralDetector()
        assert detector.model_id == "ministral-3b-pii-preview@q8_0"

    def test_Should_ReturnEmptyList_When_TextIsEmpty(self):
        detector = _build_detector([_chat_response([])])
        assert detector.detect_pii("") == []

    def test_Should_ReturnEntitiesWithMinistralSource_When_LlmDetects(self):
        detector = _build_detector(
            [_chat_response([{"text": "john@acme.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("Contact: john@acme.com", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "EMAIL"
        assert entities[0].source is DetectorSource.MINISTRAL
        assert entities[0].start == len("Contact: ")
        assert entities[0].end == len("Contact: john@acme.com")


class TestLabelMapping:
    def test_Should_MapDetectorLabelToCanonicalPiiType_When_LabelMatchesConfig(self):
        detector = _build_detector(
            [_chat_response([{"text": "4111 1111 1111 1111", "label": "CREDITCARD"}])]
        )
        configs = _configs(("CREDIT_CARD", "CREDITCARD", 0.0))
        entities = detector.detect_pii("4111 1111 1111 1111", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "CREDIT_CARD"

    def test_Should_PassThroughUnknownLabels_When_LabelNotInConfig(self):
        # Ministral emits an open vocabulary: a label with no config row must NOT
        # be dropped — it passes through as its normalized UPPER_SNAKE form so the
        # detection survives to the DB/front (the gRPC type-config gate keeps
        # no-config types). This is the fix for the ~95% silent-drop bug.
        detector = _build_detector([_chat_response([
            {"text": "blue", "label": "Eye Color"},
            {"text": "john@acme.com", "label": "EMAIL"},
        ])])
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("blue john@acme.com", pii_type_configs=configs)
        assert {e.pii_type for e in entities} == {"EMAIL", "EYE_COLOR"}

    def test_Should_SkipPair_When_TextNotFoundInChunk(self):
        detector = _build_detector(
            [_chat_response([{"text": "hallucinated@nowhere.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        assert detector.detect_pii("no pii here", pii_type_configs=configs) == []

    def test_Should_MatchConfigCaseAndFormatInsensitively_When_LabelDiffersFromDetectorLabel(self):
        # The model emits "Ip Address"; the DB detector_label is "ip_address".
        # Normalized resolution must route it to the configured canonical type.
        detector = _build_detector(
            [_chat_response([{"text": "10.0.0.1", "label": "Ip Address"}])]
        )
        configs = _configs(("IP_ADDRESS", "ip_address", 0.0))
        entities = detector.detect_pii("host 10.0.0.1", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "IP_ADDRESS"

    def test_Should_NormalizeCamelCaseLabel_When_PassthroughUnknown(self):
        # An unmapped camelCase label passes through as UPPER_SNAKE (not
        # "TRACKINGNUMBER"), so the Java enum can still resolve a FR label.
        detector = _build_detector(
            [_chat_response([{"text": "TRK-42", "label": "TrackingNumber"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("ref TRK-42", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "TRACKING_NUMBER"


class TestOffsetRebasingAcrossChunks:
    def test_Should_RebaseOffsetsToGlobalCoordinates_When_TwoChunks(self):
        # chunk_size=1 token * chars_per_token=4 -> 4-char windows, overlap 0,
        # so the text is split into multiple non-overlapping chunks. Each chunk
        # call returns its own entity; offsets must be rebased to GLOBAL coords.
        first = "AAA "          # chunk 0 -> [0:?]
        text = "AAA name BB id99"
        detector = MinistralDetector()
        # Force the char-ratio FallbackChunker so the 8-char windows are
        # deterministic regardless of whether the real tokenizer is cached.
        detector._get_tokenizer = lambda: None
        client = MagicMock()

        def fake_post(url, json=None):
            chunk_text = json["messages"][1]["content"]
            if "name" in chunk_text:
                return _chat_response([{"text": "name", "label": "PERSON"}])
            if "id99" in chunk_text:
                return _chat_response([{"text": "id99", "label": "ID"}])
            return _chat_response([])

        client.post = MagicMock(side_effect=fake_post)
        detector._client = client
        configs = _configs(("PERSON", "PERSON", 0.0), ("ID", "ID", 0.0))

        entities = detector.detect_pii(
            text, pii_type_configs=configs, chunk_size=2, overlap=0
        )
        by_type = {e.pii_type: e for e in entities}
        assert "PERSON" in by_type and "ID" in by_type
        # Global offsets must match the actual positions in the FULL text.
        assert text[by_type["PERSON"].start: by_type["PERSON"].end] == "name"
        assert text[by_type["ID"].start: by_type["ID"].end] == "id99"
        assert by_type["ID"].start == text.index("id99")


class TestJudgeStatusDefault:
    def test_Should_NotSetJudgeStatus_So_DefaultsToNotAudited(self):
        detector = _build_detector(
            [_chat_response([{"text": "john@acme.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("john@acme.com", pii_type_configs=configs)
        assert len(entities) == 1
        # The detector must NOT attach judge_status (mirrors openmed/gliner2);
        # it defaults to NOT_AUDITED in the gRPC response builder.
        assert entities[0].get("judge_status", JudgeStatus.NOT_AUDITED) is JudgeStatus.NOT_AUDITED


class TestFailOpenPerChunk:
    def test_Should_NotCrash_When_OneChunkHttpFails(self):
        text = "AAA name BB id99"

        def fake_post(url, json=None):
            chunk_text = json["messages"][1]["content"]
            if "name" in chunk_text:
                raise httpx.ConnectError("endpoint unreachable")
            if "id99" in chunk_text:
                return _chat_response([{"text": "id99", "label": "ID"}])
            return _chat_response([])

        detector = MinistralDetector()
        # Force the char-ratio FallbackChunker for deterministic 8-char windows.
        detector._get_tokenizer = lambda: None
        client = MagicMock()
        client.post = MagicMock(side_effect=fake_post)
        detector._client = client
        configs = _configs(("PERSON", "PERSON", 0.0), ("ID", "ID", 0.0))

        # The failed chunk is skipped; the healthy chunk still yields its entity.
        entities = detector.detect_pii(
            text, pii_type_configs=configs, chunk_size=2, overlap=0
        )
        assert {e.pii_type for e in entities} == {"ID"}


class TestTolerantJsonParsing:
    def test_Should_ParseArray_When_WrappedInMarkdownFenceAndProse(self):
        fenced = (
            "Here are the entities I found:\n"
            "```json\n"
            '[{"text": "john@acme.com", "label": "EMAIL"}]\n'
            "```\n"
            "Hope this helps."
        )
        detector = _build_detector([_chat_response([], as_text=fenced)])
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("john@acme.com", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "EMAIL"

    def test_Should_ReturnEmpty_When_NoJsonArrayPresent(self):
        detector = _build_detector(
            [_chat_response([], as_text="I could not find any PII.")]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        assert detector.detect_pii("john@acme.com", pii_type_configs=configs) == []


class TestFixedConfidence:
    def test_Should_NotSuppressEntity_When_PerTypeThresholdBelowMaxConfidence(self):
        # Ministral emits a FIXED max confidence (1.0): a generative extractor
        # asserts each span IS PII, with no probabilistic score. Per-type
        # thresholds therefore never suppress its findings (disable the type
        # instead). This is the fix for the secondary drop bug where the score
        # equalled the request threshold (0.30 < the seed's 0.50 per-type).
        detector = _build_detector(
            [_chat_response([{"text": "john@acme.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.9))
        entities = detector.detect_pii(
            "john@acme.com", threshold=0.3, pii_type_configs=configs
        )
        assert len(entities) == 1
        assert entities[0].pii_type == "EMAIL"
        assert entities[0].score == 1.0

    def test_Should_AssignMaxConfidence_When_LowRequestThreshold(self):
        # Regression guard: a low request threshold (the runtime default is 0.30)
        # must NOT lower the entity score and get it filtered downstream.
        detector = _build_detector(
            [_chat_response([{"text": "10.0.0.1", "label": "Ip Address"}])]
        )
        configs = _configs(("IP_ADDRESS", "ip_address", 0.5))
        entities = detector.detect_pii(
            "host 10.0.0.1", threshold=0.3, pii_type_configs=configs
        )
        assert len(entities) == 1
        assert entities[0].score == 1.0


class TestChunkerSelection:
    """The detector chunks with real tokens when the tokenizer loads, and
    degrades to the char-ratio FallbackChunker otherwise."""

    def test_Should_UseTokenChunker_When_TokenizerAvailable(self):
        detector = MinistralDetector()
        detector._get_tokenizer = lambda: _WhitespaceFakeTokenizer()
        chunker = detector._build_chunker(chunk_size=2048, overlap=410)
        assert isinstance(chunker, MinistralTokenChunker)

    def test_Should_UseFallbackChunker_When_TokenizerUnavailable(self):
        detector = MinistralDetector()
        detector._get_tokenizer = lambda: None
        chunker = detector._build_chunker(chunk_size=2048, overlap=410)
        assert isinstance(chunker, FallbackChunker)


class TestTokenChunkerOffsetRebasing:
    """End-to-end: token-based chunks must still rebase entity offsets to
    correct GLOBAL coordinates (the whole point of the token chunker)."""

    def test_Should_RebaseOffsetsToGlobal_When_TokenChunked(self):
        # 8 whitespace tokens; chunk_size=3 tokens, overlap=0 -> 3 token windows.
        text = "alpha PERSON_Jean gamma delta IBAN_CH99 zeta eta theta"
        detector = MinistralDetector()
        detector._get_tokenizer = lambda: _WhitespaceFakeTokenizer()
        client = MagicMock()

        def fake_post(url, json=None):
            chunk_text = json["messages"][1]["content"]
            pairs = []
            if "PERSON_Jean" in chunk_text:
                pairs.append({"text": "PERSON_Jean", "label": "PERSON"})
            if "IBAN_CH99" in chunk_text:
                pairs.append({"text": "IBAN_CH99", "label": "IBAN"})
            return _chat_response(pairs)

        client.post = MagicMock(side_effect=fake_post)
        detector._client = client
        configs = _configs(("PERSON", "PERSON", 0.0), ("IBAN", "IBAN", 0.0))

        entities = detector.detect_pii(
            text, pii_type_configs=configs, chunk_size=3, overlap=0
        )
        by_type = {e.pii_type: e for e in entities}
        assert "PERSON" in by_type and "IBAN" in by_type
        # Global offsets must index back to the exact spans in the FULL text.
        assert text[by_type["PERSON"].start: by_type["PERSON"].end] == "PERSON_Jean"
        assert text[by_type["IBAN"].start: by_type["IBAN"].end] == "IBAN_CH99"
        assert by_type["IBAN"].start == text.index("IBAN_CH99")


class TestTokenizerLoading:
    """The tokenizer is loaded lazily and any failure degrades gracefully."""

    def test_Should_ReturnNone_When_TokenizersLoadFails(self):
        detector = MinistralDetector()
        # Simulate an unavailable/offline tokenizer: the loader must swallow the
        # error and return None so the detector falls back instead of crashing.
        with patch.object(
            MinistralDetector, "_load_tokenizer",
            staticmethod(lambda repo: (_ for _ in ()).throw(RuntimeError("offline"))),
        ):
            assert detector._get_tokenizer() is None


# Snapshot of the DB ``detector_label`` vocabulary seeded for MINISTRAL
# (data.sql, detector='MINISTRAL'). pii_type == UPPER(detector_label).
_MINISTRAL_DB_DETECTOR_LABELS = [
    "account_number", "age", "api_key", "bank_routing_number", "biometric_identifier",
    "blood_type", "building_number", "certificate_license_number", "city", "company_name",
    "coordinate", "country", "county", "credit_debit_card", "customer_id", "cvv", "date",
    "date_of_birth", "date_time", "device_identifier", "driver_license_number",
    "education_level", "email", "employee_id", "employment_status", "ethnicity",
    "fax_number", "first_name", "gender", "health_plan_beneficiary_number", "http_cookie",
    "iban", "ip_address", "language", "last_name", "license_plate", "mac_address",
    "marital_status", "medical_record_number", "national_id", "nationality", "occupation",
    "organization", "password", "phone_number", "pin", "political_view", "postcode",
    "race", "race_ethnicity", "religion", "religious_belief", "salary", "sexuality",
    "social_security_number", "ssn", "state", "street_address", "swift_bic", "tax_id",
    "time", "title", "unique_id", "url", "user_name", "vehicle_identifier", "zip_code",
]

# The 115 distinct labels Ministral emitted in the 2026-06-28 LM Studio scan
# (server-logs/2026-06/llm_labels.txt). This is the ground truth of the goal:
# every one of these must resolve to a non-empty pii_type (nothing dropped).
_LOGGED_MINISTRAL_LABELS = [
    "Account Id", "Account Number", "Asset Tag", "AssetTag", "Bank Account", "Base64",
    "Branch", "Build Identifier", "Build Number", "CVV", "Card Number", "Catalogue Code",
    "Chassis Reference", "Container Number", "Correlation GUID", "CorrelationId", "Custom",
    "Customer Number", "Database Identifier", "Date", "Document Reference", "Employee Badge",
    "Employee Badge Number", "Expiry Date", "Fidelity Number", "Fingerprint", "Government ID",
    "Handle", "Hash", "IBAN", "IMEI", "Internal Reference", "Inventory Number", "Invoice Number",
    "Ip Address", "Issue Tracker ID", "JIRA Issue ID", "License", "Location",
    "Logistics Reference", "MongoDB ObjectId", "National ID", "ObjectId", "Order Number",
    "Order Reference", "OrderNumber", "Organization", "Organization Name", "PagerDuty Schedule",
    "Passport Number", "Password", "Payment Card", "Phone", "Phone Number", "Postal Code",
    "Product Code", "PromoCode", "RecoveryCode", "Routing Number", "S3 Bucket", "SHA", "SKU",
    "SSN", "Sensitive Account Id", "Serial Number", "SessionId", "Shipment Reference",
    "Shipping Code", "Social Security Number", "Tax ID", "Ticket Reference", "Token", "TraceId",
    "Tracking Number", "TrackingNumber", "Transaction ID", "URL", "Username", "VAT", "Version",
    "ZIP+4", "_id", "access_token", "api_key", "artifact", "asset_tag", "bill_of_lading",
    "build_number", "contact", "driver_license", "firmware_version", "geographic", "iban",
    "license", "license_number", "location", "order_reference", "organization",
    "passport_number", "password", "phone", "postal_code_order_id", "reisenummer", "secret",
    "serial_number", "siret", "sku", "social_media", "software_license_key", "tax_id", "url",
    "username", "uuid", "vat", "version",
]


def _ministral_resolver() -> _LabelResolver:
    return _LabelResolver.from_mapping(
        {label: label.upper() for label in _MINISTRAL_DB_DETECTOR_LABELS}
    )


class TestNormalizeLabel:
    @pytest.mark.parametrize("raw,expected", [
        ("Ip Address", "ip_address"),
        ("TrackingNumber", "tracking_number"),
        ("RecoveryCode", "recovery_code"),
        ("SessionId", "session_id"),
        ("ZIP+4", "zip_4"),
        ("IBAN", "iban"),
        ("ipv4", "ipv4"),      # digit boundary NOT split -> trained label preserved
        ("IPv4", "ipv4"),
        ("_id", "id"),
        ("Social Security Number", "social_security_number"),
    ])
    def test_Should_NormalizeToCanonicalSnakeCase(self, raw, expected):
        assert _normalize_label(raw) == expected


class TestLabelResolver:
    def test_Should_ResolveExactDetectorLabel_When_LabelMatchesVerbatim(self):
        resolver = _LabelResolver.from_mapping({"CREDITCARD": "CREDIT_CARD"})
        assert resolver.resolve("CREDITCARD") == "CREDIT_CARD"

    def test_Should_ResolveNormalized_When_LabelDiffersInCaseOrFormat(self):
        resolver = _ministral_resolver()
        assert resolver.resolve("Ip Address") == "IP_ADDRESS"
        assert resolver.resolve("Phone Number") == "PHONE_NUMBER"
        assert resolver.resolve("Social Security Number") == "SOCIAL_SECURITY_NUMBER"

    def test_Should_PassThroughAsUpperSnake_When_LabelUnknown(self):
        resolver = _ministral_resolver()
        assert resolver.resolve("TrackingNumber") == "TRACKING_NUMBER"
        assert resolver.resolve("Passport Number") == "PASSPORT_NUMBER"
        assert resolver.resolve("S3 Bucket") == "S3_BUCKET"

    def test_Should_ReturnNone_When_LabelIsEmptyOrPunctuationOnly(self):
        resolver = _ministral_resolver()
        assert resolver.resolve("") is None
        assert resolver.resolve("!!!") is None

    def test_Should_ResolveEveryLoggedLabel_When_MinistralEmitsOpenVocabulary(self):
        # Goal guard-rail: none of the 115 labels from the 2026-06-28 scan may be
        # dropped. Every one must resolve to a non-empty canonical pii_type.
        resolver = _ministral_resolver()
        dropped = [label for label in _LOGGED_MINISTRAL_LABELS
                   if not resolver.resolve(label)]
        assert dropped == [], f"{len(dropped)} logged label(s) dropped: {dropped}"
