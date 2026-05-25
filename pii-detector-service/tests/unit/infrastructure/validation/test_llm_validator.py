"""
Tests for :class:`LLMJudgeValidator` (Qwen 3.6 as judge).

The suite covers:

- :func:`_resolve_model_id` exact / fuzzy / blacklist / failure paths.
- :func:`_extract_json_payload` priority on ``reasoning_content``, defense-
  in-depth strip of ``<think>`` blocks and markdown fences, error paths.
- :func:`_log_inference_metrics` happy path.
- :meth:`LLMJudgeValidator._invoke_remote` HTTP success, timeout, 5xx, 4xx,
  malformed JSON paths.
- :meth:`LLMJudgeValidator.filter` MVP decision rule (FALSE_POSITIVE
  -> discard, TRUE_POSITIVE / UNSURE -> keep), GLiNER-only scope and
  fail-open policy.
- Lifecycle: ``atexit.register`` wired, ``shutdown`` idempotent.
- Singleton accessor.
"""

from __future__ import annotations

import atexit
import json
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, patch

import httpx
import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.port.pii_post_filter_protocol import (
    PIIPostFilterProtocol,
)
from pii_detector.infrastructure.validation import llm_validator as lv
from pii_detector.infrastructure.validation.llm_validator import (
    BACKEND_LOCAL,
    BACKEND_REMOTE,
    LLMJudgeValidator,
    _extract_json_payload,
    _log_inference_metrics,
    _resolve_model_id,
    get_instance,
)
from pii_detector.infrastructure.validation.prompt_templates import (
    PiiVerdict,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _gliner_entity(
    text: str = "John Doe",
    pii_type: str = "PERSON",
    start: int = 0,
    end: int = 8,
    score: float = 0.9,
) -> PIIEntity:
    return PIIEntity(
        text=text,
        pii_type=pii_type,
        type_label=pii_type,
        start=start,
        end=end,
        score=score,
        source=DetectorSource.GLINER,
    )


def _regex_entity(
    text: str = "alice@example.com",
    pii_type: str = "EMAIL",
    start: int = 0,
    end: int = 17,
) -> PIIEntity:
    return PIIEntity(
        text=text,
        pii_type=pii_type,
        type_label=pii_type,
        start=start,
        end=end,
        score=1.0,
        source=DetectorSource.REGEX,
    )


def _models_response(ids: List[str]) -> dict:
    return {"data": [{"id": i, "object": "model"} for i in ids]}


def _completion_response(
    verdict: str = "TRUE_POSITIVE",
    confidence: float = 0.9,
    reason: str = "ok",
    *,
    content: str = "",
    reasoning_extra: str = "",
    usage: Optional[Dict[str, Any]] = None,
) -> dict:
    body = (
        f'{{"verdict": "{verdict}", "confidence": {confidence}, '
        f'"reason": "{reason}"}}'
    )
    return {
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": content,
                    "reasoning_content": reasoning_extra + body,
                },
                "finish_reason": "stop",
            }
        ],
        "usage": usage
        or {
            "prompt_tokens": 100,
            "completion_tokens": 80,
            "total_tokens": 180,
            "completion_tokens_details": {"reasoning_tokens": 75},
        },
    }


# ---------------------------------------------------------------------------
# _resolve_model_id
# ---------------------------------------------------------------------------


