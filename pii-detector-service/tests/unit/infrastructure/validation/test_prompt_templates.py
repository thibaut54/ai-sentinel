"""
Tests for :mod:`pii_detector.infrastructure.validation.prompt_templates`.

Covers:
- :data:`SYSTEM_PROMPT` content (few-shots + strict JSON rules).
- :func:`build_user_prompt` context-window handling at boundaries.
- :class:`PiiVerdict` validation rules.
"""

from __future__ import annotations

from dataclasses import dataclass

import pytest
from pydantic import ValidationError

from pii_detector.infrastructure.validation.prompt_templates import (
    ACTIVE_VARIANT,
    DEFAULT_CONTEXT_WINDOW,
    PROMPT_VARIANTS,
    PiiVerdict,
    SYSTEM_PROMPT,
    SYSTEM_PROMPT_CONTEXT_AWARE,
    VERDICT_SCHEMA,
    build_user_prompt,
    extract_context,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@dataclass
class _FakeEntity:
    text: str
    pii_type: str
    type_label: str
    start: int
    end: int
    score: float = 0.9
    source: str = "GLINER"
    page_title: str = "Page"
    source_file: str = "doc.txt"


# ---------------------------------------------------------------------------
# SYSTEM_PROMPT
# ---------------------------------------------------------------------------


class TestSystemPrompt:
    def test_system_prompt_contains_few_shots(self) -> None:
        # Spot-check the 10 few-shots advertised in the spec (sec 2.4).
        expected_fragments = [
            "10.217.4.11",                    # TP IPv4
            "374111111111111",                # TP AmEx
            "CH6930000011100005458",          # TP IBAN
            "1A1zP1eP5QGefi2DMPTfTL",         # TP crypto wallet
            "756.3407.8913.03",               # TP AVS
            "021 316 01 57",                  # TP phone
            "PCV-1189",                       # FP project code
            "41780007878",                    # FP mobile not AVS
            "92366499.59",                    # FP amount
            "DGAIC",                          # FP acronym
        ]
        for fragment in expected_fragments:
            assert fragment in SYSTEM_PROMPT, (
                f"few-shot fragment {fragment!r} missing from SYSTEM_PROMPT"
            )

    def test_system_prompt_contains_strict_json_rules(self) -> None:
        # The model is reminded that the answer MUST be JSON only.
        assert "Reponds UNIQUEMENT par un objet JSON valide" in SYSTEM_PROMPT
        assert "Pas de markdown" in SYSTEM_PROMPT
        assert "/no_think" in SYSTEM_PROMPT

    def test_system_prompt_documents_three_verdict_values(self) -> None:
        assert "TRUE_POSITIVE" in SYSTEM_PROMPT
        assert "FALSE_POSITIVE" in SYSTEM_PROMPT
        assert "UNSURE" in SYSTEM_PROMPT

    def test_system_prompt_documents_separator_normalization(self) -> None:
        # The judge must be told that separators are stripped before format
        # evaluation, otherwise it drops dash/space-formatted identifiers
        # (regression: IMEI '356-938-035-643-809' wrongly rejected).
        assert "FORMATAGE" in SYSTEM_PROMPT
        assert "separateurs" in SYSTEM_PROMPT
        assert "value_digits_only" in SYSTEM_PROMPT

    def test_system_prompt_contains_separator_edge_case_few_shots(self) -> None:
        # Edge-case few-shots placed last (recency-weighted attention).
        edge_cases = [
            "356-938-035-643-809",            # IMEI with dashes
            "8 6 9 4 6 2 0 5 0 2 7 4 3 1 9",  # IMEI with spaces
            "CH69 3000 0011 1000 0545 8",     # IBAN with spaces
            "4242 4242 4242 4242",            # credit card with spaces
        ]
        for fragment in edge_cases:
            assert fragment in SYSTEM_PROMPT, (
                f"separator few-shot {fragment!r} missing from SYSTEM_PROMPT"
            )

    def test_system_prompt_imposes_reason_first_output_order(self) -> None:
        # reason must be decoded before verdict (in-schema mini-CoT).
        assert SYSTEM_PROMPT.index('"reason"') < SYSTEM_PROMPT.index('"verdict"')


# ---------------------------------------------------------------------------
# PROMPT_VARIANTS — offline A/B comparison registry
# ---------------------------------------------------------------------------


# Every registered variant must honour the same output contract so the
# comparison harness measures the variable under test (the context clause)
# rather than collateral prompt damage. Parametrising over the registry holds
# any future variant to this contract automatically.
_VARIANT_ITEMS = list(PROMPT_VARIANTS.items())
_VARIANT_IDS = [name for name, _ in _VARIANT_ITEMS]


class TestPromptVariants:
    def test_registry_exposes_the_expected_variants(self) -> None:
        assert set(PROMPT_VARIANTS) == {"v1_baseline", "v2_context_aware"}

    def test_active_variant_is_v2_and_drives_system_prompt(self) -> None:
        # v2_context_aware has been promoted to prod: it is the active variant
        # (from config/llm-judge-prompts.toml) and SYSTEM_PROMPT resolves to it.
        assert ACTIVE_VARIANT == "v2_context_aware"
        assert SYSTEM_PROMPT == PROMPT_VARIANTS["v2_context_aware"]

    @pytest.mark.parametrize("prompt", [p for _, p in _VARIANT_ITEMS], ids=_VARIANT_IDS)
    def test_variant_preserves_strict_json_output_rules(self, prompt: str) -> None:
        assert "Reponds UNIQUEMENT par un objet JSON valide" in prompt
        assert "Pas de markdown" in prompt
        assert "/no_think" in prompt

    @pytest.mark.parametrize("prompt", [p for _, p in _VARIANT_ITEMS], ids=_VARIANT_IDS)
    def test_variant_imposes_reason_first_output_order(self, prompt: str) -> None:
        assert prompt.index('"reason"') < prompt.index('"verdict"')

    @pytest.mark.parametrize("prompt", [p for _, p in _VARIANT_ITEMS], ids=_VARIANT_IDS)
    def test_variant_documents_three_verdict_values(self, prompt: str) -> None:
        assert "TRUE_POSITIVE" in prompt
        assert "FALSE_POSITIVE" in prompt
        assert "UNSURE" in prompt

    @pytest.mark.parametrize("prompt", [p for _, p in _VARIANT_ITEMS], ids=_VARIANT_IDS)
    def test_variant_keeps_separator_normalization_contract(self, prompt: str) -> None:
        # value_digits_only + FORMATAGE must survive in every variant, else the
        # IMEI-with-dashes regression resurfaces under that variant.
        assert "FORMATAGE" in prompt
        assert "value_digits_only" in prompt

    @pytest.mark.parametrize("prompt", [p for _, p in _VARIANT_ITEMS], ids=_VARIANT_IDS)
    def test_variant_keeps_canonical_and_edge_case_few_shots(self, prompt: str) -> None:
        # Same few-shots across variants (canonical + separator edge cases).
        for fragment in ("CH6930000011100005458", "356-938-035-643-809", "PCV-1189"):
            assert fragment in prompt, f"few-shot {fragment!r} missing from a variant"


class TestContextAwareVariant:
    def test_context_aware_differs_from_baseline(self) -> None:
        # The context-aware variant must carry strictly more than the baseline
        # (the asymmetric context clause), else the comparison would be a no-op.
        baseline = PROMPT_VARIANTS["v1_baseline"]
        assert SYSTEM_PROMPT_CONTEXT_AWARE != baseline
        assert len(SYSTEM_PROMPT_CONTEXT_AWARE) > len(baseline)

    def test_context_aware_carries_the_asymmetric_clause(self) -> None:
        assert "CONTEXTE" in SYSTEM_PROMPT_CONTEXT_AWARE
        assert "ASYMETRIQUE" in SYSTEM_PROMPT_CONTEXT_AWARE
        # "absence of cue is not a rejection" half — protects no_clue recall.
        assert "ABSENCE" in SYSTEM_PROMPT_CONTEXT_AWARE
        # "context names another type -> FP" half — drives look-alike rejection.
        assert "sha256" in SYSTEM_PROMPT_CONTEXT_AWARE

    def test_context_clause_sits_before_the_output_rules(self) -> None:
        # The clause is inserted ahead of the output rules so the few-shots and
        # the reason-first JSON tail stay byte-identical to the baseline.
        assert SYSTEM_PROMPT_CONTEXT_AWARE.index("CONTEXTE (CRITIQUE") < (
            SYSTEM_PROMPT_CONTEXT_AWARE.index("REGLES DE SORTIE (CRITIQUES)")
        )


# ---------------------------------------------------------------------------
# build_user_prompt
# ---------------------------------------------------------------------------


class TestBuildUserPrompt:
    def test_build_user_prompt_extracts_300_chars_context(self) -> None:
        # Entity placed in the middle of a 1000-char text.
        text = "x" * 500 + "VALUE12345" + "y" * 490
        entity = _FakeEntity(
            text="VALUE12345",
            pii_type="NATIONAL_ID",
            type_label="numero national",
            start=500,
            end=510,
        )

        prompt = build_user_prompt(entity, text, context_window=300)

        # ctx_before: 300 'x' chars; ctx_after: 300 'y' chars.
        assert "x" * 300 in prompt
        assert "y" * 300 in prompt
        # The value must appear delimited with markers in the rendered context.
        assert ">>>VALUE12345<<<" in prompt
        assert "= NATIONAL_ID" in prompt
        assert "= numero national" in prompt

    def test_build_user_prompt_truncates_context_at_start_boundary(self) -> None:
        text = "PCV-1189 was decided last week."  # value at index 0
        entity = _FakeEntity(
            text="PCV-1189",
            pii_type="NATIONAL_ID",
            type_label="numero national",
            start=0,
            end=8,
        )

        prompt = build_user_prompt(entity, text, context_window=300)

        # No chars before -> ctx_before is empty, only ctx_after present.
        # The template renders a literal space between ctx_before and the
        # value marker; with an empty ctx_before this leaves "... >>>".
        assert "... >>>PCV-1189<<<" in prompt
        assert "was decided last week." in prompt

    def test_build_user_prompt_truncates_context_at_end_boundary(self) -> None:
        text = "End of doc: DGAIC"  # value flush to the end
        entity = _FakeEntity(
            text="DGAIC",
            pii_type="PASSWORD",
            type_label="mot de passe",
            start=len(text) - 5,
            end=len(text),
        )

        prompt = build_user_prompt(entity, text, context_window=300)

        # The value must be rendered with markers and no trailing context.
        assert ">>>DGAIC<<<" in prompt
        # Trailing window is empty -> closing dots immediately after marker.
        assert ">>>DGAIC<<< ...\"" in prompt

    def test_build_user_prompt_respects_custom_window(self) -> None:
        text = "abcdefghij" + "VALUE" + "0123456789"
        entity = _FakeEntity(
            text="VALUE",
            pii_type="EMAIL",
            type_label="email",
            start=10,
            end=15,
        )

        prompt = build_user_prompt(entity, text, context_window=4)

        # Only the last 4 chars of "abcdefghij" appear, idem for tail.
        assert "...ghij >>>VALUE<<< 0123" in prompt

    def test_build_user_prompt_uses_pii_type_when_label_missing(self) -> None:
        text = "left RIGHT trailing"
        entity = _FakeEntity(
            text="RIGHT",
            pii_type="API_KEY",
            type_label="",  # explicit empty -> falls back on pii_type
            start=5,
            end=10,
        )

        prompt = build_user_prompt(entity, text, context_window=2)

        # type_label has been backfilled from pii_type (defence-in-depth).
        assert "= API_KEY" in prompt

    def test_build_user_prompt_exposes_value_digits_only(self) -> None:
        # Separators (dashes/spaces/dots/slashes) stripped so the judge can
        # count digits without reconstructing the value under Q4_K_M noise.
        text = "IMEI: 356-938-035-643-809 enregistre."
        entity = _FakeEntity(
            text="356-938-035-643-809",
            pii_type="IMEI",
            type_label="IMEI",
            start=6,
            end=25,
        )

        prompt = build_user_prompt(entity, text)

        assert '= "356-938-035-643-809"' in prompt          # raw value kept
        assert '= "356938035643809"' in prompt               # normalized form
        assert "value_digits_only" in prompt

    def test_build_user_prompt_omits_noise_fields(self) -> None:
        # detector / score / page_title / source_file were dropped: they don't
        # inform a format/semantics verdict and only pollute the context.
        text = "abcXYZdef"
        entity = _FakeEntity(
            text="XYZ",
            pii_type="EMAIL",
            type_label="email",
            start=3,
            end=6,
        )

        prompt = build_user_prompt(entity, text)

        assert "score" not in prompt
        assert "detector" not in prompt
        assert "page_title" not in prompt
        assert "source_file" not in prompt


# ---------------------------------------------------------------------------
# PiiVerdict
# ---------------------------------------------------------------------------


class TestPiiVerdict:
    @pytest.mark.parametrize(
        "verdict_value",
        ["TRUE_POSITIVE", "FALSE_POSITIVE", "UNSURE"],
    )
    def test_pii_verdict_accepts_valid_verdicts(
        self, verdict_value: str
    ) -> None:
        verdict = PiiVerdict(
            verdict=verdict_value,  # type: ignore[arg-type]
            confidence=0.5,
            reason="ok",
        )
        assert verdict.verdict == verdict_value
        assert verdict.confidence == 0.5

    def test_pii_verdict_rejects_invalid_verdict(self) -> None:
        with pytest.raises(ValidationError):
            PiiVerdict(
                verdict="MAYBE",  # type: ignore[arg-type]
                confidence=0.5,
                reason="ok",
            )

    @pytest.mark.parametrize("confidence", [-0.1, 1.5, 2.0])
    def test_pii_verdict_rejects_confidence_out_of_range(
        self, confidence: float
    ) -> None:
        with pytest.raises(ValidationError):
            PiiVerdict(
                verdict="UNSURE",
                confidence=confidence,
                reason="ok",
            )

    def test_pii_verdict_rejects_reason_too_long(self) -> None:
        with pytest.raises(ValidationError):
            PiiVerdict(
                verdict="UNSURE",
                confidence=0.5,
                reason="x" * 301,
            )

    def test_pii_verdict_strips_reason_whitespace(self) -> None:
        verdict = PiiVerdict(
            verdict="TRUE_POSITIVE",
            confidence=0.99,
            reason="  trimmed  ",
        )
        assert verdict.reason == "trimmed"


# ---------------------------------------------------------------------------
# VERDICT_SCHEMA (sanity for commit 3 payload)
# ---------------------------------------------------------------------------


class TestVerdictSchema:
    def test_verdict_schema_is_strict_object(self) -> None:
        assert VERDICT_SCHEMA["type"] == "object"
        assert VERDICT_SCHEMA["additionalProperties"] is False
        assert set(VERDICT_SCHEMA["required"]) == {
            "verdict",
            "confidence",
            "reason",
        }
        assert set(VERDICT_SCHEMA["properties"]["verdict"]["enum"]) == {
            "TRUE_POSITIVE",
            "FALSE_POSITIVE",
            "UNSURE",
        }

    def test_verdict_schema_matches_pydantic_model(self) -> None:
        """The JSON schema sent to LM Studio and the Pydantic validator must
        accept and reject the same payloads.

        Defense-in-depth: ``response_format: json_schema strict`` is enforced
        by LM Studio (spec sec 1.5) but we still validate with Pydantic on the
        client. The two surfaces must stay in sync to avoid a payload
        accepted by one and rejected by the other.
        """
        schema_props = set(VERDICT_SCHEMA["properties"].keys())
        model_fields = set(PiiVerdict.model_fields.keys())
        assert schema_props == model_fields

        # Verdict enum must match the Literal in the Pydantic model.
        schema_enum = set(VERDICT_SCHEMA["properties"]["verdict"]["enum"])
        # Trying every enum value against the Pydantic model must succeed.
        for value in schema_enum:
            PiiVerdict(verdict=value, confidence=0.5, reason="ok")  # type: ignore[arg-type]
        # Required fields must match the Pydantic mandatory fields.
        assert set(VERDICT_SCHEMA["required"]) == model_fields


# ---------------------------------------------------------------------------
# extract_context
# ---------------------------------------------------------------------------


class TestExtractContext:
    def test_extract_context_default_window_is_300_chars(self) -> None:
        assert DEFAULT_CONTEXT_WINDOW == 300
        text = "a" * 500 + "MIDDLE" + "b" * 500
        before, after = extract_context(text, 500, 506)
        assert len(before) == 300
        assert len(after) == 300
        assert before == "a" * 300
        assert after == "b" * 300

    def test_extract_context_clamps_at_start_boundary(self) -> None:
        text = "EARLY" + "b" * 500
        before, after = extract_context(text, 0, 5, window=300)
        # Entity is at the very start -> no context before.
        assert before == ""
        # Only 300 'b' chars after.
        assert after == "b" * 300

    def test_extract_context_clamps_at_end_boundary(self) -> None:
        text = "a" * 500 + "LATE"
        before, after = extract_context(text, 500, 504, window=300)
        assert before == "a" * 300
        # No context after the last character.
        assert after == ""

    def test_extract_context_clamps_when_text_shorter_than_window(self) -> None:
        text = "abc HELLO def"
        before, after = extract_context(text, 4, 9, window=300)
        # Window is larger than the text so we get the whole flanks back.
        assert before == "abc "
        assert after == " def"

    def test_extract_context_returns_substrings_of_input(self) -> None:
        text = "lorem ipsum dolor sit amet"
        before, after = extract_context(text, 12, 17, window=5)
        # Verbatim substrings (no rstrip / lstrip / decoration).
        assert before == "psum "
        assert after == " sit "
