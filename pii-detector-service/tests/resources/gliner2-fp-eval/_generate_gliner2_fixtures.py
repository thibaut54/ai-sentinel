"""Generate the GLiNER2 FP/FN evaluation fixtures (24 PII types).

Companion of ``openmed-fp-eval/_generate_fixtures.py`` but for the subset of
``fastino/gliner2-privacy-filter-PII-multi`` native labels we evaluate against
``fastino/gliner2-large-v1`` (and the PII fine-tune) in
``tests/integration/test_gliner2_realistic_fp_evaluation_with_judge.py``.

Each ``build_*`` function returns the payload for one type as a dict, with
offsets computed via :func:`_fixture_helpers._span` so they stay byte-exact
even when the prompt body is edited. Builders are grouped by family in the
``_fixtures_*.py`` modules; this script merges them, self-checks every
expected span, and writes one JSON file per PII type next to itself.

Run with::

    python tests/resources/gliner2-fp-eval/_generate_gliner2_fixtures.py

The 24 types and their GLiNER2 native ``detector_label`` (the label string
fed to ``GLiNER2.extract_entities``) — grouped into the 4 families from the
privacy-filter label set:

    Government / tax IDs
        GOVERNMENT_ID         -> government_id
        NATIONAL_ID           -> national_id_number
        PASSPORT              -> passport_number
        DRIVER_LICENSE        -> drivers_license_number
        LICENSE_NUMBER        -> license_number
        TAX_ID                -> tax_id
        TAX_NUMBER            -> tax_number
    Banking / payment
        BANK_ACCOUNT          -> bank_account
        ACCOUNT_NUMBER        -> account_number
        ROUTING_NUMBER        -> routing_number
        IBAN                  -> iban
        PAYMENT_CARD          -> payment_card
        CARD_NUMBER           -> card_number
        CARD_EXPIRY           -> card_expiry
        CARD_CVV              -> card_cvv
    Digital identity
        USERNAME              -> username
        IP_ADDRESS            -> ip_address
        ACCOUNT_ID            -> account_id
        SENSITIVE_ACCOUNT_ID  -> sensitive_account_id
    Secrets / credentials
        PASSWORD              -> password
        SECRET                -> secret
        API_KEY               -> api_key
        ACCESS_TOKEN          -> access_token
        RECOVERY_CODE         -> recovery_code
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable, Dict

# Per-family builder registries. Each module exposes a ``BUILDERS`` dict
# mapping the canonical PII type -> a zero-arg builder returning the payload.
from _fixtures_government import BUILDERS as GOVERNMENT_BUILDERS
from _fixtures_banking import BUILDERS as BANKING_BUILDERS
from _fixtures_identity import BUILDERS as IDENTITY_BUILDERS
from _fixtures_secrets import BUILDERS as SECRETS_BUILDERS


# Single source of truth for the canonical type -> GLiNER2 label mapping and
# the per-type threshold baked into each fixture file. Builders MUST set the
# same ``pii_type`` / ``detector_label`` / ``threshold`` in their payload; the
# self-check below enforces consistency.
GLINER2_TYPES: Dict[str, Dict[str, Any]] = {
    # Government / tax IDs
    "GOVERNMENT_ID":        {"label": "government_id",          "threshold": 0.50},
    "NATIONAL_ID":          {"label": "national_id_number",     "threshold": 0.50},
    "PASSPORT":             {"label": "passport_number",        "threshold": 0.50},
    "DRIVER_LICENSE":       {"label": "drivers_license_number", "threshold": 0.50},
    "LICENSE_NUMBER":       {"label": "license_number",         "threshold": 0.50},
    "TAX_ID":               {"label": "tax_id",                 "threshold": 0.50},
    "TAX_NUMBER":           {"label": "tax_number",             "threshold": 0.50},
    # Banking / payment
    "BANK_ACCOUNT":         {"label": "bank_account",           "threshold": 0.50},
    "ACCOUNT_NUMBER":       {"label": "account_number",         "threshold": 0.50},
    "ROUTING_NUMBER":       {"label": "routing_number",         "threshold": 0.50},
    "IBAN":                 {"label": "iban",                   "threshold": 0.50},
    "PAYMENT_CARD":         {"label": "payment_card",           "threshold": 0.50},
    "CARD_NUMBER":          {"label": "card_number",            "threshold": 0.50},
    "CARD_EXPIRY":          {"label": "card_expiry",            "threshold": 0.50},
    "CARD_CVV":             {"label": "card_cvv",               "threshold": 0.50},
    # Digital identity
    "USERNAME":             {"label": "username",               "threshold": 0.50},
    "IP_ADDRESS":           {"label": "ip_address",             "threshold": 0.50},
    "ACCOUNT_ID":           {"label": "account_id",             "threshold": 0.50},
    "SENSITIVE_ACCOUNT_ID": {"label": "sensitive_account_id",   "threshold": 0.50},
    # Secrets / credentials
    "PASSWORD":             {"label": "password",               "threshold": 0.50},
    "SECRET":               {"label": "secret",                 "threshold": 0.50},
    "API_KEY":              {"label": "api_key",                "threshold": 0.50},
    "ACCESS_TOKEN":         {"label": "access_token",           "threshold": 0.50},
    "RECOVERY_CODE":        {"label": "recovery_code",          "threshold": 0.50},
}


def _all_builders() -> Dict[str, Callable[[], Dict[str, Any]]]:
    merged: Dict[str, Callable[[], Dict[str, Any]]] = {}
    for family in (
        GOVERNMENT_BUILDERS,
        BANKING_BUILDERS,
        IDENTITY_BUILDERS,
        SECRETS_BUILDERS,
    ):
        overlap = merged.keys() & family.keys()
        if overlap:
            raise AssertionError(f"Duplicate builder(s) across families: {overlap}")
        merged.update(family)
    return merged


def main() -> None:
    here = Path(__file__).resolve().parent
    builders = _all_builders()

    missing = GLINER2_TYPES.keys() - builders.keys()
    extra = builders.keys() - GLINER2_TYPES.keys()
    if missing or extra:
        raise AssertionError(
            f"Builder/registry mismatch — missing builders: {sorted(missing)}; "
            f"unregistered builders: {sorted(extra)}"
        )

    for pii_type, builder in builders.items():
        payload = builder()
        spec = GLINER2_TYPES[pii_type]

        # Registry consistency: builder payload must match the canonical map.
        assert payload["pii_type"] == pii_type, (
            f"{pii_type}: payload declares pii_type={payload['pii_type']!r}"
        )
        assert payload["detector_label"] == spec["label"], (
            f"{pii_type}: payload detector_label={payload['detector_label']!r} "
            f"!= registry label {spec['label']!r}"
        )

        # Byte-exact self-check of every expected span before writing.
        for case in payload["cases"]:
            for span in case["expected_spans"]:
                slice_ = case["text"][span["start"]:span["end"]]
                assert slice_ == span["value"], (
                    f"Self-check failed for {case['id']}: "
                    f"text[{span['start']}:{span['end']}]={slice_!r} "
                    f"but span.value={span['value']!r}"
                )

        out_path = here / f"{pii_type}.json"
        with out_path.open("w", encoding="utf-8") as fp:
            json.dump(payload, fp, indent=2, ensure_ascii=False)
        print(f"wrote {out_path.name} — {len(payload['cases'])} cases")


if __name__ == "__main__":
    main()
