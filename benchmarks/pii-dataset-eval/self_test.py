"""Offline self-test for the dataset conversion harness.

Runs WITHOUT network and WITHOUT the ``datasets`` package: it exercises the pure
normalization logic on two hand-crafted docs (one per dataset shape) plus the
detector concept map and the synthetic blind-spot fixtures.

Run: ``python self_test.py``  (also importable as a pytest module).
"""
from __future__ import annotations

import json

import build_datasets
import mapping
import synthetic_fixtures

# --- gretelai shape: pii_spans is a JSON *string* ---------------------------
# generated_text + a json.dumps'd list of {start,end,label}. iban -> IBAN (gold),
# swift_bic_code -> excluded (ignore), and an unknown label -> ignore too.
GRETELAI_TEXT = "Wire to IBAN GB29NWBK60161331926819 at SWIFT NWBKGB2L for client."
_GRETELAI_RAW = [
    {"start": GRETELAI_TEXT.index("GB29NWBK60161331926819"),
     "end": GRETELAI_TEXT.index("GB29NWBK60161331926819") + len("GB29NWBK60161331926819"),
     "label": "iban"},
    {"start": GRETELAI_TEXT.index("NWBKGB2L"),
     "end": GRETELAI_TEXT.index("NWBKGB2L") + len("NWBKGB2L"),
     "label": "swift_bic_code"},
    {"start": GRETELAI_TEXT.index("client"),
     "end": GRETELAI_TEXT.index("client") + len("client"),
     "label": "totally_unknown_label"},
]
GRETELAI_SPANS_JSON = json.dumps(_GRETELAI_RAW)

# --- ai4privacy shape: privacy_mask is a native list of dicts ----------------
# source_text + [{value,start,end,label}]. SOCIALNUMBER -> NATIONAL_ID_NUMBER
# (gold), EMAIL -> excluded (ignore).
AI4PRIVACY_TEXT = "SSN 123-45-6789 and email john@acme.test on file."
AI4PRIVACY_SPANS = [
    {"value": "123-45-6789",
     "start": AI4PRIVACY_TEXT.index("123-45-6789"),
     "end": AI4PRIVACY_TEXT.index("123-45-6789") + len("123-45-6789"),
     "label": "SOCIALNUMBER"},
    {"value": "john@acme.test",
     "start": AI4PRIVACY_TEXT.index("john@acme.test"),
     "end": AI4PRIVACY_TEXT.index("john@acme.test") + len("john@acme.test"),
     "label": "EMAIL"},
]


def _check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def test_gretelai_json_string_mapping() -> None:
    maps = mapping.load_dataset_maps()
    excludes = mapping.load_dataset_excludes()
    raw = build_datasets._parse_spans(GRETELAI_SPANS_JSON, "json_string")
    gold, ignore, skipped = build_datasets.normalize_doc(
        GRETELAI_TEXT, raw, maps["gretelai"], excludes["gretelai"]
    )
    _check(skipped == 0, f"gretelai: unexpected skipped={skipped}")
    _check(len(gold) == 1, f"gretelai: expected 1 gold span, got {len(gold)}")
    _check(gold[0]["label"] == "IBAN", f"gretelai: expected IBAN, got {gold[0]['label']}")
    _check(GRETELAI_TEXT[gold[0]["start"]:gold[0]["end"]] == "GB29NWBK60161331926819",
           "gretelai: gold offsets do not slice the IBAN value")
    # swift_bic_code (excluded) + unknown label -> 2 ignore spans.
    _check(len(ignore) == 2, f"gretelai: expected 2 ignore spans, got {len(ignore)}")
    ignore_labels = {sp["src_label"] for sp in ignore}
    _check(ignore_labels == {"swift_bic_code", "totally_unknown_label"},
           f"gretelai: wrong ignore labels {ignore_labels}")


def test_ai4privacy_object_list_mapping() -> None:
    maps = mapping.load_dataset_maps()
    excludes = mapping.load_dataset_excludes()
    raw = build_datasets._parse_spans(AI4PRIVACY_SPANS, "object_list")
    gold, ignore, skipped = build_datasets.normalize_doc(
        AI4PRIVACY_TEXT, raw, maps["ai4privacy"], excludes["ai4privacy"]
    )
    _check(skipped == 0, f"ai4privacy: unexpected skipped={skipped}")
    _check(len(gold) == 1, f"ai4privacy: expected 1 gold span, got {len(gold)}")
    _check(gold[0]["label"] == "NATIONAL_ID_NUMBER",
           f"ai4privacy: expected NATIONAL_ID_NUMBER, got {gold[0]['label']}")
    _check(AI4PRIVACY_TEXT[gold[0]["start"]:gold[0]["end"]] == "123-45-6789",
           "ai4privacy: gold offsets do not slice the SSN value")
    _check(len(ignore) == 1 and ignore[0]["src_label"] == "EMAIL",
           f"ai4privacy: expected 1 EMAIL ignore span, got {ignore}")


def test_detector_concept_map_keys() -> None:
    detector_map = mapping.load_detector_concept_map()
    _check(set(detector_map.keys()) == {"GLINER2", "PRESIDIO", "REGEX", "OPENMED"},
           f"detector map keys wrong: {sorted(detector_map)}")
    _check(detector_map["GLINER2"]["IBAN"] == "IBAN", "GLINER2.IBAN should map to IBAN")
    _check(detector_map["PRESIDIO"]["IBAN_CODE"] == "IBAN", "PRESIDIO.IBAN_CODE should map to IBAN")
    _check(detector_map["REGEX"]["SOCIALNUM"] == "NATIONAL_ID_NUMBER",
           "REGEX.SOCIALNUM should map to NATIONAL_ID_NUMBER")
    _check(detector_map["OPENMED"]["CVV"] == "CARD_CVV", "OPENMED.CVV should map to CARD_CVV")


def test_extractor_concept_map() -> None:
    extractor_map = mapping.load_extractor_concept_map()
    _check("_default" in extractor_map, "extractor map must have a _default section")
    default = extractor_map["_default"]
    # in-scope mappings
    _check(default.get("iban") == "IBAN", "extractor iban -> IBAN")
    _check(default.get("ssn") == "NATIONAL_ID_NUMBER", "extractor ssn -> NATIONAL_ID_NUMBER")
    _check(default.get("credit_card") == "CARD_NUMBER", "extractor credit_card -> CARD_NUMBER")
    # out-of-scope -> IGNORE
    _check(default.get("email") == "IGNORE", "extractor email -> IGNORE")
    _check(default.get("first_name") == "IGNORE", "extractor first_name -> IGNORE")


def test_synthetic_fixtures_byte_exact() -> None:
    docs = synthetic_fixtures.build_docs()
    labels = {doc["spans"][0]["label"] for doc in docs}
    _check({"ACCESS_TOKEN", "RECOVERY_CODE", "CARD_EXPIRY", "SECRET"}.issubset(labels),
           f"synthetic fixtures missing blind-spot labels: {labels}")
    for doc in docs:
        for span in doc["spans"]:
            sliced = doc["text"][span["start"]:span["end"]]
            _check(bool(sliced), f"synthetic: empty slice in {doc['id']}")


def main() -> int:
    tests = [
        test_gretelai_json_string_mapping,
        test_ai4privacy_object_list_mapping,
        test_detector_concept_map_keys,
        test_extractor_concept_map,
        test_synthetic_fixtures_byte_exact,
    ]
    for test in tests:
        test()
        print(f"OK  {test.__name__}")
    print(f"\nAll {len(tests)} self-tests passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
