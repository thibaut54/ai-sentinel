from __future__ import annotations

import re
from dataclasses import dataclass
from unittest.mock import MagicMock, patch

import pytest

from pii_detector.infrastructure.validation.llm_validator import (
    LLMValidator,
    _VERDICT_PATTERN,
)
from pii_detector.infrastructure.validation.prompt_templates import (
    build_batch_prompt,
    build_single_prompt,
    extract_context,
)


@dataclass
class FakeEntity:
    text: str
    pii_type: str
    type_label: str
    start: int
    end: int
    score: float = 0.9


SOURCE_TEXT = (
    "Le client Jean Dupont habite au 12 rue de la Paix, Paris. "
    "Son numero de telephone est 06 12 34 56 78."
)


# --- prompt_templates tests ---


class TestExtractContext:
    def test_middle_of_text(self):
        before, after = extract_context(SOURCE_TEXT, 10, 21, window=5)
        assert before == SOURCE_TEXT[5:10]
        assert after == SOURCE_TEXT[21:26]

    def test_start_boundary(self):
        before, after = extract_context(SOURCE_TEXT, 0, 5, window=200)
        assert before == ""
        assert after == SOURCE_TEXT[5:205]

    def test_end_boundary(self):
        text_len = len(SOURCE_TEXT)
        before, after = extract_context(
            SOURCE_TEXT, text_len - 5, text_len, window=200
        )
        assert before == SOURCE_TEXT[max(0, text_len - 205) : text_len - 5]
        assert after == ""


class TestBuildBatchPrompt:
    def test_formats_correctly(self):
        entities = [
            FakeEntity("Jean Dupont", "PERSON", "personne", 10, 21),
            FakeEntity("06 12 34 56 78", "PHONE", "telephone", 80, 94),
        ]
        prompt = build_batch_prompt(entities, SOURCE_TEXT, context_window=30)

        assert "Tu es un expert en protection des donnees" in prompt
        # Localized type_label is used (consistent with build_single_prompt)
        # so Gemma 4 sees French labels everywhere instead of opaque enum names.
        assert "[0] Type: personne" in prompt
        assert '"Jean Dupont"' in prompt
        assert "[1] Type: telephone" in prompt
        assert '"06 12 34 56 78"' in prompt
        assert "Reponds UNIQUEMENT au format suivant" in prompt
        assert "[0]: TRUE_POSITIVE" in prompt
        assert "[1]: FALSE_POSITIVE" in prompt

    def test_falls_back_to_pii_type_when_label_missing(self):
        # type_label may be empty/None in legacy paths; we must not crash
        # and should fall back to the raw pii_type identifier.
        e = FakeEntity("Jean", "PERSON_NAME", "", 10, 14)
        prompt = build_batch_prompt([e], SOURCE_TEXT, context_window=10)
        assert "Type: PERSON_NAME" in prompt

    def test_empty_entities(self):
        prompt = build_batch_prompt([], SOURCE_TEXT)
        assert "Tu es un expert" in prompt
        # Robust against template renames: assert no entity-row index marker
        # (start of a line with "[N]" followed by something other than a verdict)
        # is rendered. The example block "[0]: TRUE_POSITIVE" is OK because it
        # is followed by ": TRUE_POSITIVE", not by " Type:".
        assert re.search(r"^\[\d+\] Type:", prompt, re.MULTILINE) is None


class TestBuildSinglePrompt:
    def test_formats_correctly(self):
        entity = FakeEntity("Jean Dupont", "PERSON", "personne", 10, 21)
        prompt = build_single_prompt(entity, SOURCE_TEXT, context_window=30)

        assert "un(e) personne" in prompt
        assert '"Jean Dupont"' in prompt
        assert "TRUE_POSITIVE" in prompt
        assert "FALSE_POSITIVE" in prompt
        assert "Reponse :" in prompt


# --- _parse_batch_response tests ---