class TestResolveModelId:
    def test_should_return_preferred_when_exact_match_present(self) -> None:
        ids = [
            "qwen/qwen3.6-35b-a3b",
            "qwen3.6-35b-a3b-uncensored-aggressive",
            "openai/gpt-oss-20b",
        ]
        with patch.object(httpx, "get") as mocked_get:
            mocked_get.return_value = MagicMock(
                json=MagicMock(return_value=_models_response(ids)),
                raise_for_status=MagicMock(),
            )
            resolved = _resolve_model_id(
                "http://example.com/v1", preferred="qwen/qwen3.6-35b-a3b"
            )
        assert resolved == "qwen/qwen3.6-35b-a3b"

    def test_should_fuzzy_match_when_exact_missing(self) -> None:
        ids = [
            "openai/gpt-oss-20b",
            "qwen3.6-35b-a3b-base",  # contains qwen3.6 + a3b, no finetune marker
        ]
        with patch.object(httpx, "get") as mocked_get:
            mocked_get.return_value = MagicMock(
                json=MagicMock(return_value=_models_response(ids)),
                raise_for_status=MagicMock(),
            )
            resolved = _resolve_model_id("http://example.com/v1")
        assert resolved == "qwen3.6-35b-a3b-base"

    @pytest.mark.parametrize(
        "blacklisted",
        [
            "qwen3.6-35b-a3b-uncensored",
            "qwen3.6-35b-a3b-heretic-v2",
            "qwen3.6-35b-a3b-claude-distilled",
            "qwen3.6-35b-a3b-aggressive",
            "qwen3.6-35b-a3b-neo-finetune",
        ],
    )
    def test_should_skip_blacklisted_finetunes(self, blacklisted: str) -> None:
        ids = ["openai/gpt-oss-20b", blacklisted]
        with patch.object(httpx, "get") as mocked_get:
            mocked_get.return_value = MagicMock(
                json=MagicMock(return_value=_models_response(ids)),
                raise_for_status=MagicMock(),
            )
            with pytest.raises(RuntimeError, match="No Qwen 3.6 A3B"):
                _resolve_model_id("http://example.com/v1")

    def test_should_raise_when_no_candidate_found(self) -> None:
        ids = ["openai/gpt-oss-20b", "dolphin-2.5-mixtral-8x7b@q6_k"]
        with patch.object(httpx, "get") as mocked_get:
            mocked_get.return_value = MagicMock(
                json=MagicMock(return_value=_models_response(ids)),
                raise_for_status=MagicMock(),
            )
            with pytest.raises(RuntimeError, match="No Qwen 3.6 A3B"):
                _resolve_model_id("http://example.com/v1")

    def test_should_propagate_http_status_errors(self) -> None:
        with patch.object(httpx, "get") as mocked_get:
            mocked_get.return_value = MagicMock(
                raise_for_status=MagicMock(
                    side_effect=httpx.HTTPStatusError(
                        "boom",
                        request=MagicMock(),
                        response=MagicMock(status_code=500),
                    )
                ),
            )
            with pytest.raises(httpx.HTTPStatusError):
                _resolve_model_id("http://example.com/v1")


# ---------------------------------------------------------------------------
# _extract_json_payload
# ---------------------------------------------------------------------------


class TestExtractJsonPayload:
    def test_should_read_reasoning_content_in_priority(self) -> None:
        body = (
            '{"verdict": "TRUE_POSITIVE", "confidence": 0.9, "reason": "ok"}'
        )
        response = {
            "choices": [
                {
                    "message": {
                        "content": "",
                        "reasoning_content": body,
                    }
                }
            ]
        }
        assert _extract_json_payload(response)["verdict"] == "TRUE_POSITIVE"

    def test_should_fallback_on_content_when_reasoning_empty(self) -> None:
        body = (
            '{"verdict": "FALSE_POSITIVE", "confidence": 0.6, "reason": "fp"}'
        )
        response = {
            "choices": [
                {
                    "message": {
                        "content": body,
                        "reasoning_content": "",
                    }
                }
            ]
        }
        assert _extract_json_payload(response)["verdict"] == "FALSE_POSITIVE"

    def test_should_strip_think_blocks_defensively(self) -> None:
        body = (
            "<think>noisy reasoning here</think>\n"
            '{"verdict": "UNSURE", "confidence": 0.5, "reason": "?"}'
        )
        response = {
            "choices": [
                {"message": {"content": "", "reasoning_content": body}}
            ]
        }
        assert _extract_json_payload(response)["verdict"] == "UNSURE"

    def test_should_strip_markdown_json_fences(self) -> None:
        body = (
            '```json\n'
            '{"verdict": "TRUE_POSITIVE", "confidence": 0.7, "reason": "ok"}'
            '\n```'
        )
        response = {
            "choices": [
                {"message": {"content": "", "reasoning_content": body}}
            ]
        }
        assert _extract_json_payload(response)["verdict"] == "TRUE_POSITIVE"

    def test_should_extract_first_json_block_when_text_around_it(self) -> None:
        body = (
            "lots of pre-text\n"
            '{"verdict": "TRUE_POSITIVE", "confidence": 0.7, "reason": "ok"}'
            "\nepilogue text"
        )
        response = {
            "choices": [
                {"message": {"content": "", "reasoning_content": body}}
            ]
        }
        assert _extract_json_payload(response)["verdict"] == "TRUE_POSITIVE"

    def test_should_raise_value_error_when_both_fields_empty(self) -> None:
        response = {
            "choices": [
                {"message": {"content": "", "reasoning_content": ""}}
            ]
        }
        with pytest.raises(ValueError, match="Empty"):
            _extract_json_payload(response)

    def test_should_raise_json_decode_error_when_payload_malformed(self) -> None:
        response = {
            "choices": [
                {
                    "message": {
                        "content": "",
                        "reasoning_content": "{not-json,,,}",
                    }
                }
            ]
        }
        with pytest.raises(json.JSONDecodeError):
            _extract_json_payload(response)