class TestParseBatchResponse:
    def setup_method(self):
        self.validator = LLMValidator(model_path="/fake/model.gguf")

    def test_happy_path(self):
        response = (
            "[0]: TRUE_POSITIVE\n"
            "[1]: FALSE_POSITIVE\n"
            "[2]: TRUE_POSITIVE\n"
        )
        rejected = self.validator._parse_batch_response(response, 3)
        assert rejected == {1}

    def test_case_insensitive(self):
        response = (
            "[0]: true_positive\n"
            "[1]: False_Positive\n"
            "[2]: TRUE_POSITIVE\n"
        )
        rejected = self.validator._parse_batch_response(response, 3)
        assert rejected == {1}

    def test_malformed_lines_ignored(self):
        response = (
            "[0]: TRUE_POSITIVE\n"
            "garbage line\n"
            "[1]: FALSE_POSITIVE\n"
            "another bad line: BLAH\n"
        )
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_missing_indices_kept(self):
        response = "[0]: FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 3)
        assert rejected == {0}
        # indices 1 and 2 have no verdict -> kept (not in rejected)

    def test_out_of_range_index_ignored(self):
        response = "[0]: FALSE_POSITIVE\n[99]: FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {0}

    def test_dot_separator(self):
        response = "0. TRUE_POSITIVE\n1. FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_no_brackets(self):
        response = "0: TRUE_POSITIVE\n1: FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_space_only_separator(self):
        # Real Gemma 4 output observed in production logs:
        # "[0] FALSE_POSITIVE" with a plain space (no colon, no dot).
        # Regression guard for the bug where this format silently failed
        # to match and all entities were "confirmed" by default.
        response = "[0] TRUE_POSITIVE\n[1] FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_dash_separator(self):
        response = "0 - TRUE_POSITIVE\n1 - FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_multiple_spaces_separator(self):
        response = "[0]   TRUE_POSITIVE\n[1]   FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_dash_no_spaces_separator(self):
        # Compressed format: no spaces around the dash.
        response = "[0]-TRUE_POSITIVE\n[1]-FALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_blank_lines_between_verdicts(self):
        # Gemma may insert blank lines despite the prompt; MULTILINE handles each
        # non-empty line independently.
        response = "[0]: TRUE_POSITIVE\n\n[1]: FALSE_POSITIVE\n\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_preamble_text_before_verdicts(self):
        # Gemma may add a French preamble. The preamble line does not match
        # the verdict pattern and is ignored.
        response = (
            "Voici les verdicts pour chaque entite :\n"
            "[0]: TRUE_POSITIVE\n"
            "[1]: FALSE_POSITIVE\n"
        )
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_markdown_code_fence_around_verdicts(self):
        # Gemma may wrap output in markdown code fences; the fence lines
        # do not match the verdict pattern and are ignored.
        response = (
            "```\n"
            "[0]: TRUE_POSITIVE\n"
            "[1]: FALSE_POSITIVE\n"
            "```\n"
        )
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == {1}

    def test_index_and_verdict_on_separate_lines_not_matched(self):
        # Critical guard: the parser MUST NOT cross newlines to pair an index
        # with a verdict on the next line. Such a malformed answer should be
        # treated as no verdict (conservative: keep both entities).
        response = "[0]\nTRUE_POSITIVE\n[1]\nFALSE_POSITIVE\n"
        rejected = self.validator._parse_batch_response(response, 2)
        assert rejected == set()


# --- LLMValidator tests ---


class TestLLMValidatorGracefulDegradation:
    def test_returns_input_when_model_unavailable(self):
        validator = LLMValidator(model_path="/fake/model.gguf")
        entities = [
            FakeEntity("Jean Dupont", "PERSON", "personne", 10, 21),
        ]
        result = validator.validate_entities(entities, SOURCE_TEXT)
        assert result is entities

    def test_is_available_false_by_default(self):
        validator = LLMValidator(model_path="/fake/model.gguf")
        assert validator.is_available is False


class TestLLMValidatorEmptyInput:
    def test_empty_input(self):
        validator = LLMValidator(model_path="/fake/model.gguf")
        validator._model = MagicMock()
        result = validator.validate_entities([], SOURCE_TEXT)
        assert result == []


class TestLLMValidatorFiltering:
    def test_filters_false_positives(self):
        validator = LLMValidator(model_path="/fake/model.gguf")
        mock_model = MagicMock()
        mock_model.create_chat_completion.return_value = {
            "choices": [
                {"message": {"content": "[0]: TRUE_POSITIVE\n[1]: FALSE_POSITIVE\n"}}
            ]
        }
        validator._model = mock_model

        entities = [
            FakeEntity("Jean Dupont", "PERSON", "personne", 10, 21),
            FakeEntity("PROJ-123", "PERSON", "personne", 30, 38),
        ]
        result = validator.validate_entities(entities, SOURCE_TEXT)

        assert len(result) == 1
        assert result[0].text == "Jean Dupont"

    def test_all_confirmed(self):
        validator = LLMValidator(model_path="/fake/model.gguf")
        mock_model = MagicMock()
        mock_model.create_chat_completion.return_value = {
            "choices": [
                {"message": {"content": "[0]: TRUE_POSITIVE\n[1]: TRUE_POSITIVE\n"}}
            ]
        }
        validator._model = mock_model

        entities = [
            FakeEntity("Jean Dupont", "PERSON", "personne", 10, 21),
            FakeEntity("06 12 34 56 78", "PHONE", "telephone", 80, 94),
        ]
        result = validator.validate_entities(entities, SOURCE_TEXT)
        assert len(result) == 2


class TestLLMValidatorTimeout:
    def test_timeout_returns_unfiltered(self):
        validator = LLMValidator(
            model_path="/fake/model.gguf", timeout_seconds=0.001
        )
        validator._model = MagicMock()

        def slow_call(*args, **kwargs):
            import time
            time.sleep(5)
            return {"choices": [{"message": {"content": "[0]: FALSE_POSITIVE\n"}}]}

        validator._model.create_chat_completion.side_effect = slow_call

        entities = [
            FakeEntity("Jean Dupont", "PERSON", "personne", 10, 21),
        ]
        result = validator.validate_entities(
            entities, SOURCE_TEXT, timeout_seconds=0.001
        )
        assert len(result) == 1
        assert result[0].text == "Jean Dupont"