# ---------------------------------------------------------------------------
# _log_inference_metrics
# ---------------------------------------------------------------------------


class TestLogInferenceMetrics:
    def test_should_emit_structured_log_with_reasoning_tokens(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        response = _completion_response()
        with caplog.at_level("INFO", logger=lv.logger.name):
            _log_inference_metrics(response, request_id="req-abc")
        record = next(r for r in caplog.records if "[LLM-JUDGE]" in r.message)
        assert "request_id=req-abc" in record.message
        assert "prompt_tokens=100" in record.message
        assert "completion_tokens=80" in record.message
        assert "reasoning_tokens=75" in record.message
        assert "total_tokens=180" in record.message

    def test_should_default_token_counts_to_zero_when_usage_missing(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        response: Dict[str, Any] = {"choices": []}
        with caplog.at_level("INFO", logger=lv.logger.name):
            _log_inference_metrics(response, request_id="req-xyz")
        record = next(r for r in caplog.records if "[LLM-JUDGE]" in r.message)
        assert "prompt_tokens=0" in record.message
        assert "reasoning_tokens=0" in record.message


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_singleton():
    """Reset the module-level singleton between tests to avoid leakage."""
    lv._reset_singleton_for_tests()
    yield
    lv._reset_singleton_for_tests()


@pytest.fixture
def validator_factory(monkeypatch: pytest.MonkeyPatch):
    """Build a validator with the model id pre-resolved (no /v1/models call)."""

    def _factory(**overrides: Any) -> LLMJudgeValidator:
        # Strip env vars so they do not leak into the validator under test.
        for var in (
            "LLM_JUDGE_BASE_URL",
            "LLM_JUDGE_PREFERRED_MODEL",
            "LLM_JUDGE_TIMEOUT_SECONDS",
            "LLM_JUDGE_MAX_BATCH_SIZE",
            "LLM_JUDGE_MAX_WORKERS",
            "LLM_JUDGE_FAIL_OPEN",
            "LLM_JUDGE_BACKEND",
        ):
            monkeypatch.delenv(var, raising=False)
        defaults: Dict[str, Any] = dict(
            base_url="http://judge.test/v1",
            preferred_model="qwen/qwen3.6-35b-a3b",
            timeout_seconds=5.0,
            max_workers=2,
            max_batch_size=1,
            backend=BACKEND_REMOTE,
            fail_open=True,
        )
        defaults.update(overrides)
        validator = LLMJudgeValidator(**defaults)
        # Skip the /v1/models call (out of scope of these tests).
        validator._resolved_model_id = defaults["preferred_model"]
        return validator

    return _factory


# ---------------------------------------------------------------------------
# Protocol / Sanity
# ---------------------------------------------------------------------------


class TestLLMJudgeValidatorSkeleton:
    def test_should_implement_port_when_instantiated(self, validator_factory) -> None:
        validator = validator_factory()
        try:
            assert isinstance(validator, PIIPostFilterProtocol)
            assert validator.name == "llm-judge-qwen-3.6"
        finally:
            validator.shutdown()


# ---------------------------------------------------------------------------
# _invoke_remote
# ---------------------------------------------------------------------------


class TestInvokeRemote:
    @staticmethod
    def _patched_client(validator: LLMJudgeValidator, post: MagicMock) -> None:
        client = MagicMock(spec=httpx.Client)
        client.post = post
        validator._client = client
        validator._owns_client = True

    def test_should_return_decoded_json_on_success(self, validator_factory) -> None:
        validator = validator_factory()
        response = MagicMock()
        response.raise_for_status = MagicMock()
        response.json = MagicMock(return_value=_completion_response())
        response.status_code = 200
        post = MagicMock(return_value=response)
        try:
            self._patched_client(validator, post)
            payload = validator._invoke_remote("user prompt", "req-1")
            assert payload["choices"][0]["message"]["reasoning_content"]
            post.assert_called_once()
            _, kwargs = post.call_args
            sent_payload = kwargs["json"]
            # spec section 2.4 payload contract
            assert sent_payload["model"] == "qwen/qwen3.6-35b-a3b"
            assert sent_payload["max_tokens"] == 2048
            assert sent_payload["temperature"] == 0.2
            assert sent_payload["top_p"] == 1.0
            assert sent_payload["stream"] is False
            assert sent_payload["response_format"]["type"] == "json_schema"
            assert (
                sent_payload["response_format"]["json_schema"]["strict"] is True
            )
            assert sent_payload["chat_template_kwargs"] == {
                "enable_thinking": False
            }
        finally:
            validator.shutdown()

    def test_should_raise_timeout_when_client_times_out(
        self, validator_factory
    ) -> None:
        validator = validator_factory()
        post = MagicMock(side_effect=httpx.TimeoutException("slow"))
        try:
            self._patched_client(validator, post)
            with pytest.raises(httpx.TimeoutException):
                validator._invoke_remote("user prompt", "req-2")
        finally:
            validator.shutdown()

    def test_should_raise_on_5xx(self, validator_factory) -> None:
        validator = validator_factory()
        response = MagicMock()
        response.raise_for_status = MagicMock(
            side_effect=httpx.HTTPStatusError(
                "internal",
                request=MagicMock(),
                response=MagicMock(status_code=500),
            )
        )
        post = MagicMock(return_value=response)
        try:
            self._patched_client(validator, post)
            with pytest.raises(httpx.HTTPStatusError):
                validator._invoke_remote("user prompt", "req-3")
        finally:
            validator.shutdown()

    def test_should_raise_on_4xx(self, validator_factory) -> None:
        validator = validator_factory()
        response = MagicMock()
        response.raise_for_status = MagicMock(
            side_effect=httpx.HTTPStatusError(
                "bad request",
                request=MagicMock(),
                response=MagicMock(status_code=400),
            )
        )
        post = MagicMock(return_value=response)
        try:
            self._patched_client(validator, post)
            with pytest.raises(httpx.HTTPStatusError):
                validator._invoke_remote("user prompt", "req-4")
        finally:
            validator.shutdown()

    def test_should_propagate_when_json_decode_fails(
        self, validator_factory
    ) -> None:
        validator = validator_factory()
        response = MagicMock()
        response.raise_for_status = MagicMock()
        response.json = MagicMock(
            side_effect=json.JSONDecodeError("err", "doc", 0)
        )
        response.status_code = 200
        post = MagicMock(return_value=response)
        try:
            self._patched_client(validator, post)
            with pytest.raises(json.JSONDecodeError):
                validator._invoke_remote("user prompt", "req-5")
        finally:
            validator.shutdown()


# ---------------------------------------------------------------------------
# filter() -- MVP decision rule + scope + fail-open
# ---------------------------------------------------------------------------


def _wire_judge(
    validator: LLMJudgeValidator,
    verdicts: List[Optional[PiiVerdict]],
) -> List[Dict[str, Any]]:
    """Bypass HTTP by mocking ``_judge_one`` to return preset verdicts."""
    calls: List[Dict[str, Any]] = []
    counter = {"i": 0}

    def fake_judge_one(text: str, entity: PIIEntity, request_id: str):
        calls.append({"text": text, "entity": entity, "request_id": request_id})
        idx = counter["i"]
        counter["i"] += 1
        return verdicts[idx] if idx < len(verdicts) else None

    validator._judge_one = fake_judge_one  # type: ignore[assignment]
    return calls


class TestLLMJudgeValidatorRuleMVP:
    def test_should_discard_false_positive_gliner_entity(
        self, validator_factory
    ) -> None:
        validator = validator_factory()
        try:
            verdict = PiiVerdict(
                verdict="FALSE_POSITIVE", confidence=0.9, reason="fp"
            )
            _wire_judge(validator, [verdict])
            entities = [_gliner_entity()]
            kept = validator.filter("text", entities)
            assert kept == []
        finally:
            validator.shutdown()

    def test_should_keep_true_positive_gliner_entity(
        self, validator_factory
    ) -> None:
        validator = validator_factory()
        try:
            verdict = PiiVerdict(
                verdict="TRUE_POSITIVE", confidence=0.9, reason="ok"
            )
            _wire_judge(validator, [verdict])
            entities = [_gliner_entity()]
            kept = validator.filter("text", entities)
            assert kept == entities
        finally:
            validator.shutdown()

    def test_should_keep_unsure_gliner_entity(self, validator_factory) -> None:
        validator = validator_factory()
        try:
            verdict = PiiVerdict(
                verdict="UNSURE", confidence=0.4, reason="?"
            )
            _wire_judge(validator, [verdict])
            entities = [_gliner_entity()]
            kept = validator.filter("text", entities)
            assert kept == entities
        finally:
            validator.shutdown()

    def test_should_bypass_non_gliner_entities(self, validator_factory) -> None:
        validator = validator_factory()
        try:
            # Mock judge to crash if invoked: it must NOT be invoked for
            # non-GLiNER entities.
            calls = _wire_judge(
                validator,
                [
                    PiiVerdict(
                        verdict="FALSE_POSITIVE", confidence=0.9, reason="fp"
                    )
                ],
            )
            entities = [_regex_entity()]
            kept = validator.filter("text", entities)
            assert kept == entities
            assert calls == []  # judge never called for regex entity
        finally:
            validator.shutdown()

    def test_should_filter_only_gliner_in_mixed_batch(
        self, validator_factory
    ) -> None:
        validator = validator_factory()
        try:
            # Two GLiNER entities (one TP, one FP) + one Regex entity.
            verdicts: List[Optional[PiiVerdict]] = [
                PiiVerdict(
                    verdict="TRUE_POSITIVE", confidence=0.9, reason="ok"
                ),
                PiiVerdict(
                    verdict="FALSE_POSITIVE", confidence=0.9, reason="fp"
                ),
            ]
            _wire_judge(validator, verdicts)
            gliner_tp = _gliner_entity(text="Marc", start=0, end=4)
            gliner_fp = _gliner_entity(
                text="DGAIC", pii_type="PASSWORD", start=10, end=15
            )
            regex_kept = _regex_entity(start=20, end=37)
            kept = validator.filter(
                "text", [gliner_tp, gliner_fp, regex_kept]
            )
            assert kept == [gliner_tp, regex_kept]
        finally:
            validator.shutdown()

    def test_should_short_circuit_on_empty_entities(
        self, validator_factory
    ) -> None:
        validator = validator_factory()
        try:
            assert validator.filter("text", []) == []
        finally:
            validator.shutdown()


class TestLLMJudgeValidatorFailOpen:
    def test_should_keep_entity_on_timeout(self, validator_factory) -> None:
        validator = validator_factory(fail_open=True)
        try:
            # Wire a failing judge through the real ``_judge_one`` path so
            # the actual error handling kicks in.
            def fake_invoke(prompt: str, request_id: str) -> dict:
                raise httpx.TimeoutException("slow")

            validator._invoke_remote = fake_invoke  # type: ignore[assignment]
            entities = [_gliner_entity()]
            kept = validator.filter("text", entities)
            assert kept == entities
        finally:
            validator.shutdown()

    def test_should_keep_entity_on_invalid_json(
        self, validator_factory
    ) -> None:
        validator = validator_factory(fail_open=True)
        try:
            def fake_invoke(prompt: str, request_id: str) -> dict:
                return {
                    "choices": [
                        {"message": {"content": "", "reasoning_content": ""}}
                    ],
                    "usage": {},
                }

            validator._invoke_remote = fake_invoke  # type: ignore[assignment]
            entities = [_gliner_entity()]
            kept = validator.filter("text", entities)
            assert kept == entities
        finally:
            validator.shutdown()

    def test_should_keep_entity_when_validator_raises_validation_error(
        self, validator_factory
    ) -> None:
        validator = validator_factory(fail_open=True)
        try:
            def fake_invoke(prompt: str, request_id: str) -> dict:
                # confidence out of range -> Pydantic ValidationError.
                return {
                    "choices": [
                        {
                            "message": {
                                "content": "",
                                "reasoning_content": (
                                    '{"verdict": "FALSE_POSITIVE", '
                                    '"confidence": 5, '
                                    '"reason": "x"}'
                                ),
                            }
                        }
                    ],
                    "usage": {},
                }

            validator._invoke_remote = fake_invoke  # type: ignore[assignment]
            entities = [_gliner_entity()]
            kept = validator.filter("text", entities)
            assert kept == entities
        finally:
            validator.shutdown()

    def test_should_discard_entity_when_fail_open_is_false_and_call_fails(
        self, validator_factory
    ) -> None:
        validator = validator_factory(fail_open=False)
        try:
            def fake_invoke(prompt: str, request_id: str) -> dict:
                raise httpx.TimeoutException("slow")

            validator._invoke_remote = fake_invoke  # type: ignore[assignment]
            entities = [_gliner_entity()]
            kept = validator.filter("text", entities)
            assert kept == []
        finally:
            validator.shutdown()


# ---------------------------------------------------------------------------
# Backend handling
# ---------------------------------------------------------------------------


class TestLocalBackend:
    def test_should_be_noop_when_backend_is_local(
        self, validator_factory, caplog: pytest.LogCaptureFixture
    ) -> None:
        with caplog.at_level("WARNING", logger=lv.logger.name):
            validator = validator_factory(backend=BACKEND_LOCAL)
        try:
            entities = [_gliner_entity(), _regex_entity()]
            assert validator.filter("text", entities) == entities
            # WARN log emitted by the constructor.
            assert any(
                "local backend out of scope" in r.message
                for r in caplog.records
            )
        finally:
            validator.shutdown()


# ---------------------------------------------------------------------------
# Lifecycle
# ---------------------------------------------------------------------------


class TestLLMJudgeValidatorShutdown:
    def test_should_be_idempotent(self, validator_factory) -> None:
        validator = validator_factory()
        validator.shutdown()
        # Second call must not raise.
        validator.shutdown()

    def test_should_register_atexit_hook(self) -> None:
        with patch.object(atexit, "register") as mocked_register:
            validator = LLMJudgeValidator(
                base_url="http://judge.test/v1",
                preferred_model="qwen/qwen3.6-35b-a3b",
                timeout_seconds=5.0,
                max_workers=2,
                backend=BACKEND_REMOTE,
            )
            try:
                mocked_register.assert_called_once_with(validator.shutdown)
            finally:
                validator.shutdown()

    def test_should_close_executor_on_shutdown(self, validator_factory) -> None:
        validator = validator_factory()
        assert validator._executor is not None
        validator.shutdown()
        assert validator._executor is None


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------


class TestSingleton:
    def test_should_return_same_instance(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # Avoid hitting the real LM Studio at construction time.
        monkeypatch.setenv("LLM_JUDGE_BASE_URL", "http://localhost:9999/v1")
        first = get_instance()
        second = get_instance()
        try:
            assert first is second
        finally:
            lv._reset_singleton_for_tests()


# ---------------------------------------------------------------------------
# Local backend explicit (out of MVP scope)
# ---------------------------------------------------------------------------


@pytest.mark.skip(reason="local backend out of scope for MVP")
class TestLLMJudgeValidatorLoadModel:
    def test_placeholder(self) -> None:  # pragma: no cover
        pass