class TestLLMValidatorSubBatching:
    def test_sub_batching(self):
        validator = LLMValidator(
            model_path="/fake/model.gguf", max_batch_size=5
        )
        mock_model = MagicMock()

        # Every call: confirm all entities in that sub-batch
        def model_side_effect(*args, **kwargs):
            # Extract prompt from messages
            messages = kwargs.get("messages", args[0] if args else [])
            prompt = messages[0]["content"] if messages else ""
            count = prompt.count("Type:")
            lines = "\n".join(
                f"[{i}]: TRUE_POSITIVE" for i in range(count)
            )
            return {"choices": [{"message": {"content": lines}}]}

        mock_model.create_chat_completion.side_effect = model_side_effect
        validator._model = mock_model

        entities = [
            FakeEntity(f"Entity{i}", "PERSON", "personne", i * 10, i * 10 + 5)
            for i in range(23)
        ]
        result = validator.validate_entities(entities, SOURCE_TEXT)

        # All 23 should be confirmed
        assert len(result) == 23
        # Model should have been called ceil(23/5) = 5 times
        assert mock_model.create_chat_completion.call_count == 5

    def test_partial_batch_failure_keeps_entities(self):
        validator = LLMValidator(
            model_path="/fake/model.gguf", max_batch_size=5
        )
        mock_model = MagicMock()
        call_count = {"n": 0}

        def model_side_effect(*args, **kwargs):
            call_count["n"] += 1
            if call_count["n"] == 2:
                raise RuntimeError("Simulated LLM failure")
            messages = kwargs.get("messages", args[0] if args else [])
            prompt = messages[0]["content"] if messages else ""
            count = prompt.count("Type:")
            lines = "\n".join(
                f"[{i}]: FALSE_POSITIVE" for i in range(count)
            )
            return {"choices": [{"message": {"content": lines}}]}

        mock_model.create_chat_completion.side_effect = model_side_effect
        validator._model = mock_model

        entities = [
            FakeEntity(f"Entity{i}", "PERSON", "personne", i * 10, i * 10 + 5)
            for i in range(12)
        ]
        result = validator.validate_entities(entities, SOURCE_TEXT)

        # Batch 1 (0-4): all rejected -> 0 kept
        # Batch 2 (5-9): error -> all 5 kept (conservative)
        # Batch 3 (10-11): all rejected -> 0 kept
        assert len(result) == 5
        assert all(e.text.startswith("Entity") for e in result)


class TestLLMValidatorLoadModel:
    def test_load_model_failure(self):
        validator = LLMValidator(model_path="/nonexistent/model.gguf")
        result = validator.load_model()
        assert result is False
        assert validator.is_available is False

    @patch("pii_detector.infrastructure.validation.llm_validator.Llama", create=True)
    def test_load_model_import_error(self, _mock_llama):
        validator = LLMValidator(model_path="/fake/model.gguf")
        # Simulate llama_cpp not installed
        with patch.dict("sys.modules", {"llama_cpp": None}):
            result = validator.load_model()
        assert result is False


class TestVerdictPatternRegex:
    @pytest.mark.parametrize(
        "line,expected_idx,expected_verdict",
        [
            ("[0]: TRUE_POSITIVE", "0", "TRUE_POSITIVE"),
            ("[1]: FALSE_POSITIVE", "1", "FALSE_POSITIVE"),
            ("0: TRUE_POSITIVE", "0", "TRUE_POSITIVE"),
            ("1. FALSE_POSITIVE", "1", "FALSE_POSITIVE"),
            ("  [2] : true_positive  ", "2", "true_positive"),
            ("[10]: FALSE_POSITIVE", "10", "FALSE_POSITIVE"),
            # Regression: real Gemma 4 output uses space only separator
            ("[0] FALSE_POSITIVE", "0", "FALSE_POSITIVE"),
            ("[1] TRUE_POSITIVE", "1", "TRUE_POSITIVE"),
            ("0 - TRUE_POSITIVE", "0", "TRUE_POSITIVE"),
            ("[3]\tFALSE_POSITIVE", "3", "FALSE_POSITIVE"),
            # Compressed (no spaces around dash).
            ("[0]-TRUE_POSITIVE", "0", "TRUE_POSITIVE"),
            ("[5]-FALSE_POSITIVE", "5", "FALSE_POSITIVE"),
        ],
    )
    def test_pattern_matches(self, line, expected_idx, expected_verdict):
        match = _VERDICT_PATTERN.match(line)
        assert match is not None
        assert match.group(1) == expected_idx
        assert match.group(2).upper() == expected_verdict.upper()

    @pytest.mark.parametrize(
        "line",
        [
            "garbage",
            "TRUE_POSITIVE",
            "[abc]: TRUE_POSITIVE",
            "",
            # No separator between index and verdict.
            "[0]TRUE_POSITIVE",
            # Verdict without separator and trailing text.
            "[0]TRUE_POSITIVE garbage",
        ],
    )
    def test_pattern_rejects(self, line):
        match = _VERDICT_PATTERN.match(line)
        assert match is None
